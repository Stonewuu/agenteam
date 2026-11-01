package com.stonewu.agenteam.service.integration.wecom;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.WecomBotEnterpriseMapper;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.WecomBotEnterpriseRow;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 每次使用共享身份都检查企业范围、启用状态与凭据版本，不能只检查曾经核验过。 */
@Service
public class WecomEnterpriseConnections {
    private final EnterpriseIntegrationMapper connections;
    private final WecomBotEnterpriseMapper links;

    public WecomEnterpriseConnections(EnterpriseIntegrationMapper connections, WecomBotEnterpriseMapper links) {
        this.connections = connections;
        this.links = links;
    }

    public EnterpriseIntegrationRow require(String enterprise, String id) {
        var row = connections.selectOne(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise).eq(EnterpriseIntegrationRow::getId, id));
        if (!ready(row)) {
            throw new ApiException(HttpStatus.CONFLICT, "WECOM_ENTERPRISE_REQUIRED", "请先配置并启用企业微信的企业接入。");
        }
        return row;
    }

    public List<EnterpriseIntegrationRow> available(String enterprise) {
        return connections.selectList(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise).eq(EnterpriseIntegrationRow::getProviderCode, "wecom")
            .eq(EnterpriseIntegrationRow::getPurpose, "enterprise").eq(EnterpriseIntegrationRow::getStatus, "enabled")
            .isNotNull(EnterpriseIntegrationRow::getIdentityVerifiedAt)).stream().filter(this::ready).toList();
    }

    public void requireAvailableName(String enterprise, String name) {
        requireAvailableName(enterprise, name, null);
    }

    public void requireAvailableName(String enterprise, String name, String currentConnection) {
        if (connections.exists(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise).eq(EnterpriseIntegrationRow::getProviderCode, "wecom_aibot")
            .ne(currentConnection != null, EnterpriseIntegrationRow::getId, currentConnection)
            .eq(EnterpriseIntegrationRow::getName, name).ne(EnterpriseIntegrationRow::getStatus, "deleted"))) {
            throw ApiException.invalidField("name", "已有同名企微机器人，请换一个便于区分的名称。");
        }
    }

    public Optional<Verified> verified(String enterprise, String botConnection) {
        return candidate(enterprise, botConnection).filter(value ->
            Objects.equals(value.bot().getCredentialRevision(), value.proof().getBotCredentialRevision())
                && Objects.equals(value.enterprise().getCredentialRevision(), value.proof().getEnterpriseCredentialRevision()));
    }

    /** 仅用于重新核验已有关联，不能直接据此读取共享用户绑定。 */
    public Optional<Verified> candidate(String enterprise, String botConnection) {
        var bot = connections.selectOne(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise).eq(EnterpriseIntegrationRow::getId, botConnection));
        var link = links.selectOne(new LambdaQueryWrapper<WecomBotEnterpriseRow>()
            .eq(WecomBotEnterpriseRow::getEnterpriseId, enterprise).eq(WecomBotEnterpriseRow::getId, botConnection));
        if (bot == null || link == null || !"wecom_aibot".equals(bot.getProviderCode())
            || !"enabled".equals(bot.getStatus()) || bot.getIdentityVerifiedAt() == null
            || !"cli_owner_conversion".equals(link.getProofMethod())) {
            return Optional.empty();
        }
        var parent = connections.selectOne(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise).eq(EnterpriseIntegrationRow::getId, link.getEnterpriseConnectionId()));
        if (!ready(parent) || !Objects.equals(parent.getExternalTenantId(), link.getExternalTenantId())) {
            return Optional.empty();
        }
        return Optional.of(new Verified(bot, parent, link));
    }

    private boolean ready(EnterpriseIntegrationRow row) {
        return row != null && "wecom".equals(row.getProviderCode()) && "enterprise".equals(row.getPurpose())
            && "enabled".equals(row.getStatus()) && row.getIdentityVerifiedAt() != null
            && row.getExternalTenantId() != null && !row.getExternalTenantId().isBlank();
    }

    public record Verified(EnterpriseIntegrationRow bot, EnterpriseIntegrationRow enterprise, WecomBotEnterpriseRow proof) {
    }
}
