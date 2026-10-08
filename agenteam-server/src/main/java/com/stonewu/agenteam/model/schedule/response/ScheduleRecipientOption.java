package com.stonewu.agenteam.model.schedule.response;

import java.util.List;

/** 当前允许选择的真实成员和已绑定且允许通知的接入应用。 */
public record ScheduleRecipientOption(String userId, String name, List<Channel> channels) {
    public record Channel(String connectionId, String name, String providerCode, String providerName) {
    }
}
