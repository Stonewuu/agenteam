package com.stonewu.agenteam.service.skill;

import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.skill.SkillNameMapper;
import com.stonewu.agenteam.mapper.skill.SkillPackageMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.response.ResourceDetailView;
import com.stonewu.agenteam.model.skill.entity.SkillPackageContent;
import com.stonewu.agenteam.model.skill.request.SkillImportConfirmRequest;
import com.stonewu.agenteam.model.skill.response.SkillImportPreviewView;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.service.file.FileContentStorage;
import com.stonewu.agenteam.service.file.SkillFileValidation;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.resource.FixedDependencyService;
import com.stonewu.agenteam.service.resource.ResourceConfigurationService;
import com.stonewu.agenteam.service.resource.ResourceDraftService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预览只读取临时文件；确认创建新草稿，不更新已有资源，也不自动匹配依赖。
 */
@Service
public class SkillImportService {
    private final FileAccessService files;
    private final FileContentStorage storage;
    private final SkillFileValidation validation;
    private final SkillPackageMapper packages;
    private final SkillImportTokenService tokens;
    private final SkillNameMapper names;
    private final ResourceConfigurationService configurations;
    private final FixedDependencyService dependencies;
    private final ResourceDraftService drafts;
    private final EnterpriseAuthorizationService authorization;
    private final ResourceJson json;

    public SkillImportService(FileAccessService files, FileContentStorage storage, SkillFileValidation validation,
                              SkillPackageMapper packages,
                              SkillImportTokenService tokens, SkillNameMapper names,
                              ResourceConfigurationService configurations,
                              FixedDependencyService dependencies, ResourceDraftService drafts,
                              EnterpriseAuthorizationService authorization, ResourceJson json) {
        this.files = files;
        this.storage = storage;
        this.validation = validation;
        this.packages = packages;
        this.tokens = tokens;
        this.names = names;
        this.configurations = configurations;
        this.dependencies = dependencies;
        this.drafts = drafts;
        this.authorization = authorization;
        this.json = json;
    }

    public void authorize(AuthContext actor, boolean create) {
        authorization.lockAndRequire(actor, "skill.import");
        if (create) {
            authorization.require(actor, "skill.create");
        }
    }

    public SkillImportPreviewView preview(AuthContext actor, String fileId) {
        var file = files.ready(actor, fileId);
        var content = parse(file);
        configurations.draft(ResourceKind.SKILL, json.object(content.config()));
        return new SkillImportPreviewView(tokens.issue(actor, file), suggestedName(actor, content.name()),
            content.description(), json.object(content.config()),
            content.dependencies(), file.expiresAt().toString());
    }

    public PreparedImport prepare(AuthContext actor, SkillImportConfirmRequest input) {
        var file = verifyPreview(actor, input.previewToken(), false);
        return new PreparedImport(file, parse(file));
    }

    public FileRecord verifyPreview(AuthContext actor, String value, boolean mutation) {
        var token = tokens.read(actor, value);
        var file = files.uploadOwner(actor, token.fileId(), mutation);
        if (!file.status().equals("ready") || !file.sha256().equals(token.sha256())) {
            throw new ApiException(HttpStatus.CONFLICT, "SKILL_IMPORT_CHANGED",
                "文件内容或可用状态已经变化，请重新预览。");
        }
        return file;
    }

    public record PreparedImport(FileRecord file, SkillPackageContent source) {
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResourceDetailView confirm(AuthContext actor, SkillImportConfirmRequest input, PreparedImport prepared) {
        authorize(actor, true);
        var file = verifyPreview(actor, input.previewToken(), true);
        if (!file.sha256().equals(prepared.file().sha256()) || !file.id().equals(prepared.file().id())) {
            throw FileAccessService.unavailable();
        }
        var config = configurations.draft(ResourceKind.SKILL, input.config());
        for (var binding : configurations.dependencies(ResourceKind.SKILL, config)) {
            dependencies.require(actor, binding);
        }
        Map<String, List<String>> errors = new LinkedHashMap<>();
        for (String kind : List.of("plugin", "knowledge")) {
            long required = prepared.source().dependencies().stream().filter(value -> value.kind().equals(kind))
                .count();
            if (config.path(kind + "VersionIds").size() < required) {
                errors.put("config." + kind + "VersionIds", List.of(kind.equals("plugin")
                    ? "导入的技能包含尚未匹配的插件，请选择需要的插件，或调整指令后保存草稿。" : "导入的技能包含尚未匹配的知识库，请选择需要的知识库，或调整指令后保存草稿。"));
            }
        }
        return drafts.importSkill(actor, input.name(), input.description(), config, errors);
    }

    private SkillPackageContent parse(FileRecord file) {
        return packages.parse(file, validation.read(file, storage));
    }

    private String suggestedName(AuthContext actor, String original) {
        String candidate = original;
        for (int index = 1; index <= 100; index++) {
            if (!names.ownNameExists(actor.enterpriseId(), actor.userId(), candidate)) {
                return candidate;
            }
            String suffix = index == 1 ? " 副本" : " 副本 " + index;
            int maximum = 80 - suffix.codePointCount(0, suffix.length());
            candidate = original.substring(0, original.offsetByCodePoints(0,
                Math.min(maximum, original.codePointCount(0, original.length())))) + suffix;
        }
        return candidate;
    }
}
