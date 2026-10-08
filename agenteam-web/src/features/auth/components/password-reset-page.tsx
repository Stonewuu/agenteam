"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import {ErrorFeedback} from "@/components/ui/error-feedback";
import {toast} from "@/components/ui/toast";

import {AuthInput} from "./auth-input";
import {AuthFrame} from "./auth-frame";

import Link from "next/link";
import {type FormEvent, useState} from "react";
import {requestPasswordReset, resetPassword} from "../api/identity-api";
import {useMailLink} from "../hooks/use-mail-link";
import {passwordProblem} from "../lib/password-validation";
import {errorMessage} from "@/lib/http/api-client";
import styles from "./auth.module.css";

export function PasswordResetPage() {
  const link = useMailLink();
  return <PasswordResetForm key={link.version} link={link}/>;
}

function PasswordResetForm({link}: { link: ReturnType<typeof useMailLink> }) {
  const uiText = useT();
  const [identifier, setIdentifier] = useState("");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [requestNew, setRequestNew] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [completed, setCompleted] = useState(false);
  const resetting = Boolean(link.token) && !requestNew;
  const linkError = requestNew ? "" : link.error;

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) {
      return;
    }
    setError("");
    if (resetting) {
      const problem = passwordProblem(password, confirmation);
      if (problem) {
        setError(problem);
        return;
      }
    }
    setBusy(true);
    try {
      if (resetting && link.token) {
        await resetPassword(link.token, password);
        setCompleted(true);
        setPassword("");
        setConfirmation("");
      } else {
        await requestPasswordReset(identifier);
        toast.success(uiText("若账号信息匹配，将收到重置邮件。请查看邮箱中的链接。"));
      }
    } catch (error) {
      setError(errorMessage(error));
    } finally {
      setBusy(false);
    }
  }

  return <AuthFrame>
    <section className={styles.card}>
      <h1
        className={styles.title}>{completed ? uiText("密码已更新") : resetting ? uiText("设置新密码") : uiText("找回密码")}</h1>
      <p
        className={styles.description}>{completed ? uiText("请使用新密码重新登录。") : resetting ? uiText("设置一个不易猜测的密码。") : uiText("填写用户名或已验证的邮箱。")}</p>
      {!link.loaded ? <p role="status">{uiText("正在读取链接…")}</p> : completed ? null :
        <form className={styles.form} onSubmit={submit}>
          {resetting ? <>
            <label className={styles.field}><span className={styles.label}>{uiText("新密码")}</span><AuthInput
              type="password" passwordLabel={uiText("新密码")} autoComplete="new-password"
              placeholder={uiText("12～128 个字符")} value={password}
              onChange={(event) => setPassword(event.target.value)} disabled={busy || Boolean(linkError)}
              required/></label>
            <label className={styles.field}><span className={styles.label}>{uiText("确认新密码")}</span><AuthInput
              type="password" passwordLabel={uiText("确认新密码")} autoComplete="new-password"
              placeholder={uiText("再次输入新密码")} value={confirmation}
              onChange={(event) => setConfirmation(event.target.value)} disabled={busy || Boolean(linkError)} required/></label>
          </> : <label className={styles.field}><span className={styles.label}>{uiText("账号")}</span><AuthInput
            value={identifier} onChange={(event) => setIdentifier(event.target.value)} autoComplete="username"
            maxLength={254} disabled={busy} required/></label>}
          <ErrorFeedback error={error || linkError}/>
          <Button className={styles.button}
                  disabled={busy || Boolean(linkError)}>{busy ? uiText("正在提交…") : resetting ? uiText("更新密码") : uiText("发送重置邮件")}</Button>
          {resetting && <Button className={styles.buttonSecondary} type="button" disabled={busy} onClick={() => {
            setRequestNew(true);
            setError("");
          }}>{uiText("重新申请重置链接")}</Button>}
        </form>}
      <div className={styles.actions}><Link className={styles.textLink} href="/login">{uiText("返回登录")}</Link></div>
    </section>
  </AuthFrame>;
}
