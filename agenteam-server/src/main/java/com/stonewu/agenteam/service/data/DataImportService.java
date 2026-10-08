package com.stonewu.agenteam.service.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.data.DataCollectionMapper;
import com.stonewu.agenteam.mapper.data.DataRecordMapper;
import com.stonewu.agenteam.mapper.file.FileDataMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.data.entity.DataCollectionRecord;
import com.stonewu.agenteam.model.data.entity.DataField;
import com.stonewu.agenteam.model.data.request.DataImportPreviewRequest;
import com.stonewu.agenteam.model.data.response.DataCollectionView;
import com.stonewu.agenteam.model.data.response.DataImportPreviewView;
import com.stonewu.agenteam.model.file.entity.CsvProfile;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.resource.ResourceInput;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 预览与确认共享原文件身份，完整验证的新版本在一次事务中变为可查询。
 */
@Service
public class DataImportService {
    public record Source(ResourceRecord resource, FileRecord file, CsvProfile profile,
                         DataCollectionRecord collection) {
    }

    private final DataResourcePolicy policy;
    private final FileAccessService access;
    private final FileMapper files;
    private final FileDataMapper data;
    private final DataCollectionMapper collections;
    private final DataRecordMapper records;
    private final DataImportTokenService tokens;
    private final DataValueCodec values;
    private final ObjectMapper json;
    private final AuditEventService audit;
    private final Clock clock;
    private final DataSourceHash sourceHash;

    public DataImportService(DataResourcePolicy policy, FileAccessService access, FileMapper files, FileDataMapper data,
                             DataCollectionMapper collections,
                             DataRecordMapper records, DataImportTokenService tokens, DataValueCodec values,
                             ObjectMapper json, AuditEventService audit, Clock clock, DataSourceHash sourceHash) {
        this.policy = policy;
        this.access = access;
        this.files = files;
        this.data = data;
        this.collections = collections;
        this.records = records;
        this.tokens = tokens;
        this.values = values;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
        this.sourceHash = sourceHash;
    }

    public DataImportPreviewView preview(AuthContext actor, String resource, DataImportPreviewRequest input) {
        var source = source(actor, resource, input.fileId(), input.collectionId(), false);
        ResourceInput.text(input.name(), "name", 80, true);
        var fields = new ArrayList<DataField>();
        int index = 0;
        for (var column : source.profile().columns()) {
            String label = column.name().substring(0, column.name()
                .offsetByCodePoints(0, Math.min(80, column.name().codePointCount(0, column.name().length()))));
            fields.add(
                new DataField(column.name(), label, column.valueType(), true, false, false, false, column.nullable(),
                    index++));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        long size = 0;
        for (var row : data.rows(source.file(), 0, 20)) {
            ObjectNode result = json.createObjectNode();
            for (var field : fields) {
                result.set(field.name(), values.csv(field, row.values().get(field.ordinal()), row.row() + 1));
            }
            size += result.toString().getBytes(StandardCharsets.UTF_8).length;
            if (size > 900 * 1024) {
                break;
            }
            rows.add(json.convertValue(result, new TypeReference<Map<String, Object>>() {
            }));
        }
        var token = tokens.token(source.resource(), source.file(), source.collection());
        return new DataImportPreviewView(tokens.issue(actor, token), List.copyOf(fields), List.copyOf(rows),
            source.profile().rowCount(), Instant.ofEpochMilli(token.expiresAt()).toString());
    }

    public Source verify(AuthContext actor, String resource, DataImportTokenService.Token token, boolean mutation) {
        if (token.expiresAt() <= clock.millis()) {
            throw new ApiException(HttpStatus.GONE, "DATA_IMPORT_EXPIRED", "数据导入预览已过期，请重新预览。");
        }
        if (!resource.equals(token.resourceId())) {
            throw FileAccessService.unavailable();
        }
        var source = source(actor, resource, token.fileId(), token.collectionId(), mutation);
        if (source.resource().revision() != token.resourceRevision()) {
            throw ApiException.versionConflict(source.resource().revision());
        }
        if (!source.file().sha256().equals(token.sha256())) {
            throw new ApiException(HttpStatus.CONFLICT, "DATA_IMPORT_CHANGED", "原文件已经变化，请重新预览。");
        }
        if (source.collection() != null) {
            policy.revision(source.collection(), token.collectionRevision());
        }
        return source;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DataCollectionView confirm(AuthContext actor, String resource, String name,
                                      DataImportTokenService.Token token, PreparedDataImport prepared) {
        var source = verify(actor, resource, token, true);
        String title = ResourceInput.text(name, "name", 80, true);
        if (source.profile().rowCount() != prepared.rowCount()) {
            throw new ApiException(HttpStatus.CONFLICT, "DATA_IMPORT_CHANGED", "原文件的解析结果已经变化，请重新预览。");
        }
        DataCollectionRecord current = source.collection();
        String id = current == null ? UUID.randomUUID().toString() : current.id();
        if (current != null && current.activeGeneration() == Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.CONFLICT, "DATA_GENERATION_LIMIT", "集合版本数量达到上限，请建立新集合。");
        }
        int generation = current == null ? 1 : current.activeGeneration() + 1;
        if (current == null) {
            collections.create(id, actor.enterpriseId(), resource, title, "file_" + source.file().id(),
                source.file().id(), prepared.rowCount(), clock.instant());
        }
        collections.generation(actor.enterpriseId(), id, generation, sourceHash.calculate(source.resource().config()),
            source.file().id(), prepared.rowCount(), prepared.fields(), clock.instant());
        prepared.batches(batch -> records.append(actor.enterpriseId(), id, generation, batch, clock.instant()));
        if (current != null) {
            collections.activate(current, title, current.sourceName(), generation, source.file().id(),
                prepared.rowCount(), clock.instant());
        }
        files.retain(source.file(), clock.instant());
        audit.record(actor.enterpriseId(), actor.user(), "data.import", "data_collection", id, "确认导入数据文件",
            Map.of("generation", generation, "fileId", source.file().id(), "rowCount", prepared.rowCount()));
        return DataCollectionMapper.view(collections.find(actor.enterpriseId(), id, false).orElseThrow(),
            prepared.fields());
    }

    private Source source(AuthContext actor, String resource, String fileId, String collectionId, boolean mutation) {
        var record = policy.edit(actor, resource, mutation);
        policy.fileSource(record);
        var file = access.uploadOwner(actor, fileId, mutation);
        if (!file.purpose().equals("data_import") || !resource.equals(file.resourceId())) {
            throw FileAccessService.unavailable();
        }
        if (!file.status().equals("ready")) {
            throw new ApiException(HttpStatus.CONFLICT, "FILE_NOT_READY", "CSV 尚未完成检查和解析，请等待完成。");
        }
        if (file.expiresAt() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "DATA_FILE_ALREADY_IMPORTED",
                "这份文件已经导入，请选择新的文件更新集合。");
        }
        var profile = data.profile(file).orElseThrow(PreparedDataImport::unavailable);
        var collection = collectionId == null ? null : policy.collection(actor.enterpriseId(), resource, collectionId,
            mutation);
        return new Source(record, file, profile, collection);
    }
}
