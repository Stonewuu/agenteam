"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Textarea} from "@/components/ui/textarea";
import {Field} from "@/components/ui/field";
import {Tabs} from "@/components/ui/tabs";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {DialogActions, DialogCancel, DialogForm} from "@/components/ui/dialog";
import {useConfirmClose} from "@/features/workspace/components/use-confirm-close";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {FormFeedback} from "@/features/auth/components/settings-forms";
import {organizationPath} from "../api/organization-api";
import type {Invitation} from "../types/organization";
import {EntityPicker, formatDate, Modal} from "./organization-shared";
import styles from "./organization.module.css";

type IssuedInvitation = { invitation: Invitation; code: string; registrationPath: string };
type Method = "code" | "link" | "email";

export function InvitationEditor({enterpriseId, initial, canReadTeams, onClose, onDone}: {
  enterpriseId: string; initial: Invitation | null; canReadTeams: boolean; onClose: () => void; onDone: () => void;
}) {
  const uiText = useT();
  const [method, setMethod] = useState<Method>(initial?.email ? "email" : "code");
  const [email, setEmail] = useState(initial?.email ?? "");
  const [displayName, setDisplayName] = useState(initial?.displayName ?? "");
  const [roleIds, setRoleIds] = useState<string[]>(initial?.roleIds ?? []);
  const [teamIds, setTeamIds] = useState<string[]>(initial?.teamIds ?? []);
  const [note, setNote] = useState("");
  const [issued, setIssued] = useState<IssuedInvitation | null>(null);
  const [copied, setCopied] = useState("");
  const action = useFormAction();
  const dirty = !issued && Boolean(email || displayName || roleIds.length || teamIds.length || note);
  const closing = useConfirmClose(dirty, action.busy, onClose);

  function copy(value: string, label: string) {
    void action.execute(async () => {
      await navigator.clipboard.writeText(value);
      setCopied(label);
    }, "");
  }

  const url = issued ? new URL(issued.registrationPath, window.location.origin).href : "";
  return <Modal title={issued ? uiText("邀请已生成") : uiText("邀请成员")} onClose={onClose}
                dialogRef={closing.dialogRef} onRequestClose={closing.canClose} busy={action.busy}>
    <AnimatedHeight preserveControlShadows>{issued ? <div className={styles.form}>
      <p>{uiText("此邀请仅可使用一次，有效期至 ")}{formatDate(issued.invitation.expiresAt)}{uiText("。请复制后发送给受邀人。")}</p>
      <Field label={uiText("邀请码")}><Input value={issued.code} readOnly autoComplete="off"
                                             onFocus={(event) => event.target.select()}/></Field>
      <Button type="button" className={method === "code" ? styles.primary : styles.secondary}
              onClick={() => copy(issued.code, "code")}>{copied === "code" ? uiText("已复制邀请码") : uiText("复制邀请码")}</Button>
      <Field label={uiText("邀请链接")}><Input value={url} readOnly autoComplete="off"
                                               onFocus={(event) => event.target.select()}/></Field>
      <Button type="button" className={method === "link" ? styles.primary : styles.secondary}
              onClick={() => copy(url, "link")}>{copied === "link" ? uiText("已复制邀请链接") : uiText("复制邀请链接")}</Button>
      <FormFeedback action={action}/>
      <DialogActions><Button type="button" className={styles.secondary}
                             onClick={onClose}>{uiText("完成")}</Button></DialogActions>
    </div> : <DialogForm className={styles.form} onSubmit={(event) => {
      event.preventDefault();
      if (!roleIds.length) {
        action.setError(uiText("请至少选择一个角色。"));
        return;
      }
      void action.execute(async () => {
        const body = {...(displayName ? {displayName} : {}), roleIds, teamIds, note};
        if (method === "email") {
          await action.mutation.run(organizationPath(enterpriseId, "/invitations"), {
            method: "POST",
            body: {...body, email}
          });
          closing.finish(() => {
            onDone();
            onClose();
          });
        } else {
          const result = await action.mutation.run<IssuedInvitation>(organizationPath(enterpriseId, "/invitations/share"), {
            method: "POST",
            body
          });
          setIssued(result);
          onDone();
        }
      }, "");
    }}>
      <Tabs value={method} onChange={(value) => {
        if (!action.busy) {
          setMethod(value as Method);
          action.resetFeedback();
        }
      }} items={[{value: "code", label: uiText("邀请码")}, {
        value: "link",
        label: uiText("邀请链接")
      }, {value: "email", label: uiText("邮件邀请")}]} label={uiText("邀请方式")}/>
      {method === "email" &&
        <Field label={uiText("受邀邮箱")} required error={action.fieldErrors.email?.join(" ")}><Input name="email"
                                                                                                      type="email"
                                                                                                      value={email}
                                                                                                      onChange={(event) => setEmail(event.target.value)}
                                                                                                      maxLength={254}
                                                                                                      disabled={action.busy}
                                                                                                      required/></Field>}
      <Field label={uiText("成员显示名（选填）")}><Input name="displayName" value={displayName}
                                                       onChange={(event) => setDisplayName(event.target.value)}
                                                       maxLength={50} disabled={action.busy}/></Field>
      <EntityPicker enterpriseId={enterpriseId} collection="roles" title={uiText("角色")} selected={roleIds}
                    onChange={setRoleIds} disabled={action.busy}/>
      {canReadTeams &&
        <EntityPicker enterpriseId={enterpriseId} collection="teams" title={uiText("初始团队")} selected={teamIds}
                      onChange={setTeamIds} disabled={action.busy}/>}
      <Field label={uiText("邀请说明（选填）")}><Textarea name="note" value={note}
                                                        onChange={(event) => setNote(event.target.value)}
                                                        maxLength={200} disabled={action.busy}/></Field>
      <FormFeedback action={action}/>
      <DialogActions><DialogCancel className={styles.secondary}
                                   disabled={action.busy}>{uiText("取消")}</DialogCancel><Button
        className={styles.primary} disabled={action.busy || !roleIds.length}>
        {action.busy ? uiText("正在创建…") : method === "email" ? uiText("发送邀请") : method === "link" ? uiText("生成邀请链接") : uiText("生成邀请码")}
      </Button></DialogActions>
    </DialogForm>}</AnimatedHeight>{closing.confirmation}
  </Modal>;
}
