"use client";

import {useEffect, useId, useRef, useState} from "react";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {Button} from "@/components/ui/button";
import {Dialog, DialogActions, DialogCancel, useDialogControl} from "@/components/ui/dialog";
import {Field} from "@/components/ui/field";
import {Fieldset} from "@/components/ui/fieldset";
import {Input} from "@/components/ui/input";
import {Textarea} from "@/components/ui/textarea";
import {Select} from "@/components/ui/select";
import {Toggle} from "@/components/ui/toggle";
import {QueryState} from "@/components/ui/query-state";
import {DetailHeading, DetailSection} from "@/components/ui/detail-section";
import {IconBell, IconCalendar, IconCalendarClock, IconRobot, IconCheck} from "@/components/ui/icons";
import {EmployeePicker} from "@/features/agent/components/employee-picker";
import type {Employee} from "@/features/employee/types/employee";
import {EmployeeIdentity} from "@/features/employee/components/employee-identity";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useBlockNavigation} from "@/features/workspace/components/navigation-guard";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import type {Schedule, ScheduleActionOption, ScheduleTimes, ScheduleWrite} from "../types/schedule";
import {initialScheduleForm, scheduleFormRequest, type ScheduleForm} from "../lib/schedule-form";
import {ScheduleTimesPreview} from "./schedule-times";
import {ScheduleRuleFields} from "./schedule-rule-fields";
import {ScheduleRecipientPicker} from "./schedule-recipient-picker";
import {ScheduleContent, ScheduleRecipients} from "./schedule-presentation";
import {useScheduleTimes} from "../hooks/use-schedule-times";
import ui from "@/components/ui/surface.module.css";
import styles from "./schedule.module.css";
import presentation from "./schedule-presentation.module.css";

export function ScheduleEditor({enterpriseId, timezone, initial, employee, showMarket, onClose, onSaved, onReload}: {
  enterpriseId: string; timezone: string; initial: Schedule | null; employee?: Employee; showMarket: boolean;
  onClose: () => void; onSaved: (value: Schedule) => void; onReload: () => void;
}) {
  const t = useT();
  const directory = useApiQuery<ScheduleActionOption[]>(organizationPath(enterpriseId, "/schedule-actions"));
  const [original, setOriginal] = useState(() => initialScheduleForm(initial, timezone, employee));
  const [form, setForm] = useState(original);
  const [initialized, setInitialized] = useState(false);
  const [selectedEmployee, setSelectedEmployee] = useState(() => ({name: initial?.agentName ?? employee?.name ?? "",
    icon: initial?.agentIcon ?? employee?.icon, color: initial?.agentColor ?? employee?.color}));
  const [picker, setPicker] = useState(false);
  const [confirmClose, setConfirmClose] = useState(false);
  const [preview, setPreview] = useState<{body: ScheduleWrite; times: ScheduleTimes} | null>(null);
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({});
  const action = useFormAction();
  const formId = useId();
  const dialog = useDialogControl();
  const stepRoot = useRef<HTMLDivElement>(null);
  const previewHeading = useRef<HTMLDivElement>(null);
  const showingPreview = Boolean(preview);
  useEffect(() => {
    // 切换步骤后从顶部核对内容，避免沿用长表单的滚动位置而错过计划摘要。
    const body = stepRoot.current?.closest<HTMLElement>(".modal-body");
    body?.scrollTo({top: 0, behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "instant" : "smooth"});
    const target = showingPreview ? previewHeading.current : document.getElementById(formId)?.querySelector<HTMLElement>('input[name="name"]');
    target?.focus({preventScroll: true});
  }, [showingPreview, formId]);
  const liveTimes = useScheduleTimes(enterpriseId, form);
  const dirty = JSON.stringify(form) !== JSON.stringify(original);
  useBlockNavigation(dirty, action.busy);
  const choices = directory.data?.filter(value => ["agent.run", "notification.send"].includes(value.type) && value.schemaVersions.includes(1)) ?? [];
  const supported = !initial || ["agent.run", "notification.send"].includes(initial.action.type);
  if (!initialized && directory.data) {
    setInitialized(true);
    if (!initial && !choices.some(value => value.type === form.actionType) && choices.length) {
      const next = {...form, actionType: choices[0].type as ScheduleForm["actionType"]};
      setForm(next);
      setOriginal(next);
    }
  }
  const fieldKeys: Partial<Record<keyof ScheduleForm, string>> = {actionType: "action.type", hireId: "action.config.hireId",
    inputText: "action.config.inputText", title: "action.config.title", body: "action.config.body", recipients: "action.config.recipients"};
  function error(field: string) {
    return localErrors[field] || Object.entries(action.fieldErrors).filter(([key]) => key === field || key.startsWith(`${field}.`))
      .flatMap(([, messages]) => messages).join(" ");
  }
  function change(patch: Partial<ScheduleForm>) {
    setForm(previous => ({...previous, ...patch}));
    const cleared = Object.keys(patch).map(key => fieldKeys[key as keyof ScheduleForm] ?? key);
    setLocalErrors(previous => Object.fromEntries(Object.entries(previous).filter(([key]) => !cleared.includes(key))));
    for (const key of Object.keys(action.fieldErrors)) {
      if (cleared.some(field => key === field || key.startsWith(`${field}.`))) {
        action.clearFieldError(key);
      }
    }
  }
  function showTimes() {
    const problems: Record<string, string> = {};
    if (!choices.some(choice => choice.type === form.actionType)) {
      problems["action.type"] = t("请选择当前可用的操作。");
    }
    if (form.actionType === "agent.run" && !form.hireId) {
      problems["action.config.hireId"] = t("请选择一位可使用的已雇佣员工。");
    }
    if (form.actionType === "notification.send" && !form.recipients.length) {
      problems["action.config.recipients"] = t("请至少选择一位接收人。");
    }
    if (form.frequency === "weekly" && !form.weekdays.length) {
      problems.weekdays = t("请至少选择一个执行日。");
    }
    if (form.frequency === "once" && !/^\d{4}-\d{2}-\d{2}$/.test(form.localDate ?? "")) {
      problems.localDate = t("请填写完整的执行日期。");
    }
    setLocalErrors(problems);
    if (Object.keys(problems).length) {
      requestAnimationFrame(() => document.getElementById(formId)?.querySelector<HTMLElement>(`[name="${Object.keys(problems)[0]}"]`)?.focus());
      return;
    }
    void action.execute(async () => {
      const body = scheduleFormRequest(form);
      const {frequency, localDate, localTime, weekdays, monthDay, timezone} = body;
      const times = await action.mutation.run<ScheduleTimes>(organizationPath(enterpriseId, "/schedules/preview-times"), {
        method: "POST", body: {frequency, localDate, localTime, weekdays, monthDay, timezone},
      });
      if (body.enabled && !times.times.length) {
        throw new Error(t("该时间已经过去，请调整执行时间后再启用计划。"));
      }
      setPreview({body, times});
    }, "");
  }
  return <Dialog title={preview ? t("确认计划") : initial ? t("编辑计划") : t("创建计划")}
    icon={preview ? <IconCheck size={21}/> : <IconCalendarClock size={21}/>} onClose={onClose} dialogRef={dialog.ref}
    onRequestClose={() => {
      if (dirty) {
        setConfirmClose(true);
        return false;
      }
      return true;
    }} busy={action.busy} wide footer={preview ? <>
      <Button className={ui.button} disabled={action.busy} onClick={() => {
        setPreview(null);
        action.resetFeedback();
      }}>{t("返回修改")}</Button>
      <Button className={ui.primary} disabled={action.busy} onClick={() => void action.execute(async () => {
        const result = await action.mutation.run<Schedule>(organizationPath(enterpriseId, `/schedules${initial ? `/${encodeURIComponent(initial.id)}` : ""}`),
          {method: initial ? "PUT" : "POST", revision: initial?.revision, body: preview.body});
        dialog.close(() => onSaved(result));
      }, "")}>{action.busy ? t("正在保存…") : t("确认保存")}</Button>
    </> : <>
      <DialogCancel className={ui.button} disabled={action.busy}>{t("取消")}</DialogCancel>
      <Button form={formId} type="submit" className={ui.primary} disabled={action.busy || !directory.data || !choices.length || !supported}>
        {action.busy ? t("正在计算…") : t("查看执行时间")}</Button>
    </>}>
    <div ref={stepRoot}><AnimatedHeight preserveControlShadows>
      <QueryState {...directory} hasData={Boolean(choices.length && supported)} empty={supported ? t("当前没有可创建的定时操作。") : t("此操作暂不支持在当前页面编辑。")}>
        {preview ? <div>
          <div ref={previewHeading} tabIndex={-1} className={presentation.confirmHeading}><DetailHeading level="h2"
            icon={preview.body.action.type === "notification.send" ? <IconBell size={21}/> : <IconRobot size={21}/>}
            title={preview.body.name} description={preview.body.action.type === "notification.send" ? t("发送通知") : t("执行智能体任务")}/></div>
          <div className={presentation.confirmationGrid}><div className={presentation.stack}>
            {preview.body.action.type === "agent.run" ? <>
              <DetailSection icon={<IconRobot size={19}/>} title={t("执行员工")}><EmployeeIdentity {...selectedEmployee}/></DetailSection>
              <ScheduleContent notification={false} body={preview.body.action.config.inputText}/>
            </> : <>
              <ScheduleContent notification title={preview.body.action.config.title} body={preview.body.action.config.body}/>
              <ScheduleRecipients recipients={form.recipients}/>
            </>}
          </div><ScheduleTimesPreview value={preview.times}/></div>
          {!preview.body.enabled && <p className={ui.notice}>{t("保存后暂不启用。")}</p>}
          <MutationFeedback action={action} onReload={() => dialog.close(onReload)} showFieldErrors/>
        </div> : <div className={styles.editorGrid}>
          <form id={formId} className={ui.form} onSubmit={event => {
            event.preventDefault();
            showTimes();
          }}><Fieldset className={`${ui.form} ${styles.fields}`} disabled={action.busy}>
            <DetailHeading icon={<IconCalendar size={18}/>} title={t("计划设置")}/>
            <Field label={t("计划名称")} required error={error("name")}><Input name="name" value={form.name} maxLength={80} required
              onChange={event => change({name: event.target.value})}/></Field>
            <Field label={t("定时操作")} required error={error("action.type")}><Select name="action.type" value={form.actionType}
              onChange={event => change({actionType: event.target.value as ScheduleForm["actionType"]})}>
              {!choices.some(choice => choice.type === form.actionType) && <option value={form.actionType} disabled>{initial?.action.name ?? t("请选择操作")}</option>}
              {choices.map(choice => <option key={choice.type} value={choice.type}>{t(choice.name)}</option>)}
            </Select></Field>
            <AnimatedHeight preserveControlShadows>
              {form.actionType === "agent.run" ? <div className={ui.form}>
                <Field label={t("执行员工")} required error={error("action.config.hireId")}>
                  <Button className={ui.button} name="action.config.hireId" type="button" onClick={() => setPicker(true)}>
                    {selectedEmployee.name ? <EmployeeIdentity {...selectedEmployee}/> : t("选择员工")}
                  </Button>
                </Field>
                <Field label={t("任务内容")} required error={error("action.config.inputText")}><Textarea name="action.config.inputText"
                  value={form.inputText} required maxLength={20000} rows={6} onChange={event => change({inputText: event.target.value})}/></Field>
                <Field label={t("失败后自动再试")} error={error("maxRetries")}><Select name="maxRetries" value={form.maxRetries}
                  onChange={event => change({maxRetries: Number(event.target.value)})}>
                  <option value={0}>{t("不再试")}</option><option value={1}>{t("最多 1 次")}</option><option value={2}>{t("最多 2 次")}</option>
                </Select></Field>
              </div> : <div className={ui.form}>
                <Field label={t("通知标题")} required error={error("action.config.title")}><Input name="action.config.title" required
                  maxLength={100} value={form.title} onChange={event => change({title: event.target.value})}/></Field>
                <Field label={t("通知内容")} error={error("action.config.body")}><Textarea name="action.config.body" maxLength={500}
                  rows={4} value={form.body} onChange={event => change({body: event.target.value})}/></Field>
                <ScheduleRecipientPicker enterpriseId={enterpriseId} selected={form.recipients} disabled={action.busy}
                  error={error("action.config.recipients")} onChange={recipients => change({recipients})}/>
              </div>}
            </AnimatedHeight>
            <div className={styles.formDivider}><DetailHeading icon={<IconCalendarClock size={18}/>} title={t("执行安排")}/></div>
            <ScheduleRuleFields form={form} change={change} error={error}/>
            <Toggle label={t("保存后启用计划")} checked={form.enabled} disabled={action.busy} onChange={enabled => change({enabled})}/>
            <MutationFeedback action={action} onReload={() => dialog.close(onReload)}/>
          </Fieldset></form>
          <aside className={styles.timePreview} aria-live="polite"><AnimatedHeight preserveControlShadows>
            {liveTimes.data ? <ScheduleTimesPreview value={liveTimes.data}/> : <><h3>{t("接下来的执行时间")}</h3>
              <p className={ui.description}>{liveTimes.loading ? t("正在计算…") : liveTimes.error || t("填写执行规则后查看时间。")}</p></>}
          </AnimatedHeight></aside>
        </div>}
      </QueryState>
    </AnimatedHeight></div>
    {picker && <EmployeePicker enterprise={enterpriseId} title={t("选择计划员工")} showMarket={showMarket} onClose={() => setPicker(false)}
      onSelect={value => {
        if (!value.canRun || !value.hireId) {
          return;
        }
        setSelectedEmployee({name: value.name, icon: value.icon, color: value.color});
        change({hireId: value.hireId, agentVersionId: initial?.hireId === value.hireId ? initial.agentVersionId : null});
        setPicker(false);
      }}/>}
    {confirmClose && <Dialog variant="discard" title={t("放弃未保存的计划修改？")} onClose={() => setConfirmClose(false)}>
      <DialogActions><DialogCancel className={ui.button}>{t("继续编辑")}</DialogCancel><DialogCancel className={ui.danger}
        onClick={() => dialog.close(onClose)}>{t("放弃修改")}</DialogCancel></DialogActions>
    </Dialog>}
  </Dialog>;
}
