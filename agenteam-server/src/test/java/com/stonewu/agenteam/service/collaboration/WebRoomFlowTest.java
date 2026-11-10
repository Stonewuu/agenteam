package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationArtifactMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationArtifactRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.model.file.response.FileSummaryView;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InProcessDocumentParsing;
import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({SharedEnterpriseTestEdition.class, InProcessDocumentParsing.class})
@SpringBootTest(properties = {"chat.worker-enabled=false", "collaboration.web-worker-enabled=false", "execution.worker.enabled=false"})
class WebRoomFlowTest extends WebRoomTestSupport {
    @Test
    void webMessageCreatesOneTaskAndOnlyItsOwnerCanReadIt() throws Exception {
        String path = base() + "/collaboration/rooms";
        var ids = List.of(agent, assistant);
        write(path, Map.of("agentIds", List.of(agent, agent)), key()).andExpect(status().isUnprocessableEntity());
        var request = Map.of("title", "比较报价", "agentIds", ids);
        String createKey = key();
        String room = data(write(path, request, createKey).andExpect(status().isCreated()).andReturn()).path("roomId").asText();
        assertEquals(room, data(write(path, request, createKey).andExpect(status().isCreated()).andReturn()).path("roomId").asText());
        var actor = new AuthContext(users.findById(admin).orElseThrow(), enterprise, Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
        assertNull(rooms.detail(actor, room, 0).task());
        var other = EnterpriseTestData.member(users, permissions, enterprise, "other-room-user", "local-test-password", "另一名成员", List.of());
        var denied = assertThrows(ApiException.class, () -> rooms.detail(new AuthContext(other, enterprise, Set.of("agent.run")), room, 0));
        assertEquals(404, denied.getStatusCode().value());

        String send = path + "/" + room + "/messages";
        String message = key();
        write(send, Map.of("clientMessageId", key(), "intent", "start", "text", "未指定成员"), key()).andExpect(status().isUnprocessableEntity());
        write(send, Map.of("clientMessageId", key(), "intent", "start", "recipientAgentId", key(), "text", "未受邀"), key()).andExpect(status().isUnprocessableEntity());
        String unchecked = upload("尚未检查.txt", "text/plain", new byte[]{65}, false);
        write(send, Map.of("clientMessageId", message, "intent", "start", "recipientAgentId", agent, "text", "", "attachmentIds", List.of(unchecked)), key())
            .andExpect(status().isConflict());
        assertNull(rooms.detail(actor, room, 0).task());
        assertTrue(rooms.detail(actor, room, 0).entries().isEmpty());
        var input = Map.of("clientMessageId", message, "intent", "start", "recipientAgentId", agent, "text", "请一起比较报价");
        String task = data(write(send, input, key()).andExpect(status().isAccepted()).andReturn()).path("taskId").asText();
        assertEquals(task, data(write(send, input, key()).andExpect(status().isAccepted()).andReturn()).path("taskId").asText());
        write(send, Map.of("clientMessageId", message, "intent", "start", "recipientAgentId", agent, "text", "换一个目标"), key()).andExpect(status().isConflict());
        write(send, Map.of("clientMessageId", key(), "intent", "start", "recipientAgentId", agent, "text", "另开任务"), key()).andExpect(status().isConflict());
        assertEquals(0L, integrations.selectCount(new LambdaQueryWrapper<EnterpriseIntegrationRow>().eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise)));
        var saved = taskRows.selectOne(new LambdaQueryWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, enterprise)
            .eq(CollaborationTaskRow::getWebRoomId, room));
        assertEquals("default", json.readTree(saved.getPolicySnapshot()).path("approvalPolicy").asText());
        assertNull(saved.getEntryBotId());
        assertNull(saved.getOriginUserBindingId());
        assertEquals(1, rooms.detail(actor, room, 0).entries().size());
        long initialRevision = rooms.detail(actor, room, 0).task().inputRevision();
        var addition = Map.of("clientMessageId", key(), "intent", "continue", "taskId", task,
            "expectedInputRevision", initialRevision, "text", "请把运费也算进去");
        String entry = data(write(send, addition, key()).andExpect(status().isAccepted()).andReturn()).path("entryId").asText();
        assertEquals(entry, data(write(send, addition, key()).andExpect(status().isAccepted()).andReturn()).path("entryId").asText());
        write(send, Map.of("clientMessageId", key(), "intent", "continue", "taskId", task,
            "expectedInputRevision", initialRevision, "text", "另一份过时的补充"), key()).andExpect(status().isConflict());
        var updated = rooms.detail(actor, room, 0);
        assertEquals(initialRevision + 1, updated.task().inputRevision());
        assertEquals(2, updated.entries().stream().filter(row -> "user".equals(row.kind())).count());
        assertEquals("请把运费也算进去", updated.entries().getLast().text());
        assertTrue(dispatch.claim("external-worker", "external_chat").isEmpty());
        var lease = dispatch.claim("web-worker", "web").orElseThrow();
        assertEquals(saved.getOwnerWorkId(), lease.workId());
        assertNotNull(dispatch.submit(lease));
        assertTrue(dispatch.claim("second-web-worker", "web").isEmpty());
    }

    @Test
    void analystDeliversToSameLeadConversationAndOnlyOneSummaryAppears() throws Exception {
        String path = base() + "/collaboration/rooms";
        String room = data(write(path, Map.of("agentIds", List.of(agent, assistant), "leadAgentId", agent), key())
            .andExpect(status().isCreated()).andReturn()).path("roomId").asText();
        var actor = new AuthContext(users.findById(admin).orElseThrow(), enterprise, Set.of("agent.run"));
        assertTrue(rooms.detail(actor, room, 0).room().title().contains("成本分析师"));
        String send = path + "/" + room + "/messages";
        String marker = "验收清单号 QF-7621";
        String doc = upload("报价清单.txt", "text/plain", marker.getBytes(StandardCharsets.UTF_8), true);
        byte[] pixels = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a0U8AAAAASUVORK5CYII=");
        String picture = upload("箱标.png", "image/png", pixels, true);
        String message = key();
        var sendFiles = Map.of("clientMessageId", message, "intent", "start", "recipientAgentId", agent, "text", "", "attachmentIds", List.of(doc, picture));
        String task = data(write(send, sendFiles, key()).andExpect(status().isAccepted()).andReturn()).path("taskId").asText();
        assertEquals(task, data(write(send, sendFiles, key()).andExpect(status().isAccepted()).andReturn()).path("taskId").asText());
        write(send, Map.of("clientMessageId", message, "intent", "start", "recipientAgentId", agent, "text", "", "attachmentIds", List.of(doc)), key()).andExpect(status().isConflict());
        var initial = rooms.detail(actor, room, 0);
        assertEquals(List.of(doc, picture), initial.entries().getFirst().attachments().stream().map(FileSummaryView::id).toList());
        assertNull(fileAccess.ready(actor, picture).expiresAt());
        var stranger = EnterpriseTestData.member(users, permissions, enterprise, "file-isolation", "local-test-password", "另一成员", List.of());
        assertThrows(ApiException.class, () -> fileAccess.ready(new AuthContext(stranger, enterprise, Set.of("agent.run")), picture));
        var allFiles = databaseAccess.mapper(CollaborationArtifactMapper.class).selectList(new LambdaQueryWrapper<CollaborationArtifactRow>()
            .eq(CollaborationArtifactRow::getEnterpriseId, enterprise).eq(CollaborationArtifactRow::getTaskId, task)
            .eq(CollaborationArtifactRow::getSchemaName, "web_message_attachment_v1"));
        assertEquals(2, allFiles.size());
        var refs = allFiles.stream().map(file -> Map.of("artifactId", file.getId(), "version", file.getArtifactVersion())).toList();
        var analyst = databaseAccess.mapper(CollaborationParticipantMapper.class).selectOne(new LambdaQueryWrapper<CollaborationParticipantRow>()
            .eq(CollaborationParticipantRow::getEnterpriseId, enterprise).eq(CollaborationParticipantRow::getTaskId, task)
            .eq(CollaborationParticipantRow::getAgentId, assistant));

        // 最新一条消息是纯文字；先前上传的图片只通过负责人明确委派的文件进入协作者执行。
        write(send, Map.of("clientMessageId", key(), "intent", "continue", "taskId", task, "expectedInputRevision", 1,
            "text", "请继续核对这份清单"), key()).andExpect(status().isAccepted());
        assertTrue(rooms.detail(actor, room, 0).entries().getLast().attachments().isEmpty());
        allowModelCalls = true;
        var turn = new AtomicInteger();
        var leadSawTextOnly = new AtomicBoolean();
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            if (turn.get() == 0) {
                var messages = request.path("messages").toString();
                leadSawTextOnly.set(messages.contains(marker) && !messages.contains("data:image/"));
            }
            switch (turn.getAndIncrement()) {
                case 0 -> requestTool(exchange, request, "collab_assign", Map.of("expectedInputRevision", 2, "targetRoleId", analyst.getId(),
                    "goal", "核算运费", "acceptanceCriteria", "给出含运总价", "artifactRefs", refs, "dependsOnWorkIds", List.of(), "required", true));
                case 1 -> {
                    var work = databaseAccess.mapper(CollaborationWorkItemMapper.class).selectOne(new LambdaQueryWrapper<CollaborationWorkItemRow>()
                        .eq(CollaborationWorkItemRow::getEnterpriseId, enterprise).eq(CollaborationWorkItemRow::getTaskId, task)
                        .eq(CollaborationWorkItemRow::getParticipantId, analyst.getId()));
                    requestTool(exchange, request, "collab_wait", Map.of("expectedInputRevision", 2,
                        "workItemIds", List.of(work.getId()), "reason", "等待报价核算"));
                }
                default -> reply(exchange, Map.of("content", "已安排核算。"), "stop");
            }
        };
        var leadRun = runNext(task);
        assertTrue(leadSawTextOnly.get(), "文本模型应读到文件正文，并且不应收到图片数据");
        String amount = "报价核算结果：含运 8100 元。";
        modelResponse = exchange -> {
            String context = json.readTree(exchange.getRequestBody()).path("messages").toString();
            reply(exchange, Map.of("content", context.contains(marker) && context.contains(Base64.getEncoder().encodeToString(pixels)) ? amount : "材料未按模型能力送达"), "stop");
        };
        var analystRun = runNext(task);
        assertNotEquals(leadRun.conversationId(), analystRun.conversationId());
        String finalAnswer = "根据成本分析师提交的 8100 元含运报价，建议比较交期。";
        modelResponse = exchange -> {
            String context = json.readTree(exchange.getRequestBody()).path("messages").toString();
            reply(exchange, Map.of("content", context.contains("8100") ? finalAnswer : "没有收到分析师的金额。"), "stop");
        };
        var resumed = runNext(task);
        assertEquals(leadRun.conversationId(), resumed.conversationId());
        var result = rooms.detail(actor, room, 0);
        assertEquals(agent, result.task().ownerAgentId());
        var submission = result.entries().stream().filter(entry -> entry.kind().equals("work_completed")).findFirst().orElseThrow();
        assertEquals("Box", submission.authorIcon());
        assertEquals("blue", submission.authorColor());
        assertEquals("Sparkles", result.entries().getLast().authorIcon());
        assertEquals("completed", result.task().status());
        assertEquals(List.of(amount), result.entries().stream().filter(e -> e.kind().equals("work_completed")).map(WebRoomTimeline.Entry::text).toList());
        assertEquals(List.of(finalAnswer), result.entries().stream().filter(e -> e.kind().equals("completed")).map(WebRoomTimeline.Entry::text).toList());
        assertEquals(0L, databaseAccess.mapper(ChatOutboundMessageMapper.class).selectCount(new LambdaQueryWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise)));

        String nextTask = data(write(send, Map.of("clientMessageId", key(), "intent", "start", "recipientAgentId", agent, "text", "另开任务"), key())
            .andExpect(status().isAccepted()).andReturn()).path("taskId").asText();
        assertTrue(rooms.detail(actor, room, 0).entries().getLast().attachments().isEmpty());
        assertEquals(0L, databaseAccess.mapper(CollaborationArtifactMapper.class).selectCount(new LambdaQueryWrapper<CollaborationArtifactRow>()
            .eq(CollaborationArtifactRow::getEnterpriseId, enterprise).eq(CollaborationArtifactRow::getTaskId, nextTask)));
        String followUp = key();
        var extra = Map.of("clientMessageId", followUp, "intent", "continue", "expectedInputRevision", 1, "taskId", nextTask,
            "text", "", "attachmentIds", List.of(picture));
        write(send, extra, key()).andExpect(status().isAccepted());
        write(send, extra, key()).andExpect(status().isAccepted());
        var afterFiles = rooms.detail(actor, room, 0);
        assertEquals(2, afterFiles.task().inputRevision());
        assertEquals(List.of(picture), afterFiles.entries().getLast().attachments().stream().map(FileSummaryView::id).toList());
        write(send, Map.of("clientMessageId", key(), "intent", "continue", "expectedInputRevision", 2, "taskId", nextTask,
            "text", "纯文字补充"), key()).andExpect(status().isAccepted());
        assertTrue(rooms.detail(actor, room, 0).entries().getLast().attachments().isEmpty());
        var conflict = assertThrows(ApiException.class, () -> inputs.append(actor, task, result.task().revision(), key(), "不要重开旧任务", null));
        assertEquals("ROOM_TASK_CHANGED", conflict.code());
        assertEquals("completed", taskRows.selectById(task).getStatus());
        var next = rooms.detail(actor, room, 0).task();
        inputs.cancel(actor, next.id(), next.revision(), key(), "取消用例中尚未执行的新任务");
        assertEquals("cancelled", rooms.detail(actor, room, 0).task().status());
    }

    @Test
    void mentionsKeepBothTasksRunningAndShareOnlyVisibleRepliesInTheSameTopic() throws Exception {
        String path = base() + "/collaboration/rooms";
        String room = data(write(path, Map.of("agentIds", List.of(agent, assistant)), key()).andExpect(status().isCreated()).andReturn()).path("roomId").asText();
        String send = path + "/" + room + "/messages";
        String first = data(write(send, Map.of("clientMessageId", key(), "intent", "start", "recipientAgentId", agent, "text", "查采购报价"), key())
            .andExpect(status().isAccepted()).andReturn()).path("taskId").asText();
        String message = key();
        var askB = Map.of("clientMessageId", message, "intent", "continue", "taskId", first, "expectedInputRevision", 1,
            "recipientAgentId", assistant, "text", "核对另一个报价");
        String second = data(write(send, askB, key()).andExpect(status().isAccepted()).andReturn()).path("taskId").asText();
        assertNotEquals(first, second);
        assertEquals(second, data(write(send, askB, key()).andExpect(status().isAccepted()).andReturn()).path("taskId").asText());
        write(send, Map.of("clientMessageId", message, "intent", "continue", "taskId", first, "expectedInputRevision", 1,
            "recipientAgentId", agent, "text", "核对另一个报价"), key()).andExpect(status().isConflict());
        var actor = new AuthContext(users.findById(admin).orElseThrow(), enterprise, Set.of("agent.run"));
        assertEquals(Set.of(first, second), Set.copyOf(rooms.detail(actor, room, 0).activeTaskIds()));
        assertEquals(1L, taskRows.selectById(first).getInputRevision());
        assertEquals(assistant, rooms.detail(actor, room, 0).task().ownerAgentId());
        String marker = "交期标记松柏7314";
        allowModelCalls = true;
        modelResponse = exchange -> reply(exchange, Map.of("content", marker + "长".repeat(2050) + "完整末尾记号"), "stop");
        var firstRun = runNext(first);
        var sawMarker = new AtomicBoolean();
        modelResponse = exchange -> {
            String seen = json.readTree(exchange.getRequestBody()).path("messages").toString();
            sawMarker.set(seen.contains(marker));
            assertFalse(seen.contains("完整末尾记号"), "其他成员的长回复应按公开消息摘录上限截取");
            reply(exchange, Map.of("content", "成本分析师已核对交期"), "stop");
        };
        runNext(second);
        assertTrue(sawMarker.get(), "同一讨论中能读取另一位成员已经公开的回复");
        var view = rooms.detail(actor, room, 0);
        assertEquals(2, view.entries().stream().filter(e -> "completed".equals(e.kind())).count());
        assertTrue(view.activeTaskIds().isEmpty());
        assertEquals("成本分析师", view.entries().stream().filter(e -> "核对另一个报价".equals(e.text())).findFirst().orElseThrow().recipientName());
        var readOtherReply = modelResponse;
        var returnedToA = Map.of("clientMessageId", key(), "intent", "continue", "taskId", second, "expectedInputRevision", 1,
            "recipientAgentId", agent, "text", "结合刚才两个人的结论继续");
        assertEquals(first, data(write(send, returnedToA, key()).andExpect(status().isAccepted()).andReturn()).path("taskId").asText());
        var sawOwnAndShared = new AtomicBoolean();
        modelResponse = exchange -> {
            String seen = json.readTree(exchange.getRequestBody()).path("messages").toString();
            sawOwnAndShared.set(seen.contains("完整末尾记号") && seen.contains("成本分析师已核对交期"));
            reply(exchange, Map.of("content", "已结合两位的结论"), "stop");
        };
        assertEquals(firstRun.conversationId(), runNext(first).conversationId());
        assertTrue(sawOwnAndShared.get(), "切回 A 后要保留 A 自己的对话，也要读到 B 已公开的回答");

        modelResponse = readOtherReply;
        String newTopic = data(write(send, Map.of("clientMessageId", key(), "intent", "start", "recipientAgentId", assistant, "text", "新问题"), key())
            .andExpect(status().isAccepted()).andReturn()).path("taskId").asText();
        runNext(newTopic);
        assertFalse(sawMarker.get(), "新任务不带入上一轮讨论");
    }

    @Test
    void firstMentionsFromTwoEmptyTabsStartBothRecipientsInOneDiscussion() throws Exception {
        String path = base() + "/collaboration/rooms";
        String room = data(write(path, Map.of("agentIds", List.of(agent, assistant)), key()).andExpect(status().isCreated()).andReturn()).path("roomId").asText();
        String send = path + "/" + room + "/messages";
        String a = data(write(send, Map.of("clientMessageId", key(), "intent", "mention", "recipientAgentId", agent, "text", "报到"), key())
            .andExpect(status().isAccepted()).andReturn()).path("taskId").asText();
        String b = data(write(send, Map.of("clientMessageId", key(), "intent", "mention", "recipientAgentId", assistant, "text", "报到"), key())
            .andExpect(status().isAccepted()).andReturn()).path("taskId").asText();
        assertNotEquals(a, b);
        assertEquals(taskRows.selectById(a).getWebTopicId(), taskRows.selectById(b).getWebTopicId());
        var taskA = taskRows.selectById(a);
        assertEquals(1L, taskA.getInputRevision());
        var actor = new AuthContext(users.findById(admin).orElseThrow(), enterprise, Set.of("agent.run"));
        inputs.append(actor, a, taskA.getRevision(), key(), "补充给第一位成员的要求", null);
        assertEquals(b, rooms.detail(actor, room, 0).task().id());
        assertEquals(2L, taskRows.selectById(a).getInputRevision());
    }

    @Test
    void directoryCombinesEmptyRoomsAndTasksWithOneSearchAndCursor() throws Exception {
        String path = base() + "/collaboration/rooms";
        var actor = new AuthContext(users.findById(admin).orElseThrow(), enterprise, Set.of("agent.run"));
        var directory = applicationDirectory;
        var members = List.of(agent, assistant);
        String a = data(write(path, Map.of("title", "采购甲组", "agentIds", members), key()).andExpect(status().isCreated()).andReturn()).path("roomId").asText();
        String b = data(write(path, Map.of("title", "采购乙组", "agentIds", members), key()).andExpect(status().isCreated()).andReturn()).path("roomId").asText();
        var first = directory.directory(actor, "not_started", "采购", null, 1);
        var second = directory.directory(actor, "not_started", "采购", first.nextCursor(), 1);
        assertTrue(first.hasMore());
        assertFalse(second.hasMore());
        assertEquals(Set.of(a, b), Set.of(first.items().getFirst().room().id(), second.items().getFirst().room().id()));
        String task = data(write(path + "/" + a + "/messages", Map.of("clientMessageId", key(), "intent", "mention",
            "recipientAgentId", agent, "text", "请核算运费"), key()).andExpect(status().isAccepted()).andReturn()).path("taskId").asText();
        var all = directory.directory(actor, null, "采购", null, 30);
        assertEquals(2, all.items().size());
        assertEquals(List.of(b), all.items().stream().filter(e -> e.room() != null).map(e -> e.room().id()).toList());
        assertEquals(List.of(task), all.items().stream().filter(e -> e.task() != null).map(e -> e.task().id()).toList());
        var found = directory.directory(actor, "active", "采购甲组", null, 30).items().getFirst().task();
        assertEquals("请核算运费", found.title());
        assertEquals("采购甲组", found.source().name());
        assertTrue(directory.directory(actor, "not_started", "采购甲组", null, 30).items().isEmpty());
        assertThrows(ApiException.class, () -> directory.directory(actor, "active", "采购", first.nextCursor(), 1));
    }

    @Test
    void aMentionWaitsForItsMembersToolWhileAnotherMemberFinishesIndependently() throws Exception {
        String path = base() + "/collaboration/rooms";
        String room = data(write(path, Map.of("agentIds", List.of(agent, assistant)), key()).andExpect(status().isCreated()).andReturn()).path("roomId").asText();
        String send = path + "/" + room + "/messages";
        String taskA = data(write(send, Map.of("clientMessageId", key(), "intent", "mention", "recipientAgentId", agent,
            "text", "开始核对"), key()).andExpect(status().isAccepted()).andReturn()).path("taskId").asText();
        var actor = new AuthContext(users.findById(admin).orElseThrow(), enterprise, Set.of("agent.run"));
        String file = upload("补充说明.txt", "text/plain", "文件中有验收标记Q39".getBytes(StandardCharsets.UTF_8), true);
        var firstCall = new CountDownLatch(1);
        var letToolRun = new CountDownLatch(1);
        var requestsA = new CopyOnWriteArrayList<JsonNode>();
        var requestsB = new CopyOnWriteArrayList<JsonNode>();
        allowModelCalls = true;
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            if ("room-vision".equals(request.path("model").asText())) {
                requestsB.add(request);
                reply(exchange, Map.of("content", "B已经完成"), "stop");
                return;
            }
            requestsA.add(request);
            if (requestsA.size() == 1) {
                firstCall.countDown();
                try {
                    if (!letToolRun.await(25, TimeUnit.SECONDS)) {
                        throw new IOException("尚未释放第一位成员的工具请求");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
                reply(exchange, Map.of("tool_calls", List.of(Map.of("index", 0, "id", "room-act-one", "type", "function",
                    "function", Map.of("name", modelTool(request, "collab_list_roles"), "arguments", "{}")))), "tool_calls");
            } else {
                reply(exchange, Map.of("content", "A完成补充" + requestsA.size()), "stop");
            }
        };
        var initial = dispatch.submit(dispatch.claim("first", "web").orElseThrow());
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            assertTrue(firstCall.await(12, TimeUnit.SECONDS));
            String client = key();
            var withFile = Map.of("clientMessageId", client, "intent", "continue", "taskId", taskA, "expectedInputRevision", 1,
                "recipientAgentId", agent, "text", "", "attachmentIds", List.of(file));
            var one = data(write(send, withFile, key()).andExpect(status().isAccepted()).andReturn());
            assertEquals(one.path("entryId"), data(write(send, withFile, key()).andExpect(status().isAccepted()).andReturn()).path("entryId"));
            var two = data(write(send, Map.of("clientMessageId", key(), "intent", "continue", "taskId", taskA, "expectedInputRevision", 1,
                "recipientAgentId", agent, "text", "先算运费"), key()).andExpect(status().isAccepted()).andReturn());
            var removed = data(write(send, Map.of("clientMessageId", key(), "intent", "continue", "taskId", taskA, "expectedInputRevision", 1,
                "recipientAgentId", agent, "text", "撤回这条草稿"), key()).andExpect(status().isAccepted()).andReturn());
            String queue = path + "/" + room + "/queue/";
            write(queue + two.path("entryId").asText() + "/prioritize", null, key()).andExpect(status().isOk());
            change(HttpMethod.DELETE, queue + removed.path("entryId").asText(), null, null, key()).andExpect(status().isOk());
            var detail = rooms.detail(actor, room, 0);
            assertEquals(List.of("先算运费", "补充说明.txt"), detail.queuedMessages().stream().map(WebRoomTimeline.Queued::text).toList());
            assertEquals(1L, detail.task().inputRevision());
            assertEquals(1, detail.entries().stream().filter(e -> e.kind().equals("user")).count());
            var b = data(write(send, Map.of("clientMessageId", key(), "intent", "continue", "taskId", taskA, "expectedInputRevision", 1,
                "recipientAgentId", assistant, "text", "另算包装"), key()).andExpect(status().isAccepted()).andReturn());
            String taskB = b.path("taskId").asText();
            var followB = data(write(send, Map.of("clientMessageId", key(), "intent", "continue", "taskId", taskB,
                "expectedInputRevision", 1, "recipientAgentId", assistant, "text", "再算包装数量"), key())
                .andExpect(status().isAccepted()).andReturn());
            assertEquals("queued", followB.path("status").asText());
            collaborationWorker.deliverWaiting();
            runNext(taskB);
            collaborationWorker.deliverWaiting();
            runNext(taskB);
            assertEquals("running", runs.find(enterprise, initial.runId(), false).orElseThrow().status());
            assertEquals(2, requestsB.size());
            assertFalse(requestsB.getFirst().toString().contains("再算包装数量"));
            assertTrue(requestsB.getLast().toString().contains("再算包装数量"));
            assertFalse(requestsB.getFirst().toString().contains("先算运费"));
            assertFalse(requestsB.getFirst().toString().contains("验收标记Q39"));
            letToolRun.countDown();
            await(() -> runs.find(enterprise, initial.runId(), false).orElseThrow().terminal());
            assertEquals(1, requestsA.size(), "工具返回后不能再以旧输入请求模型");
            observe(taskA, initial.runId());
            assertTrue(rooms.detail(actor, room, 0).entries().stream().noneMatch(row -> row.kind().equals("completed") && taskA.equals(row.taskId())));
            collaborationWorker.deliverWaiting();
            var next = runNext(taskA);
            assertEquals(initial.conversationId(), next.conversationId());
            assertTrue(modelTextForCall(requestsA.get(1), "room-act-one").contains("成本分析师"));
            assertTrue(requestsA.get(1).toString().contains("先算运费"));
            assertFalse(requestsA.get(1).toString().contains("验收标记Q39"));
            collaborationWorker.deliverWaiting();
            runNext(taskA);
            assertEquals(3, requestsA.size());
            assertTrue(requestsA.getLast().toString().contains("验收标记Q39"));
            assertFalse(requestsA.getLast().toString().contains("撤回这条草稿"));
            var end = rooms.detail(actor, room, 0);
            assertTrue(end.queuedMessages().isEmpty());
            assertEquals(assistant, end.task().ownerAgentId(), "后台处理 A 的消息不能把当前成员从 B 切走");
            assertEquals(5, end.entries().stream().filter(row -> row.kind().equals("user")).count());
        } finally {
            letToolRun.countDown();
        }
    }

    @Test
    void directMentionWaitsForDelegatedWorkAndThenRestoresThatWorksToolResult() throws Exception {
        String path = base() + "/collaboration/rooms";
        String room = data(write(path, Map.of("agentIds", List.of(agent, assistant)), key()).andExpect(status().isCreated()).andReturn()).path("roomId").asText();
        String send = path + "/" + room + "/messages";
        String original = data(write(send, Map.of("clientMessageId", key(), "intent", "mention", "recipientAgentId", agent,
            "text", "请委派成本核算，等结果再汇总"), key()).andExpect(status().isAccepted()).andReturn()).path("taskId").asText();
        var delegate = databaseAccess.mapper(CollaborationParticipantMapper.class).selectOne(new LambdaQueryWrapper<CollaborationParticipantRow>()
            .eq(CollaborationParticipantRow::getEnterpriseId, enterprise).eq(CollaborationParticipantRow::getTaskId, original)
            .eq(CollaborationParticipantRow::getAgentId, assistant));
        var leadCalls = new AtomicInteger();
        var helperCalls = new CopyOnWriteArrayList<JsonNode>();
        var working = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        allowModelCalls = true;
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            if (!"room-vision".equals(request.path("model").asText())) {
                switch (leadCalls.getAndIncrement()) {
                    case 0 -> requestTool(exchange, request, "collab_assign", Map.of("expectedInputRevision", 1,
                        "targetRoleId", delegate.getId(), "goal", "核对成本", "acceptanceCriteria", "给出单价",
                        "artifactRefs", List.of(), "dependsOnWorkIds", List.of(), "required", true));
                    case 1 -> {
                        var work = databaseAccess.mapper(CollaborationWorkItemMapper.class).selectOne(new LambdaQueryWrapper<CollaborationWorkItemRow>()
                            .eq(CollaborationWorkItemRow::getEnterpriseId, enterprise).eq(CollaborationWorkItemRow::getTaskId, original)
                            .eq(CollaborationWorkItemRow::getParticipantId, delegate.getId()));
                        requestTool(exchange, request, "collab_wait", Map.of("expectedInputRevision", 1, "workItemIds", List.of(work.getId()), "reason", "等待成本"));
                    }
                    default -> reply(exchange, Map.of("content", "汇总了委派成果"), "stop");
                }
                return;
            }
            helperCalls.add(request);
            if (helperCalls.size() == 1) {
                working.countDown();
                try {
                    if (!release.await(20, TimeUnit.SECONDS)) {
                        throw new IOException("等待验收消息超时");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
                reply(exchange, Map.of("tool_calls", List.of(Map.of("index", 0, "id", "delegated-act", "type", "function",
                    "function", Map.of("name", modelTool(request, "collab_list_roles"), "arguments", "{}")))), "tool_calls");
            } else {
                reply(exchange, Map.of("content", helperCalls.size() == 2 ? "单独提问的答案" : "委派核算完成"), "stop");
            }
        };
        var lead = runNext(original);
        var delegated = dispatch.submit(dispatch.claim("delegate", "web").orElseThrow());
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            assertTrue(working.await(10, TimeUnit.SECONDS));
            var queued = data(write(send, Map.of("clientMessageId", key(), "intent", "continue", "recipientAgentId", assistant,
                "taskId", original, "expectedInputRevision", 1, "text", "另外单独提问"), key()).andExpect(status().isAccepted()).andReturn());
            assertEquals("queued", queued.path("status").asText());
            assertTrue(dispatch.claim("no-overlap", "web").isEmpty());
            release.countDown();
            await(() -> runs.find(enterprise, delegated.runId(), false).orElseThrow().terminal());
            observe(original, delegated.runId());
            collaborationWorker.deliverWaiting();
            var actor = new AuthContext(users.findById(admin).orElseThrow(), enterprise, Set.of("agent.run"));
            String direct = rooms.detail(actor, room, 0).task().id();
            assertNotEquals(original, direct);
            var directRun = runNext(direct);
            assertNotEquals(delegated.conversationId(), directRun.conversationId());
            var resumed = runNext(original);
            assertEquals(delegated.conversationId(), resumed.conversationId());
            assertTrue(resumed.executionConfig().path("resumeWebRoomWork").asBoolean());
            var previousTool = helperCalls.get(2).path("messages");
            assertEquals("tool", previousTool.get(previousTool.size() - 1).path("role").asText());
            assertTrue(modelTextForCall(helperCalls.get(2), "delegated-act").contains("成本分析师"));
            assertTrue(helperCalls.get(1).toString().contains("另外单独提问"));
            assertEquals(lead.conversationId(), runNext(original).conversationId());
            assertEquals("completed", taskRows.selectById(original).getStatus());
            assertEquals(3, helperCalls.size());
        } finally {
            release.countDown();
        }
    }
}
