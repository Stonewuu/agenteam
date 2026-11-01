package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.model.execution.request.QuestionAnswerRequest;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.execution.RunQuestionApiService;
import com.stonewu.agenteam.service.execution.RunQuestionService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 只能回答本人的问题，不允许管理员代替其他成员作答。 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}")
public class RunQuestionController {
    private final RunQuestionService questions;
    private final RunQuestionApiService mutations;
    private final AuthContextService identity;
    private final ApiResponses responses;

    public RunQuestionController(RunQuestionService questions, RunQuestionApiService mutations, AuthContextService identity, ApiResponses responses) {
        this.questions = questions;
        this.mutations = mutations;
        this.identity = identity;
        this.responses = responses;
    }

    @GetMapping("/runs/{runId}/questions")
    public Object list(@PathVariable String enterpriseId, @PathVariable String runId, HttpServletRequest request) {
        return responses.success(questions.list(identity.requireEnterprise(request.getSession(false), enterpriseId), runId), request);
    }

    @PostMapping("/questions/{questionId}/answer")
    public Object answer(@PathVariable String enterpriseId, @PathVariable String questionId,
            @RequestBody QuestionAnswerRequest input, HttpServletRequest request) {
        return responses.operation(mutations.answer(enterpriseId, questionId, input, request), request);
    }
}
