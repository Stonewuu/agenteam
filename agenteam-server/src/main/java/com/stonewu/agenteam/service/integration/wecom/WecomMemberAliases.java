package com.stonewu.agenteam.service.integration.wecom;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.chat.ChatInboundReceiptMapper;
import com.stonewu.agenteam.mapper.integration.WecomMemberAliasMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.chat.entity.ChatReceiptLease;
import com.stonewu.agenteam.model.chat.entity.NormalizedChatEvent;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.model.integration.entity.WecomMemberAliasRow;
import com.stonewu.agenteam.service.chat.ChatPayloadCipher;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.integration.IntegrationTokenService;
import com.stonewu.agenteam.service.integration.provider.wecom.WecomMemberClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/** 接收记录进入数据库后再调用企微转换身份，网络等待不占用消息事务或长连接回调。 */
@Service
public class WecomMemberAliases {
    private final WecomMemberAliasMapper aliases;
    private final WecomEnterpriseConnections enterprises;
    private final ChatInboundReceiptMapper receipts;
    private final ChatBotBindingMapper bots;
    private final ChatPayloadCipher cipher;
    private final IntegrationQueryService queries;
    private final IntegrationTokenService tokens;
    private final WecomMemberClient members;
    private final ResourceJson json;
    private final Clock clock;
    private final WecomEnterpriseVerifier verifier;

    public WecomMemberAliases(WecomMemberAliasMapper aliases, WecomEnterpriseConnections enterprises,
            ChatInboundReceiptMapper receipts, ChatBotBindingMapper bots, ChatPayloadCipher cipher,
            IntegrationQueryService queries, IntegrationTokenService tokens, WecomMemberClient members, ResourceJson json, Clock clock,
            WecomEnterpriseVerifier verifier) {
        this.aliases = aliases;
        this.enterprises = enterprises;
        this.receipts = receipts;
        this.bots = bots;
        this.cipher = cipher;
        this.queries = queries;
        this.tokens = tokens;
        this.members = members;
        this.json = json;
        this.clock = clock;
        this.verifier = verifier;
    }

    public void prepare(ChatReceiptLease lease) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("企微成员转换不能在数据库事务中执行");
        }
        var receipt = receipts.selectById(lease.receiptId());
        if (receipt == null || !lease.enterpriseId().equals(receipt.getEnterpriseId())
            || !lease.owner().equals(receipt.getLeaseOwner()) || receipt.getLeaseVersion() != lease.version()) {
            return;
        }
        if (!List.of("aibot_msg_callback", "template_card_event").contains(receipt.getEventType())) {
            return;
        }
        var bot = bots.selectById(receipt.getBotBindingId());
        if (bot == null || !lease.enterpriseId().equals(bot.getEnterpriseId())) {
            return;
        }
        var verified = verifier.ensure(lease.enterpriseId(), bot.getConnectionId());
        if (verified.isEmpty()) {
            return;
        }
        var context = verified.get();
        if (!receipt.getConnectionRevision().equals(context.bot().getCredentialRevision())) {
            throw unavailable();
        }
        var frame = cipher.decrypt(lease.enterpriseId(), "receipt", receipt.getId(), receipt.getPayloadEncrypted());
        boolean memberEvent = "aibot_msg_callback".equals(frame.path("cmd").asText())
            || "aibot_event_callback".equals(frame.path("cmd").asText())
                && "template_card_event".equals(frame.path("body").path("event").path("eventtype").asText());
        if (!memberEvent || !"user".equals(frame.path("body").path("from").path("type").asText("user"))) {
            return;
        }
        String subject = frame.path("body").path("from").path("userid").asText();
        String reportedTenant = frame.path("body").path("from").path("corpid").asText(null);
        if (reportedTenant != null && !reportedTenant.isBlank() && !context.enterprise().getExternalTenantId().equals(reportedTenant)) {
            throw new ChannelProviderException("WECOM_ENTERPRISE_MISMATCH", "消息不属于已接入的企微企业。");
        }
        if (find(lease.enterpriseId(), bot.getConnectionId(), "wecom_aibot_userid", subject).isPresent()) {
            return;
        }
        var application = queries.application(context.enterprise());
        var token = tokens.get(application);
        WecomMemberClient.Member member;
        try {
            member = members.resolve(application, token, subject, reportedTenant);
        } catch (ChannelProviderException failure) {
            if ("CHANNEL_TOKEN_EXPIRED".equals(failure.code())) {
                tokens.invalidate(application, token);
            }
            throw failure;
        }
        var current = enterprises.verified(lease.enterpriseId(), bot.getConnectionId()).orElseThrow(WecomMemberAliases::unavailable);
        if (!context.proof().getBotCredentialRevision().equals(current.proof().getBotCredentialRevision())
            || !context.proof().getEnterpriseCredentialRevision().equals(current.proof().getEnterpriseCredentialRevision())
            || !context.enterprise().getId().equals(current.enterprise().getId())) {
            throw unavailable();
        }
        var row = new WecomMemberAliasRow();
        row.setId(key(context, subject));
        row.setEnterpriseId(lease.enterpriseId());
        row.setConnectionId(bot.getConnectionId());
        row.setEnterpriseConnectionId(context.enterprise().getId());
        row.setBotCredentialRevision(context.bot().getCredentialRevision());
        row.setEnterpriseCredentialRevision(context.enterprise().getCredentialRevision());
        row.setSubjectHash(json.hash(json.tree(subject)));
        row.setMemberId(member.userId());
        row.setExpiresAt(clock.instant().plusSeconds(600));
        int changed = aliases.update(new LambdaUpdateWrapper<WecomMemberAliasRow>().eq(WecomMemberAliasRow::getId, row.getId())
            .eq(WecomMemberAliasRow::getEnterpriseId, row.getEnterpriseId()).set(WecomMemberAliasRow::getMemberId, row.getMemberId())
            .set(WecomMemberAliasRow::getExpiresAt, row.getExpiresAt()));
        if (changed == 0) {
            try {
                aliases.insert(row);
            } catch (DuplicateKeyException concurrent) {
                // 另一接收任务已经保存相同凭据版本的结果，后续按当前企业关联重新读取。
            }
        }
    }

    public Optional<Member> find(String enterprise, String connection, String subjectType, String subject) {
        if (subject == null || subject.isBlank()) {
            return Optional.empty();
        }
        // 此类型只由消息处理器在转换成功后写入加密消息，平台原始事件始终使用 wecom_aibot_userid。
        if ("wecom_userid".equals(subjectType)) {
            return enterprises.verified(enterprise, connection).map(context -> new Member(context.enterprise().getId(), subject));
        }
        if (!"wecom_aibot_userid".equals(subjectType)) {
            return Optional.empty();
        }
        return enterprises.verified(enterprise, connection).flatMap(context -> {
            var row = aliases.selectOne(new LambdaQueryWrapper<WecomMemberAliasRow>().eq(WecomMemberAliasRow::getId, key(context, subject))
                .eq(WecomMemberAliasRow::getEnterpriseId, enterprise).eq(WecomMemberAliasRow::getConnectionId, connection)
                .eq(WecomMemberAliasRow::getEnterpriseConnectionId, context.enterprise().getId())
                .eq(WecomMemberAliasRow::getBotCredentialRevision, context.bot().getCredentialRevision())
                .eq(WecomMemberAliasRow::getEnterpriseCredentialRevision, context.enterprise().getCredentialRevision())
                .gt(WecomMemberAliasRow::getExpiresAt, clock.instant()));
            return row == null ? Optional.empty() : Optional.of(new Member(context.enterprise().getId(), row.getMemberId()));
        });
    }

    public NormalizedChatEvent canonicalEvent(IntegrationApplication app, NormalizedChatEvent event) {
        if (!"wecom_aibot".equals(app.providerCode())
            || !(event.humanMessage() || event.kind() == NormalizedChatEvent.Kind.CARD_ACTION && "user".equals(event.senderKind()))) {
            return event;
        }
        var member = find(app.enterpriseId(), app.id(), event.subjectType(), event.senderId());
        if (member.isEmpty()) {
            return event;
        }
        return new NormalizedChatEvent(event.providerCode(), event.eventType(), event.kind(), event.deliveryId(), event.messageId(),
            event.externalChatId(), event.chatKind(), event.senderKind(), "wecom_userid", member.get().subjectId(), event.text(),
            event.messageType(), event.mentions(), event.resources(), event.replyToMessageId(), event.threadId(), event.platformCreatedAt(), event.metadata());
    }

    @Scheduled(fixedDelay = 1800000)
    public void cleanExpired() {
        aliases.delete(new LambdaQueryWrapper<WecomMemberAliasRow>().lt(WecomMemberAliasRow::getExpiresAt, clock.instant().minusSeconds(3600)));
    }

    private String key(WecomEnterpriseConnections.Verified context, String subject) {
        return json.hash(json.tree(List.of(context.bot().getEnterpriseId(), context.bot().getId(), context.enterprise().getId(),
            context.bot().getCredentialRevision(), context.enterprise().getCredentialRevision(), subject)));
    }

    private static ChannelProviderException unavailable() {
        return new ChannelProviderException("WECOM_ENTERPRISE_CHANGED", "企微接入已变化，请重新核验后继续。");
    }

    public record Member(String connectionId, String subjectId) {
    }
}
