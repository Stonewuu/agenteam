package com.stonewu.agenteam.mapper.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.agent.entity.EmployeeQueryRow;
import com.stonewu.agenteam.model.agent.entity.EmployeeRecord;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 广场先限制当前使用授权；本人列表只关联当前用户的雇佣和待处理申请。
 */
@Repository
public class EmployeeMapper {

    private final EmployeeSqlMapper statements;

    private final ResourceJson json;

    public EmployeeMapper(EmployeeSqlMapper statements, ResourceJson json) {
        this.statements = statements;
        this.json = json;
    }

    public List<EmployeeRecord> list(String enterprise, String user, boolean mine, ResourceQueryScope market,
                                     String query, List<String> tags, PagePosition cursor, int limit, Instant now) {
        return statements.listEmployees(enterprise, user, mine, market, query, tags, cursor, limit + 1, now).stream()
            .map(this::map).toList();
    }

    public Optional<EmployeeRecord> find(String enterprise, String user, String id, ResourceQueryScope market,
                                         Instant now) {
        return statements.findEmployee(enterprise, user, id, market, now).stream().map(this::map).findFirst();
    }

    /**
     * 默认选择先排除不能运行的雇佣，再按真实使用时间和雇佣时间排序。
     */
    public List<EmployeeRecord> recentAvailable(String user, ResourceQueryScope usage, Instant now) {
        return statements.recentEmployees(user, usage, now).stream().map(this::map).toList();
    }

    private EmployeeRecord map(EmployeeQueryRow rows) {
        return new EmployeeRecord(rows.getId(), rows.getName(), rows.getDescription(), rows.getBusinessRole(),
            rows.getIcon(), rows.getColor(), strings(rows.getExamples()), strings(rows.getSkillVersions()),
            rows.getWelcomeMessage(), strings(rows.getSuggestedQuestions()), rows.getResourceStatus(),
            rows.getOwnerActive(), rows.getVersionStatus(), rows.getModelAvailable(), rows.getHireId(),
            rows.getHireRevision(), rows.getApplicationId(), rows.getApplicationRevision(), rows.getHireStatus(),
            rows.getListed(), rows.getRequiresApproval(), rows.getAttachmentsEnabled(),
            rows.getPositionTime().toInstant());
    }

    private List<String> strings(String value) {
        JsonNode array = json.read(value);
        if (array == null || array.isNull()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        array.forEach(item -> result.add(item.asText()));
        return List.copyOf(result);
    }
}
