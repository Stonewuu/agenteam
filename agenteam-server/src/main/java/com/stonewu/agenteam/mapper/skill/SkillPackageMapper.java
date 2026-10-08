package com.stonewu.agenteam.mapper.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.model.skill.entity.SkillPackageContent;
import com.stonewu.agenteam.model.skill.entity.SkillPackageContent.NamedDependency;
import com.stonewu.agenteam.model.skill.request.SkillConfigurationInput;
import com.stonewu.agenteam.model.skill.request.SkillPackageInput;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.InputValidation;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 可移植文件只接收正式格式，删除内部引用与认证字段，不查询其他企业资源。
 */
@Component
public class SkillPackageMapper {
    private static final List<String> CONFIG_FIELDS = List.of("icon", "color", "scenario", "inputDescription",
        "instructions", "outputDescription", "example", "showInWorkspace");
    private final ObjectMapper mapper;
    private final ResourceJson json;

    public SkillPackageMapper(ObjectMapper mapper, ResourceJson json) {
        this.mapper = mapper;
        this.json = json;
    }

    public SkillPackageContent parse(FileRecord file, String text) {
        if (!file.mediaType().equals("application/json")) {
            return text(text);
        }
        JsonNode source;
        try {
            source = mapper.readTree(text);
        } catch (Exception malformed) {
            throw invalid();
        }
        if (!source.isObject() || !source.path("formatVersion").isIntegralNumber() || source.path("formatVersion")
            .asInt() != 1) {
            throw invalid();
        }
        var portable = mapper.createObjectNode().put("formatVersion", 1);
        for (String name : List.of("name", "description")) {
            if (source.has(name)) {
                portable.set(name, source.get(name));
            }
        }
        var config = portable.putObject("config");
        for (String name : CONFIG_FIELDS) {
            if (source.path("config").has(name)) {
                config.set(name, source.path("config").get(name));
            }
        }
        var dependencies = portable.putArray("dependencies");
        if (!source.path("dependencies").isArray()) {
            throw invalid();
        }
        for (var dependency : source.path("dependencies")) {
            var cleaned = dependencies.addObject();
            for (String name : List.of("kind", "name")) {
                if (dependency.has(name)) {
                    cleaned.set(name, dependency.get(name));
                }
            }
        }
        InputValidation.read(portable, SkillPackageInput.class, "file");
        var names = new ArrayList<NamedDependency>();
        dependencies.forEach(
            value -> names.add(new NamedDependency(value.path("kind").asText(), value.path("name").asText())));
        for (String kind : List.of("plugin", "knowledge")) {
            if (!source.path("config").path(kind + "VersionIds").isEmpty() && names.stream()
                .noneMatch(value -> value.kind().equals(kind))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SKILL_PACKAGE_INVALID",
                    "技能文件缺少依赖名称，请补齐后再导入。");
            }
        }
        config.putArray("pluginVersionIds");
        config.putArray("knowledgeVersionIds");
        return new SkillPackageContent(portable.path("name").asText(), portable.path("description").asText(), config,
            List.copyOf(names));
    }

    public JsonNode portable(String name, String description, JsonNode config, List<NamedDependency> dependencies) {
        var result = mapper.createObjectNode().put("formatVersion", 1).put("name", name)
            .put("description", description);
        var exported = result.putObject("config");
        for (String field : CONFIG_FIELDS) {
            exported.set(field, config.path(field));
        }
        result.set("dependencies", json.tree(dependencies));
        InputValidation.read(result, SkillPackageInput.class, "file");
        return result;
    }

    private SkillPackageContent text(String text) {
        String[] lines = text.split("\\R", 2);
        if (lines.length < 2 || lines[1].isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SKILL_PACKAGE_INVALID",
                "文件首行之后还需要填写技能执行指令。");
        }
        String name = lines[0].replaceFirst("^#{1,6}\\s+", "").strip();
        if (name.isBlank()) {
            throw invalid();
        }
        if (name.codePointCount(0, name.length()) > 80) {
            name = name.substring(0, name.offsetByCodePoints(0, 80));
        }
        JsonNode config = json.tree(
            Map.of("icon", "BookOpen", "color", "purple", "scenario", "", "inputDescription", "", "instructions",
                lines[1].strip(),
                "outputDescription", "", "example", "", "showInWorkspace", false, "pluginVersionIds", List.of(),
                "knowledgeVersionIds", List.of()));
        InputValidation.read(config, SkillConfigurationInput.class, "config");
        return new SkillPackageContent(name, "", config, List.of());
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SKILL_PACKAGE_INVALID",
            "技能文件格式不正确或格式版本不受支持。");
    }
}
