package com.stonewu.agenteam.controller.enterprise;

import com.stonewu.agenteam.model.enterprise.request.MemberCreateRequest;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.service.enterprise.MemberCreationApiService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手动创建复用成员管理授权，不提供修改已有全局账号的入口。
 */
@RestController
public class MemberCreationApiController {

    private final MemberCreationApiService members;

    private final ApiResponses responses;

    public MemberCreationApiController(MemberCreationApiService members, ApiResponses responses) {
        this.members = members;
        this.responses = responses;
    }

    @PostMapping("/api/v1/enterprises/{enterpriseId}/members")
    public ResponseEntity<ApiResponse<Object>> create(@PathVariable String enterpriseId,
                                                      @RequestBody MemberCreateRequest payload,
                                                      HttpServletRequest request) {
        return responses.operation(members.create(enterpriseId, payload, request), request);
    }
}
