package com.stonewu.agenteam.controller.skill;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.skill.request.SkillImportConfirmRequest;
import com.stonewu.agenteam.model.skill.request.SkillImportPreviewRequest;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.skill.SkillTransferApiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 技能文件的预览、确认和指定版本导出入口。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/skills")
public class SkillTransferApiController {

    private final SkillTransferApiService skills;

    private final ApiResponses responses;

    public SkillTransferApiController(SkillTransferApiService skills, ApiResponses responses) {
        this.skills = skills;
        this.responses = responses;
    }

    @PostMapping("/import-preview")
    public ResponseEntity<ApiResponse<Object>> preview(@PathVariable String enterpriseId,
                                                       @RequestBody SkillImportPreviewRequest body,
                                                       HttpServletRequest request) {
        return responses.operation(skills.preview(enterpriseId, body.fileId(), request), request);
    }

    @PostMapping("/import")
    public ResponseEntity<ApiResponse<Object>> confirm(@PathVariable String enterpriseId,
                                                       @RequestBody SkillImportConfirmRequest body,
                                                       HttpServletRequest request) {
        return responses.operation(skills.confirm(enterpriseId, body, request), request);
    }

    @PostMapping("/{resourceId}/versions/{versionId}/export")
    public ResponseEntity<ApiResponse<Object>> export(@PathVariable String enterpriseId,
                                                      @PathVariable String resourceId, @PathVariable String versionId,
                                                      HttpServletRequest request) {
        return responses.operation(skills.export(enterpriseId, resourceId, versionId, request), request);
    }
}
