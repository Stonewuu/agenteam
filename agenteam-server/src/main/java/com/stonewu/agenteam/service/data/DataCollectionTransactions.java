package com.stonewu.agenteam.service.data;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.mapper.data.DataCollectionMapper;
import com.stonewu.agenteam.mapper.data.DataRecordMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.data.entity.DataField;
import com.stonewu.agenteam.model.data.request.DataCollectionWriteRequest;
import com.stonewu.agenteam.model.data.response.DataCollectionView;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.resource.ResourceInput;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;

/**
 * 字段修改创建新版本，旧字段定义保留供已记录版本核对。
 */
@Service
public class DataCollectionTransactions {
    private final DataResourcePolicy policy;
    private final DataCollectionMapper collections;
    private final DataRecordMapper rows;
    private final Clock clock;
    private final AuditEventService audit;
    private final DataSourceHash sourceHash;

    public DataCollectionTransactions(DataResourcePolicy policy, DataCollectionMapper collections,
                                      DataRecordMapper rows, Clock clock, AuditEventService audit,
                                      DataSourceHash sourceHash) {
        this.policy = policy;
        this.collections = collections;
        this.rows = rows;
        this.clock = clock;
        this.audit = audit;
        this.sourceHash = sourceHash;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DataCollectionView updateFile(AuthContext actor, String resource, String id, long revision,
                                         DataCollectionWriteRequest input, PreparedDataImport prepared) {
        var source = policy.edit(actor, resource, true);
        policy.fileSource(source);
        var current = policy.collection(actor.enterpriseId(), resource, id, true);
        policy.revision(current, revision);
        if (!current.sourceName().equals(input.sourceName())) {
            throw ApiException.invalidField("sourceName", Messages.text("error.data.dataCollectionTransactions.updateFile.1"));
        }
        if (current.activeGeneration() == Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.CONFLICT, "DATA_GENERATION_LIMIT", Messages.text("error.data.dataCollectionTransactions.updateFile.2"));
        }
        int generation = current.activeGeneration() + 1;
        String name = ResourceInput.text(input.name(), "name", 80, true);
        collections.generation(actor.enterpriseId(), id, generation, sourceHash.calculate(source.config()),
            current.fileId(), prepared.rowCount(), prepared.fields(), clock.instant());
        prepared.batches(batch -> rows.append(actor.enterpriseId(), id, generation, batch, clock.instant()));
        collections.activate(current, name, current.sourceName(), generation, current.fileId(), prepared.rowCount(),
            clock.instant());
        audit.record(actor.enterpriseId(), actor.user(), "data.collection.update", "data_collection", id,
            Messages.text("text.data.dataCollectionTransactions.updateFile.1"),
            Map.of("generation", generation, "fields", prepared.fields().stream().map(DataField::name).toList()));
        return DataCollectionMapper.view(collections.find(actor.enterpriseId(), id, false).orElseThrow(),
            prepared.fields());
    }
}
