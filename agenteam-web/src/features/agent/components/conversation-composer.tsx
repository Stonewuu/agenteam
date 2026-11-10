"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import {LoadingTransition} from "@/components/ui/loading-transition";
import {EmployeePickerButton} from "@/features/employee/components/employee-picker-button";

import type {RefObject} from "react";
import type {ComposerSelectionContext} from "../lib/composer-selection";
import {useComposerLayout} from "../hooks/use-composer-layout";
import {ConversationComposerSurface} from "./conversation-composer-surface";
import type {QueuedMessage, Run} from "../types/execution";
import {ConversationMessageQueue} from "./conversation-message-queue";
import {IconMessages, IconPlayerStop} from "@/components/ui/icons";
import questionStyles from "./run-question.module.css";
import styles from "./agent-workspace.module.css";
import controls from "./conversation-controls.module.css";
import type {SkillOption} from "@/features/skill/types/skill";
import type {Employee} from "@/features/employee/types/employee";
import skillStyles from "@/features/skill/components/workspace-skills.module.css";

import type {DocumentOption} from "@/features/knowledge/types/knowledge";
import type {useFileUploads} from "@/features/file/hooks/use-file-uploads";
import {ConversationApprovalPolicy} from "./conversation-approval-policy";
import {ComposerExtraTools} from "./composer-extra-tools";
import {ComposerTransition} from "./composer-transition";
import type {useConversationApprovalPolicy} from "../hooks/use-conversation-approval-policy";
import type {useConversationModel} from "../hooks/use-conversation-model";
import {ConversationModelControls, ConversationModelFeedback} from "./conversation-model-controls";
import {ComposerProjectFrame} from "./composer-project-frame";
import type {useConversationProject} from "@/features/project/hooks/use-conversation-project";

export function ConversationComposer({
                                       employee,
                                       employeeName,
                                       employeeLoading,
                                       employeeReady,
                                       text,
                                       onText,
                                       onSubmit,
                                       canSubmit,
                                       canQueue,
                                       queuedMessages = [],
                                       onQueueChange,
                                       submitting,
                                       activeRun,
                                       canStop,
                                       stopping,
                                       onStop,
                                       reason,
                                       error,
                                       approvalPolicy,
                                       model,
                                       project,
                                       skills,
                                       canChooseSkills,
                                       skillsLoading,
                                       onChooseSkills,
                                       onRemoveSkill,
                                       files,
                                       attachmentsEnabled,
                                       documents,
                                       canChooseDocuments,
                                       onChooseDocuments,
                                       onRemoveDocument,
                                       onChooseEmployee,
                                       inputRef,
                                       activePicker
                                     }: {
  employee?: Pick<Employee, "icon" | "color"> | null;
  employeeName?: string;
  employeeLoading: boolean;
  employeeReady: boolean;
  text: string;
  onText: (text: string) => void;
  onSubmit: () => void;
  canSubmit: boolean;
  canQueue: boolean;
  queuedMessages: QueuedMessage[];
  onQueueChange: (run: string, prioritize: boolean) => Promise<void>;
  submitting: boolean;
  activeRun: Run | null;
  canStop: boolean;
  stopping: boolean;
  onStop: () => void;
  reason: string | null;
  error: string;
  skills: SkillOption[];
  canChooseSkills: boolean;
  skillsLoading: boolean;
  onChooseSkills: (context?: ComposerSelectionContext) => void;
  onRemoveSkill: (id: string) => void;
  files: ReturnType<typeof useFileUploads>;
  attachmentsEnabled: boolean;
  documents: DocumentOption[];
  canChooseDocuments: boolean;
  onChooseDocuments: (context?: ComposerSelectionContext) => void;
  onRemoveDocument: (id: string) => void;
  onChooseEmployee: (context?: ComposerSelectionContext) => void;
  inputRef: RefObject<HTMLTextAreaElement | null>;
  activePicker: "employee" | "skill" | "document" | null;
  approvalPolicy: ReturnType<typeof useConversationApprovalPolicy>;
  model: ReturnType<typeof useConversationModel>;
  project: ReturnType<typeof useConversationProject>;
}) {
  const uiText = useT();
  const container = useComposerLayout();
  const isStopping = stopping || activeRun?.status === "cancelling";
  const waitingForAnswer = activeRun?.status === "waiting_input";
  const queue = <ConversationMessageQueue items={queuedMessages.map(item => ({id: item.runId, text: item.text}))} onChange={onQueueChange}/>;
  const stopLabel = queuedMessages.length ? uiText("停止任务和队列") : uiText("停止");
  const stopAction = canStop && <Button className={styles.sendButton} type="button"
    data-composer-stop aria-label={stopLabel} title={isStopping ? uiText("正在停止") : stopLabel}
    aria-busy={isStopping} disabled={isStopping} onClick={onStop}><IconPlayerStop size={19}/></Button>;
  return <div ref={container} className={styles.composerWrap}>
    {error && <p className={controls.composerNotice} role="alert">{localizeUiMessage(error ?? "", uiText)}</p>}
    {!activeRun && reason && <p className={controls.composerNotice}>{uiText(reason)}</p>}
    <LoadingTransition state={waitingForAnswer ? "waiting_input" : "composer"} layout="stack"
      className={questionStyles.composerTransition} contentClassName={questionStyles.composerStates}>
      {waitingForAnswer && queue}
      {waitingForAnswer && <div className={questionStyles.waitingBar}>
        <IconMessages size={19}/><span className={questionStyles.waitingCopy}>{uiText("请先回答上方的问题")}</span>
        <div className={questionStyles.waitingActions}><Button type="button" className={questionStyles.locate} onClick={() => {
          const viewport = container.current?.parentElement?.querySelector<HTMLElement>("[data-conversation-viewport]");
          const question = viewport?.querySelector<HTMLElement>('[data-question-pending="true"]');
          if (viewport && question) {
            // 只滚动消息区域，避免把页面标题和固定输入栏一同移出视口。
            viewport.scrollTo({top: viewport.scrollTop + question.getBoundingClientRect().top - viewport.getBoundingClientRect().top - 24,
              behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth"});
            question.focus({preventScroll: true});
          }
        }}>{uiText("查看问题")}</Button>{canStop && <Button type="button" className={questionStyles.stop}
          aria-label={uiText("停止任务")} title={uiText("停止任务")} onClick={onStop} disabled={isStopping}><IconPlayerStop size={17}/></Button>}</div>
      </div>}
      <div className={questionStyles.composerFull} hidden={waitingForAnswer}>
    <ComposerProjectFrame state={project}>{!waitingForAnswer && queue}<ComposerTransition part="surface">
      <ConversationComposerSurface text={text} onText={onText} onSubmit={onSubmit} canSubmit={canSubmit}
        submitting={submitting} files={files} acceptsFiles={employeeReady && attachmentsEnabled} inputRef={inputRef}
        placeholder={canQueue ? uiText("补充消息，当前步骤结束后处理") : undefined}
        running={Boolean(activeRun)} onCommand={({marker, context}) => {
          if (marker === "/" && canChooseSkills && employeeReady) {
            onChooseSkills(context);
          }
          if (marker === "@") {
            if (canChooseDocuments && employeeReady) {
              onChooseDocuments(context);
            } else {
              onChooseEmployee(context);
            }
          }
        }}
        selections={<>
          {documents.length > 0 && <div className={skillStyles.selections}>{documents.map(document =>
            <span className={skillStyles.selection} key={document.documentId}>{document.name}
              <Button type="button" disabled={submitting} aria-label={uiText("移除资料“{0}”", [document.name])}
                onClick={() => onRemoveDocument(document.documentId)}>×</Button></span>)}</div>}
          {skills.length > 0 && <div className={skillStyles.selections}>{skills.map(skill =>
            <span className={skillStyles.selection} key={skill.versionId}>{skill.name}
              <Button type="button" disabled={submitting} aria-label={uiText("移除技能“{0}”", [skill.name])}
                onClick={() => onRemoveSkill(skill.versionId)}>×</Button></span>)}</div>}
        </>}
        mainTools={<>
          <ComposerTransition part="employee"><EmployeePickerButton name={employeeName} icon={employee?.icon}
            color={employee?.color} loading={employeeLoading && !employee} disabled={submitting} expanded={activePicker === "employee"}
            onClick={event => onChooseEmployee({anchor: event.currentTarget})}/></ComposerTransition>
          <ComposerExtraTools files={files} attachmentsEnabled={attachmentsEnabled} canChooseSkills={canChooseSkills}
            canChooseDocuments={canChooseDocuments} skillsLoading={skillsLoading} loading={employeeLoading && !employee}
            disabled={submitting || !employeeReady} onChooseSkills={onChooseSkills} onChooseDocuments={onChooseDocuments}/>
        </>}
        endTools={<><ConversationModelControls state={model}/>
          <ComposerTransition part="policy"><ConversationApprovalPolicy value={approvalPolicy.value} onChange={value => void approvalPolicy.change(value)}
            disabled={approvalPolicy.disabled || model.saving} saving={approvalPolicy.saving} running={Boolean(activeRun)}/></ComposerTransition>{canQueue && stopAction}</>}
        submitAction={activeRun && !canQueue ? stopAction : undefined}/>
    </ComposerTransition><ConversationModelFeedback state={model}/></ComposerProjectFrame>
      </div>
    </LoadingTransition>
  </div>;
}
