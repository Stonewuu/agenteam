"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import {Textarea} from "@/components/ui/textarea";
import {EmployeePickerButton} from "@/features/employee/components/employee-picker-button";

import {type RefObject, useImperativeHandle} from "react";
import {composerCommand, type ComposerSelectionContext} from "../lib/composer-selection";
import {useComposerLayout} from "../hooks/use-composer-layout";
import {useComposerInputSize} from "../hooks/use-composer-input-size";
import type {Run} from "../types/execution";
import {IconPlayerStop, IconSend} from "@/components/ui/icons";
import styles from "./agent-workspace.module.css";
import controls from "./conversation-controls.module.css";
import type {SkillOption} from "@/features/skill/types/skill";
import type {Employee} from "@/features/employee/types/employee";
import skillStyles from "@/features/skill/components/workspace-skills.module.css";

import type {DocumentOption} from "@/features/knowledge/types/knowledge";
import type {useFileUploads} from "@/features/file/hooks/use-file-uploads";
import {FileUploadSelection} from "@/features/file/components/file-upload-picker";
import {pasteUpload} from "@/features/file/lib/paste-upload";
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
  const input = useComposerInputSize(text);
  const container = useComposerLayout();
  const isStopping = stopping || activeRun?.status === "cancelling";
  useImperativeHandle(inputRef, () => input.current!, [input]);
  return <div ref={container} className={styles.composerWrap}>
    {error && <p className={controls.composerNotice} role="alert">{localizeUiMessage(error ?? "", uiText)}</p>}
    {!activeRun && reason && <p className={controls.composerNotice}>{uiText(reason)}</p>}
    <ComposerProjectFrame state={project}><ComposerTransition part="surface">
      <form className={styles.composer} data-composer-part="surface" data-composer-location="conversation"
            onSubmit={(event) => {
              event.preventDefault();
              if (canSubmit && (text.trim() || files.fileIds.length)) {
                onSubmit();
              }
            }}>
        <div className={styles.composerBody}>
          <FileUploadSelection uploads={files} disabled={submitting}/>
          {documents.length > 0 &&
            <div className={skillStyles.selections}>{documents.map((document) => <span className={skillStyles.selection}
                                                                                       key={document.documentId}>{document.name}
              <Button type="button" disabled={submitting} aria-label={uiText("移除资料“{0}”", [document.name])}
                      onClick={() => onRemoveDocument(document.documentId)}>×</Button></span>)}</div>}
          {skills.length > 0 &&
            <div className={skillStyles.selections}>{skills.map((skill) => <span className={skillStyles.selection}
                                                                                 key={skill.versionId}>{skill.name}
              <Button type="button" disabled={submitting} aria-label={uiText("移除技能“{0}”", [skill.name])}
                      onClick={() => onRemoveSkill(skill.versionId)}>×</Button></span>)}</div>}
          <ComposerTransition part="text"><Textarea ref={input} className={styles.composerInput}
                                                    data-composer-part="text" aria-label={uiText("输入消息")}
                                                    placeholder={uiText("描述你希望完成的任务")} value={text} rows={2}
                                                    maxLength={20000}
                                                    disabled={submitting}
                                                    onPaste={(event) => pasteUpload(event, files.add, !submitting && employeeReady && attachmentsEnabled)}
                                                    onChange={(event) => {
                                                      onText(event.target.value);
                                                      if ((event.nativeEvent as InputEvent).isComposing) {
                                                        return;
                                                      }
                                                      const command = composerCommand(event.currentTarget);
                                                      if (command?.marker === "/" && canChooseSkills && employeeReady) {
                                                        onChooseSkills(command.context);
                                                      }
                                                      if (command?.marker === "@") {
                                                        if (canChooseDocuments && employeeReady) {
                                                          onChooseDocuments(command.context);
                                                        } else {
                                                          onChooseEmployee(command.context);
                                                        }
                                                      }
                                                    }} onKeyDown={(event) => {
            if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing && event.keyCode !== 229 && canSubmit) {
              event.preventDefault();
              if (text.trim() || files.fileIds.length) {
                onSubmit();
              }
            }
          }}/></ComposerTransition>
        </div>
        <div className={styles.composerTools} data-running={Boolean(activeRun)}>
          <div className={styles.composerMainTools}>
            <ComposerTransition part="employee"><EmployeePickerButton name={employeeName} icon={employee?.icon}
                                                                      color={employee?.color}
                                                                      loading={employeeLoading && !employee}
                                                                      disabled={submitting}
                                                                      expanded={activePicker === "employee"}
                                                                      onClick={(event) => onChooseEmployee({anchor: event.currentTarget})}/></ComposerTransition>
            <ComposerExtraTools files={files} attachmentsEnabled={attachmentsEnabled} canChooseSkills={canChooseSkills}
                                canChooseDocuments={canChooseDocuments}
                                skillsLoading={skillsLoading} loading={employeeLoading && !employee}
                                disabled={submitting || !employeeReady}
                                onChooseSkills={onChooseSkills} onChooseDocuments={onChooseDocuments}/>
          </div>
          <div className={styles.composerSubmitTools}>
            <ConversationModelControls state={model}/>
            <ComposerTransition part="policy"><ConversationApprovalPolicy value={approvalPolicy.value}
                                                                          onChange={(value) => void approvalPolicy.change(value)}
                                                                          disabled={approvalPolicy.disabled || model.saving}
                                                                          saving={approvalPolicy.saving}
                                                                          running={Boolean(activeRun)}/></ComposerTransition>
            <ComposerTransition part="submit">{activeRun ? canStop &&
              <Button className={styles.sendButton} data-composer-part="submit" type="button" data-composer-stop
                      aria-label={isStopping ? uiText("正在停止") : uiText("停止")}
                      title={isStopping ? uiText("正在停止") : uiText("停止")} aria-busy={isStopping}
                      disabled={isStopping} onClick={onStop}><IconPlayerStop size={19}/></Button>
              : <Button className={styles.sendButton} data-composer-part="submit" type="submit"
                        disabled={!canSubmit || (!text.trim() && !files.fileIds.length)} aria-label={uiText("发送消息")}
                        title={uiText("发送消息（Enter）")}><IconSend size={19}/></Button>}</ComposerTransition>
          </div>
        </div>
      </form>
    </ComposerTransition><ConversationModelFeedback state={model}/></ComposerProjectFrame>
  </div>;
}
