"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import {toast} from "@/components/ui/toast";

import {useEffect, useRef, useState} from "react";
import {ApiMutation, errorMessage} from "@/lib/http/api-client";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import type {DisplayMessage} from "../types/conversation-display";
import type {Run, RunApproval} from "../types/execution";
import {ConversationBlocks, ConversationMarkdown} from "./conversation-message-blocks";
import styles from "./assistant-message.module.css";
import layout from "./conversation-controls.module.css";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import type {Employee} from "@/features/employee/types/employee";
import {IconBookmark, IconCheck, IconCopy, IconThumbDown, IconThumbUp} from "@/components/ui/icons";
import {ConversationRunFeedback} from "./conversation-run-feedback";
import {type LiveRunStep, mergeExecutionSteps, messageRunSteps, type RunStepHistory} from "../lib/conversation-steps";

export function ConversationMessage({
                                      message,
                                      enterprise,
                                      latestRun,
                                      liveApprovals,
                                      liveSteps,
                                      stepHistory,
                                      onReloadSteps,
                                      onFeedback,
                                      onChanged,
                                      onMemory,
                                      onRetry,
                                      retrying,
                                      retryError,
                                      employeeName: providedEmployeeName,
                                      employee
                                    }: {
  message: DisplayMessage; enterprise: string; latestRun: Run | null; liveApprovals?: ReadonlyMap<string, RunApproval>;
  liveSteps?: ReadonlyMap<string, LiveRunStep>; stepHistory?: RunStepHistory; onReloadSteps?: () => void;
  onRetry: (runId: string) => Promise<boolean>; retrying: boolean; retryError: string;
  onFeedback: (id: string, value: "positive" | "negative" | null) => void;
  onChanged?: () => void;
  onMemory?: (message: DisplayMessage, text: string) => void;
  employeeName?: string;
  employee?: Pick<Employee, "icon" | "color"> | null;
}) {
  const uiText = useT();
  const employeeName = providedEmployeeName ?? uiText("数字员工");
  const contentRef = useRef<HTMLDivElement>(null);
  const selectedRef = useRef("");
  const [feedbackMutation] = useState(() => new ApiMutation());
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  const copyTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => {
    if (copyTimer.current) {
      clearTimeout(copyTimer.current);
    }
  }, []);
  const ended = ["completed", "failed", "cancelled"].includes(message.status);
  const blocks = mergeExecutionSteps(message.blocks, messageRunSteps(message, stepHistory, liveSteps));
  const run = latestRun?.id === message.runId ? latestRun : null;
  const memoryText = message.content.trim() || message.blocks.flatMap((block) => block.kind === "workflow" ? [block.summary] : []).join("\n");

  function selectedText() {
    const selection = window.getSelection();
    return selection?.anchorNode && selection.focusNode && contentRef.current?.contains(selection.anchorNode) && contentRef.current.contains(selection.focusNode) ? selection.toString().trim() : "";
  }

  async function feedback(value: "positive" | "negative") {
    if (busy) {
      return;
    }
    setBusy(true);
    const next = message.feedback === value ? null : value;
    try {
      await feedbackMutation.run(organizationPath(enterprise, `/messages/${encodeURIComponent(message.id)}/feedback`), {
        method: "PUT",
        body: {value: next}
      });
      onFeedback(message.id, next);
    } catch (failed) {
      console.error("保存回复评价失败", {messageId: message.id, error: failed});
      toast.error(errorMessage(failed));
    } finally {
      setBusy(false);
    }
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(message.content);
      toast.success(uiText("回复已复制。"));
      if (copyTimer.current) {
        clearTimeout(copyTimer.current);
      }
      setCopied(true);
      copyTimer.current = setTimeout(() => setCopied(false), 1800);
    } catch (failure) {
      console.error("复制对话回复失败", {messageId: message.id, error: failure});
      setCopied(false);
      toast.error(uiText("暂时无法复制，请选择文字后复制。"));
    }
  }

  return <div className={styles.assistantMessage}>
    <div className={layout.messageMeta}>
      <span className={layout.messageAvatar} aria-hidden="true">
        {employee && <ResourceAvatar icon={employee.icon} color={employee.color} size="small"/>}
      </span>
      <span>{employeeName}</span>
      <EnterpriseDateTime value={message.createdAt} compact/>
    </div>
    {message.hasMultipleAttempts &&
      <p className={layout.resultNotice}>{uiText("第 ")}{message.attemptNo}{uiText(" 次尝试")}</p>}
    <div className={layout.messageBody} ref={contentRef}>{blocks.length ?
      <ConversationBlocks blocks={blocks} enterprise={enterprise} runId={message.runId} liveApprovals={liveApprovals}
                          onChanged={onChanged}/> : message.content ? <ConversationMarkdown content={message.content}/>
        : !ended || message.status === "completed" ? <span
          className={styles.placeholder}>{ended ? message.hasMultipleAttempts ? uiText("本次尝试没有返回内容。") : uiText("本次任务没有返回内容。") : message.status === "pending" ? uiText("等待执行…") : uiText("正在处理…")}</span> : null}</div>
    {stepHistory?.error &&
      <p className={layout.resultNotice} role="alert">{localizeUiMessage(stepHistory.error ?? "", uiText)} <Button
        type="button" className="text-button" onClick={onReloadSteps}>{uiText("重新读取执行过程")}</Button></p>}
    {ended && (message.status !== "completed" || run?.hasStepErrors) &&
      <ConversationRunFeedback enterprise={enterprise} message={message} latestRun={latestRun} busy={retrying}
                               error={retryError} onRetry={onRetry}/>}
    {ended && <div className={layout.messageActions} role="group" aria-label={uiText("回复操作")}>
      {message.content &&
        <Button type="button" aria-label={uiText("复制回复")} title={copied ? uiText("已复制") : uiText("复制回复")}
                data-copied={copied} onClick={() => void copy()}>{copied ? <IconCheck size={18}/> :
          <IconCopy size={18}/>}</Button>}
      <Button type="button" aria-label={message.feedback === "positive" ? uiText("取消赞同") : uiText("赞同")}
              title={message.feedback === "positive" ? uiText("取消赞同") : uiText("赞同")}
              aria-pressed={message.feedback === "positive"} disabled={busy}
              onClick={() => void feedback("positive")}><IconThumbUp size={18}
                                                                     variant={message.feedback === "positive" ? "Bold" : "Linear"}/></Button>
      <Button type="button" aria-label={message.feedback === "negative" ? uiText("取消不同意") : uiText("不同意")}
              title={message.feedback === "negative" ? uiText("取消不同意") : uiText("不同意")}
              aria-pressed={message.feedback === "negative"} disabled={busy}
              onClick={() => void feedback("negative")}><IconThumbDown size={18}
                                                                       variant={message.feedback === "negative" ? "Bold" : "Linear"}/></Button>
      {onMemory && memoryText &&
        <Button type="button" aria-label={uiText("保存为偏好")} title={uiText("保存为偏好")} onPointerDown={() => {
          selectedRef.current = selectedText();
        }} onClick={() => {
          const text = selectedRef.current || selectedText() || memoryText;
          selectedRef.current = "";
          onMemory(message, text);
        }}><IconBookmark size={18}/></Button>}
    </div>}
  </div>;
}
