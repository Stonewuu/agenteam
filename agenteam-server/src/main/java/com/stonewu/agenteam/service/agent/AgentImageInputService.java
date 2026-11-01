package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.mapper.file.MessageAttachmentMapper;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.execution.ExecutionAccessService;
import com.stonewu.agenteam.service.collaboration.CollaborationMessageSources;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.service.file.ImageFileContent;
import com.stonewu.agenteam.service.http.ApiException;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** 在发送模型请求前读取当前会话图片，不把图片字节重复保存进执行检查点，不新增图片提示词。 */
@Service
public class AgentImageInputService {
    private final MessageAttachmentMapper attachments;
    private final FileAccessService files;
    private final ExecutionAccessService access;
    private final ImageFileContent images;
    private final CollaborationMessageSources sources;

    public AgentImageInputService(MessageAttachmentMapper attachments, FileAccessService files, ExecutionAccessService access,
            ImageFileContent images, CollaborationMessageSources sources) {
        this.attachments = attachments;
        this.files = files;
        this.access = access;
        this.images = images;
        this.sources = sources;
    }

    public List<Msg> prepare(RunRecord run, List<Msg> messages) {
        var ids = messages.stream().filter(message -> message.getRole() == MsgRole.USER).map(Msg::getId)
            .filter(id -> id != null && !id.isBlank()).distinct().toList();
        var selected = attachments.forConversationMessages(run.enterpriseId(), run.userId(), run.conversationId(), ids);
        if (selected.isEmpty()) {
            return messages;
        }
        var origins = sources.forMessages(run.enterpriseId(), run.userId(), run.conversationId(), ids);
        var actor = access.actor(run);
        var loaded = new HashMap<String, ImageBlock>();
        var result = new ArrayList<Msg>();
        long total = 0;
        for (var message : messages) {
            var content = new ArrayList<ContentBlock>(message.getContent());
            if (message.getRole() == MsgRole.USER) {
                var attached = selected.getOrDefault(message.getId(), List.of());
                var source = origins.get(message.getId());
                for (var attachment : source == null ? attached : source.filter(attached)) {
                    if (!ImageFileContent.supports((String) attachment.get("mediaType"))) continue;
                    String id = attachment.get("id").toString();
                    var file = files.ready(actor, id);
                    if (!file.ownerUserId().equals(run.userId())) throw FileAccessService.unavailable();
                    total += file.sizeBytes();
                    if (total > 32L * 1024 * 1024) {
                        throw ApiException.invalidField("attachmentIds", "本次对话的图片合计超过三十二 MiB，请减少图片后重试。");
                    }
                    content.add(loaded.computeIfAbsent(id, ignored -> images.read(file)));
                }
            }
            result.add(content.equals(message.getContent()) ? message : Msg.builderForRole(message.getRole()).id(message.getId())
                .name(message.getName()).content(content).metadata(message.getMetadata()).timestamp(message.getTimestamp())
                .usage(message.getUsage()).build());
        }
        return List.copyOf(result);
    }
}
