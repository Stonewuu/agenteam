"use client";

import {useState} from "react";
import {QueryState} from "@/components/ui/query-state";
import {editionClientExtension} from "@/features/edition/client-extension";
import type {IdentityUser} from "@/features/auth/types/identity";
import {SystemManagementGate} from "@/features/auth/components/system-management-gate";
import type {Enterprise} from "@/features/enterprise/types/organization";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import {IntegrationPanel} from "./integration-panel";

export function SystemIntegrationPage() {
  const t = useT();
  return <SystemManagementGate title={t("企业接入")}>{(_context, user) => <EnterpriseIntegrations user={user}/>}</SystemManagementGate>;
}

function EnterpriseIntegrations({user}: {user: IdentityUser}) {
  const t = useT();
  const list = useApiQuery<Enterprise[]>("/api/v1/enterprises");
  const [selectedId, setSelectedId] = useState("");
  const enterprises = list.data ?? [];
  const EnterpriseSelection = editionClientExtension.enterpriseSelection;
  const canSelect = Boolean(EnterpriseSelection && user.capabilities.includes("enterprise.switch"));
  const selected = canSelect ? enterprises.find((value) => value.id === selectedId) ?? enterprises.find((value) => value.status === "active") ?? enterprises[0] : enterprises.length === 1 ? enterprises[0] : undefined;
  return <QueryState {...list} hasData={enterprises.length > 0} empty={t("暂无可配置的企业。")}>
    {selected && <IntegrationPanel key={selected.id} enterpriseId={selected.id} system canManage={selected.status === "active"}
      enterpriseSelector={canSelect && EnterpriseSelection ? <EnterpriseSelection enterprises={enterprises} selectedId={selected.id} onSelect={setSelectedId}/> : undefined}/>}
  </QueryState>;
}
