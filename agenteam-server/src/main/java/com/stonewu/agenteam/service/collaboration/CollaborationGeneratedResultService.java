package com.stonewu.agenteam.service.collaboration;

import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRunLinkRow;
import com.stonewu.agenteam.model.file.entity.PreparedGeneratedFile;
import com.stonewu.agenteam.service.file.GeneratedFileService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/** 大回答在终态事务外写私有存储，事务内核对执行和正文摘要后才关联成果。 */
@Service
public class CollaborationGeneratedResultService {
    private final RunMapper runs;
    private final ExecutionMessageMapper messages;
    private final CollaborationAccessService access;
    private final CollaborationWorkService works;
    private final GeneratedFileService files;
    private final ResourceJson json;

    public CollaborationGeneratedResultService(RunMapper runs, ExecutionMessageMapper messages, CollaborationAccessService access,
            CollaborationWorkService works, GeneratedFileService files, ResourceJson json) {
        this.runs = runs;
        this.messages = messages;
        this.access = access;
        this.works = works;
        this.files = files;
        this.json = json;
    }

    public Prepared prepare(CollaborationRunLinkRow link) {
        var run = runs.find(link.getEnterpriseId(), link.getRunId(), false).orElseThrow();
        if (!run.status().equals("completed")) {
            return null;
        }
        var task = access.require(link.getEnterpriseId(), link.getTaskId());
        var work = works.require(task, link.getWorkId());
        if (!Set.of("running", "waiting_approval").contains(work.getStatus()) || !task.getInputRevision().equals(link.getInputRevision())) {
            return null;
        }
        String text = messages.find(run.enterpriseId(), run.conversationId(), run.outputMessageId()).orElseThrow().content();
        if (json.tree(text).toString().getBytes(StandardCharsets.UTF_8).length <= 16 * 1024) {
            return null;
        }
        var role = access.participant(task, link.getParticipantId());
        var actor = access.requireArtifactRole(task, role);
        return new Prepared(files.collaborationText(actor, role.getAgentId(), role.getAgentVersionId(), run.id(), text), json.hash(json.tree(text)));
    }

    public record Prepared(PreparedGeneratedFile file, String contentHash) {
    }
}
