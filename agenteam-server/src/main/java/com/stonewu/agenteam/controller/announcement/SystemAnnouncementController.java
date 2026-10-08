package com.stonewu.agenteam.controller.announcement;

import com.stonewu.agenteam.model.announcement.request.AnnouncementStatusRequest;
import com.stonewu.agenteam.model.announcement.request.AnnouncementWriteRequest;
import com.stonewu.agenteam.model.announcement.response.AnnouncementManagementView;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.announcement.AnnouncementApiService;
import com.stonewu.agenteam.service.announcement.AnnouncementManagementService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 超级管理员的平台与企业公告配置入口。
 */
@RestController
@RequestMapping("/api/v1/system/announcements")
public class SystemAnnouncementController {

    private final AnnouncementManagementService management;

    private final AnnouncementApiService mutations;

    private final ApiResponses responses;

    public SystemAnnouncementController(AnnouncementManagementService management, AnnouncementApiService mutations,
                                        ApiResponses responses) {
        this.management = management;
        this.mutations = mutations;
        this.responses = responses;
    }

    @GetMapping("/enterprises")
    public ApiResponse<PageResponse<AnnouncementManagementService.EnterpriseOption>> enterprises(
        @RequestParam(required = false) String query, @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return responses.success(
            management.enterpriseOptions(mutations.actor(request, null, true), query, cursor, limit), request);
    }

    @GetMapping("/platform")
    public ApiResponse<AnnouncementManagementView> listPlatform(@RequestParam(required = false) String cursor,
                                                                @RequestParam(required = false) Integer limit,
                                                                HttpServletRequest request) {
        return responses.success(management.list(mutations.actor(request, null, true), null, true, cursor, limit),
            request);
    }

    @PostMapping("/platform")
    public ResponseEntity<?> createPlatform(@RequestBody AnnouncementWriteRequest input, HttpServletRequest request) {
        return responses.operation(mutations.create(null, true, input, request), request);
    }

    @PutMapping("/platform/{id}")
    public ResponseEntity<?> updatePlatform(@PathVariable String id, @RequestBody AnnouncementWriteRequest input,
                                            HttpServletRequest request) {
        return responses.operation(mutations.update(null, true, id, input, request), request);
    }

    @PostMapping("/platform/{id}/status")
    public ResponseEntity<?> statusPlatform(@PathVariable String id, @RequestBody AnnouncementStatusRequest input,
                                            HttpServletRequest request) {
        return responses.operation(mutations.status(null, true, id, input, request), request);
    }

    @DeleteMapping("/platform/{id}")
    public ResponseEntity<?> deletePlatform(@PathVariable String id, HttpServletRequest request) {
        return responses.operation(mutations.delete(null, true, id, request), request);
    }

    @GetMapping("/enterprises/{enterpriseId}")
    public ApiResponse<AnnouncementManagementView> listEnterprise(@PathVariable String enterpriseId,
                                                                  @RequestParam(required = false) String cursor,
                                                                  @RequestParam(required = false) Integer limit,
                                                                  HttpServletRequest request) {
        return responses.success(
            management.list(mutations.actor(request, enterpriseId, true), enterpriseId, true, cursor, limit), request);
    }

    @PostMapping("/enterprises/{enterpriseId}")
    public ResponseEntity<?> createEnterprise(@PathVariable String enterpriseId,
                                              @RequestBody AnnouncementWriteRequest input, HttpServletRequest request) {
        return responses.operation(mutations.create(enterpriseId, true, input, request), request);
    }

    @PutMapping("/enterprises/{enterpriseId}/{id}")
    public ResponseEntity<?> updateEnterprise(@PathVariable String enterpriseId, @PathVariable String id,
                                              @RequestBody AnnouncementWriteRequest input, HttpServletRequest request) {
        return responses.operation(mutations.update(enterpriseId, true, id, input, request), request);
    }

    @PostMapping("/enterprises/{enterpriseId}/{id}/status")
    public ResponseEntity<?> statusEnterprise(@PathVariable String enterpriseId, @PathVariable String id,
                                              @RequestBody AnnouncementStatusRequest input,
                                              HttpServletRequest request) {
        return responses.operation(mutations.status(enterpriseId, true, id, input, request), request);
    }

    @DeleteMapping("/enterprises/{enterpriseId}/{id}")
    public ResponseEntity<?> deleteEnterprise(@PathVariable String enterpriseId, @PathVariable String id,
                                              HttpServletRequest request) {
        return responses.operation(mutations.delete(enterpriseId, true, id, request), request);
    }
}
