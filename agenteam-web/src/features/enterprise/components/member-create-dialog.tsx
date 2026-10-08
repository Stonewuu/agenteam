"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/field";
import {DialogActions, DialogCancel, DialogForm} from "@/components/ui/dialog";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {AuthInput} from "@/features/auth/components/auth-input";
import {FormFeedback} from "@/features/auth/components/settings-forms";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {passwordProblem} from "@/features/auth/lib/password-validation";
import {useConfirmClose} from "@/features/workspace/components/use-confirm-close";
import {organizationPath} from "../api/organization-api";
import {EntityPicker, Modal} from "./organization-shared";
import styles from "./organization.module.css";

export function MemberCreateDialog({enterpriseId, canReadTeams, onClose, onDone}: {
  enterpriseId: string; canReadTeams: boolean; onClose: () => void; onDone: () => void;
}) {
  const uiText = useT();
  const [username, setUsername] = useState("");
  const [name, setName] = useState("");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [roleIds, setRoleIds] = useState<string[]>([]);
  const [teamIds, setTeamIds] = useState<string[]>([]);
  const action = useFormAction();
  const closing = useConfirmClose(Boolean(username || name || password || roleIds.length || teamIds.length), action.busy, onClose);
  return <Modal title={uiText("创建用户")} onClose={onClose} dialogRef={closing.dialogRef}
                onRequestClose={closing.canClose} busy={action.busy}>
    <AnimatedHeight preserveControlShadows><DialogForm className={styles.form} onInput={action.resetFeedback}
                                                       onSubmit={(event) => {
                                                         event.preventDefault();
                                                         if (username.trim().toLowerCase() === "test") {
                                                           const input = event.currentTarget.elements.namedItem("username");
                                                           if (input instanceof HTMLInputElement) {
                                                             input.focus();
                                                           }
                                                           return;
                                                         }
                                                         const problem = passwordProblem(password, confirmation);
                                                         if (problem || !roleIds.length) {
                                                           action.setError(problem || uiText("请至少选择一个角色。"));
                                                           return;
                                                         }
                                                         void action.execute(async () => {
                                                           await action.mutation.run(organizationPath(enterpriseId, "/members"), {
                                                             method: "POST",
                                                             body: {
                                                               username,
                                                               password,
                                                               displayName: name,
                                                               roleIds,
                                                               teamIds
                                                             },
                                                           });
                                                           setPassword("");
                                                           setConfirmation("");
                                                           closing.finish(() => {
                                                             onDone();
                                                             onClose();
                                                           });
                                                         }, uiText("用户已创建。"));
                                                       }}>
      <Field label={uiText("用户名")} required
             error={username.trim().toLowerCase() === "test" ? uiText("此用户名已保留，请使用其他用户名。") : undefined}
             hint={uiText("3～64 个字符，可使用字母、数字、点、下划线和短横线。")}><Input name="username" value={username}
                                                                                    onChange={(event) => setUsername(event.target.value)}
                                                                                    autoComplete="off"
                                                                                    pattern="[A-Za-z0-9._\-]{3,64}"
                                                                                    maxLength={64}
                                                                                    disabled={action.busy}
                                                                                    required/></Field>
      <Field label={uiText("显示名（选填）")}><Input name="displayName" value={name}
                                                   onChange={(event) => setName(event.target.value)} maxLength={50}
                                                   disabled={action.busy}/></Field>
      <Field label={uiText("密码")} required hint={uiText("12～128 个字符。")}><AuthInput name="password" type="password"
                                                                                        value={password}
                                                                                        onChange={(event) => setPassword(event.target.value)}
                                                                                        autoComplete="new-password"
                                                                                        maxLength={256}
                                                                                        disabled={action.busy}
                                                                                        required/></Field>
      <Field label={uiText("确认密码")} required><AuthInput name="confirmation" type="password"
                                                            passwordLabel={uiText("确认密码")} value={confirmation}
                                                            onChange={(event) => setConfirmation(event.target.value)}
                                                            autoComplete="new-password" maxLength={256}
                                                            disabled={action.busy} required/></Field>
      <EntityPicker enterpriseId={enterpriseId} collection="roles" title={uiText("角色")} selected={roleIds}
                    onChange={setRoleIds} disabled={action.busy}/>
      {canReadTeams &&
        <EntityPicker enterpriseId={enterpriseId} collection="teams" title={uiText("初始团队")} selected={teamIds}
                      onChange={setTeamIds} disabled={action.busy}/>}
      <FormFeedback action={action}/><DialogActions><DialogCancel className={styles.secondary}
                                                                  disabled={action.busy}>{uiText("取消")}</DialogCancel>
      <Button className={styles.primary}
              disabled={action.busy || !roleIds.length}>{action.busy ? uiText("正在创建…") : uiText("创建用户")}</Button></DialogActions>
    </DialogForm></AnimatedHeight>{closing.confirmation}
  </Modal>;
}
