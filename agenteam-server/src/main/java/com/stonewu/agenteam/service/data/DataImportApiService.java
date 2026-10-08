package com.stonewu.agenteam.service.data;

import com.stonewu.agenteam.model.data.request.DataImportConfirmRequest;
import com.stonewu.agenteam.model.data.request.DataImportPreviewRequest;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 解析与完整类型检查在确认事务前完成，最终写入之前重新验证原请求和修改版本。
 */
@Service
public class DataImportApiService {
    private final AuthContextService identity;
    private final DataResourcePolicy policy;
    private final DataImportService imports;
    private final DataImportTokenService tokens;
    private final DataImportPreparation preparation;
    private final DataFieldValidation fields;
    private final IdempotentRequestService requests;

    public DataImportApiService(AuthContextService identity, DataResourcePolicy policy, DataImportService imports,
                                DataImportTokenService tokens,
                                DataImportPreparation preparation, DataFieldValidation fields,
                                IdempotentRequestService requests) {
        this.identity = identity;
        this.policy = policy;
        this.imports = imports;
        this.tokens = tokens;
        this.preparation = preparation;
        this.fields = fields;
        this.requests = requests;
    }

    public ApiOperationResult preview(String enterprise, String resource, DataImportPreviewRequest input,
                                      HttpServletRequest request) {
        InputValidation.request(request, DataImportPreviewRequest.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        Runnable authorize = () -> policy.edit(identity.requireEnterprise(request.getSession(false), enterprise),
            resource, true);
        var replay = requests.replay(request, actor.user(), enterprise, Set.of(), authorize);
        if (replay.isPresent()) {
            return replay.get();
        }
        var preview = imports.preview(actor, resource, input);
        return requests.execute(request, actor.user(), enterprise, Set.of(), authorize, () -> {
            imports.verify(actor, resource, tokens.read(actor, preview.previewToken()), true);
            return ApiOperationResult.of(200, preview);
        });
    }

    public ApiOperationResult confirm(String enterprise, String resource, DataImportConfirmRequest input,
                                      HttpServletRequest request) {
        InputValidation.request(request, DataImportConfirmRequest.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        Runnable authorize = () -> policy.edit(identity.requireEnterprise(request.getSession(false), enterprise),
            resource, true);
        var replay = requests.replay(request, actor.user(), enterprise, Set.of(), authorize);
        if (replay.isPresent()) {
            return replay.get();
        }
        var token = tokens.read(actor, input.previewToken());
        var source = imports.verify(actor, resource, token, false);
        try (var prepared = preparation.prepare(source.file(), source.profile(), fields.fields(input.fields()))) {
            return requests.execute(request, actor.user(), enterprise, Set.of(), authorize,
                () -> ApiOperationResult.of(201, imports.confirm(actor, resource, input.name(), token, prepared)));
        }
    }
}
