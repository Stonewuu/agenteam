package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.mapper.file.ConversationWorkspaceFileMapper;
import com.stonewu.agenteam.mapper.file.FilePreviewMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.file.response.ConversationFileView;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.execution.ConversationQueryService;
import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.project.ProjectWorkspaceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 读取项目时持有用户锁，临时预览继续读取已发布的版本。
 */
@Service
public class WorkspaceBrowserService {
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceBrowserService.class);
    private final WorkspaceStore store;
    private final WorkspaceAccessService access;
    private final ToolCallMapper calls;
    private final ConversationQueryService conversations;
    private final ListPagination pagination;
    private final ProjectWorkspaceStore projects;
    private final ConversationWorkspaceFileMapper contextFiles;

    public WorkspaceBrowserService(WorkspaceStore store, WorkspaceAccessService access, ToolCallMapper calls,
                                   ConversationQueryService conversations, ListPagination pagination,
                                   ProjectWorkspaceStore projects,
                                   ConversationWorkspaceFileMapper contextFiles) {
        this.store = store;
        this.access = access;
        this.calls = calls;
        this.conversations = conversations;
        this.pagination = pagination;
        this.projects = projects;
        this.contextFiles = contextFiles;
    }

    public PageResponse<ConversationFileView> list(AuthContext actor, String conversation, String path, String cursor,
                                                   Integer limit) {
        String directory = WorkspacePaths.projectLogical(path);
        int size = pagination.limit(limit);
        return read(actor, conversation, snapshot -> {
            String version = snapshot.manifest() == null ? "" : Objects.toString(snapshot.manifest().version(), "");
            var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "workspace-files",
                conversation + "|" + directory + "|" + version, "path_asc");
            var after = pagination.read(cursor, binding);
            List<ConversationFileView> files = snapshot.files() == null ? List.of() : snapshot.files()
                .files(directory, false).stream()
                .map(file -> FilePreviewMapper.view(FilePreviewMapper.workspaceId(file.path()), name(file.path()),
                    file.path(), file.isDirectory(), "workspace", null,
                    file.size(), file.modifiedAt(),
                    Utf8Text.revision(version, file.path() + "|" + file.size() + "|" + file.modifiedAt())))
                .sorted(Comparator.comparing(WorkspaceBrowserService::order))
                .filter(file -> after == null || order(file).compareTo(after.sortValue()) > 0).limit(size + 1L)
                .toList();
            return pagination.page(files, size, binding,
                file -> new PagePosition(Instant.EPOCH, Utf8Text.revision(file.path(), ""), order(file)));
        });
    }

    /**
     * 只列出本次会话成功创建、编辑且仍存在的文件，读取最新内容时沿用工作区权限检查。
     */
    public List<ConversationFileView> contextFiles(AuthContext actor, String conversation) {
        var paths = contextFiles.modifiedPaths(actor.enterpriseId(), actor.userId(), conversation).stream()
            .map(WorkspacePaths::projectLogical).distinct().toList();
        if (paths.isEmpty()) {
            return List.of();
        }
        return read(actor, conversation, snapshot -> {
            if (snapshot.files() == null) {
                return List.of();
            }
            String version = snapshot.manifest() == null ? "" : Objects.toString(snapshot.manifest().version(), "");
            var result = new ArrayList<ConversationFileView>();
            for (String path : paths) {
                var file = snapshot.files().resolve(path, false);
                try {
                    var metadata = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!metadata.isRegularFile()) {
                        continue;
                    }
                    String modified = metadata.lastModifiedTime().toInstant().toString();
                    result.add(FilePreviewMapper.view(FilePreviewMapper.workspaceId(path), name(path), path, false,
                        "workspace", null,
                        metadata.size(), modified,
                        Utf8Text.revision(version, path + "|" + metadata.size() + "|" + modified)));
                } catch (NoSuchFileException missing) {
                    LOG.debug("会话关联的工作文件已不存在，对话编号 {}", conversation, missing);
                } catch (IOException failure) {
                    throw WorkspacePaths.io(failure);
                }
            }
            return List.copyOf(result);
        });
    }

    public <T> T read(AuthContext actor, String conversation, Function<WorkspaceStore.Snapshot, T> action) {
        var row = conversations.readable(actor, conversation);
        if (row.mode().equals("normal")) {
            return projects.read(actor.enterpriseId(), actor.userId(), conversation, snapshot -> {
                access.requireSources(actor, snapshot.manifest().sourceResultIds(), snapshot.manifest().inputFileIds());
                return action.apply(snapshot);
            });
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            var snapshot = store.snapshot(actor.enterpriseId(), actor.userId(), conversation);
            if (snapshot.files() == null && !snapshot.cleared() && calls.hasConversationWorkspace(actor.enterpriseId(),
                actor.userId(), conversation)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WORKSPACE_STORAGE_UNAVAILABLE",
                    "暂时无法读取此对话已保存的工作文件。");
            }
            if (snapshot.manifest() != null) {
                access.requireSources(actor, snapshot.manifest().sourceResultIds(), snapshot.manifest().inputFileIds());
            }
            try {
                return action.apply(snapshot);
            } catch (ApiException failure) {
                if (attempt != 0 || !List.of("WORKSPACE_IO_FAILED", "WORKSPACE_FILE_MISSING")
                    .contains(failure.code())) {
                    throw failure;
                }
                var latest = store.snapshot(actor.enterpriseId(), actor.userId(), conversation);
                if (Objects.equals(snapshot.manifest(), latest.manifest())) {
                    throw failure;
                }
                LOG.debug("工作文件版本在读取期间更新，将重新读取，对话编号 {}", conversation, failure);
            }
        }
        throw new IllegalStateException("工作文件读取未完成");
    }

    private static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String order(ConversationFileView file) {
        return (file.directory() ? "0:" : "1:") + file.path();
    }
}
