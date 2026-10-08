"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Fieldset} from "@/components/ui/fieldset";
import {Checkbox} from "@/components/ui/checkbox";
import {Button} from "@/components/ui/button";

import {Select} from "@/components/ui/select";


import {useState} from "react";
import {Dialog, DialogActions, DialogCancel, DialogForm} from "@/components/ui/dialog";
import {QueryState} from "@/components/ui/query-state";
import {useApiQuery} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useConfirmClose} from "@/features/workspace/components/use-confirm-close";
import type {Grant, Listing, ResourceDetail, ResourceSummary} from "../types/resource";
import {resourceLabel} from "../lib/resource-display";
import {TextField} from "./resource-fields";
import {GrantFields} from "./resource-access-dialog";
import {AgentUseScopeFields} from "./agent-use-scope-fields";
import {initialAgentUseGrants, replaceAgentUseGrants} from "../lib/agent-listing-access";
import ui from "@/components/ui/surface.module.css";
import styles from "./resource.module.css";

export function ResourcePublishDialog({enterpriseId, resource, onClose, onChanged}: {
  enterpriseId: string;
  resource: ResourceSummary;
  onClose: () => void;
  onChanged: () => void
}) {
  const uiText = useT();
  const detail = useApiQuery<ResourceDetail>(organizationPath(enterpriseId, `/resources/${encodeURIComponent(resource.id)}`));
  if (!detail.data) {
    return <Dialog title={uiText("发布{0}“{1}”", [resourceLabel(resource.kind), resource.name])}
                   onClose={onClose}><QueryState {...detail} hasData={false}
                                                 empty={uiText("无法读取待发布内容。")}/></Dialog>;
  }
  return <PublishForm enterpriseId={enterpriseId} detail={detail.data} onClose={onClose} onChanged={onChanged}/>;
}

function PublishForm({enterpriseId, detail, onClose, onChanged}: {
  enterpriseId: string;
  detail: ResourceDetail;
  onClose: () => void;
  onChanged: () => void
}) {
  const uiText = useT();
  const action = useFormAction();
  const [note, setNote] = useState("");
  const [changeGrants, setChangeGrants] = useState(false);
  const [grants, setGrants] = useState<Grant[]>(detail.grants);
  const [initialUseGrants] = useState(() => initialAgentUseGrants(detail.resource, detail.grants, enterpriseId));
  const [useGrants, setUseGrants] = useState(initialUseGrants);
  const [initialListing] = useState<Listing>(() => ({
    listed: true,
    hirePolicy: detail.resource.listing?.hirePolicy ?? "automatic"
  }));
  const [listing, setListing] = useState<Listing>(initialListing);
  const changed = Boolean(note || changeGrants || JSON.stringify(listing) !== JSON.stringify(initialListing) || JSON.stringify(useGrants) !== JSON.stringify(initialUseGrants));
  const closing = useConfirmClose(changed, action.busy, onClose);
  const {resource} = detail;
  const publishUseGrants = resource.kind === "agent" && listing.listed && resource.allowedActions.includes("grants");
  const errors = [...Object.values(detail.fieldErrors), ...Object.values(action.fieldErrors)].flat();
  return <Dialog title={uiText("发布{0}“{1}”", [resourceLabel(resource.kind), resource.name])} onClose={onClose}
                 dialogRef={closing.dialogRef} onRequestClose={closing.canClose} busy={action.busy}>
    <DialogForm className={ui.form} onSubmit={(event) => {
      event.preventDefault();
      void action.execute(async () => {
        await action.mutation.run(organizationPath(enterpriseId, `/resources/${encodeURIComponent(resource.id)}/publish`), {
          method: "POST", revision: resource.revision,
          body: {releaseNote: note, ...(publishUseGrants ? {grants: replaceAgentUseGrants(detail.grants, useGrants)} : changeGrants ? {grants} : {}), ...(resource.kind === "agent" ? {listing} : {})}
        });
        closing.finish(() => {
          onChanged();
          onClose();
        });
      });
    }}>
      <p className={ui.description}>{resource.description}</p>
      <TextField label={uiText("发布说明")} name="releaseNote" value={note} onChange={setNote} maximum={500} required
                 multiline errors={action.fieldErrors}/>
      <Fieldset className={styles.editorFields} disabled={action.busy}>
        {resource.kind === "agent" && <ListingFields value={listing} onChange={setListing}/>}
        {resource.kind === "agent" && resource.allowedActions.includes("grants") &&
          <AgentUseScopeFields enterpriseId={enterpriseId} resourceId={resource.id} grants={useGrants}
                               onChange={setUseGrants} visible={listing.listed}/>}
        {resource.kind !== "agent" && resource.allowedActions.includes("grants") && <><label
          className={ui.check}><Checkbox checked={changeGrants}
                                         onCheckedChange={(checked) => setChangeGrants(checked)}/>{uiText("同时修改授权")}
        </label>
          {changeGrants && <GrantFields enterpriseId={enterpriseId} resourceId={resource.id} grants={grants}
                                        onChange={setGrants}/>}</>}
      </Fieldset>
      {errors.length > 0 && <ul className={ui.error}>{Array.from(new Set(errors)).map((error, index) => <li
        key={index}>{localizeUiMessage(error ?? "", uiText)}</li>)}</ul>}
      <MutationFeedback action={action} onReload={() => {
        closing.finish(() => {
          onChanged();
          onClose();
        });
      }}/>
      <DialogActions className={ui.footer}><DialogCancel className={ui.button} type="button"
                                                         disabled={action.busy}>{uiText("取消")}</DialogCancel><Button
        className={ui.primary}
        disabled={action.busy || Object.keys(detail.fieldErrors).length > 0}>{action.busy ? uiText("正在发布…") : uiText("确认发布")}</Button></DialogActions>
    </DialogForm>{closing.confirmation}
  </Dialog>;
}

export function ListingFields({value, onChange}: { value: Listing; onChange: (value: Listing) => void }) {
  const uiText = useT();
  return <div className={ui.form}><label className={ui.check}><Checkbox checked={value.listed}
                                                                        onCheckedChange={(checked) => onChange({
                                                                          ...value,
                                                                          listed: checked
                                                                        })}/>{uiText("列入员工广场")}</label>
    <label className={ui.field}><span>{uiText("雇佣方式")}</span><Select className={ui.select} value={value.hirePolicy}
                                                                         onChange={(event) => onChange({
                                                                           ...value,
                                                                           hirePolicy: event.target.value as Listing["hirePolicy"]
                                                                         })}>
      <option value="automatic">{uiText("自行雇佣")}</option>
      <option value="approval">{uiText("需要审批")}</option>
    </Select></label></div>;
}

type ListingDialogProps = {
  enterpriseId: string;
  resource: ResourceSummary;
  onClose: () => void;
  onChanged: () => void;
  startListing?: boolean;
  onSaved?: (resource: ResourceSummary) => void
};

export function ListingDialog(props: ListingDialogProps) {
  const uiText = useT();
  const canSetRange = props.resource.allowedActions.includes("grants");
  const grants = useApiQuery<Grant[]>(canSetRange ? organizationPath(props.enterpriseId, `/resources/${encodeURIComponent(props.resource.id)}/grants`) : null);
  if (canSetRange && !grants.data) {
    return <Dialog title={uiText("广场与雇佣设置")} onClose={props.onClose}><QueryState {...grants} hasData={false}
                                                                                        empty={uiText("无法读取当前授权。")}/></Dialog>;
  }
  return <ListingForm {...props} currentGrants={grants.data ?? []} canSetRange={canSetRange}/>;
}

function ListingForm({
                       enterpriseId,
                       resource,
                       onClose,
                       onChanged,
                       onSaved,
                       startListing = false,
                       currentGrants,
                       canSetRange
                     }: ListingDialogProps & { currentGrants: Grant[]; canSetRange: boolean }) {
  const uiText = useT();
  const action = useFormAction();
  const [initialValue] = useState<Listing>(() => ({
    listed: startListing || resource.listing?.listed === true,
    hirePolicy: resource.listing?.hirePolicy ?? "automatic"
  }));
  const [value, setValue] = useState(initialValue);
  const [initialUseGrants] = useState(() => initialAgentUseGrants(resource, currentGrants, enterpriseId));
  const [useGrants, setUseGrants] = useState(initialUseGrants);
  const closing = useConfirmClose(JSON.stringify(value) !== JSON.stringify(initialValue) || JSON.stringify(useGrants) !== JSON.stringify(initialUseGrants), action.busy, onClose);
  return <Dialog title={uiText("广场与雇佣设置")} onClose={onClose} dialogRef={closing.dialogRef}
                 onRequestClose={closing.canClose} busy={action.busy}>
    <div className={ui.form}>
      <p className={ui.description}>{uiText("下架会阻止新雇佣，已有雇佣保留。")}</p>
      <Fieldset className={styles.editorFields} disabled={action.busy}>
        <ListingFields value={value} onChange={setValue}/>
        {canSetRange && <AgentUseScopeFields enterpriseId={enterpriseId} resourceId={resource.id} grants={useGrants}
                                             onChange={setUseGrants} visible={value.listed}/>}
      </Fieldset><MutationFeedback action={action} onReload={() => {
      closing.finish(() => {
        onChanged();
        onClose();
      });
    }}/>
      <DialogActions className={ui.footer}><DialogCancel className={ui.button}
                                                         disabled={action.busy}>{uiText("取消")}</DialogCancel><Button
        className={ui.primary} disabled={action.busy} onClick={() => void action.execute(async () => {
        const updated = await action.mutation.run<ResourceSummary>(organizationPath(enterpriseId, `/agents/${encodeURIComponent(resource.id)}/listing`), {
          method: "PUT",
          revision: resource.revision,
          body: {...value, ...(value.listed && canSetRange ? {useGrants} : {})},
        });
        closing.finish(() => {
          onSaved?.(updated);
          onChanged();
          onClose();
        });
      })}>{action.busy ? uiText("正在保存…") : uiText("保存设置")}</Button></DialogActions></div>
    {closing.confirmation}</Dialog>;
}
