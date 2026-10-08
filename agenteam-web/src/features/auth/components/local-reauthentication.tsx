"use client";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {Dialog, DialogActions, DialogCancel, DialogForm} from "@/components/ui/dialog";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/field";
import {IconKey} from "@/components/ui/icons";
import {useFormAction} from "../hooks/use-form-action";
import {apiRequest, clearCsrf} from "@/lib/http/api-client";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useT} from "@/lib/i18n/locale-provider";
import ui from "@/components/ui/surface.module.css";

export function LocalReauthentication({onClose, onSuccess}: {onClose: () => void; onSuccess: () => void}) {
  const t = useT();
  const action = useFormAction();
  const [password, setPassword] = useState("");
  return <Dialog title={t("验证账号密码")} icon={<IconKey size={21}/>} busy={action.busy} onClose={onClose}>
    <DialogForm className={ui.form} onSubmit={(event) => {
      event.preventDefault();
      void action.execute(async () => {
        await apiRequest("/api/v1/auth/reauthenticate", {method: "POST", body: {password}});
        setPassword("");
        clearCsrf();
        window.dispatchEvent(new Event("agenteam:identity-changed"));
        onSuccess();
      }, "");
    }}>
      <Field label={t("当前账号密码")} required error={action.fieldErrors.password?.join(" ")}>
        <Input type="password" name="password" required maxLength={256} autoComplete="current-password"
               disabled={action.busy} value={password} onChange={(event) => setPassword(event.target.value)}/>
      </Field>
      <MutationFeedback action={action}/>
      <DialogActions><DialogCancel className={ui.button} disabled={action.busy}>{t("取消")}</DialogCancel>
        <Button className={ui.primary} disabled={action.busy}>{action.busy ? t("正在验证…") : t("验证并继续")}</Button>
      </DialogActions>
    </DialogForm>
  </Dialog>;
}
