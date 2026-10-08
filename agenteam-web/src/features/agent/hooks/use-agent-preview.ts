"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useRef, useState} from "react";
import {ApiError, ApiMutation, errorMessage} from "@/lib/http/api-client";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import type {DraftWrite} from "@/features/resource/types/resource";
import {runPath, textInput} from "../api/conversation-api";
import type {FileReference, RunAccepted} from "../types/execution";
import {useConversationStream} from "./use-conversation-stream";
import type {ReasoningEffort} from "@/features/modelprofile/lib/reasoning-effort";

type PreviewRequest = {
  draft: DraftWrite;
  modelProfileId: string | null;
  reasoningEffort: ReasoningEffort | null;
  text: string;
  revision: string;
  modelName: string;
  attachments: FileReference[]
};

/** 两个对比窗口各自保存请求和执行结果，失败或停止不会影响另一侧。 */
export function useAgentPreview(enterprise: string, agent: string) {
  const uiText = useT();
  const [accepted, setAccepted] = useState<RunAccepted | null>(null);
  const [input, setInput] = useState<PreviewRequest | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [error, setError] = useState("");
  const pending = useRef(false);
  const mutation = useRef(new ApiMutation());
  const cancel = useRef(new ApiMutation());
  const stream = useConversationStream(enterprise, accepted?.conversationId ?? null);
  const run = stream.state?.snapshot.activeRun ?? stream.state?.latestRun ?? null;
  const active = Boolean(accepted && (!run || !["completed", "failed", "cancelled"].includes(run.status)));

  async function start(request: PreviewRequest) {
    if (pending.current || active) {
      return;
    }
    pending.current = true;
    setSubmitting(true);
    setAccepted(null);
    setInput(request);
    setError("");
    try {
      setAccepted(await mutation.current.run<RunAccepted>(organizationPath(enterprise, `/agents/${encodeURIComponent(agent)}/preview`), {
        method: "POST", revision: request.revision,
        body: {
          draft: request.draft, input: {
            ...textInput(request.text), attachmentIds: request.attachments.map((file) => file.id),
            modelSelection: request.modelProfileId ? {
              modelProfileId: request.modelProfileId,
              reasoningEffort: request.reasoningEffort
            } : null
          }
        },
      }));
    } catch (failed) {
      const fields = failed instanceof ApiError ? Array.from(new Set(Object.values(failed.fieldErrors).flat())).join(" ") : "";
      setError(fields || errorMessage(failed, uiText("预览未能开始，请重试。")));
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
      setError(errorMessage(failed, uiText("暂时无法停止，请重试。")));
    } finally {
      setStopping(false);
    }
  }

  return {
    accepted, input, run, active, submitting, stopping, error, stream, start, stop,
    clear: () => {
      if (!active && !submitting) {
        setAccepted(null);
        setInput(null);
        setError("");
      }
    },
    retry: () => input ? start(input) : Promise.resolve()
  };
}
