package com.stonewu.agenteam.model.environment.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record EnvironmentUploadDirectoriesInput(@NotBlank String path,
        @NotEmpty @Size(max = 256) List<@NotBlank @Size(max = 512) String> directories) {
}
