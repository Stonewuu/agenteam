"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {AuthFrame} from "./auth-frame";

import Link from "next/link";
import {useEffect, useState} from "react";
import {verifyEmail} from "../api/identity-api";
import {useMailLink} from "../hooks/use-mail-link";
import {errorMessage} from "@/lib/http/api-client";
import styles from "./auth.module.css";

export function EmailVerificationPage() {
  const link = useMailLink();
  return <EmailVerificationResult key={link.version} link={link}/>;
}

function EmailVerificationResult({link}: { link: ReturnType<typeof useMailLink> }) {
  const uiText = useT();
  const [state, setState] = useState<"verifying" | "completed" | "failed">("verifying");
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    if (!link.loaded || link.error || !link.token) {
      return;
    }
    let cancelled = false;
    verifyEmail(link.token).then(() => {
      if (!cancelled) {
        setState("completed");
      }
    }).catch((error) => {
      if (!cancelled) {
        setError(errorMessage(error));
        setState("failed");
      }
    });
    return () => {
      cancelled = true;
    };
  }, [link.loaded, link.error, link.token, retry]);
  const missing = link.loaded && !link.token;
  return <AuthFrame>
    <section className={styles.card}>
      <h1 className={styles.title}>{state === "completed" ? uiText("邮箱已完成验证") : uiText("验证邮箱")}</h1>
      {state === "completed" ?
        <p className={styles.description} role="status">{uiText("邮箱已更新，可以使用该邮箱登录。")}</p>
        : link.error || missing || state === "failed" ? <p className={styles.description}
                                                           role="alert">{link.error || error || uiText("请通过验证邮件中的完整链接打开此页面。")}</p>
          : <p className={styles.description} role="status">{uiText("正在确认邮箱…")}</p>}
      {state === "failed" && <Button className={styles.button} onClick={() => {
        setState("verifying");
        setRetry((value) => value + 1);
      }}>{uiText("重新确认")}</Button>}
      <div className={styles.actions}><Link className={styles.textLink}
                                            href="/settings">{uiText("个人设置")}</Link><Link
        className={styles.textLink} href="/login">{uiText("前往登录")}</Link></div>
    </section>
  </AuthFrame>;
}
