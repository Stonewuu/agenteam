package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.chat.ChatConversationMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.mapper.execution.ConversationSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.resource.ResourceVersionSqlMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.execution.entity.AgentConversationRow;
import com.stonewu.agenteam.model.execution.request.ConversationUpdateInput;
import com.stonewu.agenteam.model.execution.response.ConversationNavigationItem;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionRow;
import com.stonewu.agenteam.service.collaboration.CollaborationTaskListService;
import com.stonewu.agenteam.service.execution.ConversationManagementService;
import com.stonewu.agenteam.service.execution.ConversationNavigationService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.workspace.HomeQueryService;
import com.stonewu.agenteam.service.workspace.WorkspaceSearchService;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 通过实际数据库验证任务搜索、合并后的对话分页、本人权限、旧链接和历史收藏。 */
class ConversationNavigationTest extends CollaborationApiTestSupport {
    private static final InvitationHttpTestEnvironment ENV = new InvitationHttpTestEnvironment();
    @Autowired ConversationNavigationService navigation;
    @Autowired ConversationMapper conversationRows;
    @Autowired ConversationManagementService management;
    @Autowired HomeQueryService home;
    @Autowired WorkspaceSearchService search;
    @Autowired ResourceJson resourceJson;
    @Autowired CollaborationTaskListService list;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENV.properties(registry);
        registry.add("chat.enabled", () -> true);
        registry.add("chat.feishu.enabled", () -> true);
        registry.add("chat.worker-enabled", () -> false);
        registry.add("execution.maintenance-enabled", () -> false);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENV.close();
    }

    @Test
    void tasksAreMergedBeforePaginationAndRecentAndSearchUseSameEntries() {
        secondBot();
        var first = task("整理年度账目", true);
        var second = task("核对本月考勤", true);
        String standalone = conversation("单独讨论", null);
        var found = new ArrayList<ConversationNavigationItem>();
        String cursor = null;
        do {
            var page = navigation.list(actor(), null, "active", null, null, "all", cursor, 1);
            assertEquals(1, page.items().size());
            found.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor != null);
        assertEquals(3, found.size());
        assertEquals(3, found.stream().map(ConversationNavigationItem::key).distinct().count());
        assertEquals(Set.of("task:" + first.getId(), "task:" + second.getId(), "conversation:" + standalone),
            Set.copyOf(found.stream().map(ConversationNavigationItem::key).toList()));
        assertEquals(3, home.get(actor()).recentConversations().size());
        assertEquals(1, search.get(actor(), "年度账目").groups().stream().filter(value -> value.key().equals("conversations")).findFirst().orElseThrow().items().size());
    }

    @Test
    void namesCodesAndRoleSearchOpenTheCorrectConversation() {
        secondBot();
        var task = task("制作工资核算表", true);
        var byTitle = navigation.list(actor(), "工资核算", null, null, null, null, null, 30).items();
        assertEquals(1, byTitle.size());
        assertEquals("制作工资核算表", byTitle.getFirst().title());
        assertEquals(byTitle.getFirst().collaboration().ownerConversationId(), byTitle.getFirst().conversation().id());
        assertEquals(1, navigation.list(actor(), task.getShortCode(), null, null, null, null, null, 30).items().size());
        var finance = navigation.list(actor(), "财务", null, null, null, null, null, 30).items().getFirst();
        assertEquals(2, finance.collaboration().roles().size());
        assertEquals(finance.collaboration().roles().stream().filter(role -> role.name().equals("财务")).findFirst().orElseThrow().conversationId(), finance.conversation().id());
        assertTrue(finance.matchedRole());
        assertEquals(finance.key(), navigation.current(actor(), finance.conversation().id()).key());
    }

    @Test
    void existingRoleFavoriteRemainsVisibleAndCanBeClearedFromAnyRole() {
        secondBot();
        task("收藏测试任务", true);
        var item = navigation.list(actor(), null, null, null, null, null, null, 30).items().getFirst();
        var peer = item.collaboration().roles().stream().filter(role -> !role.conversationId().equals(item.conversation().id())).findFirst().orElseThrow();
        databaseAccess.mapper(ConversationSqlMapper.class).update(new LambdaUpdateWrapper<AgentConversationRow>()
            .eq(AgentConversationRow::getEnterpriseId, enterprise).eq(AgentConversationRow::getId, peer.conversationId()).set(AgentConversationRow::getFavorite, 1));
        assertEquals(1, navigation.list(actor(), null, null, true, null, null, null, 30).items().size());
        assertTrue(navigation.current(actor(), item.conversation().id()).favorite());
        management.update(actor(), item.conversation().id(), new ConversationUpdateInput(null, false, null, null), Long.parseLong(item.conversation().revision()));
        assertTrue(navigation.list(actor(), null, null, true, null, null, null, 30).items().isEmpty());
        var latest = conversationRows.find(enterprise, initiator.id(), peer.conversationId(), false).orElseThrow();
        management.update(actor(), peer.conversationId(), new ConversationUpdateInput(null, true, null, null), latest.revision());
        assertTrue(navigation.current(actor(), item.conversation().id()).favorite());
    }

    @Test
    void filterAndCursorAreScopedAndOtherUsersCannotReadRoles() {
        secondBot();
        task("私有协作事项", true);
        conversation("私有单独对话", null);
        assertEquals(1, navigation.list(actor(), null, null, null, null, "standalone", null, 30).items().size());
        var grouped = navigation.list(actor(), null, null, null, null, "collaboration", null, 30).items().getFirst();
        var other = new AuthContext(users.findById(admin).orElseThrow(), enterprise, Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
        assertTrue(navigation.list(other, null, null, null, null, null, null, 30).items().isEmpty());
        assertThrows(ApiException.class, () -> navigation.current(other, grouped.conversation().id()));
        String cursor = navigation.list(actor(), null, null, null, null, "all", null, 1).nextCursor();
        assertNotNull(cursor);
        assertEquals("CURSOR_INVALID", assertThrows(ApiException.class, () -> navigation.list(actor(), null, null, null, null, "standalone", cursor, 1)).code());
    }

    @Test
    void rolesWithoutConversationRemainVisibleWithoutInventingLinks() {
        secondBot();
        task("等待财务参与", false);
        var value = navigation.list(actor(), null, null, null, null, null, null, 30).items().getFirst();
        assertEquals(2, value.collaboration().roles().size());
        assertEquals(1, value.collaboration().roles().stream().filter(role -> role.conversationId() == null).count());
        assertEquals(value.conversation().id(), value.collaboration().ownerConversationId());
    }

    @Test
    void rolesUseTheirFixedAgentAppearanceAndOwnerComesFirst() {
        var versions = databaseAccess.mapper(ResourceVersionSqlMapper.class);
        var stored = versions.selectById(version);
        var config = (ObjectNode) resourceJson.read(stored.getConfigJson());
        config.put("icon", "Telescope");
        config.put("color", "teal");
        versions.update(new LambdaUpdateWrapper<ResourceVersionRow>()
            .eq(ResourceVersionRow::getEnterpriseId, enterprise).eq(ResourceVersionRow::getId, version)
            .set(ResourceVersionRow::getConfigJson, resourceJson.write(config)));
        secondBot();
        task("检查岗位图标", true);
        var group = navigation.list(actor(), null, null, null, null, null, null, 30).items().getFirst().collaboration();
        assertEquals(group.ownerConversationId(), group.roles().getFirst().conversationId());
        for (var role : group.roles()) {
            assertEquals("Telescope", role.icon());
            assertEquals("teal", role.color());
        }
    }

    private AuthContext actor() {
        return new AuthContext(initiator, enterprise, Set.copyOf(permissions.listPermissionCodes(initiator.id(), enterprise)));
    }

    private CollaborationTaskRow task(String text, boolean allRoles) {
        var message = receive(UUID.randomUUID().toString(), text);
        routing.route(enterprise, message.getId());
        var task = databaseAccess.mapper(CollaborationTaskMapper.class).selectOne(new LambdaQueryWrapper<CollaborationTaskRow>()
            .eq(CollaborationTaskRow::getEnterpriseId, enterprise).eq(CollaborationTaskRow::getOriginMessageId, message.getId()));
        var roles = databaseAccess.mapper(CollaborationParticipantMapper.class).selectList(new LambdaQueryWrapper<CollaborationParticipantRow>()
            .eq(CollaborationParticipantRow::getEnterpriseId, enterprise).eq(CollaborationParticipantRow::getTaskId, task.getId()));
        for (var role : roles) {
            if (allRoles || role.getId().equals(task.getOwnerParticipantId())) {
                String conversation = conversation(task.getShortCode() + " " + role.getRoleName(), role.getId());
                databaseAccess.mapper(CollaborationParticipantMapper.class).update(new LambdaUpdateWrapper<CollaborationParticipantRow>()
                    .eq(CollaborationParticipantRow::getEnterpriseId, enterprise).eq(CollaborationParticipantRow::getId, role.getId())
                    .set(CollaborationParticipantRow::getConversationId, conversation));
            }
        }
        return task;
    }

    private String conversation(String title, String role) {
        String id = UUID.randomUUID().toString();
        conversationRows.create(id, enterprise, initiator.id(), agent, version,
            hires.forAgent(enterprise, initiator.id(), agent, false).orElseThrow().id(), title, "normal", Instant.now());
        if (role != null) {
            databaseAccess.mapper(ConversationSqlMapper.class).update(new LambdaUpdateWrapper<AgentConversationRow>()
                .eq(AgentConversationRow::getEnterpriseId, enterprise).eq(AgentConversationRow::getId, id)
                .set(AgentConversationRow::getSourceType, "collaboration").set(AgentConversationRow::getSourceId, role));
        }
        return id;
    }

    @Test
    void searchFindsOlderMatchesBeyondTheFirstBatchAndPaginatesWithoutRepeatingThem() {
        var original = original();
        var seeded = seed(original, 130);
        var first = list.list(actor(), null, null, "历史关键词", null, 1);
        assertEquals(List.of(seeded.get(102).getId()), first.items().stream().map(value -> value.id()).toList());
        assertTrue(first.hasMore());
        var second = list.list(actor(), null, null, "历史关键词", first.nextCursor(), 1);
        assertEquals(List.of(seeded.get(129).getId()), second.items().stream().map(value -> value.id()).toList());
        assertFalse(second.hasMore());
        assertNull(second.nextCursor());
        assertEquals(seeded.get(129).getId(), list.list(actor(), null, null, seeded.get(129).getShortCode().toLowerCase(), null, 30).items().getFirst().id());
        var completed = list.list(actor(), group.getId(), "completed", "历史关键词", null, 30);
        assertEquals(List.of(seeded.get(102).getId()), completed.items().stream().map(value -> value.id()).toList());
        assertTrue(list.list(actor(), null, null, "不存在的合成搜索词", null, 30).items().isEmpty());
    }

    @Test
    void actualSourceAndParticipatingRolesAreReturnedWithOnlyExistingConversations() {
        secondBot();
        var task = original();
        databaseAccess.mapper(ChatConversationMapper.class).update(new LambdaUpdateWrapper<ChatConversationRow>()
            .eq(ChatConversationRow::getEnterpriseId, enterprise).eq(ChatConversationRow::getId, group.getId()).set(ChatConversationRow::getName, "合成研发群"));
        var before = list.list(actor(), null, null, null, null, 30).items().getFirst();
        assertEquals("feishu", before.source().platformCode());
        assertEquals("group", before.source().kind());
        assertEquals("合成研发群", before.source().name());
        assertEquals(2, before.roles().size());
        assertNull(before.primaryConversationId());
        String conversation = UUID.randomUUID().toString();
        conversationRows.create(conversation, enterprise, initiator.id(), agent, version,
            hires.forAgent(enterprise, initiator.id(), agent, false).orElseThrow().id(), "合成岗位对话", "normal", Instant.now());
        databaseAccess.mapper(CollaborationParticipantMapper.class).update(new LambdaUpdateWrapper<CollaborationParticipantRow>()
            .eq(CollaborationParticipantRow::getEnterpriseId, enterprise).eq(CollaborationParticipantRow::getId, task.getOwnerParticipantId())
            .set(CollaborationParticipantRow::getConversationId, conversation));
        var after = list.list(actor(), null, null, null, null, 30).items().getFirst();
        assertEquals(task.getOwnerParticipantId(), after.roles().getFirst().id());
        assertEquals(conversation, after.primaryConversationId());
        assertEquals(1, after.roles().stream().filter(role -> role.conversationId() != null).count());
    }

    @Test
    void cursorCannotCrossUsersOrFiltersAndAdministratorCannotListAnotherMembersTasks() {
        seed(original(), 3);
        var first = list.list(actor(), null, null, null, null, 1);
        assertEquals("CURSOR_INVALID", assertThrows(ApiException.class,
            () -> list.list(actor(), null, "completed", null, first.nextCursor(), 1)).code());
        assertEquals("CURSOR_INVALID", assertThrows(ApiException.class,
            () -> list.list(actor(), null, null, "别的条件", first.nextCursor(), 1)).code());
        var administrator = new AuthContext(users.findById(admin).orElseThrow(), enterprise,
            Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
        assertTrue(list.list(administrator, null, null, null, null, 30).items().isEmpty());
        assertEquals("CURSOR_INVALID", assertThrows(ApiException.class,
            () -> list.list(administrator, null, null, null, first.nextCursor(), 1)).code());
        assertThrows(ApiException.class, () -> list.list(actor(), null, "invented-status", null, null, 30));
    }

    @Test
    void updatedTaskMovesToTheTopWithoutLosingOlderTasks() {
        var initial = original();
        var seeded = seed(initial, 3);
        databaseAccess.mapper(CollaborationTaskMapper.class).update(new LambdaUpdateWrapper<CollaborationTaskRow>()
            .eq(CollaborationTaskRow::getEnterpriseId, enterprise).eq(CollaborationTaskRow::getId, seeded.getLast().getId())
            .set(CollaborationTaskRow::getUpdatedAt, Instant.now().plusSeconds(1)));
        var result = list.list(actor(), null, null, null, null, 30);
        assertEquals(seeded.getLast().getId(), result.items().getFirst().id());
        assertEquals(4, result.items().size());
        assertEquals(4, result.items().stream().map(value -> value.id()).distinct().count());
    }

    private CollaborationTaskRow original() {
        routing.route(enterprise, receive(UUID.randomUUID().toString(), "普通合成任务").getId());
        return onlyTask();
    }

    private List<CollaborationTaskRow> seed(CollaborationTaskRow original, int count) {
        var originalMessage = messages().selectById(original.getOriginMessageId());
        var addedMessages = new ArrayList<ChatMessageRow>();
        var addedTasks = new ArrayList<CollaborationTaskRow>();
        for (int index = 0; index < count; index++) {
            String text = index == 102 || index == 129 ? "历史关键词的合成任务 " + index : "普通记录 " + index;
            var message = json.convertValue(originalMessage, ChatMessageRow.class);
            String messageId = UUID.randomUUID().toString();
            message.setId(messageId);
            message.setTaskId(null);
            message.setCanonicalKey("test:" + messageId);
            message.setPlatformMessageId("om_" + messageId);
            message.setContentEncrypted(cipher.encrypt(enterprise, "message", messageId, Map.of("text", text)));
            message.setSenderIdentitiesEncrypted(cipher.encrypt(enterprise, "message-senders", messageId,
                cipher.decrypt(enterprise, "message-senders", originalMessage.getId(), originalMessage.getSenderIdentitiesEncrypted())));
            addedMessages.add(message);
            var task = json.convertValue(original, CollaborationTaskRow.class);
            task.setId(UUID.randomUUID().toString());
            task.setOriginMessageId(messageId);
            task.setShortCode("GC-" + task.getId().replace("-", "").substring(0, 12).toUpperCase());
            task.setOwnerParticipantId(null);
            task.setOwnerWorkId(null);
            task.setStatus(index % 2 == 0 ? "completed" : "active");
            task.setUpdatedAt(original.getUpdatedAt().minusSeconds(index + 1));
            task.setObjectiveEncrypted(cipher.encrypt(enterprise, "task-objective", task.getId(), Map.of("text", text)));
            addedTasks.add(task);
        }
        messages().insert(addedMessages);
        databaseAccess.mapper(CollaborationTaskMapper.class).insert(addedTasks);
        return addedTasks;
    }
}
