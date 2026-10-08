package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.model.file.request.FileCompleteRequest;
import com.stonewu.agenteam.model.file.request.FilePrepareRequest;
import com.stonewu.agenteam.model.file.response.FileSummaryView;
import com.stonewu.agenteam.model.file.response.FileUploadView;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * 文件检查请求只提交真实文件摘要，实际检查由持久后台任务执行。
 */
@Service
public class FileApiService {
    private final AuthContextService identity;
    private final FileAccessService access;
    private final FileUploadTransactions transactions;
    private final FileContentStorage storage;
    private final IdempotentRequestService requests;
    private final String origin;
    private final FileRetentionTransactions retention;

    public FileApiService(AuthContextService identity, FileAccessService access, FileUploadTransactions transactions,
                          FileContentStorage storage,
                          IdempotentRequestService requests, FileRetentionTransactions retention,
                          @Value("${agenteam.web.public-base-url:http://localhost:3000}") String origin) {
        this.identity = identity;
        this.access = access;
        this.transactions = transactions;
        this.storage = storage;
        this.requests = requests;
        this.origin = origin.replaceAll("/$", "");
        this.retention = retention;
    }

    public ApiOperationResult prepare(String enterprise, FilePrepareRequest input, HttpServletRequest request) {
        InputValidation.request(request, FilePrepareRequest.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> access.authorizeUpload(identity.requireEnterprise(request.getSession(false), enterprise),
                input.purpose(), input.resourceId(), true), () -> {
                var file = transactions.prepare(actor, input);
                return ApiOperationResult.of(201, new FileUploadView(file.id(),
                    origin + "/api/v1/enterprises/" + file.enterpriseId() + "/files/" + file.id() + "/content",
                    "PUT", Map.of("Content-Type", file.mediaType()), file.uploadExpiresAt().toString()));
            });
    }

    public ApiOperationResult complete(String enterprise, String id, FileCompleteRequest input,
                                       HttpServletRequest request) {
        InputValidation.request(request, FileCompleteRequest.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        Runnable authorize = () -> access.uploadOwner(identity.requireEnterprise(request.getSession(false), enterprise),
            id, true);
        var replay = requests.replay(request, actor.user(), enterprise, Set.of(), authorize);
        if (replay.isPresent()) {
            return replay.get();
        }
        var file = access.uploadOwner(actor, id, false);
        if (file.status().equals("pending")) {
            throw new ApiException(HttpStatus.CONFLICT, "FILE_NOT_UPLOADED", "请先完成文件上传。");
        }
        var actual = storage.verify(file);
        return requests.execute(request, actor.user(), enterprise, Set.of(), authorize,
            () -> ApiOperationResult.of(202,
                FileMapper.summary(transactions.complete(actor, file.id(), input, actual))));
    }

    public FileSummaryView status(String enterprise, String id, HttpServletRequest request) {
        return FileMapper.summary(
            access.readable(identity.requireEnterprise(request.getSession(false), enterprise), id, false));
    }

    public ApiOperationResult delete(String enterprise, String id, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> access.uploadOwner(identity.requireEnterprise(request.getSession(false), enterprise), id, true),
            () -> {
                retention.delete(actor, id);
                return ApiOperationResult.of(200, Map.of("success", true));
            });
    }
}
