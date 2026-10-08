package com.stonewu.agenteam.support;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.configuration.auth.AccountPasswordEncoder;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.mapper.test.agent.AgentHireFixtureMapper;
import com.stonewu.agenteam.mapper.test.enterprise.ResourceFixtureMapper;
import com.stonewu.agenteam.mapper.test.resource.ResourceVersionFixtureMapper;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.model.user.entity.UserEntity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 为业务边界测试准备已加入企业的账号；真实邀请流程由邀请接口测试验证。
 */
public final class EnterpriseTestData {

    private EnterpriseTestData() {
    }

    /**
     * 用已停用员工准备只读会话，身份接口测试不会发起模型调用。
     */
    public static void conversation(MybatisTestDatabase databaseAccess, ConversationMapper conversations, String id, String user, String enterprise, String title) {
        String agent = UUID.randomUUID().toString(), version = UUID.randomUUID().toString(), hire = UUID.randomUUID().toString();
        var now = Instant.now();
        databaseAccess.mapper(ResourceFixtureMapper.class).enterpriseTestDataConversationUpdate(agent, enterprise, user);
        databaseAccess.mapper(ResourceVersionFixtureMapper.class).enterpriseTestDataConversationUpdate(version, enterprise, agent, "0".repeat(64), user, Timestamp.from(now));
        databaseAccess.mapper(ResourceSqlMapper.class).update(new LambdaUpdateWrapper<ResourceRow>().eq(ResourceRow::getId, (agent)).set(ResourceRow::getPublishedVersionId, (version)));
        databaseAccess.mapper(AgentHireFixtureMapper.class).enterpriseTestDataConversationUpdate(hire, enterprise, user, agent, Timestamp.from(now));
        conversations.create(id, enterprise, user, agent, version, hire, title, "normal", now);
    }

    public static UserEntity member(AuthMapper users, PermissionMapper permissions, String enterpriseId, String username, String password, String name, List<String> roleIds) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        users.insertUser(id, username, new AccountPasswordEncoder().encode(password), name, false, now);
        users.addMember(enterpriseId, id, name, now);
        permissions.replaceUserRoles(id, enterpriseId, Set.copyOf(roleIds), now);
        return users.findById(id).orElseThrow();
    }
}
