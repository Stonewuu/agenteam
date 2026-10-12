"use client";

import {useRef, useState} from "react";
import {Field} from "@/components/ui/field";
import {DateRangePicker} from "@/components/ui/date-range-picker";
import {SearchableSelect} from "@/components/ui/searchable-select";
import {QueryState} from "@/components/ui/query-state";
import {Disclosure, DisclosureSummary} from "@/components/ui/disclosure";
import {DetailSection} from "@/components/ui/detail-section";
import {IconCalendar, IconChartBar, IconListDetails, IconRobot, IconUser} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import {useApiPage, useApiQuery} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {ModelUsageSummaryCards} from "./model-usage-summary";
import {ModelCallList} from "./model-call-list";
import {ModelCallPagination} from "./model-call-pagination";
import {tokenCount, usageAmount} from "../lib/model-usage-display";
import type {ModelUsageSummary, ModelCall} from "../types/model-usage";
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
  const [pageSize, setPageSize] = useState(10);
  const detail = useRef<HTMLDivElement>(null);
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
  const calls = useApiPage<ModelCall>(`${base}/model-calls?${query}`, refresh, 200, pageSize, valid);
  const total = summary.error ? undefined : summary.data?.totals.calls;
  const page = calls.previous.length + 1;
  function changePage(action: () => void) {
    action();
    detail.current?.scrollIntoView({block: "start", behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth"});
  }
  return <div className={styles.stack}>
    <div className={styles.filters}>
      <div className={styles.rangeFilter}><Field label={t("日期范围")} icon={<IconCalendar size={16}/>} error={rangeError}>
        <DateRangePicker value={range} onChange={setRange} maxDays={93}/>
      </Field></div>
      <UsageOptionPicker path={valid ? `${base}/model-options?${dates}&kind=model` : null} kind="model" value={model} onChange={setModel}/>
      <UsageOptionPicker path={valid ? `${base}/model-options?${dates}&kind=user` : null} kind="user" value={user} onChange={setUser}/>
    </div>
    {valid && <><QueryState {...summary} hasData={Boolean(summary.data)} loadingLayout="cards"
      empty={t("所选范围内还没有模型用量记录。") }>
      {summary.data && <ModelUsageSummaryCards totals={summary.data.totals}/>}
    </QueryState>
    <div ref={detail} className={styles.callSection}>
      <DetailSection title={t("调用明细")} icon={<IconListDetails size={20}/>} level="h2"
        description={t("每行对应一次模型调用，缓存读取已包含在输入中。")}
        action={<span className={styles.recordCount}>{total === undefined ? "" : t("共 {0} 条", [tokenCount(total, t.formatLocale) ?? "0"])}</span>}>
        <QueryState {...calls} hasData={Boolean(!calls.loading && !calls.error && calls.data?.items.length)}
          retry={() => {
            calls.first();
            calls.retry();
          }} empty={t("没有匹配的调用记录。") }>
          <ModelCallList calls={calls.data?.items ?? []}/>
        </QueryState>
        <ModelCallPagination page={page} pageSize={pageSize} total={total} count={calls.data?.items.length ?? 0}
          loading={calls.loading} error={calls.error} hasMore={Boolean(calls.data?.hasMore)}
          back={() => changePage(calls.back)} next={() => changePage(calls.next)}
          onPageSizeChange={(size) => changePage(() => setPageSize(size))}/>
      </DetailSection>
    </div>
    {summary.data && summary.data.days.length > 0 && <Disclosure className={styles.daily} animateContentHeight>
      <DisclosureSummary><IconChartBar size={18}/>{t("每日汇总")}</DisclosureSummary>
      <div className={styles.dailyBody}>
          <div className={styles.tableScroll}><table className={styles.table}><thead><tr><th>{t("日期")}</th><th>{t("输入")}</th><th>{t("输出")}</th><th>{t("已完整计价金额")}</th></tr></thead><tbody>
            {summary.data.days.map((day) => <tr key={day.date}><td>{day.date}</td><td>{tokenCount(day.totals.tokens.input?.value, t.formatLocale) ?? t("未返回")}</td>
              <td>{tokenCount(day.totals.tokens.output?.value, t.formatLocale) ?? t("未返回")}</td><td>
                {day.totals.amounts.some((amount) => amount.completeAmount !== null) ? day.totals.amounts.filter((amount) => amount.completeAmount !== null)
                  .map((amount) => <p key={amount.currency} title={amount.completeAmount!}>{usageAmount(amount.completeAmount!, t.formatLocale)} {amount.currency}</p>) : t("未计价")}
              </td></tr>)}
          </tbody></table></div>
      </div>
    </Disclosure>}</>}
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
