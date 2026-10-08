package com.stonewu.agenteam.controller.announcement;

import com.stonewu.agenteam.model.announcement.request.AnnouncementStatusRequest;
import com.stonewu.agenteam.model.announcement.request.AnnouncementWriteRequest;
import com.stonewu.agenteam.model.announcement.response.AnnouncementManagementView;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.service.announcement.AnnouncementApiService;
import com.stonewu.agenteam.service.announcement.AnnouncementManagementService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 企业公告配置入口，不接受客户端更改公告所属范围。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/announcements/manage")
public class EnterpriseAnnouncementController {

    private final AnnouncementManagementService management;

    private final AnnouncementApiService mutations;

    private final ApiResponses responses;

    public EnterpriseAnnouncementController(AnnouncementManagementService management, AnnouncementApiService mutations,
                                            ApiResponses responses) {
        this.management = management;
        this.mutations = mutations;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<AnnouncementManagementView> listEnterprise(@PathVariable String enterpriseId,
                                                                  @RequestParam(required = false) String cursor,
                                                                  @RequestParam(required = false) Integer limit,
                                                                  HttpServletRequest request) {
        return responses.success(
            management.list(mutations.actor(request, enterpriseId, false), enterpriseId, false, cursor, limit),
            request);
    }

    @PostMapping
    public ResponseEntity<?> createEnterprise(@PathVariable String enterpriseId,
                                              @RequestBody AnnouncementWriteRequest input, HttpServletRequest request) {
        return responses.operation(mutations.create(enterpriseId, false, input, request), request);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateEnterprise(@PathVariable String enterpriseId, @PathVariable String id,
                                              @RequestBody AnnouncementWriteRequest input, HttpServletRequest request) {
        return responses.operation(mutations.update(enterpriseId, false, id, input, request), request);
    }

    @PostMapping("/{id}/status")
    public ResponseEntity<?> statusEnterprise(@PathVariable String enterpriseId, @PathVariable String id,
                                              @RequestBody AnnouncementStatusRequest input,
                                              HttpServletRequest request) {
        return responses.operation(mutations.status(enterpriseId, false, id, input, request), request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteEnterprise(@PathVariable String enterpriseId, @PathVariable String id,
                                              HttpServletRequest request) {
        return responses.operation(mutations.delete(enterpriseId, false, id, request), request);
    }
}
