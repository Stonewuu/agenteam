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
  const query = useApiQuery<Run>(message.runId && !live ? runPath(enterprise, message.runId) : null, message.updatedAt);
  const attempts = useApiQuery<RunAttempt[]>(message.hasMultipleAttempts && message.runId ? `${runPath(enterprise, message.runId)}/attempts` : null, message.updatedAt);
  const run = live ?? query.data;
  const attempt = attempts.data?.find((value) => value.attemptNo === message.attemptNo);
  const reason = attempt?.errorSummary || (run?.currentAttemptNo === message.attemptNo ? run.errorMessage : null)
    || (message.status === "cancelled" ? uiText("已停止。") : message.status === "failed" ? uiText("本次执行未能完成，请稍后重试。") : "");
  return <div className={styles.recovery} aria-label={uiText("执行结果")}>
    <p>{reason}</p>
    {query.error && <p role="alert">{localizeUiMessage(query.error ?? "", uiText)}<Button type="button"
                                                                                          onClick={query.retry}>{uiText("重新加载")}</Button>
    </p>}
    {attempts.error && <p role="alert">{localizeUiMessage(attempts.error ?? "", uiText)}<Button type="button"
                                                                                                onClick={attempts.retry}>{uiText("重新加载记录")}</Button>
    </p>}
    {run?.canRetry && run.currentAttemptNo === message.attemptNo &&
      <Button type="button" disabled={busy} onClick={() => {
        setRetried(true);
        void onRetry(run.id).then((accepted) => {
          if (accepted) {
            query.retry();
            attempts.retry();
          }
        });
      }}><IconRefresh size={16}/>{busy && retried ? uiText("正在重新执行…") : uiText("重新执行")}</Button>}
    {retried && error && <p role="alert">{localizeUiMessage(error ?? "", uiText)}</p>}
  </div>;
}
