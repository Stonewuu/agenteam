package com.stonewu.agenteam.mapper.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.query.MappedCallbackFailures;
import com.stonewu.agenteam.model.data.entity.DataQueryBudget;
import com.stonewu.agenteam.model.data.entity.DataQueryParameters;
import com.stonewu.agenteam.model.data.entity.DataQueryPlan;
import com.stonewu.agenteam.model.data.entity.DataQueryRows;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 读取已保存的数据字段，保留空值、字节上限和取消能力。
 */
@Repository
public class FileDataQueryMapper {
    private final FileDataQuerySqlMapper statements;
    private final ObjectMapper json;

    public FileDataQueryMapper(FileDataQuerySqlMapper statements, ObjectMapper json) {
        this.statements = statements;
        this.json = json;
    }

    public DataQueryRows query(DataQueryPlan plan, long deadline, int byteBudget, ToolCallControl control) {
        var budget = new DataQueryBudget(deadline);
        var results = new DataQueryResultCollector(plan.limit(), byteBudget, budget,
            (row, index) -> readRow(plan, row, byteBudget), () -> {
        });
        try {
            statements.readRows(DataQueryParameters.prepare(plan, null), budget, control, results);
        } catch (RuntimeException failure) {
            throw MappedCallbackFailures.propagate(failure);
        }
        return results.result();
    }

    private ObjectNode readRow(DataQueryPlan plan, Map<String, Object> row, int byteBudget) {
        ObjectNode result = json.createObjectNode();
        try {
            for (int i = 0; i < plan.fields().size(); i++) {
                String text = (String) row.get("v" + i);
                if (text == null) {
                    throw new IllegalStateException("当前数据行缺少已声明字段");
                }
                if (text.length() > 1024 * 1024) {
                    throw tooLarge();
                }
                result.set(plan.fields().get(i).name(), json.readTree(text));
                if (result.toString().getBytes(StandardCharsets.UTF_8).length > byteBudget) {
                    throw tooLarge();
                }
            }
            return result;
        } catch (ApiException failed) {
            throw failed;
        } catch (Exception invalid) {
            throw new IllegalStateException("已保存的数据行无法读取", invalid);
        }
    }

    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "DATA_RESULT_TOO_LARGE",
            "字段内容超过读取上限，请减少返回字段或拆分原始资料。");
    }
}
