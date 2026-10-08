package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.mapper.file.ConversationFileMapper;
import com.stonewu.agenteam.mapper.file.FilePreviewMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.file.entity.FileObjectRow;
import com.stonewu.agenteam.model.file.entity.OpenedPreviewFile;
import com.stonewu.agenteam.model.file.response.ConversationFileView;
import com.stonewu.agenteam.model.file.response.FileTextSearch;
import com.stonewu.agenteam.model.file.response.FileTextSlice;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.execution.ConversationQueryService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.workspace.WorkspaceBrowserService;
import com.stonewu.agenteam.service.workspace.WorkspacePaths;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 会话文件列表和预览共享关联与权限校验，不因为知道文件编号而授予访问。
 */
@Service
public class ConversationFileService {
    private record ContextFile(ConversationFileView view, PagePosition position) {
    }

    private static final int MAXIMUM_TEXT_BYTES = 20 * 1024 * 1024;
    private final ConversationQueryService conversations;
    private final ConversationFileMapper files;
    private final FileAccessService access;
    private final FileContentStorage storage;
    private final WorkspaceBrowserService workspace;
    private final ListPagination pagination;
    private final Clock clock;
    private final AuditEventService audit;
    private final WorkspaceFileSnapshot snapshots;
    private final TextDocumentCache textCache = new TextDocumentCache(64L * 1024 * 1024);

    public ConversationFileService(ConversationQueryService conversations, ConversationFileMapper files,
                                   FileAccessService access,
                                   FileContentStorage storage, WorkspaceBrowserService workspace,
                                   ListPagination pagination, Clock clock, AuditEventService audit,
                                   WorkspaceFileSnapshot snapshots) {
        this.conversations = conversations;
        this.files = files;
        this.access = access;
        this.storage = storage;
        this.workspace = workspace;
        this.pagination = pagination;
        this.clock = clock;
        this.audit = audit;
        this.snapshots = snapshots;
    }

    public PageResponse<ConversationFileView> list(AuthContext actor, String conversation, String scope, String path,
                                                   String cursor, Integer limit) {
        if (scope.equals("workspace")) {
            return workspace.list(actor, conversation, path, cursor, limit);
        }
        if (!scope.equals("context")) {
            throw ApiException.invalidField("scope", "请选择文件或上下文。");
        }
        conversations.readable(actor, conversation);
        int size = pagination.limit(limit);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "conversation-files",
            conversation, "modified_desc");
        var after = pagination.read(cursor, binding);
        var attachments = files.list(actor.enterpriseId(), actor.userId(), conversation, after, size + 1,
            clock.instant());
        var order = Comparator.comparing((ContextFile file) -> file.position().time())
            .thenComparing(file -> file.position().id()).reversed();
        var rows = Stream.concat(attachments.stream().map(this::view),
                workspace.contextFiles(actor, conversation).stream())
            .map(file -> new ContextFile(file, new PagePosition(Instant.parse(file.modifiedAt()),
                file.id().startsWith("w.") ? "w." + Utf8Text.revision(file.path(), "") : file.id())))
            .filter(file -> after == null || file.position().time().isBefore(after.time())
                || file.position().time().equals(after.time()) && file.position().id().compareTo(after.id()) < 0)
            .sorted(order).limit(size + 1L).toList();
        var page = pagination.page(rows, size, binding, ContextFile::position);
        return new PageResponse<>(page.items().stream().map(ContextFile::view).toList(), page.nextCursor(),
            page.hasMore());
    }

    public OpenedPreviewFile open(AuthContext actor, String conversation, String id) {
        conversations.readable(actor, conversation);
        if (id.startsWith("w.")) {
            String path = FilePreviewMapper.workspacePath(id);
            return workspace.read(actor, conversation, snapshot -> {
                if (snapshot.files() == null) {
                    throw FileAccessService.unavailable();
                }
                var file = snapshot.files().resolve(path, false);
                try {
                    return snapshots.open(file, id, path, snapshot.files()::requireActive, actor.userId());
                } catch (NoSuchFileException failure) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "WORKSPACE_FILE_MISSING",
                        "文件已不存在，请刷新列表后重试。", failure);
                } catch (IOException failure) {
                    throw WorkspacePaths.io(failure);
                }
            });
        }
        if (!id.startsWith("f.") || id.length() > 102 || !files.contains(actor.enterpriseId(), actor.userId(),
            conversation, id.substring(2), clock.instant())) {
            throw FileAccessService.unavailable();
        }
        var file = access.ready(actor, id.substring(2));
        var metadata = FilePreviewMapper.view(id, file.originalName(), file.originalName(), false,
            file.purpose().equals("attachment") ? "uploaded" : "generated",
            file.mediaType(), file.sizeBytes(), file.createdAt().toString(), file.sha256());
        return new OpenedPreviewFile(metadata, storage.open(file));
    }

    public FileTextSlice text(AuthContext actor, String conversation, String id, int offset, int limit,
                              boolean backwards, String revision) {
        if (offset < 0 || limit < 4 || limit > 256 * 1024) {
            throw ApiException.invalidField("offset", "读取位置或范围不正确。");
        }
        return document(actor, conversation, id, revision).window(offset, limit, backwards);
    }

    public ConversationFileView metadata(AuthContext actor, String conversation, String id) {
        try (var opened = open(actor, conversation, id)) {
            return opened.file();
        } catch (IOException failure) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "FILE_PREVIEW_UNAVAILABLE",
                "文件暂时无法读取，请稍后重试。", failure);
        }
    }

    public FileTextSearch search(AuthContext actor, String conversation, String id, String query, int startLine,
                                 String revision) {
        return document(actor, conversation, id, revision).search(query, false, true, startLine, 1, 1, 1, 8192,
            () -> false);
    }

    public void downloaded(AuthContext actor, String conversation) {
        audit.record(actor.enterpriseId(), actor.user(), "file.download", "conversation", conversation, "下载会话文件",
            Map.of());
    }

    private TextFileDocument document(AuthContext actor, String conversation, String id, String revision) {
        try (var opened = open(actor, conversation, id)) {
            var file = opened.file();
            if (!Set.of("text", "markdown").contains(file.previewKind())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_NOT_TEXT",
                    "此文件不能按文本预览，请使用对应的预览方式或下载查看。");
            }
            if (file.sizeBytes() > MAXIMUM_TEXT_BYTES) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_PREVIEW_TOO_LARGE",
                    "此文本文件较大，请下载后查看。");
            }
            String key = actor.enterpriseId() + ":" + actor.userId() + ":" + conversation + ":" + file.id() + ":" + file.revision();
            var document = textCache.get(key, () -> {
                try {
                    byte[] bytes = opened.input().readNBytes(MAXIMUM_TEXT_BYTES + 1);
                    if (bytes.length > MAXIMUM_TEXT_BYTES) {
                        throw WorkspacePaths.capacity();
                    }
                    String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                    if (text.indexOf('\0') >= 0) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_NOT_TEXT",
                            "此文件包含非文本内容，请下载后查看。");
                    }
                    return new TextFileDocument(file.path(), Utf8Text.revision(file.path(), text), text);
                } catch (IOException failure) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_TEXT_UNREADABLE",
                        "此文件无法按 UTF-8 文本读取，请下载后查看。", failure);
                }
            });
            if (revision != null && !revision.equals(document.revision())) {
                throw new ApiException(HttpStatus.CONFLICT, "TOOL_CONTENT_CHANGED", "文件已经更新，请重新打开预览。");
            }
            return document;
        } catch (IOException failure) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "FILE_PREVIEW_UNAVAILABLE",
                "文件暂时无法读取，请稍后重试。", failure);
        }
    }

    private ConversationFileView view(FileObjectRow file) {
        return FilePreviewMapper.view("f." + file.getId(), file.getOriginalName(), file.getOriginalName(), false,
            file.getPurpose().equals("attachment") ? "uploaded" : "generated", file.getMediaType(), file.getSizeBytes(),
            file.getCreatedAt().toString(), file.getSha256());
    }
}
