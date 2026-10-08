"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useRef, useState} from "react";
import {ApiError, ApiMutation, errorMessage} from "@/lib/http/api-client";
import {useApiQuery} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {
  type ModelSelection,
  type ReasoningEffort,
  sameModelSelection,
  selectModel
} from "@/features/modelprofile/lib/reasoning-effort";
import {conversationPath} from "../api/conversation-api";
import type {Conversation, ConversationModelOptions} from "../types/execution";

/** 新对话随首次发送保存；已有对话保存成功后更新显示，失败时保留原选择。 */
export function useConversationModel({
                                       enterprise,
                                       agentId,
                                       draftKey,
                                       conversation,
                                       enabled,
                                       busy,
                                       onChanged,
                                       initialOptions
                                     }: {
  enterprise: string; agentId?: string | null; draftKey: string; conversation?: Conversation;
  enabled: boolean; busy: boolean; onChanged?: () => void;
  initialOptions?: ConversationModelOptions;
}) {
  const uiText = useT();
  const path = enabled && agentId && (!conversation || conversation.mode === "normal")
    ? organizationPath(enterprise, `/agents/${encodeURIComponent(agentId)}/model-options${conversation ? `?conversationId=${encodeURIComponent(conversation.id)}` : ""}`) : null;
  const query = useApiQuery<ConversationModelOptions>(path, Number(conversation?.revision ?? 0));
  const displayed = query.data ?? initialOptions;
  const [drafts, setDrafts] = useState<Record<string, ModelSelection>>({});
  const [saved, setSaved] = useState<Conversation | null>(null);
  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState<{ key: string; message: string } | null>(null);
  const mutation = useRef(new ApiMutation());
  const pending = useRef(false);
  const current = saved?.id === conversation?.id && saved && conversation && BigInt(saved.revision) > BigInt(conversation.revision) ? saved : conversation;
  const selection = conversation ? current?.modelSelection ?? displayed?.selection ?? null : drafts[draftKey] ?? displayed?.selection ?? null;
  const options = displayed?.models ?? [];
  const model = options.find((option) => option.id === selection?.modelProfileId);
  const configurable = displayed?.configurable ?? false;
  const invalid = configurable && (!options.some((option) => option.available) ? uiText("当前员工没有可用模型，请联系企业管理员。")
    : !model?.available ? model?.unavailableReason ? uiText("当前模型{0}，请选择其他模型。", [uiText(model.unavailableReason)]) : uiText("请选择可用模型。")
      : selection?.reasoningEffort && !model.reasoningEfforts.includes(selection.reasoningEffort) ? uiText("当前模型不支持所选思考强度，请重新选择。") : "");
  const disabled = !enabled || busy || saving || !query.data || Boolean(conversation && (conversation.status !== "active" || conversation.activeRunId));

  async function change(next: ModelSelection) {
    if (disabled || pending.current || sameModelSelection(next, selection)) {
      return;
    }
    setFailure(null);
    if (!conversation) {
      setDrafts((previous) => ({...previous, [draftKey]: next}));
      return;
    }
    if (!current) {
      return;
    }
    pending.current = true;
    setSaving(true);
    const endpoint = `${conversationPath(enterprise, conversation.id)}/model-selection`;
    try {
      const result = await mutation.current.run<Conversation>(endpoint, {
        method: "PUT",
        revision: current.revision,
        body: next
      });
      setSaved(result);
      onChanged?.();
    } catch (error) {
      console.error("保存对话模型选择失败", {
        method: "PUT",
        path: endpoint,
        conversationId: conversation.id,
        requestId: error instanceof ApiError ? error.requestId : undefined,
      }, error);
      setFailure({key: draftKey, message: errorMessage(error, uiText("模型选择未能保存，请重试。"))});
      query.retry();
      onChanged?.();
    } finally {
      pending.current = false;
      setSaving(false);
    }
  }

  return {
    selection,
    model,
    options,
    configurable,
    disabled,
    saving,
    loading: query.loading && !displayed,
    visible: Boolean(path || initialOptions),
    error: failure?.key === draftKey ? failure.message : query.error || invalid || "",
    loadError: Boolean(query.error),
    reload: query.retry,
    ready: Boolean(query.data) && !query.error && !saving && !invalid,
    loaded: Boolean(query.data || query.error),
    handoff: query.data ? {...query.data, selection} : undefined,
    defaults: query.data?.defaultSelection ?? null,
    changeModel: (id: string, effort?: ReasoningEffort | null) => {
      const next = options.find((option) => option.id === id && option.available);
      if (!next || effort && !next.reasoningEfforts.includes(effort)) {
        return;
      }
      void change(effort === undefined ? selectModel(next, selection) : {modelProfileId: id, reasoningEffort: effort});
    },
    changeEffort: (effort: ReasoningEffort | null) => {
      if (selection) {
        void change({...selection, reasoningEffort: effort});
      }
    },
    reset: () => {
      if (query.data?.defaultSelection) {
        void change(query.data.defaultSelection);
      }
    },
    resetDraft: () => setDrafts({}),
  };
}
