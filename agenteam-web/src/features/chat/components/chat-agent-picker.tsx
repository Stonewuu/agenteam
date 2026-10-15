"use client";

import {useState} from "react";
import {Field} from "@/components/ui/field";
import {SearchableSelect} from "@/components/ui/searchable-select";
import {Select} from "@/components/ui/select";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {type ApiPage, useApiPage, useApiQuery} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";

type Choice = {agentId: string; versionId: string; name: string; versionNo: number};

type Props = {path: string; agentId: string; versionId: string; disabled: boolean; error?: string; onChange: (agentId: string, versionId: string, name?: string) => void};

export function ChatAgentPicker({path, agentId, versionId, disabled, error, onChange}: Props) {
  const t = useT();
  const [search, setSearch] = useState("");
  const [open, setOpen] = useState(false);
  const [chosen, setChosen] = useState<Choice | null>(null);
  const agents = useApiPage<Choice>(`${path}/agent-versions?query=${encodeURIComponent(search)}`);
  const known = useApiQuery<ApiPage<Choice>>(versionId ? `${path}/agent-versions?versionId=${encodeURIComponent(versionId)}` : null);
  const choices = agents.data?.items ?? [];
  const selected = chosen?.agentId === agentId ? chosen : known.data?.items.find((item) => item.agentId === agentId) ?? choices.find((item) => item.agentId === agentId);
  return <div data-agent-picker>
    <Field label={t("关联智能体")} required error={error}><SearchableSelect options={choices.map((item) => ({value: item.agentId, label: item.name}))}
      value={selected ? {value: selected.agentId, label: selected.name} : null} disabled={disabled || known.loading}
      query={search} onQueryChange={setSearch} open={open} onOpenChange={setOpen} loading={agents.loading}
      error={agents.error || known.error} onRetry={() => {
        agents.retry();
        known.retry();
      }} resetLabel={t("请选择智能体")} searchLabel={t("搜索智能体")} emptyLabel={t(search.trim() ? "没有匹配的智能体，请换个名称搜索。" : "没有可绑定的已发布智能体，请先在能力中心发布智能体。")}
      onChange={(option) => {
        const choice = choices.find((item) => item.agentId === option?.value);
        setChosen(choice ?? null);
        onChange(choice?.agentId ?? "", choice?.versionId ?? "", choice?.name);
      }}/></Field>
    <Pagination {...agents} hasMore={agents.data?.hasMore}/>
  </div>;
}

export function ChatAgentVersionPicker({path, agentId, versionId, disabled, onChange}: Props) {
  const t = useT();
  const versions = useApiPage<Choice>(`${path}/agent-versions?agentId=${encodeURIComponent(agentId)}`, 0, 0, 30, Boolean(agentId));
  const known = useApiQuery<ApiPage<Choice>>(versionId ? `${path}/agent-versions?versionId=${encodeURIComponent(versionId)}` : null);
  const choices = versions.data?.items ?? [];
  const selected = known.data?.items.find((item) => item.agentId === agentId);
  const older = selected && !choices.some((item) => item.versionId === selected.versionId) ? [selected] : [];
  return <QueryState {...versions} hasData={choices.length > 0 || older.length > 0} empty={t("没有可绑定的已发布智能体。")}>
    <Field label={t("智能体版本")} required><Select required value={versionId} disabled={disabled} onChange={(event) => onChange(agentId, event.target.value)}>
      <option value="">{t("请选择")}</option>{[...older, ...choices].map((choice) => <option key={choice.versionId} value={choice.versionId}>{t("版本 {0}", [choice.versionNo])}</option>)}
    </Select></Field><Pagination {...versions} hasMore={versions.data?.hasMore}/>
  </QueryState>;
}
