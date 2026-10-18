package com.stonewu.agenteam.service.environment;

import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.model.environment.response.EnvironmentView;

/** 本机与独立执行节点提供同一组用户环境操作。 */
public interface UserEnvironmentRuntime {
    EnvironmentView manage(String action, ProjectLocation project);
}
