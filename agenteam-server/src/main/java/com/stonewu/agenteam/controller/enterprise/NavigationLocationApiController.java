package com.stonewu.agenteam.controller.enterprise;

import com.stonewu.agenteam.model.enterprise.response.NavigationLocation;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.service.enterprise.NavigationLocationService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 仅返回已授权资源的页面定位信息。 */
@RestController
public class NavigationLocationApiController {
    private final NavigationLocationService service;
    private final ApiResponses responses;

    public NavigationLocationApiController(NavigationLocationService service, ApiResponses responses) {
        this.service = service;
        this.responses = responses;
    }

    @GetMapping("/api/v1/navigation/resource")
    public ApiResponse<NavigationLocation> locate(@RequestParam String type, @RequestParam String id,
                                                 HttpServletRequest request) {
        return responses.success(service.locate(request.getSession(false), type, id), request);
    }
}
