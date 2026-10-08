"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useState} from "react";
import {SystemManagementGate} from "@/features/auth/components/system-management-gate";
import type {EnterpriseContext} from "@/features/auth/types/identity";
import {Tabs} from "@/components/ui/tabs";
import {Select} from "@/components/ui/select";
import {SearchInput} from "@/components/ui/search-input";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {useApiPage} from "@/lib/http/use-api-query";
import {AnnouncementManager} from "./announcement-manager";
import styles from "./announcement.module.css";

type EnterpriseOption = { id: string; name: string };

export function AnnouncementManagementPage() {
  const uiText = useT();
  return <SystemManagementGate title={uiText("公告管理")}>{(context) => <SystemAnnouncements
    context={context}/>}</SystemManagementGate>;
}

function SystemAnnouncements({context}: { context: EnterpriseContext | null }) {
  const uiText = useT();
  const [scope, setScope] = useState<"platform" | "enterprise">("platform");
  const [enterprise, setEnterprise] = useState<EnterpriseOption | null>(context?.enterprise ?? null);
  const endpoint = scope === "platform" ? "/api/v1/system/announcements/platform" : enterprise ? `/api/v1/system/announcements/enterprises/${encodeURIComponent(enterprise.id)}` : null;
  return <AnnouncementManager endpoint={endpoint} title={uiText("公告管理")}>
    <Tabs value={scope} onChange={setScope} label={uiText("公告范围")}
          items={[{value: "platform", label: uiText("平台公告")}, {value: "enterprise", label: uiText("企业公告")}]}/>
    {scope === "enterprise" && <EnterprisePicker selected={enterprise} onSelect={setEnterprise}/>}
  </AnnouncementManager>;
}

function EnterprisePicker({selected, onSelect}: {
  selected: EnterpriseOption | null;
  onSelect: (value: EnterpriseOption | null) => void
}) {
  const uiText = useT();
  const [query, setQuery] = useState("");
  const list = useApiPage<EnterpriseOption>(`/api/v1/system/announcements/enterprises?query=${encodeURIComponent(query)}`);
  const options = list.data?.items ?? [];
  return <div className={styles.enterprisePicker}><SearchInput aria-label={uiText("搜索企业")}
                                                               placeholder={uiText("搜索企业…")} value={query}
                                                               onChange={(event) => {
                                                                 list.first();
                                                                 setQuery(event.target.value);
                                                               }}/>
    <QueryState {...list} hasData={Boolean(selected || options.length)} empty={uiText("没有匹配的企业。")}>
      <Select aria-label={uiText("选择公告所属企业")} value={selected?.id ?? ""}
              onChange={(event) => onSelect(options.find((value) => value.id === event.target.value) ?? selected)}>
        <option value="">{uiText("请选择企业")}</option>
        {selected && !options.some((value) => value.id === selected.id) &&
          <option value={selected.id}>{selected.name}</option>}
        {options.map((value) => <option key={value.id} value={value.id}>{value.name}</option>)}
      </Select>
    </QueryState><Pagination {...list} hasMore={list.data?.hasMore}/>
  </div>;
}
