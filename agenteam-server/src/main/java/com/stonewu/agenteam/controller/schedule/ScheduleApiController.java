package com.stonewu.agenteam.controller.schedule;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.schedule.request.ScheduleEnabledRequest;
import com.stonewu.agenteam.model.schedule.request.ScheduleWriteRequest;
import com.stonewu.agenteam.model.schedule.response.ScheduleOccurrenceView;
import com.stonewu.agenteam.model.schedule.response.ScheduleView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.schedule.ScheduleApiService;
import com.stonewu.agenteam.service.schedule.ScheduleQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 接收本人计划请求；保存、固定版本与当前授权由对应服务处理。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/schedules")
public class ScheduleApiController {

    private final AuthContextService identity;

    private final ScheduleQueryService queries;

    private final ScheduleApiService mutations;

    private final ApiResponses responses;

    public ScheduleApiController(AuthContextService identity, ScheduleQueryService queries,
                                 ScheduleApiService mutations, ApiResponses responses) {
        this.identity = identity;
        this.queries = queries;
        this.mutations = mutations;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<PageResponse<ScheduleView>> list(@PathVariable String enterpriseId,
                                                        @RequestParam(required = false) String query,
                                                        @RequestParam(required = false) String cursor,
                                                        @RequestParam(required = false) Integer limit,
                                                        HttpServletRequest request) {
        return responses.success(
            queries.list(identity.requireEnterprise(request.getSession(false), enterpriseId), query, cursor, limit),
            request);
    }

    @GetMapping("/{scheduleId}")
    public ApiResponse<ScheduleView> get(@PathVariable String enterpriseId, @PathVariable String scheduleId,
                                         HttpServletRequest request) {
        return responses.success(
            queries.get(identity.requireEnterprise(request.getSession(false), enterpriseId), scheduleId), request);
    }

    @GetMapping("/{scheduleId}/occurrences")
    public ApiResponse<PageResponse<ScheduleOccurrenceView>> occurrences(@PathVariable String enterpriseId,
                                                                         @PathVariable String scheduleId,
                                                                         @RequestParam(required = false) String cursor,
                                                                         @RequestParam(required = false) Integer limit,
                                                                         HttpServletRequest request) {
        return responses.success(
            queries.occurrences(identity.requireEnterprise(request.getSession(false), enterpriseId), scheduleId, cursor,
                limit), request);
    }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable String enterpriseId, @RequestBody ScheduleWriteRequest input,
                                    HttpServletRequest request) {
        return responses.operation(mutations.create(enterpriseId, input, request), request);
    }

    @GetMapping("/{scheduleId}/occurrences/{occurrenceId}")
    public Object occurrence(@PathVariable String enterpriseId, @PathVariable String scheduleId, @PathVariable String occurrenceId,
                              HttpServletRequest request) {
        return responses.success(queries.occurrence(identity.requireEnterprise(request.getSession(false), enterpriseId), scheduleId, occurrenceId), request);
    }

    @PutMapping("/{scheduleId}")
    public ResponseEntity<?> update(@PathVariable String enterpriseId, @PathVariable String scheduleId,
                                    @RequestBody ScheduleWriteRequest input, HttpServletRequest request) {
        return responses.operation(mutations.update(enterpriseId, scheduleId, input, request), request);
    }

    @PatchMapping("/{scheduleId}/enabled")
    public ResponseEntity<?> enabled(@PathVariable String enterpriseId, @PathVariable String scheduleId,
                                     @RequestBody ScheduleEnabledRequest input, HttpServletRequest request) {
        return responses.operation(mutations.enabled(enterpriseId, scheduleId, input, request), request);
    }

    @PostMapping("/{scheduleId}/upgrade-version")
    public ResponseEntity<?> upgrade(@PathVariable String enterpriseId, @PathVariable String scheduleId,
                                     HttpServletRequest request) {
        return responses.operation(mutations.upgrade(enterpriseId, scheduleId, request), request);
    }

    @PostMapping("/{scheduleId}/run")
    public ResponseEntity<?> manual(@PathVariable String enterpriseId, @PathVariable String scheduleId,
                                    HttpServletRequest request) {
        return responses.operation(mutations.manual(enterpriseId, scheduleId, request), request);
    }

    @PostMapping("/{scheduleId}/occurrences/{occurrenceId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String enterpriseId, @PathVariable String scheduleId,
                                    @PathVariable String occurrenceId, HttpServletRequest request) {
        return responses.operation(mutations.cancel(enterpriseId, scheduleId, occurrenceId, request), request);
    }

    @DeleteMapping("/{scheduleId}")
    public ResponseEntity<?> delete(@PathVariable String enterpriseId, @PathVariable String scheduleId,
                                    HttpServletRequest request) {
        return responses.operation(mutations.delete(enterpriseId, scheduleId, request), request);
    }
}
