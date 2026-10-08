package com.stonewu.agenteam.controller.background;

import com.stonewu.agenteam.model.background.response.JobView;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.background.PublicJobService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/jobs")
public class PublicJobController {

    private final AuthContextService identity;

    private final PublicJobService jobs;

    private final ApiResponses responses;

    public PublicJobController(AuthContextService identity, PublicJobService jobs, ApiResponses responses) {
        this.identity = identity;
        this.jobs = jobs;
        this.responses = responses;
    }

    @GetMapping("/{jobId}")
    public ApiResponse<JobView> get(@PathVariable String enterpriseId, @PathVariable String jobId,
                                    HttpServletRequest request) {
        return responses.success(jobs.get(identity.requireEnterprise(request.getSession(false), enterpriseId), jobId),
            request);
    }
}
