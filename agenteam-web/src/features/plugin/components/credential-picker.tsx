"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Button} from "@/components/ui/button";

import {useState} from "react";
import {Dialog, DialogAction, DialogActions} from "@/components/ui/dialog";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {useApiPage} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import type {Credential, CredentialKind} from "../types/plugin";
import {CredentialEditDialog, credentialKinds} from "./credential-panel";
import ui from "@/components/ui/surface.module.css";
import styles from "./plugin.module.css";

export function CredentialPicker({
                                   enterpriseId,
                                   value,
                                   onChange,
                                   canManage,
                                   canUse = canManage,
                                   readOnly = false,
                                   kinds = ["api_key", "bearer", "basic"]
                                 }: {
  enterpriseId: string;
  value: string | null;
  onChange: (value: string | null) => void;
  canManage: boolean;
  canUse?: boolean;
  readOnly?: boolean;
  kinds?: CredentialKind[];
}) {
  const uiText = useT();
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState<Credential | null>(null);
  const name = selected?.id === value ? selected.name : value ? uiText("已设置连接凭据") : uiText("未设置凭据");
  return <div className={ui.field}><span>{uiText("连接凭据")}</span>
    <div className={ui.actions}><span>{name}</span>
      {!readOnly && canUse && <Button className={ui.button} type="button"
                                      onClick={() => setOpen(true)}>{value ? uiText("更换") : uiText("选择凭据")}</Button>}
      {!readOnly && value && <Button className={ui.button} type="button" onClick={() => {
        onChange(null);
        setSelected(null);
      }}>{uiText("移除")}</Button>}</div>
    {open && <Picker enterpriseId={enterpriseId} kinds={kinds} canManage={canManage} onClose={() => setOpen(false)}
                     onChoose={(next) => {
                       setSelected(next);
                       onChange(next.id);
                       setOpen(false);
                     }}/>}
  </div>;
}

function Picker({enterpriseId, kinds, canManage, onClose, onChoose}: {
  enterpriseId: string;
  kinds: CredentialKind[];
  canManage: boolean;
  onClose: () => void;
  onChoose: (value: Credential) => void
}) {
  const uiText = useT();
  const [creating, setCreating] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const list = useApiPage<Credential>(organizationPath(enterpriseId, "/credentials"), refresh);
  const choices = list.data?.items.filter((value) => value.status === "active" && kinds.includes(value.kind)) ?? [];
  if (creating && canManage) {
    return <CredentialEditDialog enterpriseId={enterpriseId} credential={null} kinds={kinds} onClose={() => {
      setCreating(false);
      setRefresh((value) => value + 1);
    }} onSaved={onChoose}/>;
  }
  return <Dialog title={uiText("选择连接凭据")} onClose={onClose}><QueryState {...list} hasData={choices.length > 0}
                                                                              empty={uiText("当前页没有可选凭据。")}>
    <div className={styles.picker}>{choices.map((value) => <DialogAction className={ui.button} type="button"
                                                                         key={value.id}
                                                                         onAction={(close) => close(() => onChoose(value))}>
      {value.name}<span
      className={styles.secondary}>{localizeCatalog(credentialKinds, uiText)[value.kind]}</span></DialogAction>)}</div>
  </QueryState><Pagination {...list} hasMore={list.data?.hasMore}/>{canManage &&
    <DialogActions className={ui.footer}><DialogAction className={ui.button} type="button"
                                                       onAction={(close) => close(() => setCreating(true))}>{uiText("新建凭据")}</DialogAction></DialogActions>}
  </Dialog>;
}
