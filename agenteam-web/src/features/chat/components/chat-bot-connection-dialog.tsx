"use client";

import {useState} from "react";
import {Dialog, DialogActions, DialogCancel} from "@/components/ui/dialog";
import {Button} from "@/components/ui/button";
import {IconKey, IconPlug} from "@/components/ui/icons";
import {QueryState} from "@/components/ui/query-state";
import {IntegrationDetailsContent} from "@/features/integration/components/integration-details-dialog";
import {IntegrationEditor} from "@/features/integration/components/integration-editor";
import {integrationPath, type Integration} from "@/features/integration/types/integration";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {ApiError, apiRequest} from "@/lib/http/api-client";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import type {ChatBot} from "../types/chat";
import ui from "@/components/ui/surface.module.css";
import styles from "./chat.module.css";

export function ChatBotConnectionDialog({enterpriseId, system, bot, canManage, canTest, onClose, onChanged}: {
  enterpriseId: string; system: boolean; bot: ChatBot; canManage: boolean; canTest: boolean; onClose: () => void; onChanged: () => void;
}) {
  const t = useT();
  const path = integrationPath(enterpriseId, system);
  const query = useApiQuery<Integration>(`${path}/${bot.connectionId}`);
  const action = useFormAction();
  const [rotating, setRotating] = useState(false);
  const value = query.data;
  const changed = () => {
    query.retry();
    onChanged();
  };
  return <Dialog title={t("连接设置")} icon={<IconPlug size={22}/>} onClose={onClose} busy={action.busy}>
    <QueryState {...query} hasData={Boolean(value)} empty={t("接入不存在或无法访问。")}>
      {value && <div className={styles.fields}>
        <IntegrationDetailsContent value={value}/>
        {value.purpose === "enterprise" && <p className={styles.muted}>{t("此机器人使用企业共用应用，修改密钥也会影响该企业接入。")}</p>}
        {value.lastCheckError && <p className={ui.error}>{value.lastCheckError}</p>}
        <div className={styles.actions}>
          {canManage && <Button className={ui.button} disabled={action.busy} onClick={() => setRotating(true)}><IconKey size={16}/>{t("更换密钥")}</Button>}
          {canManage && canTest && <Button className={ui.button} disabled={action.busy} onClick={() => void action.execute(async () => {
            try {
              const current = await apiRequest<Integration>(`${path}/${bot.connectionId}`);
              const checked = await action.mutation.run<Integration>(`${path}/${current.id}/check`, {method: "POST", revision: current.revision});
              if (checked.lastCheckStatus !== "passed") {
                throw new ApiError(400, "INTEGRATION_CHECK_FAILED", checked.lastCheckError || "应用校验未通过，请核对机器人编号和密钥。");
              }
              if (checked.status !== "enabled") {
                await action.mutation.run(`${path}/${checked.id}/status`, {method: "PATCH", revision: checked.revision, body: {status: "enabled"}});
              }
            } finally {
              changed();
            }
          }, t("连接配置已校验并启用。"))}><IconPlug size={16}/>{t("校验并连接")}</Button>}
        </div>
      </div>}
    </QueryState>
    <MutationFeedback action={action}/><DialogActions><DialogCancel className={ui.button} disabled={action.busy}>{t("关闭")}</DialogCancel></DialogActions>
    {rotating && value && <IntegrationEditor path={path} initial={value} mode="secret" onClose={() => setRotating(false)} onSaved={() => {
      setRotating(false);
      changed();
    }}/>}
  </Dialog>;
}
