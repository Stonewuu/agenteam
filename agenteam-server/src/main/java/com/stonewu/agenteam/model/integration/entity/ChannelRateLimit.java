package com.stonewu.agenteam.model.integration.entity;

import com.stonewu.agenteam.service.i18n.Messages;

/** 平台发送频率约束；日额度按指定时区的自然日统计，其他规则使用连续时间窗口。 */
public record ChannelRateLimit(String name, boolean perRecipient, int windowSeconds, int maximum,
                                boolean calendarDay, int dayOffsetSeconds) {
    public ChannelRateLimit {
        if (name == null || !name.matches("[a-z_]+") || windowSeconds < 1 || maximum < 1
            || Math.abs(dayOffsetSeconds) > 18 * 3600) {
            throw new IllegalArgumentException(Messages.text("error.integration.channelRateLimit.init.1"));
        }
    }
}
