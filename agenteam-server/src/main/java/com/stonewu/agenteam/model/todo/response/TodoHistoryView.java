package com.stonewu.agenteam.model.todo.response;

import com.stonewu.agenteam.model.enterprise.response.ActorView;

public record TodoHistoryView(String id, ActorView actor, String action, String summary, String reason,
                              String createdAt) {
}
