"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Button} from "@/components/ui/button";


import {useEffect, useState} from "react";
import {SearchInput} from "@/components/ui/search-input";
import {organizationPath} from "../api/organization-api";
import {useCollectionPage} from "../hooks/use-collection-page";
import type {Invitation} from "../types/organization";
import {CollectionStatus, ConfirmAction, formatDate, statusLabel} from "./organization-shared";
import {InvitationEditor} from "./invitation-editor";
import styles from "./organization.module.css";

export {InvitationEditor} from "./invitation-editor";

const deliveryNames = {pending: "未发送", sent: "已发送", failed: "发送失败"};

export function InvitationPanel({
                                  enterpriseId,
                                  permissions,
                                  query,
                                  onQuery,
                                  externalRefresh = 0,
                                  hideInvite = false,
                                  hideToolbar = false
                                }: {
  enterpriseId: string;
  permissions: string[];
  query: string;
  onQuery: (value: string) => void;
  externalRefresh?: number;
  hideInvite?: boolean;
  hideToolbar?: boolean
}) {
  const uiText = useT();
  const [refresh, setRefresh] = useState(0);
  const list = useCollectionPage<Invitation>(enterpriseId, "invitations", query, refresh + externalRefresh);
  const [creating, setCreating] = useState<Invitation | null | undefined>(undefined);
  const [confirm, setConfirm] = useState<{ invitation: Invitation; action: "revoke" | "resend" } | null>(null);
  const canManage = permissions.includes("enterprise.members.manage");
  const canReadRoles = permissions.includes("enterprise.roles.view");
  const changed = () => setRefresh((value) => value + 1);
  const hasPending = list.page?.items.some((invitation) => invitation.status === "pending") ?? false;
  useEffect(() => {
    if (!hasPending) {
      return;
    }
    const timer = window.setInterval(() => {
      if (!document.hidden) {
        setRefresh((value) => value + 1);
      }
    }, 10000);
    return () => window.clearInterval(timer);
  }, [hasPending]);
  return <>
    {!hideToolbar && <div className={styles.toolbar}><SearchInput value={query} onChange={(event) => {
      list.first();
      onQuery(event.target.value);
    }} placeholder={uiText("查找受邀邮箱或姓名")} aria-label={uiText("查找邀请")}/>
      {!hideInvite && canManage && canReadRoles &&
        <Button className={styles.primary} onClick={() => setCreating(null)}>{uiText("邀请成员")}</Button>}
      <Button className={styles.secondary} onClick={changed}>{uiText("刷新")}</Button></div>}
    <CollectionStatus list={list} empty={query ? uiText("没有匹配的邀请。") : uiText("暂无邀请记录。")}>
      <div className={styles.tableWrap}>
        <table className={styles.table}>
          <thead>
          <tr>
            <th>{uiText("受邀成员")}</th>
            <th>{uiText("邀请状态")}</th>
            <th>{uiText("邮件")}</th>
            <th>{uiText("有效期至")}</th>
            <th>{uiText("操作")}</th>
          </tr>
          </thead>
          <tbody>{list.page?.items.map((invitation) => <tr key={invitation.id}>
            <td><strong>{invitation.displayName || invitation.email || uiText("邀请码 / 邀请链接")}</strong>
              {invitation.displayName && <span className={styles.secondaryText}>{invitation.email}</span>}</td>
            <td><span className={styles.status}>{uiText(statusLabel(invitation.status))}</span></td>
            <td>{invitation.email ? localizeCatalog(deliveryNames, uiText)[invitation.deliveryStatus] : uiText("无需邮件")}</td>
            <td>{formatDate(invitation.expiresAt)}</td>
            <td>
              <div className={styles.rowActions}>
                {canManage && invitation.status === "pending" && <>{invitation.email &&
                  <Button className={styles.textButton}
                          onClick={() => setConfirm({invitation, action: "resend"})}>{uiText("重发")}</Button>}
                  <Button className={styles.textButton}
                          onClick={() => setConfirm({invitation, action: "revoke"})}>{uiText("撤回")}</Button></>}
                {canManage && canReadRoles && (invitation.status === "expired" || invitation.status === "revoked") &&
                  <Button className={styles.textButton}
                          onClick={() => setCreating(invitation)}>{uiText("重新邀请")}</Button>}
              </div>
            </td>
          </tr>)}</tbody>
        </table>
      </div>
    </CollectionStatus>
    {creating !== undefined && <InvitationEditor enterpriseId={enterpriseId} initial={creating}
                                                 canReadTeams={permissions.includes("enterprise.teams.view")}
                                                 onClose={() => setCreating(undefined)} onDone={changed}/>}
    {confirm && <ConfirmAction title={confirm.action === "revoke" ? uiText("撤回邀请") : uiText("重新发送邀请")}
                               description={confirm.action === "revoke" ? uiText("撤回后，此邀请码与邀请链接将无法继续使用。") : uiText("再次向 {0} 发送邀请。原有效期保持不变。", [confirm.invitation.email])}
                               path={organizationPath(enterpriseId, `/invitations/${encodeURIComponent(confirm.invitation.id)}/${confirm.action}`)}
                               method="POST" revision={confirm.invitation.revision}
                               onClose={() => setConfirm(null)} onDone={changed}/>}
  </>;
}
