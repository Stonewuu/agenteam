package com.stonewu.agenteam.model.modelprofile.request;

import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ModelProfileWriteRequest(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{validation.textLength}") String providerId,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 80, message = "{validation.textLength}") String name,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 128, message = "{validation.textLength}") String modelName,
    @NotNull(message = "{validation.required}") @Valid ModelCapabilities capabilities,
    @NotNull(message = "{validation.required}") Boolean enabled) {
}
