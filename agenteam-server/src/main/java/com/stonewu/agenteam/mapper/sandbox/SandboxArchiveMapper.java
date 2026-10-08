package com.stonewu.agenteam.mapper.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 跨主机传输只接受约定的逻辑目录，限制展开字节数并拒绝重复或越界文件。
 */
public final class SandboxArchiveMapper {
    private final ObjectMapper json;
    private final WorkspaceSettings settings;

    public SandboxArchiveMapper(ObjectMapper json, WorkspaceSettings settings) {
        this.json = json;
        this.settings = settings;
    }

    public void write(OutputStream output, Object manifest, Path directory, Set<String> areas,
                      ToolCallControl control) throws IOException {
        try (var archive = new ZipOutputStream(output)) {
            archive.putNextEntry(new ZipEntry("manifest.json"));
            archive.write(json.writeValueAsBytes(manifest));
            archive.closeEntry();
            for (String area : areas.stream().sorted().toList()) {
                Path base = directory.resolve(area);
                WorkspacePaths.requireInside(directory, base);
                try (var paths = Files.walk(base)) {
                    for (Path path : paths.toList()) {
                        control.requireActive();
                        WorkspacePaths.requireInside(directory, path);
                        boolean folder = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
                        if (!folder && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                            throw WorkspacePaths.invalid();
                        }
                        String name = directory.relativize(path).toString().replace('\\', '/') + (folder ? "/" : "");
                        archive.putNextEntry(new ZipEntry(name));
                        if (!folder) {
                            try (var source = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                                source.transferTo(archive);
                            }
                        }
                        archive.closeEntry();
                    }
                }
            }
        }
    }

    public <T> T read(InputStream input, Path directory, Set<String> areas, Class<T> type,
                      ToolCallControl control) throws IOException {
        Files.createDirectories(directory);
        T manifest = null;
        long total = 0;
        var names = new HashSet<String>();
        try (var archive = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                control.requireActive();
                String name = entry.getName();
                if (!names.add(name) || names.size() > settings.maximumFiles() * 2 + 10 || name.startsWith("/")
                    || name.contains("\\") || name.contains("\0")) {
                    throw WorkspacePaths.invalid();
                }
                if (name.equals("manifest.json")) {
                    byte[] metadata = archive.readNBytes(65537);
                    if (metadata.length > 65536) {
                        throw WorkspacePaths.capacity();
                    }
                    manifest = json.readValue(metadata, type);
                    continue;
                }
                String logical = WorkspacePaths.logical(
                    name.endsWith("/") ? name.substring(0, name.length() - 1) : name);
                String area = logical.split("/", 2)[0];
                if (!areas.contains(area)) {
                    throw WorkspacePaths.invalid();
                }
                Path path = WorkspacePaths.resolve(directory, logical, false);
                if (entry.isDirectory()) {
                    Files.createDirectories(path);
                } else {
                    Files.createDirectories(path.getParent());
                    WorkspacePaths.requireInside(directory, path);
                    long size = 0;
                    try (var output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = archive.read(buffer)) >= 0) {
                            control.requireActive();
                            size += count;
                            total += count;
                            if (size > settings.fileBytes() || total > settings.workspaceBytes()) {
                                throw WorkspacePaths.capacity();
                            }
                            output.write(buffer, 0, count);
                        }
                    }
                }
                archive.closeEntry();
            }
        }
        if (manifest == null) {
            throw new IOException("执行数据缺少描述信息");
        }
        for (String area : areas) {
            Files.createDirectories(directory.resolve(area));
        }
        return manifest;
    }
}
