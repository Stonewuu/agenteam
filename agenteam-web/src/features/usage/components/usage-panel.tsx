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
import ui from "@/components/ui/surface.module.css";
import styles from "./usage.module.css";

export function UsagePanel({ enterpriseId }: { enterpriseId: string; permissions?: string[] }) {
  const uiText = useT();
  const [refresh, setRefresh] = useState(0);
  const usage = useApiQuery<Usage>(organizationPath(enterpriseId, "/usage"), refresh);
  return (
    <div className={styles.panel}>
      <PageHeader
        title={uiText("执行用量")}
        description={uiText("查看本月已使用和预留的执行次数。")}
        actions={
          <Button className={ui.button} disabled={usage.loading} onClick={() => setRefresh((value) => value + 1)}>
            {uiText("刷新")}
          </Button>
        }
      />
      <QueryState {...usage} hasData={Boolean(usage.data)} empty={uiText("暂时无法读取用量。")}>
        {usage.data && <UsageOverview usage={usage.data} />}
      </QueryState>
    </div>
  );
}
