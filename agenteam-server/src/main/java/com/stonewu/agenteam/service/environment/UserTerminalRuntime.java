package com.stonewu.agenteam.service.environment;

import com.stonewu.agenteam.model.environment.request.EnvironmentTerminalInput;
import com.stonewu.agenteam.model.environment.response.EnvironmentTerminalView;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;

public interface UserTerminalRuntime {
    EnvironmentTerminalView exchange(ProjectLocation project, EnvironmentTerminalInput input);
}
