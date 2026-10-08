package com.stonewu.agenteam.controller.data;

import com.stonewu.agenteam.model.data.request.DataImportConfirmRequest;
import com.stonewu.agenteam.model.data.request.DataImportPreviewRequest;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.service.data.DataImportApiService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 数据文件预览与明确确认的接口。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/data/{resourceId}")
public class DataImportApiController {

    private final DataImportApiService imports;

    private final ApiResponses responses;

    public DataImportApiController(DataImportApiService imports, ApiResponses responses) {
        this.imports = imports;
        this.responses = responses;
    }

    @PostMapping("/import-preview")
    public ResponseEntity<ApiResponse<Object>> preview(@PathVariable String enterpriseId,
                                                       @PathVariable String resourceId,
                                                       @RequestBody DataImportPreviewRequest input,
                                                       HttpServletRequest request) {
        return responses.operation(imports.preview(enterpriseId, resourceId, input, request), request);
    }

    @PostMapping("/import")
    public ResponseEntity<ApiResponse<Object>> confirm(@PathVariable String enterpriseId,
                                                       @PathVariable String resourceId,
                                                       @RequestBody DataImportConfirmRequest input,
                                                       HttpServletRequest request) {
        return responses.operation(imports.confirm(enterpriseId, resourceId, input, request), request);
    }
}
