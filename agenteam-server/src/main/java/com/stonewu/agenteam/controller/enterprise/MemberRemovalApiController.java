package com.stonewu.agenteam.controller.enterprise;

import com.stonewu.agenteam.model.enterprise.request.MemberRemovalRequest;
import com.stonewu.agenteam.model.enterprise.response.MemberRemovalImpactView;
import com.stonewu.agenteam.model.enterprise.response.MemberRemovalRecipientView;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.enterprise.MemberRemovalApiService;
import com.stonewu.agenteam.service.enterprise.MemberRemovalQueryService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/members/{userId}")
public class MemberRemovalApiController {

    private final AuthContextService identity;

    private final MemberRemovalQueryService queries;

    private final MemberRemovalApiService changes;

    private final ApiResponses responses;

    public MemberRemovalApiController(AuthContextService identity, MemberRemovalQueryService queries,
                                      MemberRemovalApiService changes, ApiResponses responses) {
        this.identity = identity;
        this.queries = queries;
        this.changes = changes;
        this.responses = responses;
    }

    @GetMapping("/removal-impact")
    public ApiResponse<MemberRemovalImpactView> impact(@PathVariable String enterpriseId, @PathVariable String userId,
                                                       HttpServletRequest request) {
        return responses.success(
            queries.impact(identity.requireEnterprise(request.getSession(false), enterpriseId), userId), request);
    }

    @GetMapping("/removal-recipients")
    public ApiResponse<PageResponse<MemberRemovalRecipientView>> recipients(@PathVariable String enterpriseId,
                                                                            @PathVariable String userId,
                                                                            @RequestParam String kind,
                                                                            @RequestParam(required = false) String query,
                                                                            @RequestParam(required = false) String cursor,
                                                                            @RequestParam(required = false) Integer limit,
                                                                            HttpServletRequest request) {
        return responses.success(
            queries.recipients(identity.requireEnterprise(request.getSession(false), enterpriseId), userId, kind, query,
                cursor, limit), request);
    }

    @PostMapping("/remove")
    public ResponseEntity<?> remove(@PathVariable String enterpriseId, @PathVariable String userId,
                                    @RequestBody MemberRemovalRequest plan, HttpServletRequest request) {
        return responses.operation(changes.remove(enterpriseId, userId, plan, request), request);
    }
}
