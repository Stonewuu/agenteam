"use client";

import {useState} from "react";
import {Field} from "@/components/ui/field";
import {Input} from "@/components/ui/input";
import {SearchableSelect} from "@/components/ui/searchable-select";
import {QueryState, Pagination} from "@/components/ui/query-state";
import {DetailSection} from "@/components/ui/detail-section";
import {IconCalendar, IconChartBar, IconListDetails, IconRobot, IconUser} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import {useApiPage, useApiQuery} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {ModelUsageSummaryCards} from "./model-usage-summary";
import {ModelCallList} from "./model-call-list";
import {tokenCount, usageAmount} from "../lib/model-usage-display";
import type {ModelUsageSummary, ModelCall} from "../types/model-usage";
import ui from "@/components/ui/surface.module.css";
import styles from "./model-usage.module.css";

type Option = {id: string; name: string};

function localDate(value: Date) {
  return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, "0")}-${String(value.getDate()).padStart(2, "0")}`;
}

export function ModelUsagePanel({enterpriseId, refresh}: {enterpriseId: string; refresh: number}) {
  const t = useT();
  const [range, setRange] = useState(() => {
    const now = new Date();
    return {from: localDate(new Date(now.getFullYear(), now.getMonth(), 1)), to: localDate(now)};
  });
  const [model, setModel] = useState<Option | null>(null);
  const [user, setUser] = useState<Option | null>(null);
  const start = new Date(`${range.from}T00:00:00`);
  const end = new Date(`${range.to}T00:00:00`);
  end.setDate(end.getDate() + 1);
  const validDates = Number.isFinite(start.getTime()) && Number.isFinite(end.getTime()) && end > start;
  const withinLimit = validDates && end.getTime() - start.getTime() <= 93 * 86400000;
  const valid = validDates && withinLimit;
  const rangeError = !validDates ? t("请选择有效的开始与结束日期。") : !withinLimit ? t("单次最多查询 93 天，请缩小日期范围。") : undefined;
  const dates = new URLSearchParams(valid ? {from: start.toISOString(), to: end.toISOString(), timezone: Intl.DateTimeFormat().resolvedOptions().timeZone} : {});
  const query = new URLSearchParams(dates);
  if (model) {
    query.set("modelProfileId", model.id);
  }
  if (user) {
    query.set("userId", user.id);
  }
  const base = organizationPath(enterpriseId, "/usage");
  const summary = useApiQuery<ModelUsageSummary>(valid ? `${base}/models?${query}&groupBy=day` : null, refresh);
  const calls = useApiPage<ModelCall>(`${base}/model-calls?${query}`, refresh, 200, 30, valid);
  return <div className={styles.stack}>
    <div className={styles.filters}>
      <Field label={t("开始日期")} icon={<IconCalendar size={16}/>}><Input className={ui.input} type="date" value={range.from}
        onChange={(event) => setRange({...range, from: event.target.value})}/></Field>
      <Field label={t("结束日期")} icon={<IconCalendar size={16}/>} error={rangeError}><Input className={ui.input} type="date" value={range.to}
        onChange={(event) => setRange({...range, to: event.target.value})}/></Field>
      <UsageOptionPicker path={valid ? `${base}/model-options?${dates}&kind=model` : null} kind="model" value={model} onChange={setModel}/>
      <UsageOptionPicker path={valid ? `${base}/model-options?${dates}&kind=user` : null} kind="user" value={user} onChange={setUser}/>
    </div>
    {valid && <QueryState {...summary} hasData={Boolean(summary.data && summary.data.totals.calls !== "0")}
      empty={t("所选范围内还没有模型用量记录。") }>
      {summary.data && <div className={styles.stack}><ModelUsageSummaryCards totals={summary.data.totals}/>
        {summary.data.days.length > 1 && <DetailSection title={t("每日用量")} icon={<IconChartBar size={20}/>}>
          <div className={styles.tableScroll}><table className={styles.table}><thead><tr><th>{t("日期")}</th><th>{t("输入")}</th><th>{t("输出")}</th><th>{t("已完整计价金额")}</th></tr></thead><tbody>
            {summary.data.days.map((day) => <tr key={day.date}><td>{day.date}</td><td>{tokenCount(day.totals.tokens.input?.value, t.formatLocale) ?? t("未返回")}</td>
              <td>{tokenCount(day.totals.tokens.output?.value, t.formatLocale) ?? t("未返回")}</td><td>
                {day.totals.amounts.some((amount) => amount.completeAmount !== null) ? day.totals.amounts.filter((amount) => amount.completeAmount !== null)
                  .map((amount) => <p key={amount.currency} title={amount.completeAmount!}>{usageAmount(amount.completeAmount!, t.formatLocale)} {amount.currency}</p>) : t("未计价")}
              </td></tr>)}
          </tbody></table></div>
        </DetailSection>}
        <DetailSection title={t("调用明细")} icon={<IconListDetails size={20}/>}>
          <QueryState {...calls} hasData={Boolean(calls.data?.items.length)} empty={t("没有匹配的调用记录。") }>
            <ModelCallList calls={calls.data?.items ?? []}/>
          </QueryState>
          <Pagination {...calls} hasMore={calls.data?.hasMore}/>
        </DetailSection>
      </div>}
    </QueryState>}
  </div>;
}

function UsageOptionPicker({path, kind, value, onChange}: {path: string | null; kind: "model" | "user"; value: Option | null; onChange: (value: Option | null) => void}) {
  const t = useT();
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState(false);
  const options = useApiQuery<Option[]>(path && open ? `${path}&query=${encodeURIComponent(query)}` : null, 0, 250);
  return <Field label={kind === "model" ? t("模型") : t("用户")} icon={kind === "model" ? <IconRobot size={16}/> : <IconUser size={16}/>}>
    <SearchableSelect options={(options.data ?? []).map((option) => ({value: option.id, label: option.name}))}
      value={value ? {value: value.id, label: value.name} : null} disabled={!path}
      onChange={(option) => onChange(option ? {id: option.value, name: option.label} : null)}
      query={query} onQueryChange={setQuery} open={open} onOpenChange={setOpen}
      resetLabel={kind === "model" ? t("全部模型") : t("全部用户")}
      searchLabel={kind === "model" ? t("查找模型") : t("查找用户")}
      emptyLabel={t("没有匹配的记录。")}
      loading={options.loading} error={options.error} onRetry={options.retry}/>
  </Field>;
}
