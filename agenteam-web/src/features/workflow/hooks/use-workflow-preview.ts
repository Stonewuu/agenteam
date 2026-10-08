"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useRef, useState} from "react";
import {ApiError, ApiMutation, errorMessage} from "@/lib/http/api-client";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useConversationStream} from "@/features/agent/hooks/use-conversation-stream";
import {runPath} from "@/features/agent/api/conversation-api";
import type {RunAccepted} from "@/features/agent/types/execution";
import type {WorkflowConfig} from "@/features/resource/types/resource";

type Request = { draft: WorkflowConfig; input: Record<string, unknown>; revision: string };

export function useWorkflowPreview(enterprise: string, resource: string) {
  const uiText = useT();
  const [accepted, setAccepted] = useState<RunAccepted | null>(null), [request, setRequest] = useState<Request | null>(null);
  const [submitting, setSubmitting] = useState(false), [stopping, setStopping] = useState(false), [error, setError] = useState("");
  const pending = useRef(false), submit = useRef(new ApiMutation()), cancel = useRef(new ApiMutation());
  const stream = useConversationStream(enterprise, accepted?.conversationId ?? null);
  const current = stream.state?.snapshot.conversation.id === accepted?.conversationId ? stream.state : null;
  const run = current?.snapshot.activeRun ?? current?.latestRun ?? null;
  const active = Boolean(accepted && (!run || !["completed", "failed", "cancelled"].includes(run.status)));

  async function start(input: Request) {
    if (pending.current || active) {
      return;
    }
    pending.current = true;
    setSubmitting(true);
    setError("");
    setAccepted(null);
    setRequest(input);
    try {
      setAccepted(await submit.current.run<RunAccepted>(organizationPath(enterprise, `/workflows/${encodeURIComponent(resource)}/preview`), {
        method: "POST",
        revision: input.revision,
        body: {draft: input.draft, input: input.input}
      }));
    } catch (failed) {
      console.error("提交工作流预览失败", {resourceId: resource}, failed);
      const fields = failed instanceof ApiError ? Array.from(new Set(Object.values(failed.fieldErrors).flat())).join(" ") : "";
      setError(fields || errorMessage(failed, uiText("测试未能开始，请检查输入后重试。")));
    } finally {
      pending.current = false;
      setSubmitting(false);
    }
  }

  async function stop() {
    if (!accepted || stopping) {
      return;
    }
    setStopping(true);
    setError("");
    try {
      await cancel.current.run(`${runPath(enterprise, accepted.runId)}/cancel`, {method: "POST"});
      stream.reload();
    } catch (failed) {
      console.error("停止工作流预览失败", {runId: accepted.runId}, failed);
      setError(errorMessage(failed, uiText("暂时无法停止，请重试。")));
    } finally {
      setStopping(false);
    }
  }

  return {accepted, request, run, active, submitting, stopping, error, stream, start, stop};
}
