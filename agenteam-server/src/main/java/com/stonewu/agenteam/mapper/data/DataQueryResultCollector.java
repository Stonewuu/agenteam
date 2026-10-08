package com.stonewu.agenteam.mapper.data;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.model.data.entity.DataQueryBudget;
import com.stonewu.agenteam.model.data.entity.DataQueryRows;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 达到行数或字节上限时停止读取，调用方可同时关闭外部连接。
 */
public final class DataQueryResultCollector implements ResultHandler<Map<String, Object>> {
    private final int limit;
    private final int byteBudget;
    private final DataQueryBudget deadline;
    private final BiFunction<Map<String, Object>, Integer, ObjectNode> convert;
    private final Runnable stopReading;
    private final List<ObjectNode> rows = new ArrayList<>();
    private long bytes;
    private boolean more;
    private boolean truncated;

    public DataQueryResultCollector(int limit, int byteBudget, DataQueryBudget deadline,
                                    BiFunction<Map<String, Object>, Integer, ObjectNode> convert,
                                    Runnable stopReading) {
        this.limit = limit;
        this.byteBudget = byteBudget;
        this.deadline = deadline;
        this.convert = convert;
        this.stopReading = stopReading;
    }

    @Override
    public void handleResult(ResultContext<? extends Map<String, Object>> context) {
        deadline.remainingMillis();
        if (rows.size() == limit) {
            more = true;
            stop(context);
            return;
        }
        var result = convert.apply(context.getResultObject(), rows.size());
        long size = result.toString().getBytes(StandardCharsets.UTF_8).length + 1;
        if (bytes + size > byteBudget) {
            more = true;
            truncated = true;
            stop(context);
            return;
        }
        rows.add(result);
        bytes += size;
    }

    private void stop(ResultContext<?> context) {
        context.stop();
        stopReading.run();
    }

    public DataQueryRows result() {
        return new DataQueryRows(List.copyOf(rows), more, truncated);
    }
}
