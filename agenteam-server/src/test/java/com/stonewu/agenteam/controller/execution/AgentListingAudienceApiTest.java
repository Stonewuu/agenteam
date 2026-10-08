package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.stonewu.agenteam.mapper.agent.AgentListingMapper;
import com.stonewu.agenteam.mapper.permission.ResourceAuthorizationMapper;
import com.stonewu.agenteam.model.agent.request.HireAgentRequest;
import com.stonewu.agenteam.model.agent.response.AgentHireResult.Hired;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import com.stonewu.agenteam.model.resource.request.AgentListingRequest;
import com.stonewu.agenteam.model.resource.request.ResourcePublishRequest;
import com.stonewu.agenteam.service.agent.AgentHireService;
import com.stonewu.agenteam.service.agent.AgentListingService;
import com.stonewu.agenteam.service.agent.EmployeeQueryService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceGrantService;
import com.stonewu.agenteam.service.resource.ResourcePolicy;
import com.stonewu.agenteam.service.resource.ResourcePublishService;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用普通成员和真实授权数据验证上架默认值、人工范围与事务回滚。
 */
@Import(SharedEnterpriseTestEdition.class)
class AgentListingAudienceApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired
    private AgentListingService listings;
    @Autowired
    private AgentListingMapper listingRecords;
    @Autowired
    private EmployeeQueryService employees;
    @Autowired
    private AgentHireService hiresService;
    @Autowired
    private ResourceAuthorizationMapper authorizations;
    @Autowired
    private ResourceGrantService grants;
    @Autowired
    private ResourcePublishService publications;
    @Autowired
    private ResourcePolicy resourcePolicy;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void listingDefaultsToEnterpriseUseButDoesNotGrantConfigurationAccess() {
        var member = member();
        assertTrue(market(member).isEmpty());
        listings.update(owner(), agent, new AgentListingRequest(true, "automatic"), 2);
        assertEquals(List.of(agent), market(member));
        assertTrue(employees.detail(member, agent).canHire());
        assertFalse(employees.detail(member, agent).canRun());
        assertEquals(List.of(new ResourceGrantSpec("enterprise", enterprise, "use")), authorizations.grants(enterprise, agent));
        assertEquals(403, assertThrows(ResponseStatusException.class,
            () -> resourcePolicy.authorize(member, agent, "view", false, false)).getStatusCode().value());
        assertInstanceOf(Hired.class, hiresService.hire(member, new HireAgentRequest(agent, null)));
        assertTrue(employees.detail(member, agent).canRun());
        listings.update(owner(), agent, new AgentListingRequest(false, "automatic"), 3);
        assertTrue(market(member).isEmpty());
        assertTrue(employees.detail(member, agent).canRun(), "下架不自动取消已有使用关系和已保存授权");
    }

    @Test
    void publisherMayNarrowUseWithoutReplacingOtherPermissionsAndRepublishingKeepsTheChoice() {
        var allowed = member();
        var excluded = member();
        listings.update(owner(), agent, new AgentListingRequest(true, "automatic"), 2);
        var viewGrant = new ResourceGrantSpec("user", excluded.userId(), "view");
        grants.replace(owner(), agent, 3, List.of(new ResourceGrantSpec("enterprise", enterprise, "use"), viewGrant));
        var useGrant = new ResourceGrantSpec("user", allowed.userId(), "use");
        listings.update(owner(), agent, new AgentListingRequest(true, "automatic", List.of(useGrant)), 4);
        assertEquals(Set.of(viewGrant, useGrant), Set.copyOf(authorizations.grants(enterprise, agent)));
        assertEquals(List.of(agent), market(allowed));
        assertTrue(market(excluded).isEmpty());
        assertEquals(404, assertThrows(ApiException.class,
            () -> hiresService.hire(excluded, new HireAgentRequest(agent, null))).getStatusCode().value());
        publications.publish(owner(), agent, new ResourcePublishRequest("保留人工使用范围", null, new AgentListingRequest(true, "automatic")), 5);
        assertEquals(List.of(agent), market(allowed));
        assertTrue(market(excluded).isEmpty());
    }

    @Test
    void explicitlyPrivatePublicationDoesNotFallBackToEnterpriseUse() {
        var member = member();
        publications.publish(owner(), agent, new ResourcePublishRequest("仅供所有者使用", List.of(), new AgentListingRequest(true, "automatic")), 2);
        assertTrue(listingRecords.find(enterprise, agent).listed());
        assertTrue(authorizations.grants(enterprise, agent).isEmpty());
        assertTrue(market(member).isEmpty());
        publications.publish(owner(), agent, new ResourcePublishRequest("继续保留私有范围", null, new AgentListingRequest(true, "automatic")), 3);
        assertTrue(market(member).isEmpty());
    }

    @Test
    void invalidAudienceRollsBackListingAndPreservesExistingUseGrants() {
        var member = member();
        listings.update(owner(), agent, new AgentListingRequest(true, "automatic"), 2);
        var invalid = new ResourceGrantSpec("enterprise", "another-enterprise", "use");
        assertEquals(422, assertThrows(ApiException.class,
            () -> listings.update(owner(), agent, new AgentListingRequest(false, "approval", List.of(invalid)), 3)).getStatusCode().value());
        assertTrue(listingRecords.find(enterprise, agent).listed());
        assertEquals("automatic", listingRecords.find(enterprise, agent).hirePolicy());
        assertEquals(List.of(agent), market(member));
        assertTrue(employees.detail(member, agent).canHire());
    }

    private List<String> market(AuthContext actor) {
        return employees.list(actor, "market", "", List.of(), null, 20).items().stream().map(employee -> employee.agentId()).toList();
    }

    private AuthContext member() {
        var member = EnterpriseTestData.member(users, permissions, enterprise, "audience-" + UUID.randomUUID(),
            "上架范围验证所使用的独立完整测试口令", "普通成员", List.of(permissions.builtinRoleId(enterprise, "member")));
        return new AuthContext(member, enterprise, Set.copyOf(permissions.listPermissionCodes(member.id(), enterprise)));
    }

    private AuthContext owner() {
        return new AuthContext(users.findById(admin).orElseThrow(), enterprise, Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
    }
}
