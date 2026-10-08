"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Fieldset} from "@/components/ui/fieldset";
import {Button} from "@/components/ui/button";

import {RadioGroup, RadioGroupItem} from "@/components/ui/radio-group";

import {useState} from "react";
import {Dialog, DialogActions, DialogCancel, DialogForm, useDialogControl} from "@/components/ui/dialog";
import {QueryState} from "@/components/ui/query-state";
import {ApiError} from "@/lib/http/api-client";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {type ApiOption, ApiOptionPicker} from "@/features/workspace/components/api-option-picker";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useBlockNavigation} from "@/features/workspace/components/navigation-guard";
import {organizationPath} from "../api/organization-api";
import type {Member} from "../types/organization";
import type {MemberRemovalImpact} from "../types/member-removal";
import ui from "@/components/ui/surface.module.css";
import styles from "./member-removal.module.css";

export function MemberRemovalDialog({enterpriseId, member, onClose, onRemoved}: {
  enterpriseId: string;
  member: Member;
  onClose: () => void;
  onRemoved: () => void
}) {
  const path = organizationPath(enterpriseId, `/members/${encodeURIComponent(member.userId)}`);
  const query = useApiQuery<MemberRemovalImpact>(`${path}/removal-impact`);
  return <RemovalPlan path={path} member={member} query={query} onClose={onClose} onRemoved={onRemoved}/>;
}

function RemovalPlan({path, member, query, onClose, onRemoved}: {
  path: string;
  member: Member;
  query: ReturnType<typeof useApiQuery<MemberRemovalImpact>>;
  onClose: () => void;
  onRemoved: () => void;
}) {
  const uiText = useT();
  const {data: impact, loading: refreshing, error, retry: onRefresh} = query;
  const action = useFormAction();
  const [owner, setOwner] = useState<ApiOption | null>(null);
  const [todoOwner, setTodoOwner] = useState<ApiOption | null>(null);
  const [handling, setHandling] = useState<"" | "transfer" | "cancel">("");
  const [picker, setPicker] = useState<"ownership" | "todo" | null>(null);
  const [discard, setDiscard] = useState(false);
  const [obsolete, setObsolete] = useState(false);
  const [impactToken, setImpactToken] = useState(impact?.impactToken);
  if (impact?.impactToken !== impactToken) {
    setImpactToken(impact?.impactToken);
    setOwner(null);
    setTodoOwner(null);
    setHandling("");
    setPicker(null);
    setDiscard(false);
    setObsolete(false);
    action.resetFeedback();
  }
  const ownership = Boolean(impact && (impact.resourceCount > 0 || impact.ownedTeamCount > 0));
  const dirty = Boolean(owner || todoOwner || handling);
  useBlockNavigation(dirty, action.busy);
  const dialog = useDialogControl();
  const disabled = action.busy || refreshing;
  const blocked = !impact || impact.lastAdministrator || ownership && !impact.canTransferOwnership;
  const ready = impact && !blocked && !obsolete && !error && (!ownership || owner) && (!impact.openTodoCount || handling === "cancel" || handling === "transfer" && todoOwner);
  return <Dialog title={uiText("移除{0}", [member.displayName])} onClose={onClose} dialogRef={dialog.ref}
                 onRequestClose={() => {
                   if (dirty) {
                     setDiscard(true);
                     return false;
                   }
                   return true;
                 }} busy={action.busy}>
    <QueryState {...query} hasData={Boolean(impact)} empty={uiText("暂时无法查看移除影响。")}>{impact && <>
      <p className={ui.description}>{member.displayName}{uiText("将无法访问当前企业。")}</p>
      <dl className={styles.counts}>
        <div>
          <dt>{uiText("资源")}</dt>
          <dd>{impact.resourceCount}</dd>
        </div>
        <div>
          <dt>{uiText("负责的团队")}</dt>
          <dd>{impact.ownedTeamCount}</dd>
        </div>
        <div>
          <dt>{uiText("未结束待办")}</dt>
          <dd>{impact.openTodoCount}</dd>
        </div>
        <div>
          <dt>{uiText("活动任务")}</dt>
          <dd>{impact.activeRunCount}</dd>
        </div>
        <div>
          <dt>{uiText("启用计划")}</dt>
          <dd>{impact.enabledScheduleCount}</dd>
        </div>
      </dl>
      {impact.activeRunCount > 0 &&
        <p className={ui.description}>{uiText("将请求停止这些活动任务。")}</p>}{impact.enabledScheduleCount > 0 &&
      <p className={ui.description}>{uiText("这些计划会暂停。")}</p>}
      {impact.lastAdministrator &&
        <p className={ui.error} role="alert">{uiText("请先保留另一名有效的企业管理员，再移除此成员。")}</p>}
      {ownership && !impact.canTransferOwnership &&
        <p className={ui.error} role="alert">{uiText("此成员负责的资源或团队，需要先由有权限的维护者完成交接。")}</p>}
      {error && <p className={ui.error} role="alert">{localizeUiMessage(error ?? "", uiText)}</p>}{refreshing &&
      <p className={ui.description} role="status">{uiText("正在重新查看影响…")}</p>}
      <DialogForm className={ui.form} onSubmit={(event) => {
        event.preventDefault();
        if (!ready) {
          return;
        }
        void action.execute(async () => {
          try {
            await action.mutation.run<Member>(`${path}/remove`, {
              method: "POST", revision: impact.memberRevision, body: {
                impactToken: impact.impactToken,
                resourceOwnerId: ownership ? owner?.id ?? null : null,
                todoOwnerId: impact.openTodoCount && handling === "transfer" ? todoOwner?.id ?? null : null,
                cancelOpenTodos: impact.openTodoCount > 0 && handling === "cancel"
              }
            });
          } catch (failed) {
            if (failed instanceof ApiError && ["DEPENDENCIES_CHANGED", "VERSION_CONFLICT"].includes(failed.code)) {
              setObsolete(true);
            }
            throw failed;
          }
          dialog.close(onRemoved);
        }, "");
      }}><Fieldset className={`${ui.form} ${styles.fields}`} disabled={disabled || blocked || obsolete}>
        {ownership && impact.canTransferOwnership &&
          <div className={ui.field}><span>{uiText("资源与团队由谁接手")}</span>
            <div className={styles.choice}><span>{owner?.name ?? uiText("尚未选择接收人")}</span>
              <Button className={ui.button} type="button"
                      onClick={() => setPicker("ownership")}>{uiText("选择资源接收人")}</Button></div>
          </div>}
        {impact.openTodoCount > 0 && <Fieldset className={styles.fields}>
          <legend>{uiText("如何处理未结束待办")}</legend>
          <RadioGroup name="todo-handling" value={handling} onValueChange={(next) => {
            setHandling(next === "cancel" ? "cancel" : "transfer");
            if (next === "cancel") {
              setTodoOwner(null);
            }
          }}>
            <label className={ui.check}><RadioGroupItem value="transfer"/>{uiText("转交给其他成员")}</label>
            <label className={ui.check}><RadioGroupItem value="cancel"/>{uiText("取消这些未结束待办")}</label>
          </RadioGroup>
          {handling === "transfer" &&
            <div className={styles.choice}><span>{todoOwner?.name ?? uiText("尚未选择接收人")}</span><Button
              className={ui.button} type="button" onClick={() => setPicker("todo")}>{uiText("选择待办接收人")}</Button>
            </div>}
        </Fieldset>}
      </Fieldset><MutationFeedback action={action} showFieldErrors/>
        <DialogActions className={ui.footer}><Button className={ui.button} type="button" disabled={disabled}
                                                     onClick={onRefresh}>{uiText("重新查看影响")}</Button><DialogCancel
          className={ui.button} disabled={action.busy}>{uiText("取消")}</DialogCancel>
          <Button className={ui.danger}
                  disabled={disabled || !ready}>{action.busy ? uiText("正在移除…") : uiText("确认移除")}</Button></DialogActions>
      </DialogForm></>}</QueryState>
    {!impact && <DialogActions className={ui.footer}><DialogCancel className={ui.button}>{uiText("取消")}</DialogCancel></DialogActions>}
    {picker && <ApiOptionPicker title={picker === "ownership" ? uiText("选择资源接收人") : uiText("选择待办接收人")}
                                path={`${path}/removal-recipients?kind=${picker}`}
                                selected={picker === "ownership" ? owner?.id : todoOwner?.id}
                                onClose={() => setPicker(null)} onSelect={(value) => {
      if (picker === "ownership") {
        setOwner(value);
      } else {
        setTodoOwner(value);
      }
      setPicker(null);
    }}/>}
    {discard &&
      <Dialog variant="discard" title={uiText("放弃当前交接选择？")} onClose={() => setDiscard(false)}><DialogActions
        className={ui.footer}><DialogCancel className={ui.button}>{uiText("继续选择")}</DialogCancel><DialogCancel
        className={ui.danger} onClick={() => dialog.close(onClose)}>{uiText("放弃选择")}</DialogCancel></DialogActions></Dialog>}
  </Dialog>;
}
