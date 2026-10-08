package com.stonewu.agenteam.controller.schedule;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.schedule.request.ScheduleRuleInput;
import com.stonewu.agenteam.model.schedule.response.ScheduleTimesView;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.schedule.ScheduleTimePreviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/**
 * 将时间规则交给共同计算服务，返回接下来五次实际执行时刻。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/schedules")
public class ScheduleTimeApiController {

    private final ScheduleTimePreviewService preview;

    private final ApiResponses responses;

    public ScheduleTimeApiController(ScheduleTimePreviewService preview, ApiResponses responses) {
        this.preview = preview;
        this.responses = responses;
    }

    @PostMapping("/preview-times")
    public ApiResponse<ScheduleTimesView> times(@PathVariable String enterpriseId, @RequestBody ScheduleRuleInput input,
                                                HttpServletRequest request) {
        return responses.success(preview.preview(enterpriseId, input, request), request);
    }
}
