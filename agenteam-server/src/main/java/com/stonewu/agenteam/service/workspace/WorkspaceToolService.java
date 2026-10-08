package com.stonewu.agenteam.service.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.file.entity.PreparedGeneratedFile;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import com.stonewu.agenteam.service.file.FileContentStorage;
import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ImageBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 平台文件与命令操作共用原有工具调用记录、确认策略和保存事务。
 */
@Service
public class WorkspaceToolService {
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceToolService.class);

    public record Outcome(JsonNode value, Set<String> sourceResultIds, Set<String> inputFileIds,
                          PreparedGeneratedFile file, String workspaceVersion, ImageBlock image) {
    }

    private static final Pattern RESULT_PATH = Pattern.compile(
        "tool-results/[A-Za-z0-9_-]{1,100}/content\\.(?:txt|json)");
    private final WorkspaceReadToolService results;
    private final WorkspaceAccessService access;
    private final ConversationWorkspaceStore store;
    private final WorkspaceInputs inputs;
    private final WorkspaceFileReader reader;
    private final SandboxExecutor runner;
    private final WorkspaceSettings settings;
    private final FileContentStorage storage;
    private final ResourceJson json;
    private final WorkspaceImageReader images;

    public WorkspaceToolService(WorkspaceReadToolService results, WorkspaceAccessService access,
                                ConversationWorkspaceStore store, WorkspaceInputs inputs,
                                WorkspaceFileReader reader, SandboxExecutor runner, WorkspaceSettings settings,
                                FileContentStorage storage, ResourceJson json,
                                WorkspaceImageReader images) {
        this.results = results;
        this.access = access;
        this.store = store;
        this.inputs = inputs;
        this.reader = reader;
        this.runner = runner;
        this.settings = settings;
        this.storage = storage;
        this.json = json;
        this.images = images;
    }

    public Outcome invoke(RunRecord run, JobLease lease, ExecutionToolBinding binding, ToolCallRecord call,
                          String session, JsonNode arguments,
                          Duration timeout, ToolCallControl control, int maximum, Set<String> shared) {
        String name = binding.definition().name();
        String path = WorkspacePaths.projectLogical(arguments.path("path").asText("tool-results/"));
        if (arguments.has("path")) {
            arguments = arguments.deepCopy();
            ((ObjectNode) arguments).put("path", path);
        }
        if (WorkspaceToolDefinitions.READ_TOOLS.contains(name) && (path.equals("tool-results") || path.startsWith(
            "tool-results/"))) {
            return new Outcome(results.invoke(run, session, name, arguments, timeout, control, maximum, shared),
                Set.of(), Set.of(), null, null, null);
        }
        var actor = access.actor(run, lease);
        try (var scope = store.open(run, session, control, timeout, () -> access.requireLease(run, lease))) {
            access.requireStoredWorkspace(run, session, scope);
            var origins = access.currentOrigins(run, scope.manifest());
            var fileIds = new LinkedHashSet<>(origins.inputs());
            fileIds.addAll(inputs.supplied(run, session, shared));
            access.requireSources(actor, origins.results(), fileIds);
            var sourceIds = new LinkedHashSet<>(origins.results());
            WorkspaceFilesystem files = scope.current();
            boolean copied = !scope.manifest().inputFileIds().containsAll(fileIds);
            if (copied || !binding.readOnly()) {
                files = scope.prepare();
                if (scope.persistent() && copied) {
                    scope.commit(sourceIds, fileIds);
                }
                inputs.copyFiles(actor, fileIds, files, control);
            }
            JsonNode response;
            PreparedGeneratedFile exported = null;
            ImageBlock imageContent = null;
            if (binding.readOnly()) {
                control.beforeSend();
                if (copied) {
                    scope.commit(sourceIds, fileIds);
                    files = scope.current();
                }
                if (name.equals("read_file") && scope.cleared()) {
                    throw new ApiException(HttpStatus.GONE, "WORKSPACE_FILES_EXPIRED",
                        "此对话的工作文件因超过保留时间已清理，请重新提供需要处理的文件。");
                }
                if (name.equals("view_image")) {
                    var image = images.read(files, path);
                    imageContent = image.block();
                    response = object().put("path", path).put("workspaceImage", true)
                        .put("workspaceVersion", scope.manifest().version())
                        .put("imageRevision", Utf8Text.revision(path, image.data()))
                        .put("mediaType", image.mediaType()).put("width", image.width()).put("height", image.height());
                } else {
                    response = reader.read(files, scope.manifest().version(), name, arguments, maximum);
                }
            } else if (name.equals("write_file")) {
                if (scope.persistent()) {
                    files.beforeWrite(control::beforeSend);
                }
                files.write(RuntimeContext.empty(), path, arguments.path("content").asText());
                response = fileResult(files, path);
                if (!scope.persistent()) {
                    control.beforeSend();
                }
                scope.commit(sourceIds, fileIds);
            } else if (name.equals("edit_file")) {
                if (scope.persistent()) {
                    files.beforeWrite(control::beforeSend);
                }
                var edited = files.edit(RuntimeContext.empty(), path, arguments.path("old_text").asText(),
                    arguments.path("new_text").asText(), arguments.path("replace_all").asBoolean());
                if (!edited.isSuccess()) {
                    response = object().put("isError", true).put("content", edited.error());
                } else {
                    response = fileResult(files, path).put("replacements", edited.occurrences());
                    if (!scope.persistent()) {
                        control.beforeSend();
                    }
                    scope.commit(sourceIds, fileIds);
                }
            } else if (name.equals("execute")) {
                long seconds = arguments.path("timeout_seconds").asLong(settings.timeoutSeconds());
                if (seconds < 1 || seconds > settings.timeoutSeconds()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "SANDBOX_TIMEOUT_INVALID",
                        "命令时间上限超过当前允许范围。");
                }
                var requested = new LinkedHashSet<String>();
                arguments.path("result_paths").forEach(value -> requested.add(WorkspacePaths.logical(value.asText())));
                var references = RESULT_PATH.matcher(arguments.path("command").asText());
                while (references.find()) {
                    requested.add(references.group());
                }
                sourceIds.addAll(inputs.copyResults(actor, run, session, shared, files, control, requested, ids -> {
                    sourceIds.addAll(ids);
                    if (scope.persistent()) {
                        // 复制和命令都可能中断，来源记录必须先于持久文件保存。
                        scope.commit(sourceIds, fileIds);
                    }
                }));
                var extracted = scope.directory().resolve("command-output-" + UUID.randomUUID());
                try {
                    Duration remaining = scope.remaining();
                    Duration executionTime = remaining.minusSeconds(Math.min(20, remaining.toSeconds() / 4));
                    Duration allowed = executionTime.compareTo(
                        Duration.ofSeconds(seconds)) < 0 ? executionTime : Duration.ofSeconds(seconds);
                    String working = arguments.path("working_directory").asText(scope.persistent() ? "." : "work");
                    var command = scope.persistent() ? scope.execute(call.id(), arguments.path("command").asText(),
                        working, allowed, control)
                        : runner.execute(files.root(), extracted, scope.directory(), call.id(),
                        arguments.path("command").asText(), working, allowed, control);
                    if (!scope.persistent()) {
                        replaceCommandFiles(files, extracted);
                    }
                    response = commandResult(files, command, maximum);
                    access.requireSources(actor, sourceIds, fileIds);
                    // 无网络沙盒只修改临时文件，在替换已保存版本前才提交修改。
                    if (!scope.persistent() && settings.network().equals("none")) {
                        control.beforeSend();
                    }
                    scope.commit(sourceIds, fileIds);
                } catch (ApiException failure) {
                    if (scope.persistent() || !settings.network().equals("none") || !failure.code()
                        .equals("WORKSPACE_CAPACITY_EXCEEDED")) {
                        throw failure;
                    }
                    LOG.warn("容器生成的文件超过允许范围，未替换已保存文件，执行编号 {}，调用编号 {}", run.id(), call.id(),
                        failure);
                    response = object().put("isError", true).put("capacityExceeded", true)
                        .put("content", "生成文件超过允许的大小或数量，本次生成内容未保存，原有工作文件仍可使用。");
                } finally {
                    try {
                        WorkspaceStore.deleteTree(scope.directory(), extracted);
                    } catch (IOException failure) {
                        LOG.warn("沙盒输出临时目录清理失败，执行编号 {}，调用编号 {}", run.id(), call.id(), failure);
                    }
                }
            } else if (name.equals("export_file")) {
                String logical = WorkspacePaths.projectLogical(path);
                if (scope.persistent()) {
                    files.resolve(logical, true);
                } else if (!logical.startsWith("outputs/")) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "WORKSPACE_EXPORT_PATH_INVALID",
                        "请先把交付文件放入 outputs 目录，再导出为附件。");
                }
                Path source = files.resolve(logical, false);
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "WORKSPACE_FILE_MISSING", "准备交付的文件不存在。");
                }
                control.beforeSend();
                try (var input = control.track(Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS))) {
                    var content = storage.write(actor.enterpriseId(), input, settings.fileBytes());
                    String fileName = source.getFileName().toString();
                    String mediaType = MediaTypeFactory.getMediaType(fileName).map(Object::toString).orElse(null);
                    exported = new PreparedGeneratedFile(call.id(), actor.enterpriseId(), actor.userId(),
                        binding.resourceId(), binding.resourceVersionId(), run.id(),
                        "artifact", fileName, mediaType == null ? "application/octet-stream" : mediaType, content.key(),
                        content.size(), content.sha256());
                    response = object().put("workspaceExport", true).put("fileId", call.id()).put("name", fileName)
                        .put("sizeBytes", content.size()).put("path", logical);
                }
                if (copied) {
                    scope.commit(sourceIds, fileIds);
                }
            } else {
                throw new ApiException(HttpStatus.NOT_FOUND, "WORKSPACE_TOOL_UNAVAILABLE", "当前文件操作不可用。");
            }
            control.requireActive();
            if (!binding.readOnly() && response instanceof ObjectNode object) {
                for (String optional : List.of("stdout", "stderr", "path", "name", "revision", "totalLines")) {
                    if (Utf8Text.size(object.toString()) <= maximum) {
                        break;
                    }
                    object.remove(optional);
                }
            }
            if (Utf8Text.size(response.toString()) > maximum) {
                throw new IllegalStateException("工作空间工具返回内容超过分配的上限");
            }
            return new Outcome(response, Set.copyOf(sourceIds), Set.copyOf(fileIds), exported,
                scope.manifest().version(), imageContent);
        } catch (IOException cause) {
            throw WorkspacePaths.io(cause);
        }
    }

    private ObjectNode fileResult(WorkspaceFilesystem files, String path) {
        var document = files.document(path);
        return object().put("path", document.path()).put("revision", document.revision())
            .put("sizeBytes", document.sizeBytes()).put("totalLines", document.totalLines());
    }

    public ImageBlock image(RunRecord run, JobLease lease, String session, JsonNode result, Duration timeout) {
        var actor = access.actor(run, lease);
        try (var control = new ToolCallControl(() -> access.requireLease(run, lease));
             var scope = store.open(run, session, control, timeout, () -> access.requireLease(run, lease))) {
            access.requireStoredWorkspace(run, session, scope);
            var origins = access.currentOrigins(run, scope.manifest());
            access.requireSources(actor, origins.results(), origins.inputs());
            if (!result.path("workspaceVersion").asText().equals(scope.manifest().version())) {
                throw new ApiException(HttpStatus.CONFLICT, "WORKSPACE_IMAGE_CHANGED",
                    "图片所属工作文件已经变化，请重新读取图片。");
            }
            String path = WorkspacePaths.projectLogical(result.path("path").asText());
            var image = images.read(scope.current(), path);
            if (result.hasNonNull("imageRevision") && !result.path("imageRevision").asText()
                .equals(Utf8Text.revision(path, image.data()))) {
                throw new ApiException(HttpStatus.CONFLICT, "WORKSPACE_IMAGE_CHANGED",
                    "图片内容已经变化，请重新读取图片。");
            }
            return image.block();
        } catch (IOException failure) {
            throw WorkspacePaths.io(failure);
        }
    }

    private ObjectNode commandResult(WorkspaceFilesystem files, SandboxExecutor.Result command, int maximum) {
        var response = object().put("exitCode", command.exitCode()).put("timedOut", command.timedOut())
            .put("isError", command.exitCode() != 0 || command.capacityExceeded())
            .put("capacityExceeded", command.capacityExceeded()).put("stdoutPath", command.stdoutPath())
            .put("stderrPath", command.stderrPath());
        if (command.capacityExceeded()) {
            response.put("content", "工作空间容量或文件数量已用完，命令未完成，请减少生成文件。");
        } else if (command.timedOut()) {
            response.put("content",
                "命令超过允许时间，已停止并保留实际输出与工作文件。请先检查已有文件，再缩小命令范围或改用其他方法继续。");
        }
        int bytes = Math.max(4, Math.min(1024, (maximum - 400) / 12));
        for (var entry : Map.of("stdout", command.stdoutPath(), "stderr", command.stderrPath()).entrySet()) {
            if (!files.exists(RuntimeContext.empty(), entry.getValue())) {
                response.remove(entry.getKey() + "Path");
                continue;
            }
            Path path = files.resolve(entry.getValue(), false);
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] content = input.readNBytes(bytes);
                String preview = new String(content, 0, Utf8Text.boundary(content, content.length),
                    StandardCharsets.UTF_8);
                response.put(entry.getKey(), Utf8Text.prefix(preview, bytes))
                    .put(entry.getKey() + "Bytes", Files.size(path));
            } catch (IOException failure) {
                throw WorkspacePaths.io(failure);
            }
        }
        return response;
    }

    private void replaceCommandFiles(WorkspaceFilesystem target, Path extracted) throws IOException {
        for (String area : List.of("work", "outputs")) {
            WorkspaceStore.deleteTree(target.root(), target.root().resolve(area));
            Files.move(extracted.resolve(area), target.root().resolve(area));
        }
        WorkspaceStore.deleteTree(target.root(), target.root().resolve("tool-results"));
        Files.createDirectory(target.root().resolve("tool-results"));
        target.validate();
    }

    private ObjectNode object() {
        return (ObjectNode) json.tree(Map.of());
    }
}
