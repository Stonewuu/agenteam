"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {Dialog, DialogActions, DialogCancel, useDialogControl} from "@/components/ui/dialog";
import {QueryState} from "@/components/ui/query-state";
import {useApiQuery} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import type {ResourceImpact, ResourceSummary} from "../types/resource";
import {resourceLabel} from "../lib/resource-display";
import ui from "@/components/ui/surface.module.css";

export function ResourceLifecycleDialog({enterpriseId, resource, operation, onClose, onChanged}: {
  enterpriseId: string;
  resource: ResourceSummary;
  operation: "status" | "delete" | "restore";
  onClose: () => void;
  onChanged: () => void;
}) {
  const uiText = useT();
  const base = organizationPath(enterpriseId, `/resources/${encodeURIComponent(resource.id)}`);
  const status = resource.status === "disabled" ? "active" : "disabled";
  const label = resourceLabel(resource.kind);
  const title = `${operation === "delete" ? uiText("删除") : operation === "restore" ? uiText("恢复") : status === "active" ? uiText("启用") : uiText("停用")}${label}`;
  const previewRequired = operation === "delete" || operation === "status" && status === "disabled";
  const preview = useApiQuery<ResourceImpact>(previewRequired ? `${base}/impact` : null);
  const action = useFormAction();
  const dialog = useDialogControl();
  const impact = preview.data;
  const blockedReason = impact?.activeRunCount
    ? uiText("请先停止未结束的任务，再删除此{0}。", [label])
    : uiText("当前无法删除此{0}，请重新加载后重试。", [label]);
  const isAgent = resource.kind === "agent";
  const description = operation === "delete"
    ? uiText("删除后，此{0}的所有版本都不可使用。{1}30 天内可以恢复；恢复后仍保持停用。{2}", [label, isAgent ? uiText("将自动解除所有人的雇佣，包括已暂停的雇佣。") : resource.kind === "plugin" ? uiText("相关工具不再提供给智能体。") : "", isAgent ? uiText("恢复后需要重新雇佣。") : ""])
    : operation === "restore"
      ? uiText("恢复后，此{0}保持停用。{1}", [label, isAgent ? uiText("启用并重新上架后，成员可重新雇佣。") : uiText("启用后才可使用。")])
      : status === "disabled"
        ? uiText("停用后，此{0}的所有版本将无法继续使用。{1}", [label, isAgent ? uiText("当前有效的雇佣会暂停。") : ""])
        : uiText("启用后，此{0}可重新使用。{1}", [label, isAgent ? uiText("已暂停的雇佣需要本人恢复，已解除的雇佣需要重新雇佣。") : ""]);
  return <Dialog title={`${title}“${resource.name}”？`} onClose={onClose} dialogRef={dialog.ref} busy={action.busy}>
    <div className={ui.form}>
      <p className={ui.description}>{description}</p>
      {previewRequired &&
        <QueryState {...preview} hasData={Boolean(impact)} empty={uiText("暂时无法读取影响，请重新加载。")}>{impact &&
          <div className={ui.form}>
            {isAgent && impact.activeHireCount > 0 && <p>{uiText("有效雇佣：")}{impact.activeHireCount}</p>}
            {impact.visibleDependencies.length > 0 && <div>
              <p>{operation === "delete" ? uiText("以下内容引用了此{0}，相关能力将不可用：", [label]) : uiText("以下内容引用了此{0}：", [label])}</p>
              <ul>{impact.visibleDependencies.map((dependency) => <li key={dependency.id}>{dependency.name}</li>)}</ul>
            </div>}
            {impact.hiddenDependencyCount > 0 &&
              <p>{uiText("另外还有 ")}{impact.hiddenDependencyCount}{uiText(" 项引用未展开。")}</p>}
            {impact.activeRunCount > 0 && <p>{uiText("未结束的执行：")}{impact.activeRunCount}</p>}
            {impact.enabledScheduleCount > 0 &&
              <p>{uiText("将暂停 ")}{impact.enabledScheduleCount}{uiText(" 个相关计划。修复后需要重新启用。")}</p>}
            {operation === "delete" && !impact.canDelete && <p className={ui.error}>{blockedReason}</p>}
          </div>}</QueryState>}
      <MutationFeedback action={action} onReload={() => {
        dialog.close(() => {
          onChanged();
          onClose();
        });
      }}/>
      <DialogActions className={ui.footer}><DialogCancel className={ui.button}
                                                         disabled={action.busy}>{uiText("取消")}</DialogCancel>
        <Button className={operation === "delete" ? ui.danger : ui.primary}
                disabled={action.busy || previewRequired && (!impact || preview.loading || Boolean(preview.error)) || operation === "delete" && !impact?.canDelete}
                onClick={() => void action.execute(async () => {
                  await action.mutation.run(`${base}${operation === "delete" ? "" : `/${operation}`}`, {
                    method: operation === "delete" ? "DELETE" : operation === "restore" ? "POST" : "PATCH",
                    revision: impact?.revision ?? resource.revision, ...(operation === "status" ? {body: {status}} : {})
                  });
                  dialog.close(() => {
                    onChanged();
                    onClose();
                  });
                })}>{action.busy ? uiText("正在处理…") : title}</Button></DialogActions>
    </div>
  </Dialog>;
}
