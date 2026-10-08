package com.stonewu.agenteam.service.data;

import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.data.request.DataQueryRequest;
import com.stonewu.agenteam.model.data.response.DataQueryResultView;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 查询开始和结果分别保存，不为等待外部接口持有平台数据库事务。
 */
@Service
public class DataQueryLogService {
    private final DataQueryLogTransactions logs;
    private final DataQueryService queries;
    private final ResourceJson json;

    public DataQueryLogService(DataQueryLogTransactions logs, DataQueryService queries, ResourceJson json) {
        this.logs = logs;
        this.queries = queries;
        this.json = json;
    }

    public DataQueryResultView query(AuthContext actor, String resource, DataQueryRequest input) {
        var call = logs.start(actor, resource, input);
        try {
            var result = queries.query(actor, resource, input, null, call.draftRevision());
            logs.finish(call, json.tree(result), null, null);
            return result;
        } catch (RuntimeException failed) {
            String code = failed instanceof ApiException known ? known.code() : "DATA_QUERY_FAILED";
            String message = failed instanceof ApiException known ? known.getReason() : "数据查询未能完成，请稍后重试。";
            logs.finish(call, json.tree(Map.of("isError", true, "message", message)), code, message);
            throw failed;
        }
    }
}
