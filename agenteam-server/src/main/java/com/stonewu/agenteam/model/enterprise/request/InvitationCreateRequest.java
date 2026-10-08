package com.stonewu.agenteam.model.enterprise.request;

import java.util.List;

public record InvitationCreateRequest(String email, String displayName, List<String> roleIds, List<String> teamIds,
                                      String note) {
}
