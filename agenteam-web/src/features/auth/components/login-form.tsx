"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import Link from "next/link";

import {type FormEvent, useEffect, useState} from "react";
import {requestPasswordReset, signIn} from "../api/identity-api";
import type {IdentityUser} from "../types/identity";
import {useFormAction} from "../hooks/use-form-action";
import {Field} from "@/components/ui/field";
import {ErrorFeedback} from "@/components/ui/error-feedback";
import {AuthFrame} from "./auth-frame";
import {AuthInput} from "./auth-input";
import styles from "./auth.module.css";

export function LoginForm({onSuccess, embedded = false, onBusyChange}: {
  onSuccess: (user: IdentityUser) => void;
  embedded?: boolean;
  onBusyChange?: (busy: boolean) => void;
}) {
  const uiText = useT();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [recover, setRecover] = useState(false);
  const action = useFormAction();
  useEffect(() => {
    onBusyChange?.(action.busy);
    return () => onBusyChange?.(false);
  }, [action.busy, onBusyChange]);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void action.execute(async () => {
      if (recover) {
        await requestPasswordReset(username);
      } else {
        onSuccess(await signIn(username, password));
      }
    }, recover ? uiText("若账号信息匹配，将收到重置邮件。请查看邮箱中的链接。") : "");
  }

  const Heading = embedded ? "h2" : "h1";
  const content = <section aria-labelledby={!embedded || recover ? "login-title" : undefined}>
    {(!embedded || recover) && <Heading id="login-title">{recover ? uiText("找回密码") : uiText("欢迎回来")}</Heading>}
    <p
      className={styles.description}>{recover ? uiText("填写用户名或已验证的邮箱，我们会向你发送重置链接。") : embedded ? uiText("登录已有账号后，即可接受这份邀请。") : uiText("登录 AgenTeam，继续与你的数字员工一起工作。")}</p>
    <form className={styles.form} onSubmit={submit} onInput={action.resetFeedback}>
      <Field key={recover ? "recovery-account" : "login-account"} label={uiText("账号")} required
             error={action.fieldErrors.identifier?.join(" ")}><AuthInput name="identifier" value={username}
                                                                         onChange={(event) => setUsername(event.target.value)}
                                                                         autoComplete="username"
                                                                         placeholder={uiText("用户名或已验证的邮箱")}
                                                                         maxLength={254} required
                                                                         disabled={action.busy}/></Field>
      {!recover &&
        <Field label={uiText("密码")} required error={action.fieldErrors.password?.join(" ")}><AuthInput name="password"
                                                                                                         type="password"
                                                                                                         value={password}
                                                                                                         onChange={(event) => setPassword(event.target.value)}
                                                                                                         autoComplete="current-password"
                                                                                                         placeholder={uiText("请输入密码")}
                                                                                                         required
                                                                                                         disabled={action.busy}/></Field>}
      {!recover && <div className="auth-links">{!embedded && <span>{uiText("使用你的企业账号登录")}</span>}<Button
        className="text-button" type="button" disabled={action.busy} onClick={() => {
        setRecover(true);
        action.resetFeedback();
      }}>{uiText("忘记密码？")}</Button></div>}
      <ErrorFeedback error={action.error} fieldErrors={action.fieldErrors} requestId={action.requestId}
                     onFieldChange={action.clearFieldError}/>
      <Button className="button primary" type="submit"
              disabled={action.busy}>{action.busy ? uiText("正在提交…") : recover ? uiText("发送重置邮件") : embedded ? uiText("登录并继续") : uiText("登录")}</Button>
      {recover && <Button type="button" className="text-button" disabled={action.busy} onClick={() => {
        setRecover(false);
        action.resetFeedback();
      }}>{uiText("返回登录")}</Button>}
    </form>
    {!embedded && !recover && <p className={styles.hint}>{uiText("还没有账号？")}<Link className={styles.textLink}
                                                                                      href="/register">{uiText("使用邀请码注册")}</Link>
    </p>}
  </section>;
  return embedded ? content : <AuthFrame>{content}</AuthFrame>;
}
