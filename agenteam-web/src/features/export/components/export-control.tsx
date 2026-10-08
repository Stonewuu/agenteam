"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Button} from "@/components/ui/button";

import {useEffect, useState, useSyncExternalStore} from "react";
import {Dialog, DialogActions, DialogCancel} from "@/components/ui/dialog";
import {QueryState} from "@/components/ui/query-state";
import {IconDownload} from "@/components/ui/icons";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {downloadFile} from "@/features/file/api/file-api";
import type {ExportJob} from "../types/export";
import ui from "@/components/ui/surface.module.css";

const statuses: Record<ExportJob["status"], string> = {
  queued: "排队中",
  leased: "正在导出",
  completed: "文件已生成",
  failed: "未能完成导出",
  cancelled: "导出已取消",
  expired: "文件已过期"
};

function subscribe(listener: () => void) {
  window.addEventListener("storage", listener);
  window.addEventListener("agenteam:exports-changed", listener);
  return () => {
    window.removeEventListener("storage", listener);
    window.removeEventListener("agenteam:exports-changed", listener);
  };
}

function savedId(key: string) {
  try {
    const value = window.localStorage.getItem(key);
    return value && /^[A-Za-z0-9_-]{1,100}$/.test(value) ? value : null;
  } catch {
    return null;
  }
}

/** 本地只记住本人的任务编号，刷新后仍通过服务端重新判断是否可读。 */
export function ExportControl({enterpriseId, userId, path, body, title, description}: {
  enterpriseId: string;
  userId: string;
  path: string;
  body?: Record<string, unknown>;
  title: string;
  description: string;
}) {
  const uiText = useT();
  const storageKey = `agenteam:export:${userId}:${path}`;
  const remembered = useSyncExternalStore(subscribe, () => savedId(storageKey), () => null);
  const [recent, setRecent] = useState<string | null>(null);
  const last = recent ?? remembered;
  const [open, setOpen] = useState<{ jobId: string | null } | null>(null);
  const created = (id: string) => {
    setRecent(id);
    try {
      window.localStorage.setItem(storageKey, id);
      window.dispatchEvent(new Event("agenteam:exports-changed"));
    } catch { /* 当前页面仍保留任务编号。 */
    }
  };
  return <div className={ui.actions}><Button type="button" className={ui.button} onClick={() => setOpen({jobId: null})}><IconDownload
    size={17}/>{uiText("导出")}</Button>
    {last && <Button type="button" className={ui.button}
                     onClick={() => setOpen({jobId: last})}>{uiText("查看上次导出")}</Button>}
    {open && <ExportDialog enterpriseId={enterpriseId} path={path} body={body} title={title} description={description}
                           initialJobId={open.jobId} onCreated={created} onClose={() => setOpen(null)}/>}
  </div>;
}

function ExportDialog({enterpriseId, path, body, title, description, initialJobId, onCreated, onClose}: {
  enterpriseId: string;
  path: string;
  body?: Record<string, unknown>;
  title: string;
  description: string;
  initialJobId: string | null;
  onCreated: (id: string) => void;
  onClose: () => void;
}) {
  const uiText = useT();
  const action = useFormAction();
  const [jobId, setJobId] = useState(initialJobId);
  return <Dialog title={title} onClose={onClose} busy={action.busy}>
    {jobId ? <ExportResult key={jobId} enterpriseId={enterpriseId} id={jobId} onNew={() => {
      action.resetFeedback();
      setJobId(null);
    }}/> : <>
      <p className={ui.description}>{description}</p><MutationFeedback action={action} showFieldErrors/>
      <DialogActions className={ui.footer}><DialogCancel className={ui.button} type="button"
                                                         disabled={action.busy}>{uiText("取消")}</DialogCancel>
        <Button className={ui.primary} type="button" disabled={action.busy}
                onClick={() => void action.execute(async () => {
                  const value = await action.mutation.run<ExportJob>(path, {method: "POST", ...(body ? {body} : {})});
                  onCreated(value.id);
                  setJobId(value.id);
                }, "")}>{action.busy ? uiText("正在提交…") : uiText("开始导出")}</Button></DialogActions>
    </>}
  </Dialog>;
}

function ExportResult({enterpriseId, id, onNew}: { enterpriseId: string; id: string; onNew: () => void }) {
  const uiText = useT();
  const [refresh, setRefresh] = useState(0);
  const query = useApiQuery<ExportJob>(organizationPath(enterpriseId, `/jobs/${encodeURIComponent(id)}`), refresh);
  const action = useFormAction();
  const value = query.data;
  const waiting = value?.status === "queued" || value?.status === "leased";
  useEffect(() => {
    if (!waiting || query.loading || query.error) {
      return;
    }
    const timer = window.setTimeout(() => setRefresh((before) => before + 1), 2000);
    return () => window.clearTimeout(timer);
  }, [waiting, query.loading, query.error, value]);
  return <><QueryState {...query} hasData={Boolean(value)} empty={uiText("无法读取此导出。")}>
    {value && value.kind !== "export" && <p className={ui.error} role="alert">{uiText("无法读取此导出。")}</p>}
    {value?.kind === "export" &&
      <div className={ui.form}><p role="status">{localizeCatalog(statuses, uiText)[value.status]}</p>
        {waiting && <p className={ui.description}>{uiText("可以关闭此窗口，稍后查看上次导出。")}</p>}
        {value.errorSummary && <p className={ui.error}>{value.errorSummary}</p>}
        {value.status === "expired" && <p className={ui.description}>{uiText("如需文件，请重新导出。")}</p>}
        {value.rowCount !== null && <p
          className={ui.description}>{uiText("共 ")}{value.rowCount.toLocaleString(uiText.formatLocale)}{uiText(" 行")}</p>}
        {value.snapshotAt &&
          <p className={ui.description}>{uiText("内容截至 ")}<EnterpriseDateTime value={value.snapshotAt}/></p>}
        {value.expiresAt && value.status === "completed" &&
          <p className={ui.description}>{uiText("可下载至 ")}<EnterpriseDateTime value={value.expiresAt}/></p>}
        {value.status === "completed" && value.resultFileId &&
          <Button className={ui.primary} type="button" disabled={action.busy}
                  onClick={() => void action.execute(async () => {
                    try {
                      await downloadFile(enterpriseId, value.resultFileId!);
                    } finally {
                      setRefresh((before) => before + 1);
                    }
                  }, "")}>{action.busy ? uiText("正在下载…") : uiText("下载文件")}</Button>}
      </div>}
  </QueryState><MutationFeedback action={action}/>
    <div className={ui.footer}><DialogCancel className={ui.button} type="button"
                                             disabled={action.busy}>{uiText("关闭")}</DialogCancel>
      {!waiting && <Button className={ui.button} type="button" disabled={query.loading || action.busy}
                           onClick={onNew}>{uiText("重新导出")}</Button>}</div>
  </>;
}
