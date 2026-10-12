package com.stonewu.agenteam.model.modelusage.entity;

/**
 * 厂商响应的有限排障信息，不保存正文、请求头或凭据。
 */
public record ModelUsageMetadata(String responseId, String model, String serviceTier, String status) {
    public ModelUsageMetadata {
        responseId = bounded(responseId);
        model = bounded(model);
        serviceTier = bounded(serviceTier);
        status = bounded(status);
    }

    private static String bounded(String value) {
        if (value == null) {
            return null;
        }
        String safe = value.replaceAll("\\p{Cntrl}", "");
        return safe.substring(0, Math.min(256, safe.length()));
    }
}
