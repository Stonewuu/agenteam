package com.stonewu.agenteam.model.modelprofile.response;

import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;

public record ManagedModelProfileView(String id, String revision, String providerId, String providerName,
                                      boolean providerEnabled,
                                      String name, String modelName, ModelCapabilities capabilities, boolean enabled,
                                      boolean inUse, String createdAt, String updatedAt) {
}
