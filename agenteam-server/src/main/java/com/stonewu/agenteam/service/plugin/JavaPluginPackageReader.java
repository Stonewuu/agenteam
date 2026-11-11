package com.stonewu.agenteam.service.plugin;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolSchemaValidation;
import com.stonewu.plugin.api.JavaToolPlugin;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.zip.ZipInputStream;

/**
 * 安装前只读取包内静态声明，不在主服务解析或实例化插件类。
 */
@Component
public class JavaPluginPackageReader {
    public static final int MAX_PACKAGE_BYTES = 20 * 1024 * 1024;
    private static final Set<String> TYPES = Set.of("string", "integer", "number", "boolean");
    private final ObjectMapper json;
    private final ToolSchemaValidation schemas;

    public JavaPluginPackageReader(ObjectMapper json, ToolSchemaValidation schemas) {
        this.json = json;
        this.schemas = schemas;
    }

    public record Descriptor(String pluginId, String version, String name, String description, String author,
                             JsonNode configurationSchema) {
    }

    public Descriptor read(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_PACKAGE_BYTES) {
            throw invalid(Messages.text("error.plugin.javaPluginPackageReader.read.1"), null);
        }
        byte[] manifest = null;
        byte[] metadata = null;
        boolean extensions = false;
        long expanded = 0;
        var names = new HashSet<String>();
        try (var archive = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = archive.getNextEntry(); entry != null; entry = archive.getNextEntry()) {
                String name = entry.getName();
                if (!names.add(name) || names.size() > 10000 || name.contains("..") || name.startsWith("/")
                    || name.contains("\\") || name.startsWith("BOOT-INF/") || name.startsWith("com/stonewu/tool/api/")
                    || name.startsWith("com/stonewu/plugin/api/") || name.startsWith("org/pf4j/")) {
                    throw invalid(Messages.text("error.plugin.javaPluginPackageReader.read.2"), null);
                }
                boolean document = name.equals("META-INF/MANIFEST.MF") || name.equals("META-INF/agenteam-plugin.json");
                var content = new ByteArrayOutputStream();
                var buffer = new byte[8192];
                int read;
                while ((read = archive.read(buffer)) != -1) {
                    expanded += read;
                    if (expanded > 80L * 1024 * 1024 || (document && content.size() + read > 65536)) {
                        throw invalid(Messages.text("error.plugin.javaPluginPackageReader.read.3"), null);
                    }
                    if (document) {
                        content.write(buffer, 0, read);
                    }
                }
                if (name.equals("META-INF/MANIFEST.MF")) {
                    manifest = content.toByteArray();
                } else if (name.equals("META-INF/agenteam-plugin.json")) {
                    metadata = content.toByteArray();
                } else if (name.equals("META-INF/extensions.idx")) {
                    extensions = true;
                }
            }
            if (manifest == null || metadata == null || !extensions) {
                throw invalid(Messages.text("error.plugin.javaPluginPackageReader.read.4"), null);
            }
            var attributes = new Manifest(new ByteArrayInputStream(manifest)).getMainAttributes();
            String id = attributes.getValue("Plugin-Id");
            String version = attributes.getValue("Plugin-Version");
            if (id == null || !id.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79}") || version == null
                || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?") || version.length() > 80
                || !JavaToolPlugin.API_VERSION.equals(attributes.getValue("Plugin-Requires"))) {
                throw invalid(Messages.text("error.plugin.javaPluginPackageReader.read.5"), null);
            }
            if (attributes.getValue("Plugin-Dependencies") != null) {
                throw invalid(Messages.text("error.plugin.javaPluginPackageReader.read.6"), null);
            }
            JsonNode document = json.readTree(metadata);
            String title = document.path("name").asText();
            String description = attributes.getValue("Plugin-Description");
            String author = attributes.getValue("Plugin-Provider");
            if (title.isBlank() || title.length() > 100 || description == null || description.isBlank()
                || description.length() > 2000 || author == null || author.isBlank() || author.length() > 100) {
                throw invalid(Messages.text("error.plugin.javaPluginPackageReader.read.7"), null);
            }
            JsonNode schema = document.path("configurationSchema");
            validateConfigurationSchema(schema);
            return new Descriptor(id, version, title, description, author, schema);
        } catch (ApiException failure) {
            throw failure;
        } catch (IOException failure) {
            throw invalid(Messages.text("error.plugin.javaPluginPackageReader.read.8"), failure);
        }
    }

    private void validateConfigurationSchema(JsonNode schema) {
        if (!schema.path("type").asText().equals("object") || !schema.path("properties").isObject()
            || !schema.path("additionalProperties").isBoolean() || schema.path("additionalProperties").asBoolean()
            || schema.path("properties").size() > 30) {
            throw invalid(Messages.text("error.plugin.javaPluginPackageReader.validateConfigurationSchema.1"), null);
        }
        schema.path("properties").fields().forEachRemaining(field -> {
            if (!field.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                || !TYPES.contains(field.getValue().path("type").asText())
                || field.getValue().path("title").asText().isBlank()) {
                throw invalid(Messages.text("error.plugin.javaPluginPackageReader.validateConfigurationSchema.2"), null);
            }
        });
        schemas.compile(schema);
    }

    private static ApiException invalid(String message, Throwable cause) {
        return ApiException.invalidField("package", message, cause);
    }
}
