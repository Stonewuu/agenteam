package com.stonewu.agenteam.model.integration.response;

import java.util.List;

/** 仅描述服务端当前已实现的能力和可填写的公开字段。 */
public record IntegrationProviderView(String code, String name, boolean identitySupported, boolean messagesSupported,
                                      List<Field> fields) {
    public record Field(String name, String label, String type, boolean required, Integer minimum, Integer maximum,
                        Object defaultValue, String hint) {
    }
}
