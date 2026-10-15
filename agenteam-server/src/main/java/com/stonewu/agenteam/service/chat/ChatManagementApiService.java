package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.model.chat.request.ChatBotWriteRequest;
import com.stonewu.agenteam.model.chat.request.ChatConversationUpdateRequest;
import com.stonewu.agenteam.model.chat.request.ChatCheckRequest;
import com.stonewu.agenteam.model.chat.request.ChatWebhookSettingsRequest;
import com.stonewu.agenteam.model.chat.response.ChatBotView;
import com.stonewu.agenteam.model.chat.response.ChatConversationView;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.auth.ChannelSessionGuard;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import com.stonewu.agenteam.service.integration.IntegrationManagementPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.function.Supplier;

/** 沿用原管理身份与请求去重，平台查询在业务事务之外执行。 */
@Service
public class ChatManagementApiService {
    private final AuthContextService identity;
    private final IntegrationManagementPolicy policy;
    private final ChatBotBindingService bots;
    private final ChatConversationService conversations;
    private final ChatPlatformRegistry platforms;
    private final IdempotentRequestService requests;
    private final ChatCapabilityService capabilities;
    private final ChatOutboundQueryService outgoing;
    private final ChatAgentChoiceService agents;
    private final ChatTestMemberService testMembers;

    public ChatManagementApiService(AuthContextService identity, IntegrationManagementPolicy policy, ChatBotBindingService bots,
                                    ChatConversationService conversations, ChatPlatformRegistry platforms, IdempotentRequestService requests,
                                    ChatCapabilityService capabilities, ChatOutboundQueryService outgoing, ChatAgentChoiceService agents, ChatTestMemberService testMembers) {
        this.identity = identity;
        this.policy = policy;
        this.bots = bots;
        this.conversations = conversations;
        this.platforms = platforms;
        this.requests = requests;
        this.capabilities = capabilities;
        this.outgoing = outgoing;
        this.agents = agents;
        this.testMembers = testMembers;
    }

    public PageResponse<ChatTestMemberService.Choice> testMembers(String enterprise, String botId, String query, String cursor, Integer limit, HttpServletRequest request) {
        var actor = actor(enterprise, request);
        policy.require(actor, enterprise, system(request), "integration.test", false);
        return testMembers.list(actor.id(), enterprise, botId, query, cursor, limit);
    }

    public PageResponse<ChatBotView> bots(String enterprise, String cursor, Integer limit, HttpServletRequest request) {
        var actor = actor(enterprise, request);
        return bots.list(actor, enterprise, system(request), cursor, limit);
    }

    public PageResponse<ChatAgentChoiceService.Choice> agents(String enterprise, String agent, String version, String query,
            String cursor, Integer limit, HttpServletRequest request) {
        var actor = actor(enterprise, request);
        policy.require(actor, enterprise, system(request), "integration.manage", false);
        return agents.list(actor.id(), enterprise, agent, version, query, cursor, limit);
    }

    public PageResponse<ChatConversationView> conversations(String enterprise, String cursor, Integer limit, HttpServletRequest request) {
        return conversations.list(actor(enterprise, request), enterprise, system(request), cursor, limit);
    }

    public ApiOperationResult create(String enterprise, ChatBotWriteRequest input, HttpServletRequest request) {
        InputValidation.request(request, ChatBotWriteRequest.class);
        var actor = actor(enterprise, request);
        Runnable authorize = () -> policy.require(identity.requireUser(request.getSession(false)), enterprise, system(request), "integration.manage", true);
        var replay = requests.replay(request, actor, enterprise, Set.of(), authorize);
        if (replay.isPresent()) {
            return replay.get();
        }
        var prepared = bots.prepare(actor, enterprise, system(request), input.connectionId());
        return requests.execute(request, actor, enterprise, Set.of(), authorize,
            () -> ApiOperationResult.of(201, bots.create(actor, enterprise, system(request), input, prepared)));
    }

    public ApiOperationResult updateBot(String enterprise, String id, ChatBotWriteRequest input, HttpServletRequest request) {
        InputValidation.request(request, ChatBotWriteRequest.class);
        long revision = input.expectedRevision() == null ? RequestPreconditions.revision(request) : input.expectedRevision();
        var actor = actor(enterprise, request);
        return execute(actor, enterprise, request, () -> ApiOperationResult.of(200, bots.update(actor, enterprise, system(request), id, revision, input)));
    }

    public ApiOperationResult updateConversation(String enterprise, String id, ChatConversationUpdateRequest input, HttpServletRequest request) {
        InputValidation.request(request, ChatConversationUpdateRequest.class);
        var actor = actor(enterprise, request);
        return execute(actor, enterprise, request, () -> ApiOperationResult.of(200, conversations.update(actor, enterprise, system(request), id, input)));
    }

    public ApiOperationResult discover(String enterprise, String botId, String cursor, HttpServletRequest request) {
        var actor = actor(enterprise, request);
        policy.require(actor, enterprise, system(request), "integration.manage", false);
        Runnable authorize = () -> policy.require(identity.requireUser(request.getSession(false)), enterprise, system(request), "integration.manage", true);
        var replay = requests.replay(request, actor, enterprise, Set.of(), authorize);
        if (replay.isPresent()) {
            return replay.get();
        }
        var bot = bots.require(enterprise, botId);
        var app = bots.application(bot);
        var page = platforms.adapter(app.providerCode()).conversations(app, cursor);
        return requests.execute(request, actor, enterprise, Set.of(), authorize, () -> {
            var current = bots.require(enterprise, botId);
            if (!current.getRevision().equals(bot.getRevision()) || bots.application(current).credentialRevision() != app.credentialRevision()) {
                throw ApiException.versionConflict(current.getRevision());
            }
            for (var item : page.items()) {
                conversations.observe(current, app, item.id(), item.name(), item.kind(), "platform_query");
            }
            return ApiOperationResult.of(200, new DiscoveryResult(page.items().size(), page.nextCursor()));
        });
    }

    private ApiOperationResult execute(UserEntity actor, String enterprise, HttpServletRequest request, Supplier<ApiOperationResult> action) {
        return requests.execute(request, actor, enterprise, Set.of(),
            () -> policy.require(identity.requireUser(request.getSession(false)), enterprise, system(request), "integration.manage", true), action);
    }

    public ApiOperationResult callbackSettings(String enterprise, String id, ChatWebhookSettingsRequest input, HttpServletRequest request) {
        InputValidation.request(request, ChatWebhookSettingsRequest.class);
        var actor = actor(enterprise, request);
        return execute(actor, enterprise, request, () -> ApiOperationResult.of(200, bots.callbackSettings(actor, enterprise, system(request), id, input)));
    }

    public ApiOperationResult check(String enterprise, String id, ChatCheckRequest input, HttpServletRequest request) {
        InputValidation.request(request, ChatCheckRequest.class);
        var actor = actor(enterprise, request);
        policy.require(actor, enterprise, system(request), "integration.test", false);
        Runnable authorize = () -> policy.require(identity.requireUser(request.getSession(false)), enterprise, system(request), "integration.test", true);
        var replay = requests.replay(request, actor, enterprise, Set.of(), authorize);
        if (replay.isPresent()) {
            return replay.get();
        }
        var prepared = capabilities.prepare(enterprise, id, input);
        return requests.execute(request, actor, enterprise, Set.of(), authorize, () -> ApiOperationResult.of(200, capabilities.checked(prepared)));
    }

    public ApiOperationResult probe(String enterprise, String id, ChatCheckRequest input, boolean receive, HttpServletRequest request) {
        InputValidation.request(request, ChatCheckRequest.class);
        var actor = actor(enterprise, request);
        return requests.execute(request, actor, enterprise, Set.of(),
            () -> policy.require(identity.requireUser(request.getSession(false)), enterprise, system(request), "integration.test", true),
            () -> ApiOperationResult.of(receive ? 200 : 202, receive ? capabilities.receive(actor, enterprise, id, input)
                : capabilities.send(enterprise, id, input, actor.id() + ":" + request.getHeader("Idempotency-Key"))));
    }

    public PageResponse<ChatOutboundQueryService.View> outbounds(String enterprise, String conversation, String cursor, Integer limit, HttpServletRequest request) {
        var actor = actor(enterprise, request);
        policy.require(actor, enterprise, system(request), "integration.view", false);
        return outgoing.list(actor.id(), enterprise, conversation, cursor, limit);
    }

    public ApiOperationResult retry(String enterprise, String id, ChatCheckRequest input, HttpServletRequest request) {
        InputValidation.request(request, ChatCheckRequest.class);
        var actor = actor(enterprise, request);
        return execute(actor, enterprise, request, () -> ApiOperationResult.of(202, outgoing.retry(enterprise, id, input.expectedRevision())));
    }

    private UserEntity actor(String enterprise, HttpServletRequest request) {
        if (ChannelSessionGuard.external(request.getSession(false))) {
            if (system(request)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "LOCAL_REAUTH_REQUIRED", "请先验证账号密码，再访问平台管理。");
            }
            identity.requireEnterprise(request.getSession(false), enterprise);
        }
        return identity.requireUser(request.getSession(false));
    }

    private boolean system(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/v1/system/");
    }

    public record DiscoveryResult(int discovered, String nextCursor) {
    }
}
