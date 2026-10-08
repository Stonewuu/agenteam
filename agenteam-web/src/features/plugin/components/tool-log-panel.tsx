"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Button} from "@/components/ui/button";

import {PageHeader} from "@/components/ui/page-header";

import {Select} from "@/components/ui/select";


import {useState} from "react";
import Link from "next/link";
import {Dialog} from "@/components/ui/dialog";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {useApiPage, useApiQuery} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {downloadFile} from "@/features/file/api/file-api";
import {enterprisePath} from "@/features/auth/lib/identity-navigation";
import {HistoryTimeFilter, recentTimeRange} from "@/features/workspace/components/history-time-filter";
import {ExportControl} from "@/features/export/components/export-control";
import type {ToolCall, ToolCallDetails as ToolCallDetailsResponse} from "../types/plugin";
import ui from "@/components/ui/surface.module.css";
import styles from "./plugin.module.css";
import {SearchInput} from "@/components/ui/search-input";
import {IconRefresh} from "@/components/ui/icons";
import {ToolCallPresentation} from "./tool-call-presentation";
import {ToolCallDetails} from "./tool-call-details";
import {payloadTextPart} from "../lib/tool-payload-format";

const states: Record<ToolCall["status"], string> = {
  prepared: "已准备",
  waiting_approval: "等待确认",
  running: "正在调用",
  succeeded: "已完成",
  failed: "调用失败",
  cancelled: "已取消",
  unknown: "结果待核对"
};
const sources: Record<NonNullable<ToolCall["source"]>, string> = {
  interactive: "对话任务",
  preview: "调试任务",
  scheduled: "计划自动运行",
  manual_schedule: "手动触发计划"
};

export function ToolLogPanel({enterpriseId, userId, permissions, query, onQuery}: {
  enterpriseId: string;
  userId: string;
  permissions: string[];
  query: string;
  onQuery: (value: string) => void
}) {
  const uiText = useT();
  const [selected, setSelected] = useState<string | null>(null);
  const [days, setDays] = useState(7);
  const [range, setRange] = useState(() => recentTimeRange(7));
  const [status, setStatus] = useState("");
  const [source, setSource] = useState("");
  const [actor, setActor] = useState<ToolCall["actor"] | null>(null);
  const params = new URLSearchParams({...range, query});
  if (status) {
    params.set("status", status);
  }
  if (source) {
    params.set("source", source);
  }
  if (actor) {
    params.set("actorUserId", actor.id);
  }
  const list = useApiPage<ToolCall>(organizationPath(enterpriseId, `/tool-calls?${params}`));
  return <>
    <PageHeader title={uiText("调用日志")} description={uiText("查看工具调用的实际结果与操作记录。")}
                actions={<>{permissions.includes("tool_log.export") &&
                  <ExportControl key={`${userId}:${enterpriseId}`} enterpriseId={enterpriseId} userId={userId}
                                 path={organizationPath(enterpriseId, "/tool-calls/export")}
                                 body={{
                                   ...range,
                                   query,
                                   actorUserId: actor?.id ?? null,
                                   status: status || null,
                                   source: source || null
                                 }} title={uiText("导出调用记录")}
                                 description={uiText("将当前筛选范围内的调用摘要保存为表格文件。")}/>}</>}/>
    <div className={`${styles.toolbar} ${styles.logFilters}`}><HistoryTimeFilter compact days={days}
                                                                                 onChange={(value) => {
                                                                                   setDays(value);
                                                                                   setRange(recentTimeRange(value));
                                                                                 }}/>
      <label className={ui.field}><span className="sr-only">{uiText("调用状态")}</span><Select className={ui.select}
                                                                                               value={status}
                                                                                               onChange={(event) => setStatus(event.target.value)}>
        <option value="">{uiText("全部状态")}</option>
        {Object.entries(localizeCatalog(states, uiText)).map(([value, label]) => <option key={value}
                                                                                         value={value}>{label}</option>)}
      </Select></label>
      <label className={ui.field}><span className="sr-only">{uiText("运行来源")}</span><Select className={ui.select}
                                                                                               value={source}
                                                                                               onChange={(event) => setSource(event.target.value)}>
        <option value="">{uiText("全部来源")}</option>
        {Object.entries(localizeCatalog(sources, uiText)).map(([value, label]) => <option key={value}
                                                                                          value={value}>{label}</option>)}
      </Select></label>
      {actor && <Button className={ui.button}
                        onClick={() => setActor(null)}>{actor.displayName}{uiText(" · 清除成员筛选")}</Button>}
      <SearchInput value={query} maxLength={100} aria-label={uiText("搜索工具或成员")}
                   placeholder={uiText("搜索工具或成员…")} onChange={(event) => onQuery(event.target.value)}/>
      <Button className="icon-button" aria-label={uiText("刷新调用日志")}
              onClick={() => setRange(recentTimeRange(days))}><IconRefresh size={18}/></Button>
    </div>
    <QueryState {...list} hasData={Boolean(list.data?.items.length)} empty={uiText("没有匹配的调用记录。")}>
      <div className={styles.tableWrap}>
        <table className={styles.table}>
          <thead>
          <tr>
            <th>{uiText("工具")}</th>
            <th>{uiText("发起成员")}</th>
            <th>{uiText("运行来源")}</th>
            <th>{uiText("结果")}</th>
            <th>{uiText("耗时")}</th>
            <th>{uiText("时间")}</th>
            <th>{uiText("操作")}</th>
          </tr>
          </thead>
          <tbody>{list.data?.items.map((call) => <tr key={call.id}>
            <td>{call.toolName}</td>
            <td><Button className="link-button" aria-label={uiText("只看{0}发起的调用", [call.actor.displayName])}
                        onClick={() => setActor(call.actor)}>{call.actor.displayName}</Button></td>
            <td>{call.source ? localizeCatalog(sources, uiText)[call.source] : "—"}</td>
            <td>{localizeCatalog(states, uiText)[call.status]}{call.errorSummary &&
              <span className={styles.secondary}>{call.errorSummary}</span>}</td>
            <td>{call.durationMs === null ? "—" : uiText("{0} 秒", [(call.durationMs / 1000).toFixed(2)])}</td>
            <td><EnterpriseDateTime value={call.createdAt} compact/></td>
            <td>
              <div className={ui.actions}>{call.canViewDetails && <Button className="link-button"
                                                                          onClick={() => setSelected(call.id)}>{uiText("查看详情")}</Button>}<ConversationLink
                enterpriseId={enterpriseId} call={call}/></div>
            </td>
          </tr>)}</tbody>
        </table>
      </div>
    </QueryState><Pagination {...list} hasMore={list.data?.hasMore}/>
    {selected && <Details enterpriseId={enterpriseId} userId={userId} id={selected} onClose={() => setSelected(null)}/>}
  </>;
}

function ConversationLink({enterpriseId, call}: { enterpriseId: string; call: ToolCall }) {
  const uiText = useT();
  return call.conversationId ? <Link className={ui.button}
                                     href={enterprisePath(enterpriseId, `/conversations/${encodeURIComponent(call.conversationId)}`)}>{uiText("查看对话")}</Link> : null;
}

function Details({enterpriseId, userId, id, onClose}: {
  enterpriseId: string;
  userId: string;
  id: string;
  onClose: () => void
}) {
  const uiText = useT();
  const details = useApiQuery<ToolCallDetailsResponse>(organizationPath(enterpriseId, `/tool-calls/${encodeURIComponent(id)}`));
  const download = useFormAction();
  const value = details.data;
  const artifact = value?.result?.truncated === true && typeof value.result.fileId === "string" ? value.result.fileId : null;
  const output = value?.result ? JSON.stringify(value.result, null, 2) : "";
  const errorSummary = value?.call.errorSummary?.trim();
  const resultContent = value?.result?.content;
  const message = typeof resultContent === "string" ? resultContent : Array.isArray(resultContent)
    ? resultContent.map(payloadTextPart).filter((text) => text !== null).join("\n\n") : "";
  return <Dialog title={value?.call.toolName ?? uiText("调用详情")} onClose={onClose} drawer><QueryState {...details}
                                                                                                         hasData={Boolean(value)}
                                                                                                         empty={uiText("此记录当前不可读取。")}>
    {value && <div className={ui.form}>
      <p>{value.call.actor.displayName} · {localizeCatalog(states, uiText)[value.call.status]} · <EnterpriseDateTime
        value={value.call.createdAt}/></p><ConversationLink enterpriseId={enterpriseId} call={value.call}/>
      <ToolCallPresentation><ToolCallDetails input={JSON.stringify(value.request, null, 2)} result={output}
                                             inputSource={{
                                               url: organizationPath(enterpriseId, `/tool-calls/${encodeURIComponent(id)}/content`),
                                               part: "input",
                                               revision: value.call.status
                                             }}
                                             resultSource={{
                                               url: organizationPath(enterpriseId, `/tool-calls/${encodeURIComponent(id)}/content`),
                                               part: "result",
                                               revision: value.call.status
                                             }}/></ToolCallPresentation>
      {artifact && value?.call.actor.id === userId && <Button className={ui.button} disabled={download.busy}
                                                              onClick={() => void download.execute(() => downloadFile(enterpriseId, artifact), "")}>{download.busy ? uiText("正在下载…") : uiText("下载完整结果")}</Button>}
      {errorSummary && message.trim() !== errorSummary && <p className={ui.error}>{errorSummary}</p>}{download.error &&
      <p className={ui.error} role="alert">{localizeUiMessage(download.error ?? "", uiText)}</p>}
    </div>}
  </QueryState></Dialog>;
}
