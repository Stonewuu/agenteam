package com.stonewu.agenteam.controller.environment;

import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.environment.request.EnvironmentTerminalInput;
import com.stonewu.agenteam.model.environment.request.EnvironmentFolderInput;
import com.stonewu.agenteam.model.environment.request.EnvironmentUploadDirectoriesInput;
import com.stonewu.agenteam.model.environment.response.EnvironmentTerminalView;
import com.stonewu.agenteam.model.environment.response.EnvironmentView;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.environment.EnvironmentFileService;
import com.stonewu.agenteam.service.environment.EnvironmentFileMutationService;
import com.stonewu.agenteam.service.environment.EnvironmentFileStatistics;
import com.stonewu.agenteam.service.environment.EnvironmentWorkspaceService;
import com.stonewu.agenteam.service.environment.UserTerminalRuntime;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.http.InputValidation;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 用户环境始终取当前登录身份，管理员也不能借此接口访问其他用户的文件或命令。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/environment")
public class EnvironmentApiController {

    private final AuthContextService identity;

    private final EnvironmentWorkspaceService environments;

    private final UserTerminalRuntime terminals;

    private final EnvironmentFileService files;
    private final EnvironmentFileMutationService mutations;

    private final ApiResponses responses;

    public EnvironmentApiController(AuthContextService identity, EnvironmentWorkspaceService environments, UserTerminalRuntime terminals,
            EnvironmentFileService files, EnvironmentFileMutationService mutations, ApiResponses responses) {
        this.identity = identity;
        this.environments = environments;
        this.terminals = terminals;
        this.files = files;
        this.mutations = mutations;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<EnvironmentView> status(@PathVariable String enterpriseId, HttpServletRequest request) {
        return responses.success(environments.status(actor(enterpriseId, request)), request);
    }

    @PostMapping("/connect")
    public ApiResponse<EnvironmentView> connect(@PathVariable String enterpriseId, HttpServletRequest request) {
        return responses.success(environments.connect(actor(enterpriseId, request)), request);
    }

    @PostMapping("/stop")
    public ApiResponse<EnvironmentView> stop(@PathVariable String enterpriseId, HttpServletRequest request) {
        return responses.success(environments.stop(actor(enterpriseId, request)), request);
    }

    @PostMapping("/activity")
    public ApiResponse<?> activity(@PathVariable String enterpriseId, HttpServletRequest request) {
        environments.activity(actor(enterpriseId, request));
        return responses.success(Map.of(), request);
    }

    @PostMapping("/terminal")
    public ApiResponse<EnvironmentTerminalView> terminal(@PathVariable String enterpriseId, @RequestBody EnvironmentTerminalInput input, HttpServletRequest request) {
        InputValidation.request(request, EnvironmentTerminalInput.class);
        var actor = actor(enterpriseId, request);
        var project = "open".equals(input.action()) ? environments.prepare(actor) : environments.location(actor);
        return responses.success(terminals.exchange(project, input), request);
    }

    @GetMapping("/files")
    public ApiResponse<EnvironmentFileService.Directory> list(@PathVariable String enterpriseId, @RequestParam(defaultValue = "/workspace") String path, @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "") String query, @RequestParam(defaultValue = "name") String sort, HttpServletRequest request) {
        return responses.success(files.list(actor(enterpriseId, request), path, offset, query, sort), request);
    }

    @GetMapping("/files/preview")
    public ApiResponse<EnvironmentFileService.Preview> preview(@PathVariable String enterpriseId, @RequestParam String path, HttpServletRequest request) {
        return responses.success(files.preview(actor(enterpriseId, request), path), request);
    }

    @GetMapping("/files/usage")
    public ApiResponse<EnvironmentFileStatistics.Usage> usage(@PathVariable String enterpriseId, @RequestParam(required = false) String after,
                                                             HttpServletRequest request) {
        return responses.success(files.usage(actor(enterpriseId, request), after), request);
    }

    @PostMapping("/files/directories")
    public ApiResponse<?> createDirectory(@PathVariable String enterpriseId, @RequestBody EnvironmentFolderInput input,
                                         HttpServletRequest request) {
        InputValidation.request(request, EnvironmentFolderInput.class);
        mutations.createDirectory(actor(enterpriseId, request), input.path(), input.name());
        return responses.success(Map.of(), request);
    }

    @PutMapping(value = "/files/content", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ApiResponse<?> upload(@PathVariable String enterpriseId, @RequestParam String path, @RequestParam String name,
                                 HttpServletRequest request) throws IOException {
        mutations.upload(actor(enterpriseId, request), path, name, request.getContentLengthLong(), request.getInputStream());
        return responses.success(Map.of(), request);
    }

    @PostMapping("/files/upload-directories")
    public ApiResponse<?> prepareUploadDirectories(@PathVariable String enterpriseId,
            @RequestBody EnvironmentUploadDirectoriesInput input, HttpServletRequest request) {
        InputValidation.request(request, EnvironmentUploadDirectoriesInput.class);
        int created = mutations.prepareUploadDirectories(actor(enterpriseId, request), input.path(), input.directories());
        return responses.success(Map.of("created", created), request);
    }

    @DeleteMapping("/files")
    public ApiResponse<?> delete(@PathVariable String enterpriseId, @RequestParam String path, @RequestParam(defaultValue = "false") boolean directory,
                                 @RequestParam(required = false) String modifiedAt, HttpServletRequest request) {
        mutations.delete(actor(enterpriseId, request), path, directory, modifiedAt);
        return responses.success(Map.of(), request);
    }

    @GetMapping("/files/content")
    public ResponseEntity<byte[]> content(@PathVariable String enterpriseId, @RequestParam String path, HttpServletRequest request) {
        byte[] bytes = files.bytes(actor(enterpriseId, request), path);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(EnvironmentFileService.name(path), StandardCharsets.UTF_8).build().toString()).body(bytes);
    }

    private AuthContext actor(String enterprise, HttpServletRequest request) {
        return identity.requireEnterprise(request.getSession(false), enterprise);
    }
}
