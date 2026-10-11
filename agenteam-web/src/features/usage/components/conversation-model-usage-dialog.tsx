"use client";

import {useEffect, useState} from "react";
import {Dialog, DialogCancel} from "@/components/ui/dialog";
import {Button} from "@/components/ui/button";
import {DetailSection} from "@/components/ui/detail-section";
import {QueryState, Pagination} from "@/components/ui/query-state";
import {IconChartBar, IconListDetails, IconRefresh} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import {useApiQuery} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {ModelUsageSummaryCards} from "./model-usage-summary";
import {ModelCallList} from "./model-call-list";
import type {ConversationModelUsage} from "../types/model-usage";
import ui from "@/components/ui/surface.module.css";
import styles from "./model-usage.module.css";

export function ConversationModelUsageDialog({enterpriseId, conversationId, runId, onClose}: {
  enterpriseId: string; conversationId: string; runId?: string | null; onClose: () => void;
}) {
  const t = useT();
  const [refresh, setRefresh] = useState(0);
  const [cursors, setCursors] = useState<string[]>([]);
  const query = new URLSearchParams({limit: "30"});
  if (runId) {
    query.set("runId", runId);
  }
  if (cursors.length) {
    query.set("cursor", cursors.at(-1)!);
  }
  const usage = useApiQuery<ConversationModelUsage>(organizationPath(enterpriseId, `/conversations/${encodeURIComponent(conversationId)}/model-usage?${query}`), refresh);
  const pending = usage.data?.summary.totals.pendingCalls;
  useEffect(() => {
    if (!pending || pending === "0") {
      return;
    }
    const timer = window.setInterval(() => {
      if (!document.hidden) {
        setRefresh((value) => value + 1);
      }
    }, 5000);
    return () => window.clearInterval(timer);
  }, [pending]);
  return <Dialog title={runId ? t("本轮模型用量") : t("对话累计用量")} icon={<IconChartBar size={21}/>} wide onClose={onClose}
    footer={<><Button className={ui.button} disabled={usage.loading} onClick={() => setRefresh((value) => value + 1)}><IconRefresh size={16}/>{t("刷新")}</Button>
      <DialogCancel className={ui.button}>{t("关闭")}</DialogCancel></>}>
    <QueryState {...usage} hasData={Boolean(usage.data && usage.data.summary.totals.calls !== "0")}
      empty={t("暂无已记录的模型用量。")}>
      {usage.data && <div className={styles.stack}>
        <ModelUsageSummaryCards totals={usage.data.summary.totals}/>
        <DetailSection title={t("调用明细")} icon={<IconListDetails size={20}/>}>
          <ModelCallList calls={usage.data.calls.items}/>
          <Pagination previous={cursors} loading={usage.loading} hasMore={usage.data.calls.hasMore}
            back={() => setCursors(cursors.slice(0, -1))} next={() => {
              if (usage.data?.calls.nextCursor) {
                setCursors([...cursors, usage.data.calls.nextCursor]);
              }
            }}/>
        </DetailSection>
      </div>}
    </QueryState>
  </Dialog>;
}
