"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {useState} from "react";
import {useApiQuery} from "@/lib/http/use-api-query";
import {IconRefresh} from "@/components/ui/icons";
import {runPath} from "../api/conversation-api";
import type {Run, RunAttempt} from "../types/execution";
import type {DisplayMessage} from "../types/conversation-display";
import styles from "./conversation-execution.module.css";

/** 失败原因和安全重试直接留在对应回复中，不打开独立详情弹窗。 */
export function ConversationRunFeedback({enterprise, message, latestRun, busy, error, onRetry}: {
  enterprise: string;
  message: DisplayMessage;
  latestRun: Run | null;
  busy: boolean;
  error: string;
  onRetry: (runId: string) => Promise<boolean>;
}) {
  const uiText = useT();
  const [retried, setRetried] = useState(false);
  const live = latestRun?.id === message.runId ? latestRun : null;
  // 结束事件只报告状态，重试权限由执行详情按当前任务与工具结果判断。
  const needsDetails = !live || live.status === "failed" || live.status === "cancelled";
  const query = useApiQuery<Run>(message.runId && needsDetails ? runPath(enterprise, message.runId) : null, message.updatedAt);
  const attempts = useApiQuery<RunAttempt[]>(message.hasMultipleAttempts && message.runId ? `${runPath(enterprise, message.runId)}/attempts` : null, message.updatedAt);
  const run = query.data?.id === message.runId ? query.data : live;
  const attempt = attempts.data?.find((value) => value.attemptNo === message.attemptNo);
  const current = run?.currentAttemptNo === message.attemptNo ? run : null;
  const reason = current?.errorCode === "MODEL_TEMPORARY_FAILURE" ? uiText("模型暂时无法回复。")
    : attempt?.errorSummary || current?.errorMessage
      || (message.status === "cancelled" ? uiText("已停止。") : message.status === "failed" ? uiText("回复未完成。") : "");
  return <div className={styles.recovery} aria-label={uiText("执行结果")}>
    {reason && <p>{localizeUiMessage(reason, uiText)}</p>}
    {query.error && <p role="alert">{localizeUiMessage(query.error ?? "", uiText)}<Button type="button"
                                                                                          onClick={query.retry}>{uiText("重新加载")}</Button>
    </p>}
    {attempts.error && <p role="alert">{localizeUiMessage(attempts.error ?? "", uiText)}<Button type="button"
                                                                                                onClick={attempts.retry}>{uiText("重新加载记录")}</Button>
    </p>}
    {run?.canRetry && run.currentAttemptNo === message.attemptNo &&
      <Button type="button" disabled={busy} onClick={() => {
        setRetried(true);
        void onRetry(run.id);
      }}><IconRefresh size={16}/>{busy && retried ? uiText("正在重试…") : uiText("重试")}</Button>}
    {retried && error && <p role="alert">{localizeUiMessage(error ?? "", uiText)}</p>}
  </div>;
}
