"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import Link from "next/link";
import {useId, useState} from "react";
import {IconAlertCircle, IconLogout, IconRefresh, IconSettings} from "@/components/ui/icons";
import {editionClientExtension} from "@/features/edition/client-extension";
import {errorMessage} from "@/lib/http/api-client";
import type {IdentityUser} from "../types/identity";
import styles from "./enterprise-error-page.module.css";

type EnterpriseErrorPageProps = {
  enterpriseId: string;
  message: string;
  user: IdentityUser | null;
  onRetry: () => void;
  onSwitchEnterprise: (enterpriseId: string) => void;
  onSignOut: () => Promise<void>;
};

export function EnterpriseErrorPage({
                                      enterpriseId,
                                      message,
                                      user,
                                      onRetry,
                                      onSwitchEnterprise,
                                      onSignOut
                                    }: EnterpriseErrorPageProps) {
  const uiText = useT();
  const titleId = useId();
  const EnterpriseSelection = editionClientExtension.enterpriseSelection;
  const [signingOut, setSigningOut] = useState(false);
  const [signOutError, setSignOutError] = useState("");
  const alternatives = user?.enterprises.filter((enterprise) => enterprise.id !== enterpriseId && enterprise.status === "active") ?? [];

  async function leave() {
    if (signingOut) {
      return;
    }
    setSigningOut(true);
    setSignOutError("");
    try {
      await onSignOut();
    } catch (error) {
      setSignOutError(errorMessage(error));
    } finally {
      setSigningOut(false);
    }
  }

  return <main className={styles.page}>
    <div className={styles.content}>
      <section className={styles.card} aria-labelledby={titleId}>
        <span className={styles.symbol}><IconAlertCircle size={28} variant="Bulk"/></span>
        <h1 id={titleId} className={styles.title}>{uiText("页面暂时无法打开")}</h1>
        <p className={styles.message} role="alert">{message}</p>
        <Button type="button" className={styles.retry} disabled={signingOut} onClick={onRetry}>
          <IconRefresh size={18}/>{uiText("重新加载")}</Button>
        {alternatives.length > 0 && EnterpriseSelection && user?.capabilities.includes("enterprise.switch") &&
          <EnterpriseSelection enterprises={alternatives} selectedId="" disabled={signingOut} onSelect={onSwitchEnterprise}/>}
      </section>
      <nav className={styles.accountActions} aria-label={uiText("账号操作")}>
        {user ? <>
          <Link className={styles.accountAction} href="/settings"><IconSettings size={16}/>{uiText("个人设置")}</Link>
          <Button type="button" className={styles.accountAction} disabled={signingOut} onClick={() => void leave()}>
            <IconLogout size={16}/>{signingOut ? uiText("正在退出…") : uiText("退出登录")}
          </Button>
        </> : <Link className={styles.accountAction} href="/login">{uiText("返回登录")}</Link>}
      </nav>
      {signOutError &&
        <p className={styles.actionError} role="alert">{localizeUiMessage(signOutError ?? "", uiText)}</p>}
    </div>
  </main>;
}
