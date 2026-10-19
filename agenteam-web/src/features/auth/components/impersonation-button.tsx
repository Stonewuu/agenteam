"use client";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {Dialog, DialogActions, DialogCancel, useDialogControl} from "@/components/ui/dialog";
import {IconUserCircle} from "@/components/ui/icons";
import {ApiMutation, clearCsrf, errorMessage} from "@/lib/http/api-client";
import {acceptRequestIdentity, broadcastIdentityChange} from "@/lib/http/request-identity";
import {useT} from "@/lib/i18n/locale-provider";
import {useEnterpriseIdentity} from "./enterprise-gate";
import {enterpriseEntryPath} from "../lib/workspace-path";
import type {ImpersonationStatus} from "../types/identity";
import ui from "@/components/ui/surface.module.css";

export function ImpersonationButton({enterpriseId, userId, displayName, status, className}: {
  enterpriseId: string; userId: string; displayName: string; status: string; className?: string
}) {
  const t = useT();
  const identity = useEnterpriseIdentity();
  const dialog = useDialogControl();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [mutation] = useState(() => new ApiMutation());
  if (!identity?.user.superAdmin || identity.user.impersonation || identity.user.id === userId || status !== "active") {
    return null;
  }

  async function start() {
    if (busy) {
      return;
    }
    setBusy(true);
    setError("");
    try {
      const next = await mutation.run<ImpersonationStatus>("/api/v1/auth/impersonation/start", {method: "POST",
        body: {userId, enterpriseId, returnPath: window.location.pathname + window.location.search}});
      acceptRequestIdentity(next.identityRevision);
      clearCsrf();
      broadcastIdentityChange();
      window.location.replace(enterpriseEntryPath(enterpriseId));
    } catch (failure) {
      console.error("切换用户身份失败", failure);
      setError(errorMessage(failure));
      setBusy(false);
    }
  }

  return <>
    <Button className={className} onClick={() => {
      setError("");
      setOpen(true);
    }}>{t("以该用户身份登录")}</Button>
    {open && <Dialog title={t("以 {0} 身份登录", [displayName])} dialogRef={dialog.ref} busy={busy}
      onClose={() => setOpen(false)}>
      <p className={ui.description}>{t("将按该用户的权限操作，修改会实际保存。当前浏览器中本站的其他标签页也会切换身份。")}</p>
      {error && <p role="alert" className={ui.error}>{error}</p>}
      <DialogActions><DialogCancel className={ui.button} disabled={busy}>{t("取消")}</DialogCancel>
        <Button className={ui.primary} disabled={busy} onClick={() => void start()}><IconUserCircle size={18}/>
          {busy ? t("正在切换…") : t("确认切换")}
        </Button>
      </DialogActions>
    </Dialog>}
  </>;
}
