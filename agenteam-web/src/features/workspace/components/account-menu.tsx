"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Button} from "@/components/ui/button";

import {useState} from "react";
import {useRouter} from "next/navigation";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger
} from "@/components/ui/shadcn/dropdown-menu";
import {Dialog, DialogActions, DialogCancel, useDialogControl} from "@/components/ui/dialog";
import {
  IconBell,
  IconBookmark,
  IconChevronDown,
  IconLock,
  IconLogout,
  IconSun,
  IconUser,
  IconUserCircle
} from "@/components/ui/icons";
import {signOut} from "@/features/auth/api/identity-api";
import type {IdentityUser} from "@/features/auth/types/identity";
import {errorMessage} from "@/lib/http/api-client";
import ui from "@/components/ui/surface.module.css";

const settings = [
  {id: "profile", label: "个人资料", icon: IconUser}, {id: "security", label: "账号安全", icon: IconLock},
  {id: "appearance", label: "外观与主题", icon: IconSun}, {id: "notifications", label: "通知偏好", icon: IconBell},
  {id: "memory", label: "个人偏好记忆", icon: IconBookmark},
];

export function AccountMenu({user, go}: { user: IdentityUser; go: (action: () => void) => void }) {
  const uiText = useT();
  const router = useRouter();
  const dialog = useDialogControl();
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function logout() {
    if (busy) {
      return;
    }
    setBusy(true);
    setError("");
    try {
      await signOut();
      dialog.close(() => {
        setConfirm(false);
        router.replace("/login");
      });
    } catch (failure) {
      console.error("退出登录失败", failure);
      setError(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  return <><DropdownMenu>
    <DropdownMenuTrigger className="account-trigger" aria-label={uiText("个人账户：{0}", [user.displayName])}><span
      className="account-trigger-avatar"><IconUserCircle size={24} variant="Bulk"/></span><span
      className="account-trigger-name">{user.displayName}</span><IconChevronDown size={13}/></DropdownMenuTrigger>
    <DropdownMenuContent align="end" className="agenteam-menu agenteam-popup account-menu">
      <div className="account-summary"><span className="account-summary-avatar"><IconUserCircle size={30}
                                                                                                variant="Bulk"/></span>
        <div className="account-summary-copy"><strong>{user.displayName}</strong>{user.email &&
          <span>{user.email}</span>}</div>
      </div>
      <DropdownMenuSeparator/>
      {localizeCatalog(settings, uiText).filter((item) => item.id !== "security" || user.authenticationMethod === "channel" || user.capabilities.includes("account.password.change")).map(({id, label, icon: Icon}) => <DropdownMenuItem key={id} onClick={() => {
        const path = `/settings?tab=${id}`;
        if (window.location.pathname === "/settings") {
          window.history.pushState(null, "", path);
        } else {
          go(() => router.push(path));
        }
      }}><Icon size={18}/>{label}</DropdownMenuItem>)}
      <DropdownMenuSeparator/><DropdownMenuItem variant="destructive" onClick={() => setConfirm(true)}><IconLogout
      size={18}/>{uiText("退出登录")}</DropdownMenuItem>
    </DropdownMenuContent>
  </DropdownMenu>
    {confirm &&
      <Dialog title={uiText("退出登录")} dialogRef={dialog.ref} busy={busy} onClose={() => setConfirm(false)}><p
        className={ui.description}>{uiText("退出后需要重新登录，未提交的内容不会保存。")}</p>{error &&
        <p role="alert" className={ui.error}>{localizeUiMessage(error ?? "", uiText)}</p>}<DialogActions
        className={ui.footer}><DialogCancel className={ui.button} disabled={busy}>{uiText("取消")}</DialogCancel><Button
        className={ui.danger} disabled={busy}
        onClick={() => go(() => void logout())}>{busy ? uiText("正在退出…") : uiText("退出登录")}</Button></DialogActions></Dialog>}
  </>;
}
