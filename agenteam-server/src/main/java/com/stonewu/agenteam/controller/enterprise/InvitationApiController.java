package com.stonewu.agenteam.controller.enterprise;

import com.stonewu.agenteam.model.auth.request.TokenRequest;
import com.stonewu.agenteam.model.auth.response.CurrentIdentityResponse;
import com.stonewu.agenteam.model.enterprise.request.InvitationAcceptRequest;
import com.stonewu.agenteam.model.enterprise.request.InvitationCreateRequest;
import com.stonewu.agenteam.model.enterprise.request.ShareInvitationRequest;
import com.stonewu.agenteam.model.enterprise.response.InvitationPreview;
import com.stonewu.agenteam.model.enterprise.response.InvitationView;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.enterprise.InvitationApiService;
import com.stonewu.agenteam.service.enterprise.ShareInvitationService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 邀请管理与持有邀请链接的公开入口，实际授权由业务服务逐次检查。
 */
@RestController
@RequestMapping("/api/v1")
public class InvitationApiController {

    private final InvitationApiService invitations;

    private final ApiResponses responses;

    private final ShareInvitationService sharing;

    public InvitationApiController(InvitationApiService invitations, ApiResponses responses,
                                   ShareInvitationService sharing) {
        this.invitations = invitations;
        this.responses = responses;
        this.sharing = sharing;
    }

    @PostMapping("/enterprises/{enterpriseId}/invitations/share")
    public ResponseEntity<ApiResponse<Object>> share(@PathVariable String enterpriseId,
                                                     @RequestBody ShareInvitationRequest payload,
                                                     HttpServletRequest request) {
        return responses.operation(sharing.create(enterpriseId, payload, request), request);
    }

    @GetMapping("/enterprises/{enterpriseId}/invitations")
    public ApiResponse<PageResponse<InvitationView>> list(@PathVariable String enterpriseId,
                                                          @RequestParam(required = false) String cursor,
                                                          @RequestParam(required = false) Integer limit,
                                                          @RequestParam(required = false) String query,
                                                          HttpServletRequest request) {
        return responses.success(invitations.list(enterpriseId, cursor, limit, query, request), request);
    }

    @PostMapping("/enterprises/{enterpriseId}/invitations")
    public ResponseEntity<ApiResponse<Object>> create(@PathVariable String enterpriseId,
                                                      @RequestBody InvitationCreateRequest payload,
                                                      HttpServletRequest request) {
        return responses.operation(invitations.create(enterpriseId, payload, request), request);
    }

    @PostMapping("/enterprises/{enterpriseId}/invitations/{id}/revoke")
    public ResponseEntity<ApiResponse<Object>> revoke(@PathVariable String enterpriseId, @PathVariable String id,
                                                      HttpServletRequest request) {
        return responses.operation(invitations.revise(enterpriseId, id, false, request), request);
    }

    @PostMapping("/enterprises/{enterpriseId}/invitations/{id}/resend")
    public ResponseEntity<ApiResponse<Object>> resend(@PathVariable String enterpriseId, @PathVariable String id,
                                                      HttpServletRequest request) {
        return responses.operation(invitations.revise(enterpriseId, id, true, request), request);
    }

    @GetMapping("/invitations/preview")
    public ApiResponse<InvitationPreview> preview(@RequestParam String token, HttpServletRequest request) {
        return responses.success(invitations.preview(token, request), request);
    }

    @PostMapping("/invitations/accept")
    public ApiResponse<CurrentIdentityResponse> accept(@RequestBody InvitationAcceptRequest payload,
                                                       HttpServletRequest request) {
        return responses.success(invitations.accept(payload, request), request);
    }

    @PostMapping("/invitations/preview")
    public ApiResponse<InvitationPreview> previewCode(@RequestBody TokenRequest payload, HttpServletRequest request) {
        return responses.success(invitations.preview(payload.token(), request), request);
    }
}
