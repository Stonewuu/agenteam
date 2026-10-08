"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Button} from "@/components/ui/button";
import {Textarea} from "@/components/ui/textarea";

import {useEffect, useState} from "react";
import {Dialog, DialogActions, DialogCancel, DialogForm, useDialogControl} from "@/components/ui/dialog";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {useApiPage, useApiQuery} from "@/lib/http/use-api-query";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useBlockNavigation} from "@/features/workspace/components/navigation-guard";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {NotificationOpenedMarker} from "@/features/notification/components/notification-opened-marker";
import type {HireApplication} from "../types/employee";
import {EmployeeIdentity} from "./employee-identity";
import ui from "@/components/ui/surface.module.css";
import styles from "./employee.module.css";
import organization from "@/features/enterprise/components/organization.module.css";

const states = {pending: "待审批", approved: "已批准", rejected: "已拒绝", withdrawn: "已撤回", expired: "已过期"};
const actions = {approve: "批准", reject: "拒绝", withdraw: "撤回"};
type Decision = keyof typeof actions;

export function HireApplications({enterpriseId, refresh, onChanged, table = false}: {
  enterpriseId: string;
  refresh: number;
  onChanged: () => void;
  table?: boolean
}) {
  const uiText = useT();
  const list = useApiPage<HireApplication>(organizationPath(enterpriseId, "/hire-requests"), refresh, 0);
  const [selected, setSelected] = useState<{ application: HireApplication; decision: Decision } | null>(null);
  const [resultFocus, setResultFocus] = useState<string | null>(null);
  useEffect(() => {
    if (!selected && resultFocus) {
      document.getElementById(`application-${resultFocus}`)?.focus({preventScroll: true});
    }
  }, [selected, resultFocus]);
  return <>
    <QueryState {...list} hasData={Boolean(list.data?.items.length)} empty={uiText("暂无雇佣申请。")}>
      {table ? <div className={organization.tableWrap}>
        <table className={organization.table}>
          <thead>
          <tr>
            <th>{uiText("申请人")}</th>
            <th>{uiText("员工")}</th>
            <th>{uiText("申请时间")}</th>
            <th>{uiText("状态")}</th>
            <th>{uiText("操作")}</th>
          </tr>
          </thead>
          <tbody>{list.data?.items.map((application) => <tr key={application.id} id={`application-${application.id}`}
                                                            tabIndex={-1}>
            <td>{application.applicant.displayName}</td>
            <td><EmployeeIdentity name={application.agentName} icon={application.agentIcon}
                                  color={application.agentColor}/>{application.requestNote &&
              <span className={organization.secondaryText}>{application.requestNote}</span>}</td>
            <td><EnterpriseDateTime value={application.createdAt}/></td>
            <td>{localizeCatalog(states, uiText)[application.status]}{application.decisionNote &&
              <span className={organization.secondaryText}>{application.decisionNote}</span>}</td>
            <td>
              <div className={ui.actions}>{application.allowedActions.map((decision) => <Button key={decision}
                                                                                                className={decision === "approve" ? ui.primary : ui.button}
                                                                                                onClick={() => {
                                                                                                  setResultFocus(null);
                                                                                                  setSelected({
                                                                                                    application,
                                                                                                    decision
                                                                                                  });
                                                                                                }}>{localizeCatalog(actions, uiText)[decision]}</Button>)}</div>
            </td>
          </tr>)}</tbody>
        </table>
      </div> : <div className={styles.applications}>{list.data?.items.map((application) => <article
        className={styles.application} key={application.id} id={`application-${application.id}`} tabIndex={-1}>
        <ApplicationSummary application={application}/>
        {application.allowedActions.length > 0 &&
          <div className={ui.actions}>{application.allowedActions.map((decision) =>
            <Button key={decision} className={decision === "approve" ? ui.primary : ui.button} onClick={() => {
              setResultFocus(null);
              setSelected({application, decision});
            }}>{localizeCatalog(actions, uiText)[decision]}</Button>)}</div>}
      </article>)}</div>}
    </QueryState><Pagination {...list} hasMore={list.data?.hasMore}/>
    {selected && <ApplicationDecision key={`${selected.application.id}:${selected.decision}`}
                                      enterpriseId={enterpriseId} {...selected} onClose={() => setSelected(null)}
                                      onChanged={() => {
                                        setResultFocus(selected.application.id);
                                        onChanged();
                                      }}/>}
  </>;
}

function ApplicationSummary({application}: { application: HireApplication }) {
  const uiText = useT();
  return <>
    <div className={styles.applicationTitle}><h2><EmployeeIdentity name={application.agentName}
                                                                   icon={application.agentIcon}
                                                                   color={application.agentColor} size="small"/></h2>
      <span className={ui.chip}>{localizeCatalog(states, uiText)[application.status]}</span></div>
    <p className={ui.description}>{application.applicant.displayName} · <EnterpriseDateTime
      value={application.createdAt}/></p>
    {application.requestNote && <p className={ui.description}>{application.requestNote}</p>}
    {application.decisionNote && <p className={ui.description}>{uiText("处理说明：")}{application.decisionNote}</p>}
    {application.status === "pending" &&
      <p className={ui.description}>{uiText("有效至 ")}<EnterpriseDateTime value={application.expiresAt}/></p>}</>;
}

export function HireApplicationDetail({enterpriseId, id, refresh, onClose}: {
  enterpriseId: string;
  id: string;
  refresh: number;
  onClose: () => void
}) {
  const uiText = useT();
  const detail = useApiQuery<HireApplication>(organizationPath(enterpriseId, `/hire-requests/${encodeURIComponent(id)}`), refresh);
  return <Dialog title={uiText("雇佣申请详情")} onClose={onClose}>
    <QueryState {...detail} hasData={Boolean(detail.data)} empty={uiText("无法访问此申请。")}>
      {detail.data && <div className={ui.form}><ApplicationSummary application={detail.data}/>
        {!detail.loading && !detail.error &&
          <NotificationOpenedMarker enterpriseId={enterpriseId} targetType="hire_request" targetId={detail.data.id}/>}
      </div>}
    </QueryState><DialogActions className={ui.footer}><DialogCancel
    className={ui.button}>{uiText("关闭详情")}</DialogCancel></DialogActions>
  </Dialog>;
}

function ApplicationDecision({enterpriseId, application, decision, onClose, onChanged}: {
  enterpriseId: string; application: HireApplication; decision: Decision; onClose: () => void; onChanged: () => void;
}) {
  const uiText = useT();
  const action = useFormAction();
  const [reason, setReason] = useState("");
  const [discarding, setDiscarding] = useState(false);
  useBlockNavigation(Boolean(reason), action.busy);
  const dialog = useDialogControl();
  return <Dialog title={uiText("{0}雇佣申请", [localizeCatalog(actions, uiText)[decision]])} busy={action.busy}
                 onClose={onClose} dialogRef={dialog.ref} onRequestClose={() => {
    if (reason) {
      setDiscarding(true);
      return false;
    }
    return true;
  }}>
    <DialogForm className={ui.form} onSubmit={(event) => {
      event.preventDefault();
      void action.execute(async () => {
        await action.mutation.run(organizationPath(enterpriseId, `/hire-requests/${encodeURIComponent(application.id)}/${decision === "withdraw" ? "withdraw" : "decision"}`),
          {
            method: "POST", revision: application.revision, ...(decision === "withdraw" ? {} : {
              body: {
                decision,
                reason
              }
            })
          });
        dialog.close(() => {
          onChanged();
          onClose();
        });
      });
    }}>
      <p
        className={ui.description}>{uiText("{0}申请雇佣“{1}”。", [application.applicant.displayName, application.agentName])}</p>
      {decision !== "withdraw" &&
        <label className={ui.field}><span>{decision === "reject" ? uiText("拒绝原因（必填）") : uiText("处理说明")}</span>
          <Textarea className={ui.textarea} value={reason} maxLength={500} required={decision === "reject"}
                    onChange={(event) => setReason(event.target.value)}/></label>}
      <MutationFeedback action={action} onReload={() => {
        dialog.close(() => {
          onChanged();
          onClose();
        });
      }}/>
      <DialogActions className={ui.footer}><DialogCancel className={ui.button}
                                                         disabled={action.busy}>{uiText("取消")}</DialogCancel>
        <Button className={ui.primary}
                disabled={action.busy}>{action.busy ? uiText("正在提交…") : localizeCatalog(actions, uiText)[decision]}</Button></DialogActions>
    </DialogForm>
    {discarding && <Dialog variant="discard" title={uiText("放弃未提交的处理说明？")}
                           onClose={() => setDiscarding(false)}><DialogActions className={ui.footer}>
      <DialogCancel className={ui.button}>{uiText("继续编辑")}</DialogCancel><DialogCancel className={ui.danger}
                                                                                           onClick={() => dialog.close(onClose)}>{uiText("放弃修改")}</DialogCancel></DialogActions></Dialog>}
  </Dialog>;
}
