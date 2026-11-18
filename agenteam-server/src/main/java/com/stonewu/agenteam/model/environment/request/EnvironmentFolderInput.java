package com.stonewu.agenteam.model.environment.request;

import jakarta.validation.constraints.NotBlank;

public record EnvironmentFolderInput(@NotBlank String path, @NotBlank String name) {
}
