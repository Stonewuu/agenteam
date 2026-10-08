package com.stonewu.agenteam.controller.file;

import com.stonewu.agenteam.model.file.response.ConversationFileView;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.file.OfficePreviewService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Office 预览使用当前对话的原文件访问权限。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/conversations/{conversationId}/files/{fileId}/preview")
public class OfficePreviewController {

    private final OfficePreviewService previews;

    private final AuthContextService identity;

    private final ApiResponses responses;

    public OfficePreviewController(OfficePreviewService previews, AuthContextService identity, ApiResponses responses) {
        this.previews = previews;
        this.identity = identity;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<ConversationFileView> prepare(@PathVariable String enterpriseId,
                                                     @PathVariable String conversationId, @PathVariable String fileId,
                                                     HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterpriseId);
        return responses.success(previews.prepare(actor, conversationId, fileId), request);
    }

    @GetMapping("/content")
    public ResponseEntity<StreamingResponseBody> content(@PathVariable String enterpriseId,
                                                         @PathVariable String conversationId,
                                                         @PathVariable String fileId, HttpServletRequest request,
                                                         HttpServletResponse response) {
        var actor = identity.requireEnterprise(request.getSession(false), enterpriseId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(output -> {
            try (var opened = previews.open(actor, conversationId, fileId)) {
                FilePreviewHttpResponse.write(opened, false, request, response, output);
            }
        });
    }
}
