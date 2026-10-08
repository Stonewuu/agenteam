"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useRef, useState} from "react";
import {ApiMutation, errorMessage} from "@/lib/http/api-client";
import {conversationPath} from "../api/conversation-api";
import type {Conversation, ToolApprovalPolicy} from "../types/execution";
import {useNewConversationApprovalPolicy} from "./use-new-conversation-approval-policy";

/** 新会话随首次发送保存；已有会话先保存成功，再允许继续发送。 */
export function useConversationApprovalPolicy({
                                                userId,
                                                enterprise,
                                                draftKey,
                                                conversationId,
                                                conversation,
                                                enabled,
                                                busy,
                                                onChanged,
                                                initialPolicy
                                              }: {
  userId: string; enterprise: string; draftKey: string; conversationId: string | null; conversation?: Conversation;
  enabled: boolean; busy: boolean; onChanged: () => void; initialPolicy?: ToolApprovalPolicy;
}) {
  const uiText = useT();
  const [drafts, setDrafts] = useState<Record<string, ToolApprovalPolicy>>({});
  const [preferredPolicy, rememberPolicy] = useNewConversationApprovalPolicy(userId, enterprise);
  const [saved, setSaved] = useState<Conversation | null>(null);
  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState<{ key: string; message: string } | null>(null);
  const pending = useRef(false);
  const mutation = useRef(new ApiMutation());
  const current = saved?.id === conversationId && conversation && BigInt(saved.revision) > BigInt(conversation.revision) ? saved : conversation;
  const value = conversationId ? current ? current.approvalPolicy ?? "default" : initialPolicy ?? "default" : drafts[draftKey] ?? preferredPolicy;
  const disabled = !enabled || busy || saving || Boolean(conversationId && (!current || current.mode !== "normal" || current.status !== "active"));

  async function change(next: ToolApprovalPolicy) {
    if (disabled || pending.current || next === value) {
      return;
    }
    setFailure(null);
    if (!conversationId) {
      setDrafts((previous) => ({...previous, [draftKey]: next}));
      rememberPolicy(next);
      return;
    }
    if (!current) {
      return;
    }
    pending.current = true;
    setSaving(true);
    try {
      const result = await mutation.current.run<Conversation>(conversationPath(enterprise, conversationId), {
        method: "PATCH", revision: current.revision, body: {approvalPolicy: next},
      });
      setSaved(result);
      onChanged();
    } catch (failed) {
      console.error("保存对话权限模式失败", {
        method: "PATCH",
        path: conversationPath(enterprise, conversationId),
        conversationId
      }, failed);
      setFailure({key: draftKey, message: errorMessage(failed, uiText("工具审批策略未能保存，请重试。"))});
      onChanged();
    } finally {
      pending.current = false;
      setSaving(false);
    }
  }

  return {
    value,
    disabled,
    saving,
    change,
    resetDraft: () => setDrafts({}),
    error: failure?.key === draftKey ? failure.message : ""
  };
}
