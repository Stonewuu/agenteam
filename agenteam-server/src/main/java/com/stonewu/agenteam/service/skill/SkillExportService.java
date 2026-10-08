package com.stonewu.agenteam.service.skill;

import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.mapper.skill.SkillPackageMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.file.entity.PreparedGeneratedFile;
import com.stonewu.agenteam.model.permission.entity.ResourceCapability;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionRecord;
import com.stonewu.agenteam.model.skill.entity.SkillPackageContent.NamedDependency;
import com.stonewu.agenteam.service.file.GeneratedFileService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.resource.ResourcePolicy;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * 导出指定固定版本，只包含技能内容及仍有权使用的依赖名称。
 */
@Service
public class SkillExportService {
    private final ResourcePolicy resources;
    private final ResourceVersionMapper versions;
    private final ResourceAuthorizationService access;
    private final SkillPackageMapper packages;
    private final GeneratedFileService files;

    public SkillExportService(ResourcePolicy resources, ResourceVersionMapper versions,
                              ResourceAuthorizationService access,
                              SkillPackageMapper packages, GeneratedFileService files) {
        this.resources = resources;
        this.versions = versions;
        this.access = access;
        this.packages = packages;
        this.files = files;
    }

    public record ExportSource(ResourceVersionRecord version, List<NamedDependency> dependencies) {
    }

    public ExportSource authorize(AuthContext actor, String id, String versionId, boolean mutation) {
        var resource = resources.authorize(actor, id, "export", mutation, false);
        if (resource.kind() != ResourceKind.SKILL) {
            throw ResourceAuthorizationService.unavailable();
        }
        var version = versions.find(actor.enterpriseId(), versionId)
            .filter(value -> value.resourceId().equals(resource.id()))
            .orElseThrow(ResourceAuthorizationService::unavailable);
        var names = new ArrayList<NamedDependency>();
        for (var binding : versions.dependencies(actor.enterpriseId(), version.id())) {
            var dependency = versions.find(actor.enterpriseId(), binding.versionId())
                .orElseThrow(ResourceAuthorizationService::unavailable);
            try {
                access.require(actor, dependency.resourceId(), binding.kind() + ".view", ResourceCapability.VIEW);
            } catch (ResponseStatusException denied) {
                if (denied.getStatusCode().value() != 403 && denied.getStatusCode().value() != 404) {
                    throw denied;
                }
                access.requireUse(actor, dependency.resourceId(), binding.kind());
            }
            names.add(new NamedDependency(binding.kind(), dependency.name()));
        }
        return new ExportSource(version, List.copyOf(names));
    }

    public PreparedGeneratedFile prepare(AuthContext actor, String id, String versionId) {
        var source = authorize(actor, id, versionId, false);
        var version = source.version();
        var portable = packages.portable(version.name(), version.description(), version.config(),
            source.dependencies());
        return files.skillExport(actor, version.resourceId(), version.id(), version.name(), portable);
    }
}
