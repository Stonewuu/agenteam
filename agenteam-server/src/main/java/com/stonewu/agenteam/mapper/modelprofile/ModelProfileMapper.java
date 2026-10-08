package com.stonewu.agenteam.mapper.modelprofile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileQueryRow;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileRecord;
import com.stonewu.agenteam.model.modelprofile.request.ModelProfileWriteRequest;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 模型按提供方归属保存，使用状态覆盖资源草稿、发布版本和对话执行。
 */
@Repository
public class ModelProfileMapper {
    private final ModelProfileSqlMapper statements;
    private final ObjectMapper json;

    public ModelProfileMapper(ModelProfileSqlMapper statements, ObjectMapper json) {
        this.statements = statements;
        this.json = json;
    }

    public Optional<ModelProfileRecord> find(String enterprise, String id) {
        return statements.findModelProfile(enterprise, id).stream().map(this::map).findFirst();
    }

    public List<ModelProfileRecord> list(String enterprise) {
        return statements.listModelProfile(enterprise).stream().map(this::map).toList();
    }

    public void insert(String enterprise, String id, ModelProfileWriteRequest value, Instant now) {
        statements.insertModelProfile(id, enterprise, value.providerId(), value.name(), value.modelName(),
            capabilities(value.capabilities()), value.enabled(), Timestamp.from(now));
    }

    public void update(String enterprise, String id, ModelProfileWriteRequest value, Instant now) {
        statements.updateModelProfile(value.providerId(), value.name(), value.modelName(),
            capabilities(value.capabilities()), value.enabled(), Timestamp.from(now), enterprise, id);
    }

    public void delete(String enterprise, String id) {
        statements.deleteModelProfile(enterprise, id);
    }

    public boolean inUse(String enterprise, String id) {
        return Boolean.TRUE.equals(DataAccessUtils.nullableSingleResult(statements.inUseResourceDraft(enterprise, id)));
    }

    public boolean providerInUse(String enterprise, String providerId) {
        return statements.providerInUseModelProfile(enterprise, providerId)
            .stream().anyMatch(id -> inUse(enterprise, id));
    }

    private String capabilities(ModelCapabilities value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("模型能力无法保存", error);
        }
    }

    private ModelProfileRecord map(ModelProfileQueryRow row) {
        try {
            return new ModelProfileRecord(row.getId(), row.getEnterpriseId(), row.getProviderId(), row.getName(),
                row.getModelName(), json.readValue(row.getCapabilitiesJson(), ModelCapabilities.class),
                row.getEnabled(),
                row.getRevision(), row.getProviderName(), row.getProtocol(), row.getBaseUrl(), row.getProviderEnabled(),
                row.getCreatedAt().toInstant(), row.getUpdatedAt().toInstant());
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("已保存的模型能力无法读取", error);
        }
    }
}
