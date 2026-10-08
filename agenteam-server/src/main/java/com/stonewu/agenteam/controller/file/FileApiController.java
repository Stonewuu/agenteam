package com.stonewu.agenteam.controller.file;

import com.stonewu.agenteam.model.file.request.FileCompleteRequest;
import com.stonewu.agenteam.model.file.request.FilePrepareRequest;
import com.stonewu.agenteam.model.file.response.FileDownloadView;
import com.stonewu.agenteam.model.file.response.FileSummaryView;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.file.FileApiService;
import com.stonewu.agenteam.service.file.FileDownloadService;
import com.stonewu.agenteam.service.file.FileUploadService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 文件正文采用流式传输，普通接口继续使用统一响应。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/files")
public class FileApiController {

    private final FileApiService files;

    private final FileUploadService uploads;

    private final FileDownloadService downloads;

    private final AuthContextService identity;

    private final ApiResponses responses;

    public FileApiController(FileApiService files, FileUploadService uploads, FileDownloadService downloads,
                             AuthContextService identity, ApiResponses responses) {
        this.files = files;
        this.uploads = uploads;
        this.downloads = downloads;
        this.identity = identity;
        this.responses = responses;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Object>> prepare(@PathVariable String enterpriseId,
                                                       @RequestBody FilePrepareRequest body,
                                                       HttpServletRequest request) {
        return responses.operation(files.prepare(enterpriseId, body, request), request);
    }

    @PutMapping("/{fileId}/content")
    public ResponseEntity<Void> upload(@PathVariable String enterpriseId, @PathVariable String fileId,
                                       HttpServletRequest request) throws IOException {
        uploads.upload(identity.requireEnterprise(request.getSession(false), enterpriseId), fileId,
            request.getContentLengthLong(), request.getInputStream());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{fileId}/complete")
    public ResponseEntity<ApiResponse<Object>> complete(@PathVariable String enterpriseId, @PathVariable String fileId,
                                                        @RequestBody FileCompleteRequest body,
                                                        HttpServletRequest request) {
        return responses.operation(files.complete(enterpriseId, fileId, body, request), request);
    }

    @GetMapping("/{fileId}")
    public ApiResponse<FileSummaryView> status(@PathVariable String enterpriseId, @PathVariable String fileId,
                                               HttpServletRequest request) {
        return responses.success(files.status(enterpriseId, fileId, request), request);
    }

    @DeleteMapping("/{fileId}")
    public ResponseEntity<ApiResponse<Object>> delete(@PathVariable String enterpriseId, @PathVariable String fileId,
                                                      HttpServletRequest request) {
        return responses.operation(files.delete(enterpriseId, fileId, request), request);
    }

    @GetMapping("/{fileId}/download")
    public ApiResponse<FileDownloadView> download(@PathVariable String enterpriseId, @PathVariable String fileId,
                                                  HttpServletRequest request) {
        return responses.success(
            downloads.issue(identity.requireEnterprise(request.getSession(false), enterpriseId), fileId), request);
    }

    @GetMapping("/{fileId}/content")
    public ResponseEntity<StreamingResponseBody> content(@PathVariable String enterpriseId, @PathVariable String fileId,
                                                         @RequestParam String token, HttpServletRequest request,
                                                         HttpServletResponse response) {
        var actor = identity.requireEnterprise(request.getSession(false), enterpriseId);
        var file = downloads.verify(actor, fileId, token);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header("Content-Security-Policy", "sandbox").body(output -> {
                try (var input = downloads.open(actor, file.id(), token)) {
                    // 打开失败时仍需返回完整错误正文，不能提前使用文件长度和下载名称。
                    response.setContentLengthLong(file.sizeBytes());
                    response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.originalName(), StandardCharsets.UTF_8).build()
                            .toString());
                    input.transferTo(output);
                }
            });
    }
}
