package com.stonewu.agenteam.controller.security;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.security.request.CredentialRotateRequest;
import com.stonewu.agenteam.model.security.request.CredentialWriteRequest;
import com.stonewu.agenteam.model.security.response.CredentialSummaryView;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.security.CredentialApiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 凭据只有摘要读取和秘密写入接口，没有明文读取入口。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/credentials")
public class CredentialApiController {

    private final CredentialApiService credentials;

    private final ApiResponses responses;

    public CredentialApiController(CredentialApiService credentials, ApiResponses responses) {
        this.credentials = credentials;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<PageResponse<CredentialSummaryView>> list(@PathVariable String enterpriseId,
                                                                 @RequestParam(required = false) String cursor,
                                                                 @RequestParam(required = false) Integer limit,
                                                                 HttpServletRequest request) {
        return responses.success(credentials.list(enterpriseId, cursor, limit, request), request);
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Object>> create(@PathVariable String enterpriseId,
                                                      @RequestBody CredentialWriteRequest body,
                                                      HttpServletRequest request) {
        return responses.operation(credentials.create(enterpriseId, body, request), request);
    }

    @PostMapping("/{credentialId}/rotate")
    public ResponseEntity<ApiResponse<Object>> rotate(@PathVariable String enterpriseId,
                                                      @PathVariable String credentialId,
                                                      @RequestBody CredentialRotateRequest body,
                                                      HttpServletRequest request) {
        return responses.operation(credentials.rotate(enterpriseId, credentialId, body.secret(), request), request);
    }

    @DeleteMapping("/{credentialId}")
    public ResponseEntity<ApiResponse<Object>> revoke(@PathVariable String enterpriseId,
                                                      @PathVariable String credentialId, HttpServletRequest request) {
        return responses.operation(credentials.revoke(enterpriseId, credentialId, request), request);
    }
}
