package com.stonewu.agenteam.model.enterprise.request;

public record EnterpriseUpdatePayload(String name, String description, String contactEmail, String timezone,
                                      Integer retentionDays) {
}
