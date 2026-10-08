package com.stonewu.agenteam.model.enterprise.request;

import java.util.List;

public record TeamWritePayload(String name, String description, String ownerUserId, List<String> memberIds) {
}
