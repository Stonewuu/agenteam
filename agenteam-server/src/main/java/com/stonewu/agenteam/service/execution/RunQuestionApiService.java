package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.model.execution.request.QuestionAnswerRequest;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class RunQuestionApiService {
    private final AuthContextService identity;
    private final IdempotentRequestService requests;
    private final RunQuestionService questions;

    public RunQuestionApiService(AuthContextService identity, IdempotentRequestService requests, RunQuestionService questions) {
        this.identity = identity;
        this.requests = requests;
        this.questions = questions;
    }

    public ApiOperationResult answer(String enterprise, String id, QuestionAnswerRequest input, HttpServletRequest request) {
        InputValidation.request(request, QuestionAnswerRequest.class);
        long revision = RequestPreconditions.revision(request);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(), () -> questions.authorizeAnswer(actor, id),
            () -> ApiOperationResult.of(200, questions.answer(actor, id, revision, input)));
    }
}
