package com.stonewu.agenteam.mapper.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.query.MappedCallbackFailures;
import com.stonewu.agenteam.model.data.entity.*;
import com.stonewu.agenteam.service.data.DataValueCodec;
import com.stonewu.agenteam.service.data.mysql.MysqlReadOnlyConnections;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.apache.ibatis.exceptions.PersistenceException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读取实际确认的单表或只读视图，不接受用户语句、连接表达式或存储过程。
 */
@Component
public class MysqlDataQueryMapper {
    private final MysqlReadOnlyConnections connections;
    private final MysqlSourceSchema schema;
    private final ObjectMapper json;
    private final DataValueCodec values;

    public MysqlDataQueryMapper(MysqlReadOnlyConnections connections, MysqlSourceSchema schema, ObjectMapper json,
                                DataValueCodec values) {
        this.connections = connections;
        this.schema = schema;
        this.json = json;
        this.values = values;
    }

    public DataQueryRows query(String enterprise, JsonNode config, DataQueryPlan plan, long deadline, int byteBudget,
                               ToolCallControl control) {
        control.requireActive();
        try (var session = control.track(connections.open(enterprise, config, deadline, control))) {
            var used = new LinkedHashMap<String, DataField>();
            plan.fields().forEach(field -> used.put(field.name(), field));
            plan.conditions().forEach(item -> used.put(item.field().name(), item.field()));
            plan.order().forEach(item -> used.put(item.field().name(), item.field()));
            schema.require(session, plan.collection().sourceName(), List.copyOf(used.values()));
            var budget = new DataQueryBudget(deadline);
            var results = new DataQueryResultCollector(plan.limit(), byteBudget, budget,
                (row, index) -> readRow(plan, row, index), session.scope()::close);
            try {
                session.mapper()
                    .readRows(DataQueryParameters.prepare(plan, session.database()), budget, byteBudget, control,
                        results);
            } catch (RuntimeException failure) {
                session.scope().close();
                throw MappedCallbackFailures.propagate(failure);
            }
            return results.result();
        } catch (PersistenceException | SQLException failed) {
            for (Throwable cause = failed; cause != null; cause = cause.getCause()) {
                if (cause instanceof ApiException api) {
                    throw api;
                }
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DATA_QUERY_FAILED",
                "数据库查询暂时未完成，请检查当前字段和连接。", failed);
        }
    }

    private ObjectNode readRow(DataQueryPlan plan, Map<String, Object> row, int index) {
        if (Boolean.TRUE.equals(row.get("too_large"))) {
            throw tooLarge();
        }
        ObjectNode result = json.createObjectNode();
        for (int i = 0; i < plan.fields().size(); i++) {
            var field = plan.fields().get(i);
            String text = (String) row.get("v" + i);
            if (field.valueType().equals("object") && "null".equals(text)) {
                text = null;
            }
            result.set(field.name(), values.csv(field, text, plan.offset() + index + 1));
        }
        return result;
    }

    private ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "DATA_RESULT_TOO_LARGE",
            "单行结果超过大小限制，请减少返回字段。");
    }
}
