package com.stonewu.agenteam.model.modelprofile.entity;

/**
 * 标记私有模型状态来自哪个配置，切换协议或提供方时不回传其他模型的签名。
 */
public record ModelHistoryOrigin(String protocol, String providerId, String modelName) {
    public static final String METADATA_KEY = "agenteam_model_origin";
}
