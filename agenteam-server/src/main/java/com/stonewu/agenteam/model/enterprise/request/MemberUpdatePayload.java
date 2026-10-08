package com.stonewu.agenteam.model.enterprise.request;

import java.util.List;

public record MemberUpdatePayload(String displayName, List<String> roleIds, List<String> teamIds) {
}
