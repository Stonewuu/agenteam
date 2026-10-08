"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import {Checkbox} from "@/components/ui/checkbox";
import {Fieldset} from "@/components/ui/fieldset";

import {Select} from "@/components/ui/select";


import {useState} from "react";
import {Dialog, DialogActions, DialogCancel, DialogForm} from "@/components/ui/dialog";
import {QueryState} from "@/components/ui/query-state";
import {useApiQuery} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import type {Grant, ResourceSummary, SubjectOption} from "../types/resource";
import {resourceLabel} from "../lib/resource-display";
import {SubjectPicker, useSubjectNames} from "./subject-picker";
import {useConfirmClose} from "@/features/workspace/components/use-confirm-close";
import {useEnterpriseIdentity} from "@/features/auth/components/enterprise-gate";
import {editionClientExtension} from "@/features/edition/client-extension";
import ui from "@/components/ui/surface.module.css";
import styles from "./resource.module.css";

export function ResourceAccessDialog({enterpriseId, resource, mode, onClose, onChanged}: {
  enterpriseId: string;
  resource: ResourceSummary;
  mode: "grants" | "transfer";
  onClose: () => void;
  onChanged: () => void;
}) {
  const uiText = useT();
  const grants = useApiQuery<Grant[]>(mode === "grants" ? organizationPath(enterpriseId, `/resources/${encodeURIComponent(resource.id)}/grants`) : null);
  if (mode === "transfer") {
    return <TransferForm enterpriseId={enterpriseId} resource={resource} onClose={onClose} onChanged={onChanged}/>;
  }
  if (!grants.data) {
    return <Dialog title={uiText("管理{0}授权", [resourceLabel(resource.kind)])}
                   onClose={onClose}><QueryState {...grants} hasData={false}
                                                 empty={uiText("无法读取当前授权。")}/></Dialog>;
  }
  return <AccessForm enterpriseId={enterpriseId} resource={resource} initial={grants.data} onClose={onClose}
                     onChanged={onChanged}/>;
}

export function GrantFields({enterpriseId, resourceId, grants, onChange, useOnly = false}: {
  enterpriseId: string;
  resourceId: string;
  grants: Grant[];
  onChange: (value: Grant[]) => void;
  useOnly?: boolean
}) {
  const uiText = useT();
  const [type, setType] = useState<Grant["subjectType"]>(useOnly ? "user" : "enterprise");
  const identity = useEnterpriseIdentity();
  const extraSubjects = (editionClientExtension.resourceGrantSubjects ?? []).filter((subject) =>
    identity?.context.capabilities.includes(subject.capability));
  const selectedType = type === "enterprise" || type === "user" || extraSubjects.some((subject) => subject.type === type)
    ? type : useOnly ? "user" : "enterprise";
  const [names, setNames] = useState<Record<string, string>>({});
  const resolved = useSubjectNames(enterpriseId, resourceId, grants);
  const subjects = [...new Map(grants.map((grant) => [`${grant.subjectType}:${grant.subjectId}`, grant])).values()];
  const add = (subjectType: Grant["subjectType"], subjectId: string, name?: string) => {
    if (subjectType !== "enterprise" && subjectType !== "user" && !extraSubjects.some((subject) => subject.type === subjectType)) {
      return;
    }
    if (name) {
      setNames((values) => ({...values, [`${subjectType}:${subjectId}`]: name}));
    }
    if (!grants.some((value) => value.subjectType === subjectType && value.subjectId === subjectId)) {
      onChange([...grants, {subjectType, subjectId, capability: "use"}]);
    }
  };
  return <div className={ui.form}>{!useOnly &&
    <p className={ui.description}>{uiText("选择可以查看、使用或编辑当前内容的授权对象。")}</p>}
    {resolved.error && <p role="alert" className={ui.error}>{localizeUiMessage(resolved.error ?? "", uiText)}<Button
      className={ui.button} type="button" onClick={resolved.retry}>{uiText("重新读取名称")}</Button></p>}
    <div className={styles.grantList}>{subjects.map((grant) => {
      const key = `${grant.subjectType}:${grant.subjectId}`;
      const subject = resolved.names[key];
      const same = (value: Grant) => value.subjectType === grant.subjectType && value.subjectId === grant.subjectId;
      const canExtend = grant.subjectType === "enterprise" || grant.subjectType === "user"
        || extraSubjects.some((subject) => subject.type === grant.subjectType);
      const label = grant.subjectType === "enterprise" ? uiText("全体企业成员") : subject ? `${subject.name}${subject.active ? "" : uiText("（已停用或移除）")}` : names[key] ?? (resolved.loading ? uiText("正在读取名称…") : uiText("此对象当前不可选择"));
      return <div className={styles.grant} key={key} role="group" aria-label={uiText("{0}的授权", [label])}>
        <span>{label}</span>
        <div>{(useOnly ? ["use"] as const : ["view", "use", "edit"] as const).map((capability) => <label
          className={ui.check} key={capability}><Checkbox
          checked={grants.some((value) => same(value) && value.capability === capability)}
          disabled={(!canExtend || grants.length >= 500) && !grants.some((value) => same(value) && value.capability === capability)}
          onCheckedChange={(checked) => onChange(checked ? [...grants, {
            ...grant,
            capability
          }] : grants.filter((value) => !(same(value) && value.capability === capability)))}/>
          {{view: uiText("查看"), use: uiText("使用"), edit: uiText("编辑")}[capability]}</label>)}</div>
        <Button className={ui.button} type="button"
                onClick={() => onChange(grants.filter((value) => !same(value)))}>{uiText("移除")}</Button>
      </div>;
    })}</div>
    <label className={ui.field}><span>{uiText("添加授权对象")}</span><Select className={ui.select} value={selectedType}
                                                                             onChange={(event) => setType(event.target.value as Grant["subjectType"])}>
      {!useOnly && <option value="enterprise">{uiText("全体企业成员")}</option>}
      {extraSubjects.map((subject) => <option key={subject.type} value={subject.type}>{uiText(subject.label)}</option>)}
      <option value="user">{uiText("成员")}</option>
    </Select></label>
    {selectedType === "enterprise" ? <Button className={ui.button} type="button" disabled={grants.length >= 500}
                                     onClick={() => add("enterprise", enterpriseId)}>{uiText("添加全体企业成员")}</Button>
      : grants.length < 500 && <SubjectPicker enterpriseId={enterpriseId} resourceId={resourceId} type={selectedType}
                                              onSelect={(subject) => add(selectedType, subject.id, subject.name)}/>}
  </div>;
}

function AccessForm({enterpriseId, resource, initial, onClose, onChanged}: {
  enterpriseId: string;
  resource: ResourceSummary;
  initial: Grant[];
  onClose: () => void;
  onChanged: () => void
}) {
  const uiText = useT();
  const action = useFormAction();
  const [grants, setGrants] = useState(initial);
  const closing = useConfirmClose(JSON.stringify(grants) !== JSON.stringify(initial), action.busy, onClose);
  return <Dialog title={uiText("管理{0}“{1}”的使用范围", [resourceLabel(resource.kind), resource.name])}
                 onClose={onClose} dialogRef={closing.dialogRef} onRequestClose={closing.canClose}
                 busy={action.busy}><DialogForm className={ui.form} onSubmit={(event) => {
    event.preventDefault();
    void action.execute(async () => {
      await action.mutation.run(organizationPath(enterpriseId, `/resources/${encodeURIComponent(resource.id)}/grants`), {
        method: "PUT",
        revision: resource.revision,
        body: {grants}
      });
      closing.finish(() => {
        onChanged();
        onClose();
      });
    });
  }}><Fieldset className={styles.editorFields} disabled={action.busy}><GrantFields enterpriseId={enterpriseId}
                                                                                   resourceId={resource.id}
                                                                                   grants={grants}
                                                                                   onChange={setGrants}/></Fieldset>
    <MutationFeedback action={action} onReload={() => {
      closing.finish(() => {
        onChanged();
        onClose();
      });
    }}/><DialogActions className={ui.footer}><DialogCancel className={ui.button} type="button"
                                                           disabled={action.busy}>{uiText("取消")}</DialogCancel>
      <Button className={ui.primary}
              disabled={action.busy}>{action.busy ? uiText("正在保存…") : uiText("保存授权")}</Button></DialogActions>
  </DialogForm>{closing.confirmation}</Dialog>;
}

function TransferForm({enterpriseId, resource, onClose, onChanged}: {
  enterpriseId: string;
  resource: ResourceSummary;
  onClose: () => void;
  onChanged: () => void
}) {
  const uiText = useT();
  const action = useFormAction();
  const [selected, setSelected] = useState<SubjectOption | null>(null);
  const closing = useConfirmClose(Boolean(selected), action.busy, onClose);
  return <Dialog title={uiText("转交{0}“{1}”", [resourceLabel(resource.kind), resource.name])} onClose={onClose}
                 dialogRef={closing.dialogRef} onRequestClose={closing.canClose} busy={action.busy}>
    <div className={ui.form}><p
      className={ui.description}>{uiText("当前所有者：")}{resource.owner.displayName}{uiText("。转交后，接收成员将负责维护“")}{resource.name}{uiText("”及其授权。")}</p>
      {selected && <p>{uiText("接收成员：")}{selected.name}</p>}<SubjectPicker enterpriseId={enterpriseId}
                                                                              resourceId={resource.id} type="user"
                                                                              purpose="owner" onSelect={setSelected}/>
      <MutationFeedback action={action} onReload={() => {
        closing.finish(() => {
          onChanged();
          onClose();
        });
      }}/><DialogActions className={ui.footer}><DialogCancel className={ui.button}
                                                             disabled={action.busy}>{uiText("取消")}</DialogCancel>
        <Button className={ui.primary} disabled={action.busy || !selected || selected.id === resource.owner.id}
                onClick={() => void action.execute(async () => {
                  await action.mutation.run(organizationPath(enterpriseId, `/resources/${encodeURIComponent(resource.id)}/owner`), {
                    method: "PUT",
                    revision: resource.revision,
                    body: {ownerUserId: selected!.id}
                  });
                  closing.finish(() => {
                    onChanged();
                    onClose();
                  });
                })}>{action.busy ? uiText("正在转交…") : uiText("确认转交")}</Button></DialogActions>
    </div>
    {closing.confirmation}</Dialog>;
}
