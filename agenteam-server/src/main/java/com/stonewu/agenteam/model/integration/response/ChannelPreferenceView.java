package com.stonewu.agenteam.model.integration.response;

import java.util.List;

/** 本人可维护的自动通知选择；接入或绑定不允许接收时只能关闭已有选择。 */
public record ChannelPreferenceView(String connectionId, String connectionName, String providerName, boolean canEnable,
                                    List<CategoryPreference> categories) {
    public record CategoryPreference(String category, String name, boolean enabled, String revision) {
    }
}
