"use client";

import {Disclosure, DisclosureSummary} from "@/components/ui/disclosure";
import {Button} from "@/components/ui/button";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {useT} from "@/lib/i18n/locale-provider";
import {localizeUiMessage} from "@/lib/i18n/ui-message";
import {hasVisibleConversationContent} from "../lib/conversation-display";
import {type LiveRunStep, mergeExecutionSteps, messageRunSteps, type RunStepHistory} from "../lib/conversation-steps";
import type {DisplayMessage} from "../types/conversation-display";
import type {RunApproval} from "../types/execution";
import {ConversationBlocks, ConversationMarkdown} from "./conversation-message-blocks";
import layout from "./conversation-controls.module.css";

export function ConversationAttemptHistory({attempts, enterprise, histories, liveSteps, approvals, onChanged, onReloadSteps}: {
  attempts: DisplayMessage[]; enterprise: string; histories?: ReadonlyMap<string, RunStepHistory>;
  liveSteps?: ReadonlyMap<string, LiveRunStep>; approvals?: ReadonlyMap<string, RunApproval>; onChanged?: () => void;
  onReloadSteps?: () => void;
}) {
  const t = useT();
  const entries = attempts.map(message => {
    const history = message.runId ? histories?.get(message.runId) : undefined;
    const blocks = mergeExecutionSteps(message.blocks, messageRunSteps(message, history, liveSteps), message.status);
    const reason = history?.attempts.find(attempt => attempt.outputMessageId === message.id)?.errorSummary;
    return {message, blocks, reason, readError: history?.error};
  }).filter(({message, blocks, reason, readError}) => message.content.trim() || blocks.some(hasVisibleConversationContent) || reason || readError);
  if (!entries.length) {
    return null;
  }
  return <Disclosure className={layout.messageBody} keepMounted={false} animateContentHeight>
    <DisclosureSummary className={layout.attemptSummary}>{t("重试前的内容")}</DisclosureSummary>
    <div className={layout.attemptHistory}>{entries.map(({message, blocks, reason, readError}) =>
      <div key={message.id} className={layout.attemptRecord}>
        {entries.length > 1 && <EnterpriseDateTime value={message.createdAt} compact/>}
        {blocks.length ? <ConversationBlocks blocks={blocks} enterprise={enterprise} runId={message.runId}
          liveApprovals={approvals} onChanged={onChanged}/> : message.content && <ConversationMarkdown content={message.content}/>}
        {reason && <p>{localizeUiMessage(reason, t)}</p>}
        {readError && <p role="alert">{localizeUiMessage(readError, t)} <Button type="button" className="text-button"
          onClick={onReloadSteps}>{t("重新读取执行过程")}</Button></p>}
      </div>)}</div>
  </Disclosure>;
}
