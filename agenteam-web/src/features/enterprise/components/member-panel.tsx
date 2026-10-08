"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {toast} from "@/components/ui/toast";

import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";

import {DialogActions, DialogCancel, DialogForm} from "@/components/ui/dialog";

import {useState} from "react";
import {useRouter} from "next/navigation";
import {SearchInput} from "@/components/ui/search-input";
import {IconUser} from "@/components/ui/icons";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {useConfirmClose} from "@/features/workspace/components/use-confirm-close";
import {useGuardedNavigation} from "@/features/workspace/components/navigation-guard";
import {type ApiPage, useApiQuery} from "@/lib/http/use-api-query";
import {apiRequest} from "@/lib/http/api-client";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {FormFeedback} from "@/features/auth/components/settings-forms";
import {organizationPath} from "../api/organization-api";
import {useCollectionPage} from "../hooks/use-collection-page";
import type {Member, Role, Team} from "../types/organization";
import {CollectionStatus, ConfirmAction, EntityPicker, Modal, statusLabel} from "./organization-shared";
import {MemberRemovalDialog} from "./member-removal-dialog";
import styles from "./organization.module.css";

export function MemberPanel({
                              enterpriseId,
                              userId,
                              permissions,
                              query,
                              onQuery,
                              hideSearch = false,
                              externalRefresh = 0
                            }: {
  enterpriseId: string;
  userId: string;
  permissions: string[];
  query: string;
  onQuery: (value: string) => void;
  hideSearch?: boolean;
  externalRefresh?: number
}) {
  const uiText = useT();
  const router = useRouter();
  const [removing, setRemoving] = useState<Member | null>(null);
  const [refresh, setRefresh] = useState(0);
  const list = useCollectionPage<Member>(enterpriseId, "members", query, refresh + externalRefresh);
  const [editing, setEditing] = useState<Member | null>(null);
  const [confirm, setConfirm] = useState<Member | null>(null);
  const canManage = permissions.includes("enterprise.members.manage");
  const roles = useApiQuery<ApiPage<Role>>(permissions.includes("enterprise.roles.view") ? organizationPath(enterpriseId, "/roles?limit=100") : null);
  const teams = useApiQuery<ApiPage<Team>>(permissions.includes("enterprise.teams.view") ? organizationPath(enterpriseId, "/teams?limit=100") : null);
  const names = (ids: string[], options?: { id: string; name: string }[]) => {
    const found = options?.filter((item) => ids.includes(item.id)).map((item) => item.name) ?? [];
    return found.length ? found.join("、") + (found.length < ids.length ? uiText(" 等 {0} 项", [ids.length]) : "") : ids.length ? uiText("{0} 个", [ids.length]) : "—";
  };
  const changed = () => setRefresh((value) => value + 1);
  return <>
    {!hideSearch && <div className={styles.toolbar}><SearchInput value={query} onChange={(event) => {
      list.first();
      onQuery(event.target.value);
    }} placeholder={uiText("搜索成员名称或邮箱…")} aria-label={uiText("查找成员")}/></div>}
    <CollectionStatus list={list} empty={query ? uiText("没有匹配的成员。") : uiText("暂无可查看的成员。")}>
      <div className={styles.tableWrap}>
        <table className={styles.table}>
          <thead>
          <tr>
            <th>{uiText("成员")}</th>
            <th>{uiText("团队")}</th>
            <th>{uiText("角色")}</th>
            <th>{uiText("状态")}</th>
            <th>{uiText("加入日期")}</th>
            <th>{uiText("操作")}</th>
          </tr>
          </thead>
          <tbody>{list.page?.items.map((member) => <tr key={member.userId}>
            <td>
              <div className={styles.memberIdentity}><IconUser
                size={23}/><span><strong>{member.displayName}</strong>{member.email &&
                <span className={styles.secondaryText}>{member.email}</span>}</span></div>
            </td>
            <td>{names(member.teamIds, teams.data?.items)}</td>
            <td><span className="badge purple">{names(member.roleIds, roles.data?.items)}</span></td>
            <td><span
              className={`badge ${member.status === "active" ? "mint" : "neutral"}`}>{uiText(statusLabel(member.status))}</span>
            </td>
            <td><EnterpriseDateTime value={member.joinedAt} dateOnly includeYear/></td>
            <td>
              <div className={styles.rowActions}><Button className={styles.textButton}
                                                         onClick={() => setEditing(member)}>{canManage ? uiText("管理") : uiText("查看")}</Button>
              </div>
            </td>
          </tr>)}</tbody>
        </table>
      </div>
    </CollectionStatus>
    {editing &&
      <MemberEditor enterpriseId={enterpriseId} initial={editing} permissions={permissions} readOnly={!canManage}
                    onClose={() => setEditing(null)} onDone={changed} onStatus={(member) => {
        setEditing(null);
        setConfirm(member);
      }} onRemove={(member) => {
        setEditing(null);
        setRemoving(member);
      }}/>}
    {removing && <MemberRemovalDialog enterpriseId={enterpriseId} member={removing} onClose={() => setRemoving(null)}
                                      onRemoved={() => {
                                        setRemoving(null);
                                        if (removing.userId === userId) {
                                          router.replace("/settings");
                                        } else {
                                          toast.success(uiText("成员已移出企业。"));
                                          list.first();
                                          changed();
                                        }
                                      }}/>}
    {confirm &&
      <ConfirmAction title={uiText("{0}成员", [confirm.status === "active" ? uiText("停用") : uiText("启用")])}
                     description={confirm.status === "active" ? uiText("停用后，「{0}」将无法访问当前企业。", [confirm.displayName]) : uiText("确认启用「{0}」的企业成员身份？", [confirm.displayName])}
                     path={organizationPath(enterpriseId, `/members/${encodeURIComponent(confirm.userId)}/status`)}
                     method="PATCH" revision={confirm.revision}
                     body={{status: confirm.status === "active" ? "disabled" : "active"}}
                     onClose={() => setConfirm(null)} onDone={changed}/>}
  </>;
}

function MemberEditor({enterpriseId, initial, permissions, readOnly, onClose, onDone, onStatus, onRemove}: {
  enterpriseId: string;
  initial: Member;
  permissions: string[];
  readOnly: boolean;
  onClose: () => void;
  onDone: () => void;
  onStatus: (member: Member) => void;
  onRemove: (member: Member) => void;
}) {
  const uiText = useT();
  const [source, setSource] = useState(initial);
  const [name, setName] = useState(initial.displayName);
  const [roleIds, setRoleIds] = useState(initial.roleIds);
  const [teamIds, setTeamIds] = useState(initial.teamIds);
  const action = useFormAction();
  const guard = useGuardedNavigation();
  const closing = useConfirmClose(!readOnly && (name !== source.displayName || [...roleIds].sort().join() !== [...source.roleIds].sort().join() || [...teamIds].sort().join() !== [...source.teamIds].sort().join()), action.busy, onClose);
  const canReadRoles = permissions.includes("enterprise.roles.view");
  const canReadTeams = permissions.includes("enterprise.teams.view");

  async function reload() {
    const latest = await apiRequest<Member>(organizationPath(enterpriseId, `/members/${encodeURIComponent(initial.userId)}`));
    setSource(latest);
    setName(latest.displayName);
    setRoleIds(latest.roleIds);
    setTeamIds(latest.teamIds);
  }

  return <Modal title={source.displayName} onClose={onClose} dialogRef={closing.dialogRef}
                onRequestClose={closing.canClose} busy={action.busy} drawer><DialogForm className={styles.form}
                                                                                        onSubmit={(event) => {
                                                                                          event.preventDefault();
                                                                                          if (readOnly) {
                                                                                            return;
                                                                                          }
                                                                                          void action.execute(async () => {
                                                                                            const body = {displayName: name, ...(canReadRoles ? {roleIds} : {}), ...(canReadTeams ? {teamIds} : {})};
                                                                                            await action.mutation.run(organizationPath(enterpriseId, `/members/${encodeURIComponent(source.userId)}`), {
                                                                                              method: "PATCH",
                                                                                              revision: source.revision,
                                                                                              body
                                                                                            });
                                                                                            closing.finish(() => {
                                                                                              onDone();
                                                                                              onClose();
                                                                                            });
                                                                                          });
                                                                                        }}>
    <label className={styles.field}><span>{uiText("成员显示名")}</span><Input className={styles.input} value={name}
                                                                              onChange={(event) => setName(event.target.value)}
                                                                              disabled={readOnly || action.busy}
                                                                              required/></label>
    {source.email && <p className={styles.description}>{uiText("邮箱：")}{source.email}</p>}
    {canReadRoles &&
      <EntityPicker enterpriseId={enterpriseId} collection="roles" title={uiText("角色")} selected={roleIds}
                    onChange={setRoleIds} disabled={readOnly || action.busy}/>}
    {canReadTeams &&
      <EntityPicker enterpriseId={enterpriseId} collection="teams" title={uiText("团队")} selected={teamIds}
                    onChange={setTeamIds} disabled={readOnly || action.busy}/>}
    <FormFeedback action={action} onReload={reload}/>
    <DialogActions className={styles.formActions}><DialogCancel className={styles.secondary} type="button"
                                                                disabled={action.busy}>{readOnly ? uiText("关闭") : uiText("取消")}</DialogCancel>
      {!readOnly && <Button className={styles.primary}
                            disabled={action.busy || (canReadRoles && roleIds.length === 0)}>{action.busy ? uiText("正在保存…") : uiText("保存成员")}</Button>}
    </DialogActions>
    {!readOnly &&
      <div className={styles.rowActions}><Button className={styles.textButton} type="button" disabled={action.busy}
                                                 onClick={() => guard ? guard(() => closing.finish(() => onStatus(source))) : closing.finish(() => onStatus(source))}>{source.status === "active" ? uiText("停用成员") : uiText("启用成员")}</Button><Button
        className={styles.textButton} type="button" disabled={action.busy}
        onClick={() => guard ? guard(() => closing.finish(() => onRemove(source))) : closing.finish(() => onRemove(source))}>{uiText("移出企业")}</Button>
      </div>}
  </DialogForm>{closing.confirmation}</Modal>;
}
