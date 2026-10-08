package com.stonewu.agenteam.controller.announcement;

import com.stonewu.agenteam.model.announcement.request.AnnouncementReadAllRequest;
import com.stonewu.agenteam.model.announcement.request.AnnouncementReadRequest;
import com.stonewu.agenteam.model.announcement.response.AnnouncementPageView;
import com.stonewu.agenteam.model.announcement.response.AnnouncementUnreadView;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.service.announcement.AnnouncementApiService;
import com.stonewu.agenteam.service.announcement.AnnouncementUserService;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 用户公告入口，企业身份始终从已登录会话验证。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/announcements")
public class AnnouncementApiController {

    private final AnnouncementUserService announcements;

    private final AnnouncementApiService mutations;

    private final AuthContextService identity;

    private final ApiResponses responses;

    public AnnouncementApiController(AnnouncementUserService announcements, AnnouncementApiService mutations,
                                     AuthContextService identity, ApiResponses responses) {
        this.announcements = announcements;
        this.mutations = mutations;
        this.identity = identity;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<AnnouncementPageView> list(@PathVariable String enterpriseId,
                                                  @RequestParam(defaultValue = "false") boolean unread,
                                                  @RequestParam(required = false) String cursor,
                                                  @RequestParam(required = false) Integer limit,
                                                  HttpServletRequest request) {
        return responses.success(
            announcements.list(identity.requireEnterprise(request.getSession(false), enterpriseId), unread, cursor,
                limit), request);
    }

    @GetMapping("/unread-count")
    public ApiResponse<AnnouncementUnreadView> unread(@PathVariable String enterpriseId, HttpServletRequest request) {
        return responses.success(
            announcements.unread(identity.requireEnterprise(request.getSession(false), enterpriseId)), request);
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<?> read(@PathVariable String enterpriseId, @PathVariable String id,
                                  @RequestBody AnnouncementReadRequest input, HttpServletRequest request) {
        return responses.operation(mutations.read(enterpriseId, id, input, request), request);
    }

    @PostMapping("/read-all")
    public ResponseEntity<?> readAll(@PathVariable String enterpriseId, @RequestBody AnnouncementReadAllRequest input,
                                     HttpServletRequest request) {
        return responses.operation(mutations.readAll(enterpriseId, input, request), request);
    }
}
