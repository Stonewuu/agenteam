"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {MessagePrimitive, ThreadPrimitive} from "@assistant-ui/react";
import {type RefObject, useEffect, useRef} from "react";
import type {useConversationWorkspace} from "../hooks/use-conversation-workspace";
import type {DisplayMessage} from "../types/conversation-display";
import {readConversationMessage} from "../lib/conversation-ui-adapter";
import {ConversationMessage} from "./conversation-message";
import {ConversationUserMessage} from "./conversation-user-message";
import {ConversationComposer} from "./conversation-composer";
import {WorkspaceSkills} from "@/features/skill/components/workspace-skills";
import styles from "./agent-workspace.module.css";
import controls from "./conversation-controls.module.css";
import ui from "@/components/ui/surface.module.css";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import {IconArrowDown} from "@/components/ui/icons";
import type {ComposerSelectionContext} from "../lib/composer-selection";

export function ConversationPane({
                                   enterprise,
                                   canRun,
                                   canStop,
                                   canUseSkills,
                                   canUseKnowledge,
                                   workspace,
                                   conversationRef,
                                   onChooseEmployee,
                                   onChooseSkills,
                                   onChooseDocuments,
                                   composerInput,
                                   onMemory
                                 }: {
  enterprise: string;
  canRun: boolean;
  canStop: boolean;
  canUseSkills: boolean;
  canUseKnowledge: boolean;
  workspace: Omit<ReturnType<typeof useConversationWorkspace>, "conversationRef">;
  conversationRef: RefObject<HTMLDivElement | null>;
  onChooseEmployee: (context?: ComposerSelectionContext) => void;
  onChooseSkills: (context?: ComposerSelectionContext) => void;
  onChooseDocuments: (context?: ComposerSelectionContext) => void;
  composerInput: RefObject<HTMLTextAreaElement | null>;
  onMemory?: (message: DisplayMessage, text: string) => void;
}) {
  const uiText = useT();
  const {stream, employee, afterComposerTransition} = workspace;
  const displayEmployee = workspace.displayEmployee;
  const loading = Boolean(workspace.conversationId) && !stream.state;
  const focusPending = useRef<string | null>(null);
  useEffect(() => {
    const id = workspace.conversationId;
    if (focusPending.current !== id) {
      focusPending.current = null;
    }
    if (workspace.focusComposerAfterSend) {
      focusPending.current = id;
    }
    if (loading || !id || focusPending.current !== id) {
      return;
    }
    let cancelled = false;
    let frame = 0;
    // 等页面过渡完成后再交还键盘焦点，避免导航过程覆盖刚设置的焦点。
    void Promise.resolve(afterComposerTransition?.()).then(() => {
      if (cancelled) {
        return;
      }
      frame = requestAnimationFrame(() => {
        const input = composerInput.current;
        if (!input || input.disabled) {
          return;
        }
        focusPending.current = null;
        if (document.activeElement === document.body) {
          input.focus({preventScroll: true});
        }
      });
    });
    return () => {
      cancelled = true;
      cancelAnimationFrame(frame);
    };
  }, [loading, workspace.conversationId, workspace.focusComposerAfterSend, afterComposerTransition, composerInput]);
  const run = stream.state?.snapshot.activeRun ?? null;
  const error = workspace.actionError || workspace.approvalPolicy.error || (!workspace.conversationId && employee.error) || workspace.skills.error || workspace.sourceError;
  const reason = workspace.conversationId ? workspace.detail?.unavailableReason ?? null : workspace.employeeLoading ? null : employee.data?.unavailableReason ?? (!workspace.selectedAgent ? uiText("请先选择员工。") : null);
  return <ThreadPrimitive.Root className={styles.threadRoot}>
    <ThreadPrimitive.Viewport asChild autoScroll={false} scrollToBottomOnRunStart={false}
                              scrollToBottomOnInitialize={false} scrollToBottomOnThreadSwitch={false}
                              ref={conversationRef}>
      <section className={styles.conversation} data-conversation-viewport aria-label={uiText("对话窗口")}>
        <div className={styles.messageList}>
          {loading ? <div className={stream.error ? styles.loadError : styles.sessionLoading}
                          role={stream.error ? "alert" : "status"}>
            <p>{stream.error || uiText("正在加载会话消息…")}</p>{stream.error &&
            <Button type="button" onClick={stream.reload}>{uiText("重新加载")}</Button>}
          </div> : <>
            {!workspace.conversationId && <div className={controls.welcome}>
              {displayEmployee && <ResourceAvatar icon={workspace.employeeAppearance?.icon ?? ""}
                                                  color={workspace.employeeAppearance?.color} size="large"/>}
              <h2>{displayEmployee ? uiText("和{0}一起开始", [displayEmployee.name]) : uiText("开始一段新对话")}</h2>
              {displayEmployee ? <>
                  <p>{displayEmployee.welcomeMessage || displayEmployee.description}</p>{displayEmployee.suggestedQuestions.length > 0 &&
                  <div className={controls.questions}>
                    {displayEmployee.suggestedQuestions.map((question, index) => <Button type="button" key={index}
                                                                                         onClick={() => workspace.setDraft(question)}>{question}</Button>)}
                  </div>}</> :
                <p>{employee.error || (workspace.employeeLoading ? uiText("正在读取员工信息…") : uiText("选择一位员工，描述希望完成的任务。"))}</p>}
              {canRun && !displayEmployee && !workspace.employeeLoading &&
                <div className={ui.actions}><Button className={ui.button} type="button"
                                                    onClick={(event) => onChooseEmployee({anchor: event.currentTarget})}>{uiText("选择员工")}</Button>
                </div>}
            </div>}
            {!workspace.conversationId && canUseSkills &&
              <WorkspaceSkills enterprise={enterprise} selectedAgent={workspace.selectedAgent}
                               onSelect={workspace.startSkill}/>}
            {stream.state?.snapshot.hasOlderMessages &&
              <Button className={controls.historyButton} type="button" disabled={stream.loadingOlder}
                      onClick={() => void workspace.loadOlder()}>{stream.loadingOlder ? uiText("正在加载…") : uiText("查看更早消息")}</Button>}
            <ThreadPrimitive.Messages>{({message}) => {
              const content = readConversationMessage(message.metadata);
              if (!content) {
                return null;
              }
              return <MessagePrimitive.Root asChild>
                <div data-conversation-message={content.id}
                     className={`${styles.messageRow} ${content.role === "user" ? styles.userMessageRow : styles.assistantMessageRow}`}>
                  {content.role === "user" ? <ConversationUserMessage message={content} enterprise={enterprise}/>
                    : <ConversationMessage employeeName={workspace.agentName} employee={workspace.employeeAppearance}
                                           message={content} enterprise={enterprise}
                                           latestRun={stream.state?.latestRun ?? null}
                                           liveApprovals={stream.state?.approvals}
                                           liveSteps={stream.state?.steps}
                                           stepHistory={content.runId ? workspace.steps.history.get(content.runId) : undefined}
                                           onReloadSteps={workspace.steps.retry}
                                           onFeedback={stream.setFeedback} onChanged={stream.reload} onMemory={onMemory}
                                           onRetry={workspace.retry} retrying={workspace.submitting}
                                           retryError={workspace.actionError}/>}
                </div>
              </MessagePrimitive.Root>;
            }}</ThreadPrimitive.Messages>
            {stream.error &&
              <div className={styles.loadError} role="alert"><p>{localizeUiMessage(stream.error ?? "", uiText)}</p>
                <Button type="button" onClick={stream.reload}>{uiText("重新加载")}</Button></div>}
          </>}
        </div>
      </section>
    </ThreadPrimitive.Viewport>
    {!loading && workspace.awayFromBottom && workspace.messages.length > 0 &&
      <Button className={styles.scrollToBottom} type="button" onClick={workspace.scrollToBottom}
              aria-label={workspace.hasUnseenContent ? uiText("有新内容，回到底部") : uiText("回到底部")}
              title={uiText("回到底部")}>
        <IconArrowDown size={18}/>{workspace.hasUnseenContent && <span>{uiText("有新内容")}</span>}
      </Button>}
    <span className={controls.actionAnnouncement}
          role="status">{workspace.hasUnseenContent ? uiText("有新内容，可以回到底部查看。") : ""}</span>
    {(!loading || workspace.composerHandoff) && (canRun || run) && <ConversationComposer inputRef={composerInput}
                                                                                         activePicker={workspace.pickerOpen ? "employee" : workspace.skillPickerOpen ? "skill" : workspace.documentPickerOpen ? "document" : null}
                                                                                         employee={workspace.employeeAppearance}
                                                                                         employeeName={workspace.agentName}
                                                                                         employeeLoading={workspace.employeeLoading}
                                                                                         employeeReady={workspace.employeeReady}
                                                                                         text={workspace.draft}
                                                                                         onText={workspace.setDraft}
                                                                                         onSubmit={() => void workspace.submitMessageContent()}
                                                                                         canSubmit={workspace.canSubmit}
                                                                                         submitting={workspace.submitting || loading}
                                                                                         activeRun={run}
                                                                                         canStop={canStop}
                                                                                         stopping={workspace.stopping}
                                                                                         approvalPolicy={workspace.approvalPolicy}
                                                                                         model={workspace.model}
                                                                                         project={workspace.project}
                                                                                         skills={workspace.skills.selected}
                                                                                         canChooseSkills={canUseSkills && (workspace.employeeLoading || Boolean(workspace.detail?.agentId ?? workspace.selectedAgent ?? displayEmployee?.agentId))}
                                                                                         skillsLoading={workspace.skills.loading}
                                                                                         onChooseSkills={onChooseSkills}
                                                                                         onRemoveSkill={(id) => workspace.skills.choose(workspace.skills.selected.filter((skill) => skill.versionId !== id))}
                                                                                         files={workspace.files}
                                                                                         attachmentsEnabled={workspace.displayAttachmentsEnabled}
                                                                                         documents={workspace.selectedDocuments}
                                                                                         canChooseDocuments={canUseKnowledge && (workspace.employeeLoading || Boolean(workspace.detail?.agentId ?? workspace.selectedAgent ?? displayEmployee?.agentId))}
                                                                                         onChooseDocuments={onChooseDocuments}
                                                                                         onRemoveDocument={(id) => workspace.chooseDocuments(workspace.selectedDocuments.filter((document) => document.documentId !== id))}
                                                                                         onChooseEmployee={onChooseEmployee}
                                                                                         onStop={() => void workspace.stop()}
                                                                                         reason={reason}
                                                                                         error={error}/>}
  </ThreadPrimitive.Root>;
}
