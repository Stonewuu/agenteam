package com.stonewu.agenteam.controller.integration;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.integration.request.ChannelBindingUpdateRequest;
import com.stonewu.agenteam.model.integration.request.ChannelBindingConfirmRequest;
import com.stonewu.agenteam.model.integration.response.ChannelBindingView;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.integration.ChannelBindingApiService;
import com.stonewu.agenteam.service.integration.ChannelBindingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 本人渠道绑定接口不允许指定其他用户编号。 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/me/channels")
public class ChannelBindingApiController {
    private final ChannelBindingService bindings;
    private final ChannelBindingApiService mutations;
    private final ApiResponses responses;

    public ChannelBindingApiController(ChannelBindingService bindings, ChannelBindingApiService mutations, ApiResponses responses) {
        this.bindings = bindings;
        this.mutations = mutations;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<List<ChannelBindingView>> list(@PathVariable String enterpriseId, HttpServletRequest request) {
        return responses.success(bindings.list(enterpriseId, request.getSession(false)), request);
    }

    @PostMapping("/confirm")
    public ResponseEntity<ApiResponse<Object>> confirm(@PathVariable String enterpriseId,
        @RequestBody ChannelBindingConfirmRequest input, HttpServletRequest request) {
        return responses.operation(mutations.confirm(enterpriseId, input, request), request);
    }

    @PatchMapping("/{bindingId}")
    public ResponseEntity<ApiResponse<Object>> update(@PathVariable String enterpriseId, @PathVariable String bindingId,
        @RequestBody ChannelBindingUpdateRequest input, HttpServletRequest request) {
        return responses.operation(mutations.update(enterpriseId, bindingId, input, request), request);
    }

    @DeleteMapping("/{bindingId}")
    public ResponseEntity<ApiResponse<Object>> revoke(@PathVariable String enterpriseId, @PathVariable String bindingId,
                                                     HttpServletRequest request) {
        return responses.operation(mutations.revoke(enterpriseId, bindingId, request), request);
    }
}
