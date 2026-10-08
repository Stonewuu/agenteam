package com.stonewu.agenteam.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.agent.AgentHireApplicationSqlMapper;
import com.stonewu.agenteam.mapper.agent.AgentHireSqlMapper;
import com.stonewu.agenteam.mapper.agent.AgentListingMapper;
import com.stonewu.agenteam.mapper.audit.AuditEventSqlMapper;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.data.DataCollectionSqlMapper;
import com.stonewu.agenteam.mapper.data.DataFieldTableMapper;
import com.stonewu.agenteam.mapper.data.DataGenerationTableMapper;
import com.stonewu.agenteam.mapper.data.DataRecordMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseTeamTableMapper;
import com.stonewu.agenteam.mapper.enterprise.InvitationSqlMapper;
import com.stonewu.agenteam.mapper.enterprise.MemberTeamSqlMapper;
import com.stonewu.agenteam.mapper.execution.*;
import com.stonewu.agenteam.mapper.file.*;
import com.stonewu.agenteam.mapper.http.ApiRequestMapper;
import com.stonewu.agenteam.mapper.knowledge.KnowledgeChunkSqlMapper;
import com.stonewu.agenteam.mapper.knowledge.KnowledgeDocumentSqlMapper;
import com.stonewu.agenteam.mapper.memory.MemorySqlMapper;
import com.stonewu.agenteam.mapper.modelprofile.ModelProfileSqlMapper;
import com.stonewu.agenteam.mapper.modelprofile.ModelProviderMapper;
import com.stonewu.agenteam.mapper.notification.NotificationSqlMapper;
import com.stonewu.agenteam.mapper.permission.MemberRoleQueryMapper;
import com.stonewu.agenteam.mapper.permission.ResourceAuthorizationSqlMapper;
import com.stonewu.agenteam.mapper.permission.SysRolePermissionTableMapper;
import com.stonewu.agenteam.mapper.permission.SysRoleTableMapper;
import com.stonewu.agenteam.mapper.plugin.PluginToolSqlMapper;
import com.stonewu.agenteam.mapper.resource.*;
import com.stonewu.agenteam.mapper.schedule.ScheduleOccurrenceSqlMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleSqlMapper;
import com.stonewu.agenteam.mapper.security.CredentialSqlMapper;
import com.stonewu.agenteam.mapper.todo.TodoHistorySqlMapper;
import com.stonewu.agenteam.mapper.todo.TodoTableMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallSqlMapper;
import com.stonewu.agenteam.mapper.usage.QuotaEntryTableMapper;
import com.stonewu.agenteam.mapper.usage.QuotaPolicySqlMapper;
import com.stonewu.agenteam.mapper.usage.QuotaSqlMapper;
import com.stonewu.agenteam.model.agent.entity.AgentHireRequestRow;
import com.stonewu.agenteam.model.agent.entity.AgentHireRow;
import com.stonewu.agenteam.model.agent.entity.AgentListingRow;
import com.stonewu.agenteam.model.audit.entity.AuditEventRow;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.data.entity.DataCollectionRow;
import com.stonewu.agenteam.model.data.entity.DataFieldRow;
import com.stonewu.agenteam.model.data.entity.DataGenerationRow;
import com.stonewu.agenteam.model.data.entity.DataRecordRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseInvitationRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseTeamMemberRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseTeamRow;
import com.stonewu.agenteam.model.execution.entity.*;
import com.stonewu.agenteam.model.file.entity.*;
import com.stonewu.agenteam.model.http.entity.ApiRequestRow;
import com.stonewu.agenteam.model.knowledge.entity.KnowledgeChunkRow;
import com.stonewu.agenteam.model.knowledge.entity.KnowledgeDocumentRow;
import com.stonewu.agenteam.model.memory.entity.AgentMemoryRow;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileRow;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProviderRow;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantRow;
import com.stonewu.agenteam.model.permission.entity.SysRolePermissionRow;
import com.stonewu.agenteam.model.permission.entity.SysRoleRow;
import com.stonewu.agenteam.model.permission.entity.SysUserRoleRow;
import com.stonewu.agenteam.model.plugin.entity.PluginToolRow;
import com.stonewu.agenteam.model.resource.entity.*;
import com.stonewu.agenteam.model.schedule.entity.ScheduledOccurrenceRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduledTaskRow;
import com.stonewu.agenteam.model.security.entity.CredentialRow;
import com.stonewu.agenteam.model.todo.entity.TodoHistoryRow;
import com.stonewu.agenteam.model.todo.entity.TodoItemRow;
import com.stonewu.agenteam.model.tool.entity.ToolCallRow;
import com.stonewu.agenteam.model.usage.entity.QuotaBucketRow;
import com.stonewu.agenteam.model.usage.entity.QuotaEntryRow;
import com.stonewu.agenteam.model.usage.entity.QuotaPolicyRow;

/**
 * 测试计数只选择明确列出的平台表，每次都限制到指定企业。
 */
public final class TestDatabaseCounts {
    private TestDatabaseCounts() {
    }

    public static int enterprise(MybatisTestDatabase database, String table, String enterprise) {
        long count = switch (table) {
            case "agent_conversation" ->
                database.mapper(ConversationSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentConversationRow>().eq(AgentConversationRow::getEnterpriseId, enterprise));
            case "agent_event" ->
                database.mapper(ExecutionEventSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentEventRow>().eq(AgentEventRow::getEnterpriseId, enterprise));
            case "agent_hire" ->
                database.mapper(AgentHireSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentHireRow>().eq(AgentHireRow::getEnterpriseId, enterprise));
            case "agent_hire_request" ->
                database.mapper(AgentHireApplicationSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentHireRequestRow>().eq(AgentHireRequestRow::getEnterpriseId, enterprise));
            case "agent_listing" ->
                database.mapper(AgentListingMapper.class).selectCount(new LambdaQueryWrapper<AgentListingRow>().eq(AgentListingRow::getEnterpriseId, enterprise));
            case "agent_memory" ->
                database.mapper(MemorySqlMapper.class).selectCount(new LambdaQueryWrapper<AgentMemoryRow>().eq(AgentMemoryRow::getEnterpriseId, enterprise));
            case "agent_message" ->
                database.mapper(ExecutionMessageSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentMessageRow>().eq(AgentMessageRow::getEnterpriseId, enterprise));
            case "agent_run" ->
                database.mapper(RunSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentRunRow>().eq(AgentRunRow::getEnterpriseId, enterprise));
            case "api_request" ->
                database.mapper(ApiRequestMapper.class).selectCount(new LambdaQueryWrapper<ApiRequestRow>().eq(ApiRequestRow::getEnterpriseId, enterprise));
            case "audit_event" ->
                database.mapper(AuditEventSqlMapper.class).selectCount(new LambdaQueryWrapper<AuditEventRow>().eq(AuditEventRow::getEnterpriseId, enterprise));
            case "background_job" ->
                database.mapper(RunJobSqlMapper.class).selectCount(new LambdaQueryWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, enterprise));
            case "credential" ->
                database.mapper(CredentialSqlMapper.class).selectCount(new LambdaQueryWrapper<CredentialRow>().eq(CredentialRow::getEnterpriseId, enterprise));
            case "data_collection" ->
                database.mapper(DataCollectionSqlMapper.class).selectCount(new LambdaQueryWrapper<DataCollectionRow>().eq(DataCollectionRow::getEnterpriseId, enterprise));
            case "data_field" ->
                database.mapper(DataFieldTableMapper.class).selectCount(new LambdaQueryWrapper<DataFieldRow>().eq(DataFieldRow::getEnterpriseId, enterprise));
            case "data_generation" ->
                database.mapper(DataGenerationTableMapper.class).selectCount(new LambdaQueryWrapper<DataGenerationRow>().eq(DataGenerationRow::getEnterpriseId, enterprise));
            case "data_record" ->
                database.mapper(DataRecordMapper.class).selectCount(new LambdaQueryWrapper<DataRecordRow>().eq(DataRecordRow::getEnterpriseId, enterprise));
            case "enterprise_invitation" ->
                database.mapper(InvitationSqlMapper.class).selectCount(new LambdaQueryWrapper<EnterpriseInvitationRow>().eq(EnterpriseInvitationRow::getEnterpriseId, enterprise));
            case "enterprise_member" ->
                database.mapper(IdentityQueryMapper.class).selectCount(new LambdaQueryWrapper<EnterpriseMemberRow>().eq(EnterpriseMemberRow::getEnterpriseId, enterprise));
            case "enterprise_team" ->
                database.mapper(EnterpriseTeamTableMapper.class).selectCount(new LambdaQueryWrapper<EnterpriseTeamRow>().eq(EnterpriseTeamRow::getEnterpriseId, enterprise));
            case "enterprise_team_member" ->
                database.mapper(MemberTeamSqlMapper.class).selectCount(new LambdaQueryWrapper<EnterpriseTeamMemberRow>().eq(EnterpriseTeamMemberRow::getEnterpriseId, enterprise));
            case "file_data_profile" ->
                database.mapper(FileDataSqlMapper.class).selectCount(new LambdaQueryWrapper<FileDataProfileRow>().eq(FileDataProfileRow::getEnterpriseId, enterprise));
            case "file_data_row" ->
                database.mapper(FileDataRowTableMapper.class).selectCount(new LambdaQueryWrapper<FileDataRow>().eq(FileDataRow::getEnterpriseId, enterprise));
            case "file_object" ->
                database.mapper(FileSqlMapper.class).selectCount(new LambdaQueryWrapper<FileObjectRow>().eq(FileObjectRow::getEnterpriseId, enterprise));
            case "file_text" ->
                database.mapper(FileTextMapper.class).selectCount(new LambdaQueryWrapper<FileTextRow>().eq(FileTextRow::getEnterpriseId, enterprise));
            case "knowledge_chunk" ->
                database.mapper(KnowledgeChunkSqlMapper.class).selectCount(new LambdaQueryWrapper<KnowledgeChunkRow>().eq(KnowledgeChunkRow::getEnterpriseId, enterprise));
            case "knowledge_document" ->
                database.mapper(KnowledgeDocumentSqlMapper.class).selectCount(new LambdaQueryWrapper<KnowledgeDocumentRow>().eq(KnowledgeDocumentRow::getEnterpriseId, enterprise));
            case "message_attachment" ->
                database.mapper(MessageAttachmentSqlMapper.class).selectCount(new LambdaQueryWrapper<MessageAttachmentRow>().eq(MessageAttachmentRow::getEnterpriseId, enterprise));
            case "message_feedback" ->
                database.mapper(MessageFeedbackTableMapper.class).selectCount(new LambdaQueryWrapper<MessageFeedbackRow>().eq(MessageFeedbackRow::getEnterpriseId, enterprise));
            case "model_profile" ->
                database.mapper(ModelProfileSqlMapper.class).selectCount(new LambdaQueryWrapper<ModelProfileRow>().eq(ModelProfileRow::getEnterpriseId, enterprise));
            case "model_provider" ->
                database.mapper(ModelProviderMapper.class).selectCount(new LambdaQueryWrapper<ModelProviderRow>().eq(ModelProviderRow::getEnterpriseId, enterprise));
            case "notification" ->
                database.mapper(NotificationSqlMapper.class).selectCount(new LambdaQueryWrapper<NotificationRow>().eq(NotificationRow::getEnterpriseId, enterprise));
            case "plugin_tool" ->
                database.mapper(PluginToolSqlMapper.class).selectCount(new LambdaQueryWrapper<PluginToolRow>().eq(PluginToolRow::getEnterpriseId, enterprise));
            case "quota_bucket" ->
                database.mapper(QuotaSqlMapper.class).selectCount(new LambdaQueryWrapper<QuotaBucketRow>().eq(QuotaBucketRow::getEnterpriseId, enterprise));
            case "quota_entry" ->
                database.mapper(QuotaEntryTableMapper.class).selectCount(new LambdaQueryWrapper<QuotaEntryRow>().eq(QuotaEntryRow::getEnterpriseId, enterprise));
            case "quota_policy" ->
                database.mapper(QuotaPolicySqlMapper.class).selectCount(new LambdaQueryWrapper<QuotaPolicyRow>().eq(QuotaPolicyRow::getEnterpriseId, enterprise));
            case "resource" ->
                database.mapper(ResourceSqlMapper.class).selectCount(new LambdaQueryWrapper<ResourceRow>().eq(ResourceRow::getEnterpriseId, enterprise));
            case "resource_dependency" ->
                database.mapper(ResourceDependencyTableMapper.class).selectCount(new LambdaQueryWrapper<ResourceDependencyRow>().eq(ResourceDependencyRow::getEnterpriseId, enterprise));
            case "resource_draft" ->
                database.mapper(ResourceDraftTableMapper.class).selectCount(new LambdaQueryWrapper<ResourceDraftRow>().eq(ResourceDraftRow::getEnterpriseId, enterprise));
            case "resource_grant" ->
                database.mapper(ResourceAuthorizationSqlMapper.class).selectCount(new LambdaQueryWrapper<ResourceGrantRow>().eq(ResourceGrantRow::getEnterpriseId, enterprise));
            case "resource_tag" ->
                database.mapper(ResourceTagTableMapper.class).selectCount(new LambdaQueryWrapper<ResourceTagRow>().eq(ResourceTagRow::getEnterpriseId, enterprise));
            case "resource_version" ->
                database.mapper(ResourceVersionSqlMapper.class).selectCount(new LambdaQueryWrapper<ResourceVersionRow>().eq(ResourceVersionRow::getEnterpriseId, enterprise));
            case "run_approval" ->
                database.mapper(RunApprovalSqlMapper.class).selectCount(new LambdaQueryWrapper<RunApprovalRow>().eq(RunApprovalRow::getEnterpriseId, enterprise));
            case "run_attempt" ->
                database.mapper(RunAttemptSqlMapper.class).selectCount(new LambdaQueryWrapper<RunAttemptRow>().eq(RunAttemptRow::getEnterpriseId, enterprise));
            case "run_checkpoint" ->
                database.mapper(RunCheckpointSqlMapper.class).selectCount(new LambdaQueryWrapper<RunCheckpointRow>().eq(RunCheckpointRow::getEnterpriseId, enterprise));
            case "run_step" ->
                database.mapper(RunStepSqlMapper.class).selectCount(new LambdaQueryWrapper<RunStepRow>().eq(RunStepRow::getEnterpriseId, enterprise));
            case "scheduled_occurrence" ->
                database.mapper(ScheduleOccurrenceSqlMapper.class).selectCount(new LambdaQueryWrapper<ScheduledOccurrenceRow>().eq(ScheduledOccurrenceRow::getEnterpriseId, enterprise));
            case "scheduled_task" ->
                database.mapper(ScheduleSqlMapper.class).selectCount(new LambdaQueryWrapper<ScheduledTaskRow>().eq(ScheduledTaskRow::getEnterpriseId, enterprise));
            case "sys_role" ->
                database.mapper(SysRoleTableMapper.class).selectCount(new LambdaQueryWrapper<SysRoleRow>().eq(SysRoleRow::getEnterpriseId, enterprise));
            case "sys_role_permission" ->
                database.mapper(SysRolePermissionTableMapper.class).selectCount(new LambdaQueryWrapper<SysRolePermissionRow>().eq(SysRolePermissionRow::getEnterpriseId, enterprise));
            case "sys_user_role" ->
                database.mapper(MemberRoleQueryMapper.class).selectCount(new LambdaQueryWrapper<SysUserRoleRow>().eq(SysUserRoleRow::getEnterpriseId, enterprise));
            case "tag" ->
                database.mapper(TagSqlMapper.class).selectCount(new LambdaQueryWrapper<TagRow>().eq(TagRow::getEnterpriseId, enterprise));
            case "todo_history" ->
                database.mapper(TodoHistorySqlMapper.class).selectCount(new LambdaQueryWrapper<TodoHistoryRow>().eq(TodoHistoryRow::getEnterpriseId, enterprise));
            case "todo_item" ->
                database.mapper(TodoTableMapper.class).selectCount(new LambdaQueryWrapper<TodoItemRow>().eq(TodoItemRow::getEnterpriseId, enterprise));
            case "tool_call" ->
                database.mapper(ToolCallSqlMapper.class).selectCount(new LambdaQueryWrapper<ToolCallRow>().eq(ToolCallRow::getEnterpriseId, enterprise));
            default -> throw new IllegalArgumentException("当前测试没有定义此表的企业计数");
        };
        return Math.toIntExact(count);
    }
}
