package com.stonewu.agenteam.support.execution;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.schedule.ScheduleMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleSqlMapper;
import com.stonewu.agenteam.model.schedule.entity.ScheduledTaskRow;
import com.stonewu.agenteam.service.schedule.ScheduleActionWorker;
import com.stonewu.agenteam.service.schedule.ScheduleTriggerService;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 两版定时执行验证共用真实接口准备和后台触发，不包含发行版替代配置。 */
public abstract class ScheduleExecutionTestSupport extends ExecutionApiTestSupport {
    @Autowired
    protected ScheduleTriggerService triggers;
    @Autowired
    protected ScheduleActionWorker actionWorker;

    protected Instant recentTime() {
        return Instant.now().truncatedTo(ChronoUnit.MINUTES).minusSeconds(300);
    }

    protected String path(String id) {
        return base() + "/schedules/" + id;
    }

    protected String create(Map<String, Object> input) throws Exception {
        return data(write(base() + "/schedules", input, UUID.randomUUID().toString())
            .andExpect(status().isCreated()).andReturn()).path("id").asText();
    }

    protected JsonNode detail(String id) throws Exception {
        return data(mvc.perform(get(path(id)).cookie(cookie)).andExpect(status().isOk()).andReturn());
    }

    protected JsonNode latest(String id) throws Exception {
        return detail(id).path("latestOccurrence");
    }

    protected JsonNode manual(String id, String key) throws Exception {
        var result = data(write(path(id) + "/run", null, key).andExpect(status().isAccepted()).andReturn());
        schemas.validate("ScheduleOccurrence", result);
        actionWorker.runOnce();
        // 接口重复请求返回首次排队响应；业务断言读取后台准备后的同一发生记录。
        return detail(id).path("latestOccurrence");
    }

    protected void process(String id) {
        triggers.process(new ScheduleMapper.Candidate(enterprise, admin, id));
        actionWorker.runOnce();
    }

    protected void makeDue(String id, Instant due, Instant checked) {
        databaseAccess.mapper(ScheduleSqlMapper.class).update(new LambdaUpdateWrapper<ScheduledTaskRow>()
            .eq(ScheduledTaskRow::getId, id).set(ScheduledTaskRow::getNextRunAt, Timestamp.from(due))
            .set(ScheduledTaskRow::getLastCheckedAt, Timestamp.from(checked)));
    }

    protected Map<String, Object> payload(Instant time) {
        var input = new LinkedHashMap<String, Object>();
        input.put("name", "每日整理");
        input.put("hireId", hires.forAgent(enterprise, admin, agent, false).orElseThrow().id());
        input.put("agentVersionId", version);
        input.put("inputText", "请独立整理本次事项");
        input.put("frequency", "daily");
        input.put("localDate", null);
        input.put("localTime", time.atZone(ZoneOffset.UTC).toLocalTime().toString());
        input.put("weekdays", List.of());
        input.put("monthDay", null);
        input.put("timezone", "UTC");
        input.put("enabled", true);
        input.put("maxRetries", 2);
        return input;
    }
}
