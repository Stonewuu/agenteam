package com.stonewu.agenteam.model.schedule.response;

import java.util.List;
import java.util.Map;

/** 操作公开配置与实际选择的成员、渠道名称，不包含外部用户身份或任何凭据。 */
public record ScheduleActionView(String type, String name, int schemaVersion, Map<String, Object> config, List<Recipient> recipients) {
    public record Recipient(String userId, String name, List<Channel> channels) {
    }

    public record Channel(String connectionId, String name, String providerCode) {
    }
}
