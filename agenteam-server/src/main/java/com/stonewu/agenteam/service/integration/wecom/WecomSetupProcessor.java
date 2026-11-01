package com.stonewu.agenteam.service.integration.wecom;

import com.stonewu.agenteam.model.integration.entity.WecomSetupJobRow;
import com.stonewu.agenteam.service.chat.ChatBotBindingService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.integration.IntegrationTokenService;
import com.stonewu.agenteam.service.integration.provider.wecom.WecomBotIdentityClient;
import com.stonewu.agenteam.service.integration.provider.wecom.WecomMemberClient;
import com.stonewu.agenteam.service.integration.provider.wecom.WecomQuickCreateClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Objects;

/** 企业归属必须通过平台身份及加密成员转换确认；失败保留进度，不能改为人工勾选。 */
@Service
public class WecomSetupProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(WecomSetupProcessor.class);
    private final WecomSetupStore store;
    private final WecomSetupCipher cipher;
    private final WecomQuickCreateClient create;
    private final WecomBotIdentityClient identity;
    private final WecomMemberClient members;
    private final WecomEnterpriseConnections enterprises;
    private final IntegrationQueryService queries;
    private final IntegrationTokenService tokens;
    private final WecomSetupTransactions transactions;
    private final ChatBotBindingService bots;
    private final Clock clock;
    private final WecomEnterpriseVerifier verifier;

    public WecomSetupProcessor(WecomSetupStore store, WecomSetupCipher cipher, WecomQuickCreateClient create,
            WecomBotIdentityClient identity, WecomMemberClient members, WecomEnterpriseConnections enterprises,
            IntegrationQueryService queries, IntegrationTokenService tokens, WecomSetupTransactions transactions,
            ChatBotBindingService bots, Clock clock, WecomEnterpriseVerifier verifier) {
        this.store = store;
        this.cipher = cipher;
        this.create = create;
        this.identity = identity;
        this.members = members;
        this.enterprises = enterprises;
        this.queries = queries;
        this.tokens = tokens;
        this.transactions = transactions;
        this.bots = bots;
        this.clock = clock;
        this.verifier = verifier;
    }

    public void process(WecomSetupJobRow claimed) {
        var row = store.copy(claimed);
        try {
            var actor = store.actor(row);
            var parent = enterprises.require(row.getEnterpriseId(), row.getEnterpriseConnectionId());
            if (!parent.getCredentialRevision().equals(row.getEnterpriseCredentialRevision())) {
                throw new ChannelProviderException("WECOM_ENTERPRISE_CHANGED", "企业接入凭据已变化，请重新检查后继续。");
            }
            row.setNextAttemptAt(clock.instant().plusSeconds(3));
            switch (row.getStage()) {
                case "queued" -> {
                    row.setEncryptedJson(cipher.encrypt(row, create.register(row.getId())));
                    row.setExpiresAt(clock.instant().plusSeconds(300));
                    row.setStage("awaiting_scan");
                    store.save(row);
                }
                case "awaiting_scan" -> {
                    if (!row.getExpiresAt().isAfter(clock.instant())) {
                        row.setStage("expired");
                        row.setEncryptedJson(null);
                    } else {
                        var result = create.poll(row.getId(), cipher.read(row));
                        row.setEncryptedJson(cipher.encrypt(row, result));
                        if (result.botId() != null && result.secret() != null) {
                            row.setStage("verifying");
                        }
                    }
                    store.save(row);
                }
                case "verifying" -> {
                    var registration = cipher.read(row);
                    var botIdentity = identity.query(row.getId(), registration.botId(), registration.secret());
                    var application = queries.application(parent);
                    var token = tokens.get(application);
                    try {
                        var owner = members.resolveEncrypted(application, token, botIdentity.encryptedOwnerId());
                        transactions.install(row, actor, botIdentity, owner.userId());
                    } catch (ChannelProviderException failure) {
                        if ("CHANNEL_TOKEN_EXPIRED".equals(failure.code())) {
                            tokens.invalidate(application, token);
                        }
                        throw failure;
                    }
                }
                case "binding" -> {
                    verifier.ensure(row.getEnterpriseId(), row.getConnectionId());
                    transactions.bind(row, actor, bots.prepare(actor, row.getEnterpriseId(), row.getSystemAccess(), row.getConnectionId()));
                }
                default -> throw new IllegalStateException("企微创建阶段无法识别");
            }
        } catch (RuntimeException failure) {
            // 提交结果不明确时重新读取租约，不能覆盖已完成或被其他线程接管的任务。
            var current = store.require(claimed.getEnterpriseId(), claimed.getOwnerUserId(), claimed.getId());
            if (!Objects.equals(current.getRevision(), claimed.getRevision()) || !Objects.equals(current.getLeaseOwner(), claimed.getLeaseOwner())) {
                return;
            }
            boolean receivedCredentials = "awaiting_scan".equals(claimed.getStage()) && "verifying".equals(row.getStage());
            if (receivedCredentials) {
                current.setEncryptedJson(row.getEncryptedJson());
            }
            current.setResumeStage(receivedCredentials ? "verifying" : claimed.getStage());
            current.setStage("failed");
            current.setErrorCode(failure instanceof ChannelProviderException provider ? provider.code()
                : failure instanceof ApiException api ? api.code() : "WECOM_SETUP_FAILED");
            current.setErrorSummary("verifying".equals(claimed.getStage()) ? "企业核验未通过，请检查后重试。"
                : failure instanceof ChannelProviderException || failure instanceof ApiException
                    ? failure.getMessage() : "创建暂时未完成，请稍后重试。");
            store.save(current);
            LOG.warn("企微创建暂时未完成，任务={}，原因={}", claimed.getId(), current.getErrorCode());
        }
    }
}
