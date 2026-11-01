package com.stonewu.agenteam.model.execution.response;

import java.util.List;

public record RunQuestionView(String id, String runId, String question, List<Option> options, String status,
                              String answer, String selectedOptionId, String revision, String expiresAt) {
    public record Option(String id, String label, String description, boolean recommended) {
    }
}
