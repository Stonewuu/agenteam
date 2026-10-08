package com.stonewu.agenteam.mapper.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.execution.response.ExecutionEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 可选的只读样本校验：只处理本地导出的事件，不连接或修改业务数据库。
 */
class ExecutionEventSampleTest {
    @Test
    @EnabledIfSystemProperty(named = "eventStorageSample", matches = ".+")
    void compactsLocalSampleAndVerifiesEveryOriginalEvent() throws Exception {
        var json = new ObjectMapper();
        var canonical = new ResourceJson(json);
        var events = new ExecutionEventCodec(json, canonical);
        var batches = new ExecutionDeltaBatchCodec(canonical, events);
        var pending = new ArrayList<ExecutionEvent>();
        long originalRows = 0, originalBytes = 0, compactRows = 0, compactBytes = 0, deltaRows = 0, deltaBytes = 0;
        long compactDeltaRows = 0, compactDeltaBytes = 0;
        Path sample = Path.of(System.getProperty("eventStorageSample"));
        try (var lines = Files.lines(sample, StandardCharsets.UTF_8)) {
            for (var iterator = lines.iterator(); iterator.hasNext(); ) {
                String line = iterator.next();
                var row = json.readTree(line.startsWith("\uFEFF") ? line.substring(1) : line);
                String data = row.path("payload").toString();
                int version = row.path("payload").path("storageVersion").asInt(1);
                for (var event : batches.decode(version, data, row.path("hash").asText())) {
                    originalRows++;
                    long bytes = events.encode(event).data().getBytes(StandardCharsets.UTF_8).length;
                    originalBytes += bytes;
                    if (!pending.isEmpty() && !batches.canAppend(pending, event)) {
                        long saved = verify(batches, pending);
                        compactRows++;
                        compactBytes += saved;
                        compactDeltaRows++;
                        compactDeltaBytes += saved;
                        pending.clear();
                    }
                    if (batches.compactable(event)) {
                        deltaRows++;
                        deltaBytes += bytes;
                        pending.add(event);
                    } else {
                        compactRows++;
                        compactBytes += bytes;
                    }
                }
            }
        }
        if (!pending.isEmpty()) {
            long saved = verify(batches, pending);
            compactRows++;
            compactBytes += saved;
            compactDeltaRows++;
            compactDeltaBytes += saved;
        }
        var report = new LinkedHashMap<String, Long>();
        report.put("originalRows", originalRows);
        report.put("originalBytes", originalBytes);
        report.put("compactedRows", compactRows);
        report.put("compactedBytes", compactBytes);
        report.put("originalDeltaRows", deltaRows);
        report.put("originalDeltaBytes", deltaBytes);
        report.put("compactedDeltaRows", compactDeltaRows);
        report.put("compactedDeltaBytes", compactDeltaBytes);
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of(sample + ".report.json").toFile(), report);
    }

    private long verify(ExecutionDeltaBatchCodec batches, List<ExecutionEvent> pending) {
        var stored = batches.encode(pending);
        assertEquals(pending, batches.decode(2, stored.data(), stored.hash()), "合并必须逐字段还原全部原事件");
        return stored.data().getBytes(StandardCharsets.UTF_8).length;
    }
}
