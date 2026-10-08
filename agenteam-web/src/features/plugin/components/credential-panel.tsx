"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";

import {PageHeader} from "@/components/ui/page-header";

import {Select} from "@/components/ui/select";


import {useState} from "react";
import {Dialog, DialogActions, DialogCancel, DialogForm, useDialogControl} from "@/components/ui/dialog";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {useApiPage} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import type {Credential, CredentialKind} from "../types/plugin";
import ui from "@/components/ui/surface.module.css";
import styles from "./plugin.module.css";

export const credentialKinds: Record<CredentialKind, string> = {
  api_key: "访问密钥",
  bearer: "访问令牌",
  basic: "用户名和密码",
  database: "数据库账号"
};

export function CredentialPanel({enterpriseId, canManage}: { enterpriseId: string; canManage: boolean }) {
  const uiText = useT();
  const [refresh, setRefresh] = useState(0);
  const [editing, setEditing] = useState<Credential | "new" | null>(null);
  const [revoking, setRevoking] = useState<Credential | null>(null);
  const list = useApiPage<Credential>(organizationPath(enterpriseId, "/credentials"), refresh);
  const changed = () => {
    setRefresh((value) => value + 1);
    setEditing(null);
    setRevoking(null);
  };
  return <>
    <PageHeader title={uiText("连接凭据")}
                description={canManage ? uiText("管理插件和数据源使用的连接账号。") : uiText("查看插件和数据源使用的连接账号。")}
                actions={<>
                  <div className={ui.actions}><Button className={ui.button}
                                                      onClick={() => setRefresh((value) => value + 1)}>{uiText("刷新")}</Button>{canManage &&
                    <Button className={ui.primary} onClick={() => setEditing("new")}>{uiText("新建凭据")}</Button>}
                  </div>
                </>}/>
    <QueryState {...list} hasData={Boolean(list.data?.items.length)} empty={uiText("暂无凭据。")}>
      <div className={styles.tableWrap}>
        <table className={styles.table}>
          <thead>
          <tr>
            <th>{uiText("名称")}</th>
            <th>{uiText("类型")}</th>
            <th>{uiText("状态")}</th>
            <th>{uiText("引用数")}</th>
            <th>{uiText("最近修改")}</th>
            {canManage && <th>{uiText("操作")}</th>}</tr>
          </thead>
          <tbody>{list.data?.items.map((credential) => <tr key={credential.id}>
            <td>{credential.name}</td>
            <td>{localizeCatalog(credentialKinds, uiText)[credential.kind]}</td>
            <td>{credential.status === "active" ? uiText("未撤销") : uiText("已撤销")}</td>
            <td>{credential.referenceCount}</td>
            <td><EnterpriseDateTime value={credential.updatedAt}/></td>
            {canManage && <td>{credential.status === "active" &&
              <div className={ui.actions}><Button className={ui.button}
                                                  onClick={() => setEditing(credential)}>{uiText("更新凭据")}</Button>
                <Button className={ui.danger} disabled={credential.referenceCount > 0}
                        onClick={() => setRevoking(credential)}>{uiText("撤销")}</Button></div>}</td>}</tr>)}</tbody>
        </table>
      </div>
    </QueryState><Pagination {...list} hasMore={list.data?.hasMore}/>
    {canManage && editing &&
      <CredentialEditDialog enterpriseId={enterpriseId} credential={editing === "new" ? null : editing}
                            onClose={() => setEditing(null)} onSaved={changed}/>}
    {canManage && revoking &&
      <CredentialRevokeDialog enterpriseId={enterpriseId} credential={revoking} onClose={() => setRevoking(null)}
                              onSaved={changed}/>}
  </>;
}

export function CredentialEditDialog({enterpriseId, credential, kinds, onClose, onSaved}: {
  enterpriseId: string;
  credential: Credential | null;
  kinds?: CredentialKind[];
  onClose: () => void;
  onSaved: (value: Credential) => void
}) {
  const uiText = useT();
  const action = useFormAction();
  const dialog = useDialogControl();
  const [name, setName] = useState(credential?.name ?? "");
  const [kind, setKind] = useState<CredentialKind>(credential?.kind ?? (kinds?.includes("bearer") ? "bearer" : kinds?.[0]) ?? "bearer");
  const [value, setValue] = useState("");
  const [username, setUsername] = useState("");
  const paired = kind === "basic" || kind === "database";
  return <Dialog title={credential ? uiText("更新“{0}”的凭据", [credential.name]) : uiText("新建凭据")}
                 onClose={onClose} dialogRef={dialog.ref} busy={action.busy}>
    <DialogForm className={ui.form} onSubmit={(event) => {
      event.preventDefault();
      void action.execute(async () => {
        const secret = kind === "database" ? JSON.stringify({
          username,
          password: value
        }) : kind === "basic" ? `${username}:${value}` : value;
        const saved = await action.mutation.run<Credential>(organizationPath(enterpriseId, credential ? `/credentials/${encodeURIComponent(credential.id)}/rotate` : "/credentials"),
          {method: "POST", revision: credential?.revision, body: credential ? {secret} : {name, kind, secret}});
        setValue("");
        setUsername("");
        dialog.close(() => onSaved(saved));
      }, "");
    }}>
      {!credential && <><label className={ui.field}><span>{uiText("名称")}</span><Input className={ui.input}
                                                                                        value={name} maxLength={80}
                                                                                        required disabled={action.busy}
                                                                                        onChange={(event) => setName(event.target.value)}/></label>
        <label className={ui.field}><span>{uiText("类型")}</span><Select className={ui.select} value={kind}
                                                                         disabled={action.busy} onChange={(event) => {
          setKind(event.target.value as CredentialKind);
          setValue("");
          setUsername("");
        }}>
          {Object.entries(localizeCatalog(credentialKinds, uiText)).filter(([id]) => !kinds || kinds.includes(id as CredentialKind)).map(([id, label]) =>
            <option key={id} value={id}>{label}</option>)}</Select></label></>}
      {paired &&
        <label className={ui.field}><span>{uiText("用户名")}</span><Input className={ui.input} value={username} required
                                                                          maxLength={255} disabled={action.busy}
                                                                          autoComplete="off"
                                                                          onChange={(event) => setUsername(event.target.value)}/></label>}
      <label
        className={ui.field}><span>{paired ? uiText("密码") : localizeCatalog(credentialKinds, uiText)[kind]}</span><Input
        className={ui.input} type="password" required value={value} maxLength={8192}
        disabled={action.busy} autoComplete="new-password" onChange={(event) => setValue(event.target.value)}/></label>
      <MutationFeedback action={action}/>
      <DialogActions className={ui.footer}><DialogCancel className={ui.button} type="button"
                                                         disabled={action.busy}>{uiText("取消")}</DialogCancel><Button
        className={ui.primary}
        disabled={action.busy}>{action.busy ? uiText("正在保存…") : uiText("保存")}</Button></DialogActions>
    </DialogForm>
  </Dialog>;
}

function CredentialRevokeDialog({enterpriseId, credential, onClose, onSaved}: {
  enterpriseId: string;
  credential: Credential;
  onClose: () => void;
  onSaved: () => void
}) {
  const uiText = useT();
  const action = useFormAction();
  const dialog = useDialogControl();
  return <Dialog title={uiText("撤销“{0}”？", [credential.name])} onClose={onClose} dialogRef={dialog.ref}
                 busy={action.busy}><p className={ui.description}>{uiText("撤销后不能继续用于连接。")}</p>
    <MutationFeedback action={action}/><DialogActions className={ui.footer}><DialogCancel className={ui.button}
                                                                                          disabled={action.busy}>{uiText("取消")}</DialogCancel>
      <Button className={ui.danger} disabled={action.busy} onClick={() => void action.execute(async () => {
        await action.mutation.run(organizationPath(enterpriseId, `/credentials/${encodeURIComponent(credential.id)}`),
          {method: "DELETE", revision: credential.revision});
        dialog.close(onSaved);
      }, "")}>{uiText("撤销凭据")}</Button></DialogActions></Dialog>;
}
