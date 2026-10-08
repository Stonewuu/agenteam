"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useRef, useState} from "react";
import {ApiError, ApiMutation, errorMessage} from "@/lib/http/api-client";
import {useApiQuery} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {conversationPath} from "@/features/agent/api/conversation-api";
import type {Conversation} from "@/features/agent/types/execution";
import type {WorkspaceProject} from "../types/project";

/** 新对话保留草稿选择，已有对话在服务端保存成功后才切换文件目录。 */
export function useConversationProject({
                                         enterprise,
                                         draftKey,
                                         conversationId,
                                         conversation,
                                         enabled,
                                         busy,
                                         onChanged,
                                         initialProject
                                       }: {
  enterprise: string; draftKey: string; conversationId?: string | null; conversation?: Conversation;
  enabled: boolean; busy: boolean; onChanged?: () => void; initialProject?: WorkspaceProject | null;
}) {
  const uiText = useT();
  const [draft, setDraft] = useState<{ key: string; project: WorkspaceProject | null } | null>(null);
  const [saved, setSaved] = useState<{ conversation: Conversation; project: WorkspaceProject } | null>(null);
  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState<{ key: string; message: string } | null>(null);
  const mutation = useRef(new ApiMutation());
  const pending = useRef(false);
  const visible = !conversation || conversation.mode === "normal";
  const current = saved && conversation && saved.conversation.id === conversation.id && BigInt(saved.conversation.revision) > BigInt(conversation.revision)
    ? saved.conversation : conversation;
  const query = useApiQuery<WorkspaceProject>(conversationId && visible && current?.projectId
    ? organizationPath(enterprise, `/projects/${encodeURIComponent(current.projectId)}`)
    : conversationId && visible && current ? `${conversationPath(enterprise, conversationId)}/project` : null);
  const selected = conversationId ? query.data ?? (saved?.conversation.id === conversationId && saved.project.id === current?.projectId ? saved.project : initialProject ?? null)
    : draft?.key === draftKey ? draft.project : null;
  const disabled = !enabled || busy || saving || Boolean(conversationId && (!current || current.status !== "active" || current.activeRunId || query.loading));

  async function change(project: WorkspaceProject | null) {
    if (disabled || pending.current || project?.id === selected?.id) {
      return false;
    }
    setFailure(null);
    if (!conversationId) {
      setDraft({key: draftKey, project});
      return true;
    }
    if (!project || !current) {
      return false;
    }
    pending.current = true;
    setSaving(true);
    const path = `${conversationPath(enterprise, conversationId)}/project`;
    try {
      const result = await mutation.current.run<Conversation>(path, {
        method: "PUT",
        revision: current.revision,
        body: {projectId: project.id}
      });
      setSaved({conversation: result, project});
      onChanged?.();
      return true;
    } catch (error) {
      console.error("切换对话项目失败", {
        method: "PUT",
        path,
        conversationId,
        requestId: error instanceof ApiError ? error.requestId : undefined
      }, error);
      setFailure({key: draftKey, message: errorMessage(error, uiText("项目未能切换，请重试。"))});
      onChanged?.();
      return false;
    } finally {
      pending.current = false;
      setSaving(false);
    }
  }

  return {
    enterprise, selected, projectId: current?.projectId ?? selected?.id ?? null, visible, disabled, saving,
    loading: Boolean(conversationId && !selected && (!current || query.loading)), existing: Boolean(conversationId),
    error: failure?.key === draftKey ? failure.message : query.error, reload: query.retry,
    change, resetDraft: () => setDraft(null),
  };
}
