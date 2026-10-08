"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Textarea} from "@/components/ui/textarea";

import {PageHeader} from "@/components/ui/page-header";

import {DialogActions, DialogCancel, DialogForm} from "@/components/ui/dialog";

import {useEffect, useState} from "react";
import {SearchInput} from "@/components/ui/search-input";
import {IconDots, IconGitBranch, IconPlus, IconTrash} from "@/components/ui/icons";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger
} from "@/components/ui/shadcn/dropdown-menu";
import {useConfirmClose} from "@/features/workspace/components/use-confirm-close";
import {apiRequest, errorMessage} from "@/lib/http/api-client";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {FormFeedback} from "@/features/auth/components/settings-forms";
import {entityOption, organizationPath, readTeamMembers} from "../api/organization-api";
import {useCollectionPage} from "../hooks/use-collection-page";
import type {EntityOption, Team} from "../types/organization";
import {CollectionStatus, ConfirmAction, EntityPicker, Modal, statusLabel} from "./organization-shared";
import styles from "./organization.module.css";

export function TeamPanel({enterpriseId, userId, displayName, permissions, query, onQuery}: {
  enterpriseId: string;
  userId: string;
  displayName: string;
  permissions: string[];
  query: string;
  onQuery: (value: string) => void;
}) {
  const uiText = useT();
  const [refresh, setRefresh] = useState(0);
  const list = useCollectionPage<Team>(enterpriseId, "teams", query, refresh);
  const [editing, setEditing] = useState<Team | null | undefined>(undefined);
  const [confirm, setConfirm] = useState<{ team: Team; action: "delete" | "status" } | null>(null);
  const canManage = permissions.includes("enterprise.teams.manage");
  const canChooseMembers = permissions.includes("enterprise.members.view");
  const changed = () => setRefresh((value) => value + 1);
  return <>
    <PageHeader title={uiText("协作，从团队开始")} description={uiText("组织成员，共享工作范围，让各自的职责更清楚。")}
                actions={<>{canManage && <Button className={styles.primary} onClick={() => setEditing(null)}><IconPlus
                  size={17}/>{uiText("创建团队")}</Button>}</>}/>
    <div className={`${styles.toolbar} ${styles.teamToolbar}`}>
      <span>{list.page ? uiText("本页 {0} 个团队", [list.page.items.length]) : ""}</span><SearchInput value={query}
                                                                                                      onChange={(event) => {
                                                                                                        list.first();
                                                                                                        onQuery(event.target.value);
                                                                                                      }}
                                                                                                      placeholder={uiText("搜索团队…")}
                                                                                                      aria-label={uiText("查找团队")}/>
    </div>
    <CollectionStatus list={list} empty={query ? uiText("没有匹配的团队。") : uiText("暂无团队。")}>
      <div className={styles.cardGrid}>{list.page?.items.map((team) => <article key={team.id}
                                                                                className={styles.managementCard}>
        <div className={styles.cardHeading}><span className="avatar blue"><IconGitBranch size={25}/></span><span
          className="badge neutral">{uiText(statusLabel(team.status))}</span></div>
        <h2>{team.name}</h2><p>{team.description}</p>
        <div className={styles.teamOwner}>{uiText("负责人 · ")}{team.owner.displayName}</div>
        <footer className={styles.teamFooter}><span>{team.memberCount}{uiText(" 位成员")}</span>
          <div className={styles.rowActions}><Button className={styles.textButton}
                                                     onClick={() => setEditing(team)}>{canManage ? uiText("管理团队") : uiText("查看")}</Button>
            {canManage && <DropdownMenu><DropdownMenuTrigger
              render={<Button className="icon-button" aria-label={uiText("{0}的更多操作", [team.name])}><IconDots
                size={17}/></Button>}/><DropdownMenuContent align="end"><DropdownMenuItem onClick={() => setConfirm({
              team,
              action: "status"
            })}>{team.status === "active" ? uiText("停用团队") : uiText("启用团队")}</DropdownMenuItem><DropdownMenuItem
              onClick={() => setConfirm({team, action: "delete"})}><IconTrash size={16}/>{uiText("删除团队")}
            </DropdownMenuItem></DropdownMenuContent></DropdownMenu>}
          </div>
        </footer>
      </article>)}</div>
    </CollectionStatus>
    {editing !== undefined &&
      <TeamEditor enterpriseId={enterpriseId} initial={editing} owner={{id: userId, name: displayName}}
                  readOnly={!canManage}
                  canChooseMembers={canChooseMembers} onClose={() => setEditing(undefined)} onDone={changed}/>}
    {confirm && <ConfirmAction
      title={confirm.action === "delete" ? uiText("删除团队") : uiText("{0}团队", [confirm.team.status === "active" ? uiText("停用") : uiText("启用")])}
      description={confirm.action === "delete" ? uiText("删除「{0}」前，需要先处理团队成员和关联记录。", [confirm.team.name]) : uiText("确认{0}「{1}」？", [confirm.team.status === "active" ? uiText("停用") : uiText("启用"), confirm.team.name])}
      path={organizationPath(enterpriseId, `/teams/${encodeURIComponent(confirm.team.id)}${confirm.action === "status" ? "/status" : ""}`)}
      method={confirm.action === "delete" ? "DELETE" : "PATCH"}
      revision={confirm.team.revision}
      body={confirm.action === "status" ? {status: confirm.team.status === "active" ? "disabled" : "active"} : undefined}
      onClose={() => setConfirm(null)} onDone={changed}/>}
  </>;
}

async function editingData(enterpriseId: string, id: string, signal?: AbortSignal) {
  for (let attempt = 0; attempt < 2; attempt++) {
    const team = await apiRequest<Team>(organizationPath(enterpriseId, `/teams/${encodeURIComponent(id)}`), {signal});
    const members = await readTeamMembers(enterpriseId, id, signal);
    const current = await apiRequest<Team>(organizationPath(enterpriseId, `/teams/${encodeURIComponent(id)}`), {signal});
    if (team.revision === current.revision && current.memberCount === members.length) {
      return {team: current, members};
    }
  }
  throw new Error("团队成员正在变化，请重新加载后再编辑。");
}

function TeamEditor({enterpriseId, initial, owner, readOnly, canChooseMembers, onClose, onDone}: {
  enterpriseId: string;
  initial: Team | null;
  owner: EntityOption;
  readOnly: boolean;
  canChooseMembers: boolean;
  onClose: () => void;
  onDone: () => void;
}) {
  const uiText = useT();
  const [source, setSource] = useState(initial);
  const [name, setName] = useState(initial?.name ?? "");
  const [description, setDescription] = useState(initial?.description ?? "");
  const [ownerId, setOwnerId] = useState(initial?.owner.id ?? owner.id);
  const [memberIds, setMemberIds] = useState<string[]>([]);
  const [known, setKnown] = useState<EntityOption[]>([]);
  const [loading, setLoading] = useState(Boolean(initial));
  const [loadError, setLoadError] = useState("");
  const action = useFormAction();

  useEffect(() => {
    if (!initial) {
      return;
    }
    const controller = new AbortController();
    editingData(enterpriseId, initial.id, controller.signal).then(({team, members}) => {
      if (controller.signal.aborted) {
        return;
      }
      setSource(team);
      setName(team.name);
      setDescription(team.description);
      setOwnerId(team.owner.id);
      setMemberIds(members.map((member) => member.userId));
      setKnown(members.map((member) => entityOption("members", member)));
    }).catch((error) => {
      if (!controller.signal.aborted) {
        setLoadError(errorMessage(error));
      }
    })
      .finally(() => {
        if (!controller.signal.aborted) {
          setLoading(false);
        }
      });
    return () => controller.abort();
  }, [enterpriseId, initial]);

  async function reload() {
    if (!source) {
      return;
    }
    const {team, members} = await editingData(enterpriseId, source.id);
    setSource(team);
    setName(team.name);
    setDescription(team.description);
    setOwnerId(team.owner.id);
    setMemberIds(members.map((member) => member.userId));
    setKnown(members.map((member) => entityOption("members", member)));
    setLoadError("");
  }

  const disabled = readOnly || action.busy || loading || Boolean(loadError);
  const dirty = !readOnly && !loading && (name !== (source?.name ?? "") || description !== (source?.description ?? "") || ownerId !== (source?.owner.id ?? owner.id) || [...memberIds].sort().join() !== known.map((item) => item.id).sort().join());
  const closing = useConfirmClose(dirty, action.busy, onClose);
  const knownOwners = source ? [owner, {id: source.owner.id, name: source.owner.displayName}] : [owner];
  return <Modal title={source ? uiText("编辑团队") : uiText("创建团队")} onClose={onClose} dialogRef={closing.dialogRef}
                onRequestClose={closing.canClose} busy={action.busy}><DialogForm className={styles.form}
                                                                                 onSubmit={(event) => {
                                                                                   event.preventDefault();
                                                                                   if (disabled) {
                                                                                     return;
                                                                                   }
                                                                                   void action.execute(async () => {
                                                                                     await action.mutation.run(organizationPath(enterpriseId, source ? `/teams/${encodeURIComponent(source.id)}` : "/teams"), {
                                                                                       method: source ? "PUT" : "POST",
                                                                                       revision: source?.revision,
                                                                                       body: {
                                                                                         name,
                                                                                         description,
                                                                                         ownerUserId: ownerId,
                                                                                         memberIds
                                                                                       },
                                                                                     });
                                                                                     closing.finish(() => {
                                                                                       onDone();
                                                                                       onClose();
                                                                                     });
                                                                                   });
                                                                                 }}>
    {loading && <p role="status">{uiText("正在读取完整团队资料…")}</p>}
    {loadError && <p className={styles.error} role="alert">{localizeUiMessage(loadError ?? "", uiText)}</p>}
    <label className={styles.field}><span>{uiText("团队名称")}</span><Input className={styles.input} value={name}
                                                                            onChange={(event) => setName(event.target.value)}
                                                                            disabled={disabled} required/></label>
    <label className={styles.field}><span>{uiText("简介")}</span><Textarea className={styles.textarea}
                                                                           value={description}
                                                                           onChange={(event) => setDescription(event.target.value)}
                                                                           disabled={disabled}/></label>
    {canChooseMembers && !readOnly ? <>
      <EntityPicker enterpriseId={enterpriseId} collection="members" title={uiText("负责人")} single
                    selected={ownerId ? [ownerId] : []} onChange={(ids) => setOwnerId(ids[0] ?? "")} disabled={disabled}
                    known={knownOwners}/>
      {!loading &&
        <EntityPicker enterpriseId={enterpriseId} collection="members" title={uiText("团队成员")} selected={memberIds}
                      onChange={setMemberIds} maximum={500} disabled={disabled} known={known}/>}
    </> : <><p>{uiText("负责人：")}{source?.owner.displayName ?? owner.name}</p>
      <p>{uiText("成员：")}{loading ? uiText("读取中…") : uiText("{0} 人", [memberIds.length])}</p>
      <div className={styles.selectedItems}>{known.map((member) => <span key={member.id}
                                                                         className={styles.status}>{member.name}</span>)}</div>
    </>}
    <FormFeedback action={action} onReload={source ? reload : undefined}/>
    {loadError && <Button className={styles.secondary} type="button"
                          onClick={() => void action.execute(reload)}>{uiText("重新加载")}</Button>}
    <DialogActions className={styles.formActions}><DialogCancel className={styles.secondary} type="button"
                                                                disabled={action.busy}>{readOnly ? uiText("关闭") : uiText("取消")}</DialogCancel>
      {!readOnly && <Button className={styles.primary}
                            disabled={disabled || !ownerId}>{action.busy ? uiText("正在保存…") : uiText("保存团队")}</Button>}
    </DialogActions>
  </DialogForm>{closing.confirmation}</Modal>;
}
