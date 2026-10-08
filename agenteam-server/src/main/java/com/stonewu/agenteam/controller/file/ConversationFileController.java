package com.stonewu.agenteam.controller.file;

import com.stonewu.agenteam.model.file.response.ConversationFileView;
import com.stonewu.agenteam.model.file.response.FileTextSearch;
import com.stonewu.agenteam.model.file.response.FileTextSlice;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.file.ConversationFileService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 会话侧栏只读入口；目录、文件内容和媒体请求均校验当前成员的访问范围。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/conversations/{conversationId}/files")
public class ConversationFileController {

    private final ConversationFileService files;

    private final AuthContextService identity;

    private final ApiResponses responses;

    public ConversationFileController(ConversationFileService files, AuthContextService identity,
                                      ApiResponses responses) {
        this.files = files;
        this.identity = identity;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<PageResponse<ConversationFileView>> list(@PathVariable String enterpriseId,
                                                                @PathVariable String conversationId,
                                                                @RequestParam(defaultValue = "workspace") String scope,
                                                                @RequestParam(defaultValue = "") String path,
                                                                @RequestParam(required = false) String cursor,
                                                                @RequestParam(required = false) Integer limit,
                                                                HttpServletRequest request) {
        return responses.success(
            files.list(identity.requireEnterprise(request.getSession(false), enterpriseId), conversationId, scope, path,
                cursor, limit), request);
    }

    @GetMapping("/{fileId}")
    public ApiResponse<ConversationFileView> metadata(@PathVariable String enterpriseId,
                                                      @PathVariable String conversationId, @PathVariable String fileId,
                                                      HttpServletRequest request) {
        return responses.success(
            files.metadata(identity.requireEnterprise(request.getSession(false), enterpriseId), conversationId, fileId),
            request);
    }

    @GetMapping("/{fileId}/text")
    public ApiResponse<FileTextSlice> text(@PathVariable String enterpriseId, @PathVariable String conversationId,
                                           @PathVariable String fileId, @RequestParam(defaultValue = "0") int offset,
                                           @RequestParam(defaultValue = "32768") int limit,
                                           @RequestParam(defaultValue = "false") boolean backwards,
                                           @RequestParam(required = false) String revision,
                                           HttpServletRequest request) {
        return responses.success(
            files.text(identity.requireEnterprise(request.getSession(false), enterpriseId), conversationId, fileId,
                offset, limit, backwards, revision), request);
    }

    @GetMapping("/{fileId}/text/search")
    public ApiResponse<FileTextSearch> search(@PathVariable String enterpriseId, @PathVariable String conversationId,
                                              @PathVariable String fileId, @RequestParam String query,
                                              @RequestParam(defaultValue = "1") int startLine,
                                              @RequestParam(required = false) String revision,
                                              HttpServletRequest request) {
        return responses.success(
            files.search(identity.requireEnterprise(request.getSession(false), enterpriseId), conversationId, fileId,
                query, startLine, revision), request);
    }

    @GetMapping({"/{fileId}/content", "/{fileId}/text/download"})
    public ResponseEntity<StreamingResponseBody> content(@PathVariable String enterpriseId,
                                                         @PathVariable String conversationId,
                                                         @PathVariable String fileId,
                                                         @RequestParam(defaultValue = "false") boolean download,
                                                         HttpServletRequest request, HttpServletResponse response) {
        var actor = identity.requireEnterprise(request.getSession(false), enterpriseId);
        boolean attachment = download || request.getRequestURI().endsWith("/download");
        boolean head = request.getMethod().equals("HEAD");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(output -> {
            try (var opened = files.open(actor, conversationId, fileId)) {
                if (attachment && !head) {
                    files.downloaded(actor, conversationId);
                }
                FilePreviewHttpResponse.write(opened, attachment, request, response, output);
            }
        });
    }
}
