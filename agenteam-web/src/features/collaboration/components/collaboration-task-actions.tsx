"use client";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {Checkbox} from "@/components/ui/checkbox";
import {Dialog, DialogActions, DialogCancel, DialogForm} from "@/components/ui/dialog";
import {Field} from "@/components/ui/field";
import {Textarea} from "@/components/ui/textarea";
import {DetailSection} from "@/components/ui/detail-section";
import {Guidance} from "@/components/ui/guidance";
import {IconAlertCircle, IconMessages, IconPlayerPause, IconPencil, IconSend} from "@/components/ui/icons";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useT} from "@/lib/i18n/locale-provider";
import {useCollaborationAction} from "../hooks/use-collaboration-action";
import type {CollaborationDetail} from "../types/collaboration";
import {CollaborationQuestions} from "./collaboration-task-sections";
import ui from "@/components/ui/surface.module.css";
import styles from "./collaboration.module.css";

export function CollaborationTaskActions({path, detail, onChanged}: {path: string; detail: CollaborationDetail; onChanged: () => void}) {
  const t = useT();
  const action = useCollaborationAction();
  const [text, setText] = useState("");
  const [revision, setRevision] = useState<number | null>(null);
  const [waived, setWaived] = useState<string[]>([]);
  const [cancelRevision, setCancelRevision] = useState<number | null>(null);
  const [editing, setEditing] = useState(false);
  const [selectedAnswers, setSelectedAnswers] = useState<Record<string, string>>({});
  const open = editing || detail.task.status === "waiting_input";
  const pending = ["active", "waiting_input", "waiting_approval", "blocked"].includes(detail.task.status);
  const completed = detail.task.status === "completed";
  if (!pending && !completed) {
    return null;
  }
  const answering = detail.task.status === "waiting_input";
  return <>{!completed && <CollaborationQuestions detail={detail} selected={selectedAnswers} busy={action.busy} onSelect={(questionId, option) => {
    setRevision((value) => value ?? detail.task.revision);
    const previous = selectedAnswers[questionId];
    setText((current) => {
      const lines = current.split("\n");
      if (previous && lines.includes(previous)) {
        return lines.map((line) => line === previous ? option : line).join("\n");
      }
      return lines.includes(option) ? current : current.trim() ? `${current}\n${option}` : option;
    });
    setSelectedAnswers((current) => ({...current, [questionId]: option}));
    action.clearFieldError("text");
  }}/>}<DetailSection className={styles.section} icon={completed ? <IconMessages size={19}/> : <IconPencil size={19}/>} title={t(completed ? "继续对话" : answering ? "回答问题" : "补充任务要求")}
    action={pending ? <Button className={`${ui.button} ${styles.stopButton}`} disabled={action.busy} onClick={() => setCancelRevision(detail.task.revision)}><IconPlayerPause size={16}/>{t("停止任务")}</Button> : undefined}>
    {!open && <Button className={ui.button} onClick={() => setEditing(true)}>{completed ? <IconMessages size={16}/> : <IconPencil size={16}/>} {t(completed ? "继续提问" : "补充要求")}</Button>}
    <AnimatedHeight preserveControlShadows>{open && <DialogForm className={styles.form} onSubmit={(event) => {
      event.preventDefault();
      void action.execute(async () => {
        await action.submit(`${path}/inputs`, {expectedRevision: revision ?? detail.task.revision, text, waivedRoleIds: waived});
        setText("");
        setRevision(null);
        setWaived([]);
        setSelectedAnswers({});
        setEditing(false);
        onChanged();
      }, t(completed ? "消息已提交。" : "补充要求已提交。"));
    }}>
      <Field label={t(completed ? "继续提问" : answering ? "你的回答" : "补充内容")} required error={action.fieldErrors.text?.join(" ")}><Textarea required name="text" maxLength={32000} value={text} disabled={action.busy}
        placeholder={t(completed ? "继续提问或说明需要调整的内容" : answering ? "填写你的回答" : "说明新增要求或需要调整的内容")} onChange={(event) => {
        setRevision((value) => value ?? detail.task.revision);
        setText(event.target.value);
        const lines = event.target.value.split("\n");
        setSelectedAnswers((current) => Object.fromEntries(Object.entries(current).filter(([, answer]) => lines.includes(answer))));
        action.clearFieldError("text");
      }}/></Field>
      {detail.roles.some((role) => role.explicitlyRequested && role.id !== detail.ownerRoleId) && <Field label={t("不再需要的岗位")}>
        <div>{detail.roles.filter((role) => role.explicitlyRequested && role.id !== detail.ownerRoleId).map((role) => <label key={role.id} className={styles.choice}>
          <Checkbox checked={waived.includes(role.id)} disabled={action.busy} onCheckedChange={(checked) => {
            setRevision((value) => value ?? detail.task.revision);
            setWaived((current) => checked ? [...current, role.id] : current.filter((id) => id !== role.id));
          }}/>{role.name}</label>)}</div>
      </Field>}
      <MutationFeedback action={action} onReload={() => {
        setRevision(null);
        onChanged();
      }}/><div className={styles.formFooter}><Button className={ui.primary} disabled={action.busy || !text.trim()}><IconSend size={16}/>{t(action.busy ? "正在提交…" : completed ? "发送并继续" : answering ? "提交回答并继续" : "提交补充要求")}</Button>
        {detail.task.status !== "waiting_input" && <Button type="button" className={ui.button} disabled={action.busy} onClick={() => setEditing(false)}>{t("收起")}</Button>}
      </div>
    </DialogForm>}</AnimatedHeight>
    {cancelRevision !== null && <Dialog title={t("停止协作任务")} icon={<IconPlayerPause size={22}/>} onClose={() => setCancelRevision(null)} busy={action.busy}>
      <div className={styles.confirmTarget}><strong>{detail.task.shortCode}</strong><p>{detail.task.title}</p></div>
      <Guidance title={t("停止后不再安排后续工作")} tone="warning" icon={<IconAlertCircle size={19}/>}>
        <p>{t("已生成的成果会保留，已经完成的外部操作不会自动撤销。")}</p>
      </Guidance><MutationFeedback action={action}/>
      <DialogActions><DialogCancel className={ui.button} disabled={action.busy}>{t("返回")}</DialogCancel><Button className={ui.danger} disabled={action.busy} onClick={() => void action.execute(async () => {
        await action.submit(`${path}/cancel`, {expectedRevision: cancelRevision, reason: "用户在任务详情停止任务"});
        setCancelRevision(null);
        onChanged();
      }, t("已请求停止任务。"))}>{t("停止任务")}</Button></DialogActions>
    </Dialog>}
  </DetailSection></>;
}
