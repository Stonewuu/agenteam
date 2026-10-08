package com.stonewu.agenteam.model.enterprise.response;

public record TeamView(String id, String revision, String createdAt, String updatedAt, String name, String description,
                       ActorView owner,
                       String status, int memberCount) {
}
