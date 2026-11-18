package com.stonewu.agenteam.controller.system;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.system.request.RuntimeSettingsRequest;
import com.stonewu.agenteam.model.system.response.RuntimeSettingsView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import com.stonewu.agenteam.service.system.RuntimeSettingsManagement;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 按分类读取和保存系统运行设置。 */
@RestController
@RequestMapping("/api/v1/system/runtime-settings")
public class RuntimeSettingsController {
    private final AuthContextService identity;
    private final RuntimeSettingsManagement management;
    private final ApiResponses responses;

    public RuntimeSettingsController(AuthContextService identity, RuntimeSettingsManagement management, ApiResponses responses) {
        this.identity = identity;
        this.management = management;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<RuntimeSettingsView> read(HttpServletRequest request) {
        return responses.success(management.read(identity.requireUser(request.getSession(false))), request);
    }

    @PutMapping("/{section}")
    public ApiResponse<RuntimeSettingsView> save(@PathVariable String section, @RequestBody RuntimeSettingsRequest input,
                                                HttpServletRequest request) {
        var actor = identity.requireUser(request.getSession(false));
        InputValidation.request(request, RuntimeSettingsRequest.class);
        return responses.success(management.save(actor, section, RequestPreconditions.revision(request), input), request);
    }
}
