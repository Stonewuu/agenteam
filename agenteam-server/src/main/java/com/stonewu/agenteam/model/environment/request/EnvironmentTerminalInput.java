package com.stonewu.agenteam.model.environment.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EnvironmentTerminalInput(
    @NotBlank @Pattern(regexp = "open|poll|input|resize|close") String action,
    @NotBlank @Pattern(regexp = "[0-9a-f-]{36}") String id,
    @Size(max = 32768) String data,
    @Min(0) long sequence,
    @Min(0) long cursor,
    @Min(20) @Max(400) int cols,
    @Min(5) @Max(160) int rows) {
}
