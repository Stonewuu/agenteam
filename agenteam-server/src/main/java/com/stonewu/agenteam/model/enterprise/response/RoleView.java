package com.stonewu.agenteam.model.enterprise.response;

import java.util.List;

public record RoleView(String id, String revision, String createdAt, String updatedAt, String name, String code,
                       String description, String dataScope,
                       List<String> permissions, boolean builtin, String status, int memberCount) {
}
