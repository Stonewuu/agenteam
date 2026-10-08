"use client";

import {pasteUpload} from "@/features/file/lib/paste-upload";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Textarea} from "@/components/ui/textarea";
import {Button} from "@/components/ui/button";

import Link from "next/link";
import {useRef, useState, useTransition} from "react";
import {useRouter} from "next/navigation";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {useFileUploads} from "@/features/file/hooks/use-file-uploads";
import {FileUploadSelection} from "@/features/file/components/file-upload-picker";
import {useConversationSkills} from "@/features/skill/hooks/use-conversation-skills";
import {SkillPickerDialog} from "@/features/skill/components/skill-picker-dialog";
import {DocumentPickerDialog} from "@/features/knowledge/components/document-picker-dialog";
import {EmployeePicker} from "@/features/agent/components/employee-picker";
import {useComposerSelection} from "@/features/agent/hooks/use-composer-selection";
import {useNewConversationApprovalPolicy} from "@/features/agent/hooks/use-new-conversation-approval-policy";
import {useConversationNavigation} from "@/features/agent/components/conversation-navigation-context";
import {composerCommand, type ComposerSelectionContext} from "@/features/agent/lib/composer-selection";
import {conversationPath, textInput} from "@/features/agent/api/conversation-api";
import {useConversationModel} from "@/features/agent/hooks/use-conversation-model";
import {useConversationProject} from "@/features/project/hooks/use-conversation-project";
import {ComposerProjectFrame} from "@/features/agent/components/composer-project-frame";
import {
  ConversationModelControls,
  ConversationModelFeedback
} from "@/features/agent/components/conversation-model-controls";
import type {RunAccepted} from "@/features/agent/types/execution";
import type {Employee} from "@/features/employee/types/employee";
import type {DocumentOption} from "@/features/knowledge/types/knowledge";
import {Dialog, DialogAction, DialogActions, DialogCancel} from "@/components/ui/dialog";
import {EmployeePickerButton} from "@/features/employee/components/employee-picker-button";
import {
  IconArrowRight,
  IconArrowUpRight,
  IconChartBar,
  IconFileText,
  IconNotes,
  IconPencil,
  IconSend,
  IconUsers,
  IconX
} from "@/components/ui/icons";
import {ComposerExtraTools} from "@/features/agent/components/composer-extra-tools";
import {ConversationApprovalPolicy} from "@/features/agent/components/conversation-approval-policy";
import {composerSendTransition, ComposerTransition} from "@/features/agent/components/composer-transition";
import {useComposerHandoff} from "@/features/agent/components/composer-handoff-context";
import {useBlockNavigation} from "./navigation-guard";
import {MutationFeedback} from "./mutation-feedback";
import styles from "./home-page.module.css";
import ui from "@/components/ui/surface.module.css";

const suggestions = [
  {icon: IconPencil, title: "写一份内容策划", text: "帮我写一份秋季新品内容策划"},
  {icon: IconFileText, title: "提炼文档要点", text: "帮我提炼这份文档的核心观点"},
  {icon: IconChartBar, title: "整理业务数据", text: "帮我整理本月的销售数据"},
  {icon: IconNotes, title: "整理会议纪要", text: "帮我把会议记录整理成纪要和行动清单"},
];

export function HomeTask({enterprise, userId, employees, permissions, onChanged}: {
  enterprise: string;
  userId: string;
  employees: Employee[];
  permissions: string[];
  onChanged: () => void
}) {
  const uiText = useT();
  const router = useRouter();
  const navigation = useConversationNavigation();
  const action = useFormAction();
  const handoff = useComposerHandoff();
  const [approvalPolicy, setApprovalPolicy] = useNewConversationApprovalPolicy(userId, enterprise);
  const [navigating, startNavigation] = useTransition();
  const busy = action.busy || navigating;
  const input = useRef<HTMLTextAreaElement>(null);
  const [draft, setDraft] = useState("");
  const [selected, setSelected] = useState<Employee | null>(null);
  const [picker, setPicker] = useState(false);
  const [skillPicker, setSkillPicker] = useState(false);
  const [documentPicker, setDocumentPicker] = useState(false);
  const selection = useComposerSelection(input, setDraft, () => {
    setPicker(false);
    setSkillPicker(false);
    setDocumentPicker(false);
  });
  const [documents, setDocuments] = useState<DocumentOption[]>([]);
  const [replacement, setReplacement] = useState<string | null>(null);
  const employee = selected ?? employees.find((value) => value.canRun);
  const model = useConversationModel({
    enterprise, agentId: employee?.agentId, draftKey: `${userId}:home:${employee?.agentId ?? ""}`,
    enabled: permissions.includes("agent.run"), busy
  });
  const project = useConversationProject({
    enterprise,
    draftKey: `${userId}:${enterprise}:home`,
    enabled: permissions.includes("agent.run"),
    busy: busy || model.saving
  });
  const root = `/enterprises/${encodeURIComponent(enterprise)}`;
  const canSkills = permissions.includes("skill.use");
  const canKnowledge = permissions.includes("knowledge.search");
  const canMarket = permissions.includes("agent.market_view");
  const files = useFileUploads(enterprise, "attachment", `${userId}:home:${employee?.agentId ?? ""}`);
  const skills = useConversationSkills(userId, enterprise, employee?.agentId ?? null, null, null, canSkills);
  const ready = Boolean(employee?.canRun) && model.ready && !project.saving && !busy && !skills.loading && !skills.error && (!files.items.length || files.ready && employee?.attachmentsEnabled);
  useBlockNavigation(Boolean(draft || files.items.length || skills.selected.length || documents.length), busy);
  const fill = (text: string) => {
    setDraft(text);
    setReplacement(null);
    window.requestAnimationFrame(() => input.current?.focus());
  };

  function showPicker(kind: "employee" | "skill" | "document", source?: ComposerSelectionContext) {
    selection.prepare(source);
    setPicker(kind === "employee");
    setSkillPicker(kind === "skill");
    setDocumentPicker(kind === "document");
  }

  async function send(focusInput = false) {
    if (!ready || !employee || !draft.trim() && !files.fileIds.length) {
      return;
    }
    await action.execute(async () => {
      try {
        const accepted = await action.mutation.run<RunAccepted>(conversationPath(enterprise), {
          method: "POST", body: {
            agentId: employee.agentId, approvalPolicy, projectId: project.selected?.id ?? null, input: {
              ...textInput(draft.trim()),
              attachmentIds: files.fileIds,
              skillVersionIds: skills.selected.map((skill) => skill.versionId),
              knowledgeReferences: documents.map((document) => ({
                documentId: document.documentId,
                generation: document.generation
              })),
              modelSelection: model.configurable ? model.selection : null,
            }
          }
        });
        handoff?.prepare({
          conversationId: accepted.conversationId,
          employee,
          approvalPolicy,
          focusInput,
          modelOptions: model.handoff,
          project: project.selected
        });
        navigation?.list.refresh();
        const navigate = () => startNavigation(() => {
          setDraft("");
          files.clear();
          skills.clear();
          setDocuments([]);
          router.push(`${root}/conversations/${encodeURIComponent(accepted.conversationId)}`, {
            scroll: false,
            transitionTypes: [composerSendTransition]
          });
        });
        if (handoff?.navigate) {
          await handoff.navigate(navigate);
        } else {
          navigate();
        }
      } catch (error) {
        onChanged();
        throw error;
      }
    }, "");
  }

  if (!employees.length && !selected) {
    return <section className={styles.emptyTask} aria-labelledby="empty-task-title">
      <span className={styles.emptyTaskIcon} aria-hidden="true"><IconUsers size={30} variant="Bulk"/></span>
      <div className={styles.emptyTaskCopy}>
        <h2 id="empty-task-title">{canMarket ? uiText("找一位数字员工，开始协作") : uiText("暂无可用的数字员工")}</h2>
        <p>{canMarket ? uiText("前往员工广场，雇佣适合当前工作的数字员工。") : uiText("请联系企业管理员获取帮助。")}</p>
      </div>
      {canMarket && <Link className={`${ui.primary} ${styles.emptyTaskAction}`}
                          href={`${root}/employees`}>{uiText("前往员工广场")}<IconArrowRight size={17}
                                                                                             aria-hidden="true"/>
      </Link>}
    </section>;
  }
  return <><ComposerProjectFrame state={project} alwaysShowProject><ComposerTransition part="surface">
    <section className={styles.task} data-composer-part="surface" data-composer-location="home"
             aria-label={uiText("开始任务")}>
      <form onSubmit={(event) => {
        event.preventDefault();
        void send();
      }}>
        <ComposerTransition part="text"><Textarea ref={input} className={styles.input} data-composer-part="text"
                                                  aria-label={uiText("任务内容")} rows={4} maxLength={20000}
                                                  value={draft} disabled={busy}
                                                  onPaste={(event) => pasteUpload(event, files.add, !busy && Boolean(employee?.attachmentsEnabled))}
                                                  placeholder={employee ? uiText("描述你想完成的工作，剩下的我们一起推进…") : uiText("选择一位数字员工，开始新的任务")}
                                                  onChange={(event) => {
                                                    setDraft(event.target.value);
                                                    if ((event.nativeEvent as InputEvent).isComposing) {
                                                      return;
                                                    }
                                                    const command = composerCommand(event.currentTarget);
                                                    if (command?.marker === "/" && employee && canSkills) {
                                                      showPicker("skill", command.context);
                                                    }
                                                    if (command?.marker === "@") {
                                                      showPicker(employee && canKnowledge ? "document" : "employee", command.context);
                                                    }
                                                  }}
                                                  onKeyDown={(event) => {
                                                    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing && event.keyCode !== 229) {
                                                      event.preventDefault();
                                                      void send(true);
                                                    }
                                                  }}/></ComposerTransition>
        <FileUploadSelection uploads={files} disabled={busy}/>
        {(skills.selected.length > 0 || documents.length > 0) &&
          <div className={ui.chips}>{skills.selected.map((skill) => <span className="tag"
                                                                          key={skill.versionId}>{skill.name}<Button
            className="icon-button" type="button" aria-label={uiText("移除技能{0}", [skill.name])}
            onClick={() => skills.choose(skills.selected.filter((value) => value.versionId !== skill.versionId))}><IconX
            size={14}/></Button></span>)}{documents.map((document) => <span className="tag"
                                                                            key={document.documentId}>{document.name}<Button
            className="icon-button" type="button" aria-label={uiText("移除资料{0}", [document.name])}
            onClick={() => setDocuments(documents.filter((value) => value.documentId !== document.documentId))}><IconX
            size={14}/></Button></span>)}</div>}
        <div className={styles.taskActions}>
          <div className={styles.mainTools}>
            <ComposerTransition part="employee"><EmployeePickerButton name={employee?.name} icon={employee?.icon}
                                                                      color={employee?.color} expanded={picker}
                                                                      disabled={busy}
                                                                      onClick={(event) => showPicker("employee", {anchor: event.currentTarget})}/></ComposerTransition>
            <ComposerExtraTools files={files} attachmentsEnabled={Boolean(employee?.attachmentsEnabled)}
                                canChooseSkills={canSkills} canChooseDocuments={canKnowledge}
                                skillsLoading={skills.loading} disabled={busy || !employee}
                                onChooseSkills={(context) => showPicker("skill", context)}
                                onChooseDocuments={(context) => showPicker("document", context)}/>
          </div>
          <div className={styles.submitTools}>
            <ConversationModelControls state={model}/>
            <ComposerTransition part="policy"><ConversationApprovalPolicy value={approvalPolicy}
                                                                          onChange={setApprovalPolicy} disabled={busy}
                                                                          saving={false}
                                                                          running={false}/></ComposerTransition>
            <ComposerTransition part="submit"><Button className={styles.send} data-composer-part="submit"
                                                      aria-label={uiText("发送任务")}
                                                      disabled={!ready || !draft.trim() && !files.fileIds.length}><IconSend
              size={19}/></Button></ComposerTransition>
          </div>
        </div>
        <ConversationModelFeedback state={model}/>
        {!employee && canMarket &&
          <Link className="text-button" href={`${root}/employees`}>{uiText("前往员工广场")}</Link>}
        <MutationFeedback action={action} showFieldErrors/>{skills.error &&
        <p className={ui.error} role="alert">{localizeUiMessage(skills.error ?? "", uiText)}</p>}
      </form>
    </section>
  </ComposerTransition></ComposerProjectFrame>
    {employee && <div className={styles.examples}
                      aria-label={uiText("任务建议")}>{localizeCatalog(suggestions, uiText).map(({
                                                                                                   icon: Icon,
                                                                                                   title,
                                                                                                   text
                                                                                                 }) => <Button
      key={title} type="button" disabled={busy}
      onClick={() => draft && draft !== text ? setReplacement(text) : fill(text)}><Icon size={17}
                                                                                        variant="Bulk"/><span>{title}</span><IconArrowUpRight
      size={13}/></Button>)}</div>}
    {picker && <EmployeePicker enterprise={enterprise} selectedId={employee?.agentId} showMarket={canMarket}
                               anchor={selection.context?.anchor} initialQuery={selection.context?.query}
                               returnFocus={input} onClose={() => setPicker(false)} onSelect={(value) => {
      selection.consume();
      setSelected(value);
      if (value.agentId !== employee?.agentId) {
        setDocuments([]);
      }
      setPicker(false);
      input.current?.focus();
    }}/>}
    {skillPicker && employee &&
      <SkillPickerDialog enterprise={enterprise} agent={employee.agentId} conversation={null} selected={skills.selected}
                         anchor={selection.context?.anchor} returnFocus={input} initialQuery={selection.context?.query}
                         onChange={(value) => {
                           skills.choose(value);
                           selection.consume();
                         }} onClose={() => setSkillPicker(false)}/>}
    {documentPicker && employee &&
      <DocumentPickerDialog enterpriseId={enterprise} agentId={employee.agentId} conversationId={null}
                            selected={documents} anchor={selection.context?.anchor} returnFocus={input}
                            initialQuery={selection.context?.query} onChange={(value) => {
        setDocuments(value);
        selection.consume();
      }} onChooseEmployee={() => showPicker("employee", selection.context ?? undefined)}
                            onClose={() => setDocumentPicker(false)}/>}
    {replacement && <Dialog title={uiText("替换当前任务内容？")} onClose={() => setReplacement(null)}><p
      className={ui.description}>{uiText("当前尚未发送的文字将被替换为这条建议。")}</p><DialogActions
      className={ui.footer}><DialogCancel className={ui.button}>{uiText("保留文字")}</DialogCancel><DialogAction
      className={ui.primary}
      onAction={(close) => close(() => fill(replacement))}>{uiText("替换内容")}</DialogAction></DialogActions></Dialog>}
  </>;
}
