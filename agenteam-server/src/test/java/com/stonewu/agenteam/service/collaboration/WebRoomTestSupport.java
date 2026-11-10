package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRunLinkMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRunLinkRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.RunAccepted;
import com.stonewu.agenteam.model.file.request.FilePrepareRequest;
import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.service.file.FileInspectionWorker;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.ModelProfileFixture;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 网页协作场景共用同一组工作进程、模型接口和数据库，按用例清理尚未领取的工作。 */
abstract class WebRoomTestSupport extends ExecutionApiTestSupport {
    @Autowired protected CollaborationTaskListService applicationDirectory;
    private static final InvitationHttpTestEnvironment ENV = new InvitationHttpTestEnvironment();
    protected String assistant;
    @Autowired protected WebRoomService rooms;
    @Autowired protected CollaborationWorker collaborationWorker;
    @Autowired protected CollaborationInputService inputs;
    @Autowired protected CollaborationRunObserver observer;
    @Autowired protected RunMapper runs;
    @Autowired protected FileInspectionWorker inspector;
    @Autowired protected FileAccessService fileAccess;
    @Autowired protected CollaborationDispatchService dispatch;
    @Autowired protected ResourceVersionMapper versions;
    @Autowired protected CollaborationTaskMapper taskRows;
    @Autowired protected EnterpriseIntegrationMapper integrations;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry properties) {
        ENV.properties(properties);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENV.close();
    }

    @AfterEach
    void stopUnfinishedRoomWork() {
        var actor = new AuthContext(users.findById(admin).orElseThrow(), enterprise, Set.of("agent.run"));
        for (var task : taskRows.selectList(new LambdaQueryWrapper<CollaborationTaskRow>()
            .eq(CollaborationTaskRow::getEnterpriseId, enterprise).eq(CollaborationTaskRow::getOriginKind, "web")
            .notIn(CollaborationTaskRow::getStatus, "completed", "cancelled", "failed", "cancelling"))) {
            inputs.cancel(actor, task.getId(), task.getRevision(), key(), "结束本用例尚未执行的工作");
        }
    }

    @BeforeEach
    void inviteRoles() throws Exception {
        var config = (ObjectNode) versions.find(enterprise, version).orElseThrow().config().deepCopy();
        var visionModel = models.saveProfiles(List.of(new ModelProfileFixture(enterprise, admin, "room-image-model", 1, "可读取图片的模型",
            "openai", "room-vision", "http://127.0.0.1:" + modelServer.getAddress().getPort() + "/v1", "ROOM_IMAGE_TEST_SECRET",
            new ModelCapabilities(true, true, 8192, 32768, List.of("text", "image")), true)), name -> "isolated-room-image-key").getFirst();
        config.put("attachmentsEnabled", true).put("modelProfileId", visionModel).put("icon", "Box").put("color", "blue");
        assistant = data(write(base() + "/resources", Map.of("kind", "agent", "name", "成本分析师",
            "description", "核算报价", "tagIds", List.of(), "config", config), key()).andExpect(status().isCreated()).andReturn())
            .at("/resource/id").asText();
        change(HttpMethod.POST, base() + "/resources/" + assistant + "/publish", Map.of("releaseNote", "协作验收"), "1", key())
            .andExpect(status().isCreated());
        hires.establish(enterprise, admin, assistant, Instant.now());
        configureAgent(value -> value.put("attachmentsEnabled", true).putArray("pluginVersionIds"));
    }

    protected void observe(String task, String runId) {
        var link = databaseAccess.mapper(CollaborationRunLinkMapper.class).selectOne(new LambdaQueryWrapper<CollaborationRunLinkRow>()
            .eq(CollaborationRunLinkRow::getEnterpriseId, enterprise).eq(CollaborationRunLinkRow::getTaskId, task)
            .eq(CollaborationRunLinkRow::getRunId, runId));
        assertNotNull(link, "后台应领取当前用例指定的岗位工作");
        observer.observe(enterprise, link.getId());
        observer.observe(enterprise, link.getId());
    }

    protected RunRecord runNext(String task) throws Exception {
        var found = new AtomicReference<RunAccepted>();
        await(() -> {
            dispatch.claim("web-test", "web").ifPresent(lease -> found.set(dispatch.submit(lease)));
            return found.get() != null;
        });
        var accepted = found.get();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> runs.find(enterprise, accepted.runId(), false).orElseThrow().terminal());
        }
        var run = runs.find(enterprise, accepted.runId(), false).orElseThrow();
        assertEquals("completed", run.status());
        observe(task, run.id());
        return run;
    }

    protected void requestTool(HttpExchange exchange, JsonNode request, String tool, Map<String, Object> args) throws IOException {
        reply(exchange, Map.of("tool_calls", List.of(Map.of("index", 0, "id", key(), "type", "function",
            "function", Map.of("name", modelTool(request, tool), "arguments", json.writeValueAsString(args))))), "tool_calls");
    }

    protected void reply(HttpExchange exchange, Map<String, Object> delta, String finish) throws IOException {
        var chunk = Map.of("id", "web-room-model", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish)));
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(("data: " + json.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }

    protected String upload(String name, String media, byte[] contents, boolean scan) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents));
        String file = data(write(base() + "/files", new FilePrepareRequest("attachment", null, name, contents.length, media, hash), key()).andExpect(status().isCreated()).andReturn()).path("fileId").asText();
        mvc.perform(request(HttpMethod.PUT, base() + "/files/" + file + "/content").cookie(cookie)
            .header("Origin", "http://localhost:3000").header("X-CSRF-Token", csrf).contentType(media).content(contents))
            .andExpect(status().isNoContent());
        if (scan) {
            write(base() + "/files/" + file + "/complete", Map.of("sizeBytes", contents.length, "sha256", hash), key()).andExpect(status().isAccepted());
            assertTrue(inspector.runNext());
        }
        return file;
    }

    protected String key() {
        return UUID.randomUUID().toString();
    }
}
