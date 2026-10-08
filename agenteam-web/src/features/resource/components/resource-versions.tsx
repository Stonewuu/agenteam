"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import {Fieldset} from "@/components/ui/fieldset";

import {useState} from "react";
import {Dialog, DialogActions, DialogCancel, DialogForm} from "@/components/ui/dialog";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {useApiPage, useApiQuery} from "@/lib/http/use-api-query";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useConfirmClose} from "@/features/workspace/components/use-confirm-close";
import type {ResourceDetail, ResourceSummary, ResourceVersion, VersionSummary} from "../types/resource";
import {ResourceConfigForm} from "./resource-config-form";
import {TextField} from "./resource-fields";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {SkillExportButton} from "@/features/skill/components/skill-export-button";
import ui from "@/components/ui/surface.module.css";
import styles from "./resource.module.css";

export function ResourceVersions({enterpriseId, resource, permissions, onLoaded, onChanged}: {
  enterpriseId: string;
  resource: ResourceSummary;
  permissions: string[];
  onLoaded: (detail: ResourceDetail) => void;
  onChanged: () => void;
}) {
  const uiText = useT();
  const list = useApiPage<VersionSummary>(organizationPath(enterpriseId, `/resources/${encodeURIComponent(resource.id)}/versions`), 0, 0);
  const [selected, setSelected] = useState<VersionSummary | null>(null);
  const [operation, setOperation] = useState<"load-draft" | "revoke" | null>(null);
  return <>
    <QueryState {...list} hasData={Boolean(list.data?.items.length)} empty={uiText("还没有发布版本。")}>
      <div className={styles.versionList}>{list.data?.items.map((version) => <article className={styles.versionItem}
                                                                                      key={version.id}>
        <div className={styles.line}><h3>{uiText("版本 ")}{version.versionNo}</h3><span
          className={ui.chip}>{version.status === "revoked" ? uiText("已撤销") : uiText("已发布")}</span></div>
        <p className={ui.description}>{version.releaseNote}</p><p
        className={styles.secondary}>{version.publishedBy.displayName} · <EnterpriseDateTime
        value={version.publishedAt}/></p>
        <div className={ui.footer}><Button className={ui.button} type="button" onClick={() => {
          setSelected(version);
          setOperation(null);
        }}>{uiText("查看配置")}</Button>
          {resource.kind === "skill" && resource.allowedActions.includes("export") &&
            <SkillExportButton enterpriseId={enterpriseId} resourceId={resource.id} versionId={version.id}/>}
          {resource.allowedActions.includes("edit") && <Button className={ui.button} type="button" onClick={() => {
            setSelected(version);
            setOperation("load-draft");
          }}>{uiText("使用此版本内容")}</Button>}
          {version.status === "available" && resource.allowedActions.includes("revoke") &&
            <Button className={ui.danger} type="button" onClick={() => {
              setSelected(version);
              setOperation("revoke");
            }}>{uiText("撤销版本")}</Button>}</div>
      </article>)}</div>
    </QueryState><Pagination {...list} hasMore={list.data?.hasMore}/>
    {selected && (operation ?
      <VersionAction key={`${selected.id}:${operation}`} enterpriseId={enterpriseId} resource={resource}
                     version={selected} operation={operation} onClose={() => setSelected(null)}
                     onLoaded={onLoaded} onChanged={() => {
        list.retry();
        onChanged();
      }}/> :
      <VersionContent enterpriseId={enterpriseId} resource={resource} version={selected} permissions={permissions}
                      onClose={() => setSelected(null)}/>)}
  </>;
}

function VersionContent({enterpriseId, resource, version, permissions, onClose}: {
  enterpriseId: string;
  resource: ResourceSummary;
  version: VersionSummary;
  permissions: string[];
  onClose: () => void
}) {
  const uiText = useT();
  const detail = useApiQuery<ResourceVersion>(organizationPath(enterpriseId, `/resources/${encodeURIComponent(resource.id)}/versions/${encodeURIComponent(version.id)}`));
  return <Dialog title={uiText("版本 {0} · {1}", [version.versionNo, version.name])} onClose={onClose}
                 wide={resource.kind === "workflow"}>
    <QueryState {...detail} hasData={Boolean(detail.data)} empty={uiText("无法读取此版本。")}>{detail.data &&
      <Fieldset className={styles.editorFields} disabled={resource.kind !== "workflow"}>
        <ResourceConfigForm enterpriseId={enterpriseId} kind={resource.kind} config={detail.data.config}
                            onChange={() => {
                            }} permissions={permissions} errors={{}} readOnly/>
        {detail.data.dependencies.length > 0 && <section className={styles.section}>
          <h2>{uiText("引用内容")}</h2>{detail.data.dependencies.map((value, index) => <p key={index}>{value.name}</p>)}
        </section>}
      </Fieldset>}</QueryState>
  </Dialog>;
}

function VersionAction({enterpriseId, resource, version, operation, onClose, onLoaded, onChanged}: {
  enterpriseId: string;
  resource: ResourceSummary;
  version: VersionSummary;
  operation: "load-draft" | "revoke";
  onClose: () => void;
  onLoaded: (detail: ResourceDetail) => void;
  onChanged: () => void;
}) {
  const uiText = useT();
  const action = useFormAction();
  const [reason, setReason] = useState("");
  const closing = useConfirmClose(Boolean(reason), action.busy, onClose);
  return <Dialog
    title={operation === "revoke" ? uiText("撤销版本 {0}？", [version.versionNo]) : uiText("使用版本 {0} 的内容？", [version.versionNo])}
    onClose={onClose} dialogRef={closing.dialogRef} onRequestClose={closing.canClose} busy={action.busy}>
    <DialogForm className={ui.form} onSubmit={(event) => {
      event.preventDefault();
      void action.execute(async () => {
        const result = await action.mutation.run<ResourceDetail | VersionSummary>(organizationPath(enterpriseId, `/resources/${encodeURIComponent(resource.id)}/versions/${encodeURIComponent(version.id)}/${operation}`),
          {method: "POST", revision: resource.revision, ...(operation === "revoke" ? {body: {reason}} : {})});
        closing.finish(() => {
          if (operation === "load-draft") {
            onLoaded(result as ResourceDetail);
          } else {
            onChanged();
          }
          onClose();
        });
      });
    }}>
      <p
        className={ui.description}>{operation === "revoke" ? uiText("此版本将不能继续使用。发布正文和已有记录保留。") : uiText("此操作会替换当前草稿，包括尚未保存的修改。当前发布版本保持不变。")}</p>
      {operation === "revoke" &&
        <TextField label={uiText("撤销原因")} name="reason" value={reason} onChange={setReason} maximum={500} multiline
                   required errors={action.fieldErrors}/>}
      <MutationFeedback action={action} onReload={() => closing.finish(() => {
        onChanged();
        onClose();
      })}/><DialogActions className={ui.footer}><DialogCancel className={ui.button} type="button"
                                                              disabled={action.busy}>{uiText("取消")}</DialogCancel>
      <Button className={operation === "revoke" ? ui.danger : ui.primary}
              disabled={action.busy}>{action.busy ? uiText("正在处理…") : operation === "revoke" ? uiText("确认撤销") : uiText("替换草稿")}</Button></DialogActions>
    </DialogForm>{closing.confirmation}
  </Dialog>;
}
