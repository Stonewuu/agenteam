package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.mapper.file.MessageAttachmentMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.service.file.FileContentStorage;
import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.tool.ToolResultDocumentService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Consumer;

/**
 * 只有当前对话明确提供的附件和授权结果会进入容器的只读目录。
 */
@Service
public class WorkspaceInputs {
    private final MessageAttachmentMapper attachments;
    private final FileAccessService files;
    private final FileContentStorage storage;
    private final ToolResultDocumentService documents;
    private final WorkspaceSettings settings;

    public WorkspaceInputs(MessageAttachmentMapper attachments, FileAccessService files, FileContentStorage storage,
                           ToolResultDocumentService documents, WorkspaceSettings settings) {
        this.attachments = attachments;
        this.files = files;
        this.storage = storage;
        this.documents = documents;
        this.settings = settings;
    }

    public Set<String> supplied(RunRecord run, String session, Set<String> shared) {
        return new LinkedHashSet<>(attachments.forConversation(run.enterpriseId(), run.userId(), run.conversationId(),
                settings.maximumFiles() + 1).stream()
            .filter(id -> session.equals(run.conversationId()) || shared.contains(id)).toList());
    }

    public void copyFiles(AuthContext actor, Set<String> ids, WorkspaceFilesystem target, ToolCallControl control) {
        if (ids.size() > settings.maximumFiles()) {
            throw WorkspacePaths.capacity();
        }
        for (String id : ids) {
            control.requireActive();
            var file = files.ready(actor, id);
            if (file.sizeBytes() > settings.fileBytes()) {
                throw WorkspacePaths.capacity();
            }
            String path = inputPath(file.id(), file.originalName());
            var destination = target.resolve(path, false);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Path temporary = destination.resolveSibling(".input-" + UUID.randomUUID());
            try {
                Files.createDirectories(destination.getParent());
                try (var input = control.track(storage.open(file)); var output = Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    var digest = MessageDigest.getInstance("SHA-256");
                    long total = 0;
                    byte[] bytes = new byte[8192];
                    int read;
                    while ((read = input.read(bytes)) >= 0) {
                        control.requireActive();
                        total += read;
                        if (total > settings.fileBytes()) {
                            throw WorkspacePaths.capacity();
                        }
                        digest.update(bytes, 0, read);
                        output.write(bytes, 0, read);
                    }
                    if (total != file.sizeBytes() || !HexFormat.of().formatHex(digest.digest()).equals(file.sha256())) {
                        throw new IOException("输入文件与保存记录不一致");
                    }
                }
                target.validate();
                control.requireActive();
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException cause) {
                throw WorkspacePaths.io(cause);
            } catch (NoSuchAlgorithmException cause) {
                throw new IllegalStateException("运行环境缺少文件摘要算法", cause);
            } finally {
                deleteTemporary(temporary);
            }
        }
    }

    public Set<String> copyResults(AuthContext actor, RunRecord run, String session, Set<String> shared,
                                   WorkspaceFilesystem target, ToolCallControl control,
                                   Set<String> paths, Consumer<Set<String>> recordSources) {
        Set<String> origins = new LinkedHashSet<>();
        if (paths.size() > 20) {
            throw WorkspacePaths.capacity();
        }
        var prepared = new LinkedHashMap<String, ToolResultDocumentService.Document>();
        for (String path : paths) {
            control.requireActive();
            var document = documents.open(actor, run.conversationId(), path, session, shared, control);
            if (document.text().sizeBytes() > settings.fileBytes()) {
                throw WorkspacePaths.capacity();
            }
            origins.add(document.call().id());
            prepared.put(path, document);
        }
        recordSources.accept(Set.copyOf(origins));
        for (var entry : prepared.entrySet()) {
            control.requireActive();
            String path = entry.getKey();
            var document = entry.getValue();
            var destination = target.resolve(path, false);
            Path temporary = destination.resolveSibling(".result-" + UUID.randomUUID());
            try {
                if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination.getParent());
                    try (var output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                        document.text().transferTo(output);
                    }
                    target.validate();
                    control.requireActive();
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (IOException cause) {
                throw WorkspacePaths.io(cause);
            } finally {
                deleteTemporary(temporary);
            }
        }
        return origins;
    }

    private void deleteTemporary(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException failure) {
            throw WorkspacePaths.io(failure);
        }
    }

    public static String inputPath(String id, String originalName) {
        String safe = originalName.replaceAll("[\\\\/<>:\"|?*\\p{Cntrl}]", "_").replaceAll("[ .]+$", "");
        if (safe.isBlank()) {
            safe = "file";
        }
        if (Utf8Text.size(safe) > 160) {
            int dot = safe.lastIndexOf('.');
            String suffix = dot >= 0 ? Utf8Text.prefix(safe.substring(dot), 24) : "";
            safe = Utf8Text.prefix(safe, 130) + suffix;
        }
        if (safe.split("\\.", 2)[0].toUpperCase(Locale.ROOT).matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
            safe = "file-" + safe;
        }
        return "inputs/" + id + "/" + safe;
    }
}
