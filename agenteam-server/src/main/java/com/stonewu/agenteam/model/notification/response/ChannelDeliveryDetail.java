package com.stonewu.agenteam.model.notification.response;

import java.util.List;

/** 每次真实网络尝试的结果，只暴露排查所需的脱敏信息。 */
public record ChannelDeliveryDetail(ChannelDeliveryView delivery, List<Attempt> attempts) {
    public record Attempt(int number, String outcome, Integer httpStatus, String providerCode, String summary,
                          String startedAt, String finishedAt) {
    }
}
