"use client";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {DetailSection} from "@/components/ui/detail-section";
import {LimitField as LimitInput} from "@/components/ui/limit-field";
import {IconDatabase, IconFileText, IconSettings} from "@/components/ui/icons";
import {PageHeader} from "@/components/ui/page-header";
import {QueryState} from "@/components/ui/query-state";
import {SystemManagementGate} from "@/features/auth/components/system-management-gate";
import {FormFeedback} from "@/features/auth/components/settings-forms";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {apiRequest} from "@/lib/http/api-client";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import {useBlockNavigation} from "./navigation-guard";
import type {WorkspaceLimits} from "../types/workspace-limits";
import styles from "./workspace-limits.module.css";
import ui from "@/components/ui/surface.module.css";

const endpoint = "/api/v1/system/workspace-limits";
type LimitField = "workspaceMb" | "fileMb" | "maximumFiles" | "commandResultsMb";

function draftOf(value: WorkspaceLimits): Record<LimitField, string> {
  return {
    workspaceMb: String(value.workspaceMb),
    fileMb: String(value.fileMb),
    maximumFiles: String(value.maximumFiles),
    commandResultsMb: String(value.commandResultsMb),
  };
}

export function WorkspaceLimitsPage() {
  const t = useT();
  return <SystemManagementGate title={t("工作空间设置")}>{() => <WorkspaceLimitsContent/>}</SystemManagementGate>;
}

function WorkspaceLimitsContent() {
  const t = useT();
  const query = useApiQuery<WorkspaceLimits>(endpoint);
  return <div className={styles.page}>
    <PageHeader title={t("工作空间设置")} icon={<IconSettings size={26} variant="Bulk"/>}
                description={t("设置每位用户的工作文件和命令输出上限。")}/>
    <QueryState {...query} hasData={Boolean(query.data)} empty={t("暂时无法读取工作空间设置。")}
                emptyContent={<Button type="button" className={ui.button} onClick={query.retry}>{t("重新加载")}</Button>}>
      {query.data && <WorkspaceLimitsForm initial={query.data}/>}
    </QueryState>
  </div>;
}

function WorkspaceLimitsForm({initial}: {initial: WorkspaceLimits}) {
  const t = useT();
  const [saved, setSaved] = useState(initial);
  const [draft, setDraft] = useState(() => draftOf(initial));
  const action = useFormAction();
  const dirty = (Object.keys(draft) as LimitField[]).some((field) => draft[field] !== String(saved[field]));
  useBlockNavigation(dirty, action.busy);

  function accept(value: WorkspaceLimits) {
    setSaved(value);
    setDraft(draftOf(value));
  }

  function field(name: LimitField, label: string, min: number, hint?: string) {
    return <LimitInput label={label} hint={hint} error={action.fieldErrors[name]?.join(" ")}
      name={name} minimum={min} value={draft[name]} disabled={action.busy} onChange={(value) => {
        setDraft((current) => ({...current, [name]: value}));
        action.clearFieldError(name);
      }}/>;
  }

  return <form className={styles.form} onSubmit={(event) => {
    event.preventDefault();
    if (!dirty) {
      return;
    }
    void action.execute(async () => {
      const result = await action.mutation.run<WorkspaceLimits>(endpoint, {
        method: "PUT", revision: saved.revision,
        body: Object.fromEntries(Object.entries(draft).map(([key, value]) => [key, Number(value)])),
      });
      accept(result);
    }, t("工作空间设置已保存。"));
  }}>
    <DetailSection icon={<IconFileText size={21}/>} title={t("工作文件")} level="h2">
      <div className={styles.fields}>
        {field("workspaceMb", t("工作空间容量（MB）"), 16)}
        {field("fileMb", t("单文件大小（MB）"), 1)}
        {field("maximumFiles", t("文件数量上限"), 10)}
      </div>
      <p className={styles.note}>{t("单文件大小修改后，已启动的环境需在「我的环境」停止并重新连接。")}</p>
    </DetailSection>
    <DetailSection icon={<IconDatabase size={21}/>} title={t("命令输出")} level="h2">
      <div className={styles.fields}>
        {field("commandResultsMb", t("命令输出容量（MB）"), 1)}
      </div>
    </DetailSection>
    <FormFeedback action={action} onReload={async () => accept(await apiRequest<WorkspaceLimits>(endpoint))}/>
    <div className={styles.actions}>
      <Button className={ui.primary} disabled={action.busy || !dirty}>{action.busy ? t("正在保存…") : t("保存设置")}</Button>
      <Button className={ui.button} type="button" disabled={action.busy || !dirty} onClick={(event) => {
        event.currentTarget.form?.reset();
        setDraft(draftOf(saved));
        action.resetFeedback();
      }}>{t("取消修改")}</Button>
    </div>
  </form>;
}
