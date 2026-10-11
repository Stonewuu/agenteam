"use client";

import { useState } from "react";
import { useT } from "@/lib/i18n/locale-provider";
import { PageHeader } from "@/components/ui/page-header";
import { Button } from "@/components/ui/button";
import { QueryState } from "@/components/ui/query-state";
import { organizationPath } from "@/features/enterprise/api/organization-api";
import { useApiQuery } from "@/lib/http/use-api-query";
import type { Usage } from "../types/usage";
import { UsageOverview } from "./usage-overview";
import {ModelUsagePanel} from "./model-usage-panel";
import {Tabs} from "@/components/ui/tabs";
import {IconChartBar, IconPlayerPlay, IconRefresh} from "@/components/ui/icons";
import ui from "@/components/ui/surface.module.css";
import styles from "./usage.module.css";

export function UsagePanel({ enterpriseId }: { enterpriseId: string; permissions?: string[] }) {
  const uiText = useT();
  const [refresh, setRefresh] = useState(0);
  const [tab, setTab] = useState<"executions" | "models">("executions");
  const usage = useApiQuery<Usage>(organizationPath(enterpriseId, "/usage"), refresh);
  return (
    <div className={styles.panel}>
      <PageHeader
        title={uiText("用量统计")}
        description={uiText("查看执行次数、模型用量和费用。")}
        actions={
          <Button className={ui.button} disabled={usage.loading} onClick={() => setRefresh((value) => value + 1)}>
            <IconRefresh size={16}/>{uiText("刷新")}
          </Button>
        }
      />
      <div className={styles.content}><Tabs value={tab} onChange={setTab} label={uiText("用量类型")} items={[
        {value: "executions", label: uiText("执行次数"), icon: IconPlayerPlay}, {value: "models", label: uiText("模型用量"), icon: IconChartBar},
      ]}/>
      {tab === "models" ? <ModelUsagePanel enterpriseId={enterpriseId} refresh={refresh}/> : <QueryState {...usage} hasData={Boolean(usage.data)} empty={uiText("暂时无法读取用量。")}>
        {usage.data && <UsageOverview usage={usage.data} />}
      </QueryState>}</div>
    </div>
  );
}
