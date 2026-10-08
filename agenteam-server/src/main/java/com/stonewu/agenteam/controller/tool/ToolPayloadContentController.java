package com.stonewu.agenteam.controller.tool;

import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.file.TextFileDocument;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.tool.ToolPayloadContentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;

/**
 * 详情只在用户展开后读取，每个请求重新检查当前会话与来源权限。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}")
public class ToolPayloadContentController {

    private final ToolPayloadContentService content;

    private final AuthContextService identity;

    private final ApiResponses responses;

    public ToolPayloadContentController(ToolPayloadContentService content, AuthContextService identity,
                                        ApiResponses responses) {
        this.content = content;
        this.identity = identity;
        this.responses = responses;
    }

    @GetMapping("/runs/{runId}/steps/{stepId}/tool-content")
    public Object forStep(@PathVariable String enterpriseId, @PathVariable String runId, @PathVariable String stepId,
                          @RequestParam(defaultValue = "result") String part,
                          @RequestParam(defaultValue = "readable") String view,
                          @RequestParam(defaultValue = "0") int offset,
                          @RequestParam(defaultValue = "false") boolean backwards,
                          @RequestParam(defaultValue = "32768") int limit,
                          @RequestParam(required = false) String revision, HttpServletRequest request) {
        return responses.success(
            content.forStep(identity.requireEnterprise(request.getSession(false), enterpriseId), runId, stepId, part,
                view, offset, backwards, limit, revision), request);
    }

    @GetMapping("/tool-calls/{callId}/content")
    public Object forLog(@PathVariable String enterpriseId, @PathVariable String callId,
                         @RequestParam(defaultValue = "result") String part,
                         @RequestParam(defaultValue = "readable") String view,
                         @RequestParam(defaultValue = "0") int offset,
                         @RequestParam(defaultValue = "false") boolean backwards,
                         @RequestParam(defaultValue = "32768") int limit,
                         @RequestParam(required = false) String revision, HttpServletRequest request) {
        return responses.success(
            content.forLog(identity.requireEnterprise(request.getSession(false), enterpriseId), callId, part, view,
                offset, backwards, limit, revision), request);
    }

    @GetMapping("/runs/{runId}/steps/{stepId}/tool-content/search")
    public Object searchStep(@PathVariable String enterpriseId, @PathVariable String runId, @PathVariable String stepId,
                             @RequestParam(defaultValue = "result") String part,
                             @RequestParam(defaultValue = "readable") String view, @RequestParam String query,
                             @RequestParam(defaultValue = "1") int startLine,
                             @RequestParam(required = false) String revision, HttpServletRequest request) {
        var document = content.stepDocument(identity.requireEnterprise(request.getSession(false), enterpriseId), runId,
            stepId, part, view, revision);
        return responses.success(document.search(query, false, true, startLine, 1, 0, 2, 2048, () -> false), request);
    }

    @GetMapping("/tool-calls/{callId}/content/search")
    public Object searchLog(@PathVariable String enterpriseId, @PathVariable String callId,
                            @RequestParam(defaultValue = "result") String part,
                            @RequestParam(defaultValue = "readable") String view, @RequestParam String query,
                            @RequestParam(defaultValue = "1") int startLine,
                            @RequestParam(required = false) String revision, HttpServletRequest request) {
        var document = content.logDocument(identity.requireEnterprise(request.getSession(false), enterpriseId), callId,
            part, view, revision);
        return responses.success(document.search(query, false, true, startLine, 1, 0, 2, 2048, () -> false), request);
    }

    @GetMapping("/runs/{runId}/steps/{stepId}/tool-content/download")
    public ResponseEntity<StreamingResponseBody> downloadStep(@PathVariable String enterpriseId,
                                                              @PathVariable String runId, @PathVariable String stepId,
                                                              @RequestParam(defaultValue = "result") String part,
                                                              @RequestParam(defaultValue = "readable") String view,
                                                              HttpServletRequest request) {
        return download(
            content.stepDocument(identity.requireEnterprise(request.getSession(false), enterpriseId), runId, stepId,
                part, view, null));
    }

    @GetMapping("/tool-calls/{callId}/content/download")
    public ResponseEntity<StreamingResponseBody> downloadLog(@PathVariable String enterpriseId,
                                                             @PathVariable String callId,
                                                             @RequestParam(defaultValue = "result") String part,
                                                             @RequestParam(defaultValue = "readable") String view,
                                                             HttpServletRequest request) {
        return download(
            content.logDocument(identity.requireEnterprise(request.getSession(false), enterpriseId), callId, part, view,
                null));
    }

    private ResponseEntity<StreamingResponseBody> download(TextFileDocument document) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).contentLength(document.sizeBytes())
            .header(HttpHeaders.CACHE_CONTROL, "no-store").header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename("工具内容.txt", StandardCharsets.UTF_8).build().toString())
            .body(document::transferTo);
    }
}
