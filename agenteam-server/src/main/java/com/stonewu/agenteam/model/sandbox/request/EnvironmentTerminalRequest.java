package com.stonewu.agenteam.model.sandbox.request;

import com.stonewu.agenteam.model.environment.request.EnvironmentTerminalInput;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;

public record EnvironmentTerminalRequest(ProjectLocation project, EnvironmentTerminalInput input) {
}
