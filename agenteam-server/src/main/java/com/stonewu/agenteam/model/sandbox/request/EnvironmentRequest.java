package com.stonewu.agenteam.model.sandbox.request;

import com.stonewu.agenteam.model.project.entity.ProjectLocation;

public record EnvironmentRequest(String action, ProjectLocation project) {
}
