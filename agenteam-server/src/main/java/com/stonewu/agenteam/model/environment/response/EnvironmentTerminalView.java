package com.stonewu.agenteam.model.environment.response;

public record EnvironmentTerminalView(String id, String status, String output, long start, long end,
    long sequence, String directory, boolean busy, int cols, int rows, String error) {
}
