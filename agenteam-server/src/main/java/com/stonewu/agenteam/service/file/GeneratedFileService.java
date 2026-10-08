package com.stonewu.agenteam.service.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.model.file.entity.PreparedGeneratedFile;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.util.UUID;

/**
 * 导出内容来自已验证的固定结构，保存文件与最终登记分开执行。
 */
@Service
public class GeneratedFileService {
    private final FileContentStorage storage;
    private final FileMapper files;
    private final ObjectMapper json;
    private final Clock clock;

    public GeneratedFileService(FileContentStorage storage, FileMapper files, ObjectMapper json, Clock clock) {
        this.storage = storage;
        this.files = files;
        this.json = json;
        this.clock = clock;
    }

    public PreparedGeneratedFile skillExport(AuthContext actor, String resourceId, String versionId, String name,
                                             JsonNode content) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("文件写入不能占用数据库事务");
        }
        try {
            byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(content);
            var saved = storage.write(actor.enterpriseId(), new ByteArrayInputStream(bytes),
                SkillFileValidation.MAX_BYTES);
            String fileName = name.replace('/', ' ').replace('\\', ' ') + ".json";
            return new PreparedGeneratedFile(UUID.randomUUID().toString(), actor.enterpriseId(), actor.userId(),
                resourceId, versionId, null, "export", fileName, "application/json", saved.key(), saved.size(),
                saved.sha256());
        } catch (Exception failure) {
            throw new IllegalStateException("技能导出文件无法保存", failure);
        }
    }

    @Transactional
    public FileRecord register(PreparedGeneratedFile file) {
        files.generated(file, clock.instant());
        return files.find(file.enterpriseId(), file.id(), false).orElseThrow();
    }

    public PreparedGeneratedFile toolResult(RunRecord run, ExecutionToolBinding binding, JsonNode redacted) {
        return toolResult(run, binding, redacted, 20 * 1024 * 1024);
    }

    public PreparedGeneratedFile toolResult(RunRecord run, ExecutionToolBinding binding, JsonNode redacted,
                                            int maxBytes) {
        return toolResult(run, binding, redacted, maxBytes, null);
    }

    public PreparedGeneratedFile toolResult(RunRecord run, ExecutionToolBinding binding, JsonNode redacted,
                                            int maxBytes, String callId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("文件写入不能占用数据库事务");
        }
        try {
            byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(redacted);
            var saved = storage.write(run.enterpriseId(), new ByteArrayInputStream(bytes), maxBytes);
            return new PreparedGeneratedFile(callId == null ? UUID.randomUUID().toString() : callId, run.enterpriseId(),
                run.userId(), binding.resourceId(), binding.resourceVersionId(), run.id(),
                "artifact", "工具结果.json", "application/json", saved.key(), saved.size(), saved.sha256());
        } catch (Exception failure) {
            throw new IllegalStateException("工具结果文件无法保存", failure);
        }
    }
}
