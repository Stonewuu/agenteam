"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import {ErrorFeedback} from "@/components/ui/error-feedback";
import {errorMessage} from "@/lib/http/api-client";

import {AuthInput} from "./auth-input";
import {AuthFrame} from "./auth-frame";

import {type FormEvent, useState} from "react";
import {initializeSystem} from "../api/identity-api";
import type {IdentityUser} from "../types/identity";
import {TimezoneSelect} from "./timezone-select";
import styles from "./auth.module.css";

export function BootstrapForm({onSuccess}: { onSuccess: (user: IdentityUser) => void }) {
  const uiText = useT();
  const [setupCredential, setSetupCredential] = useState("");
  const [email, setEmail] = useState("");
  const [timezone, setTimezone] = useState("Asia/Shanghai");
  const [enterpriseName, setEnterpriseName] = useState("");
  const [username, setUsername] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    if (!/^[A-Za-z0-9._-]{3,64}$/.test(username.trim())) {
      setError(uiText("用户名需要 3～64 个字符，只能包含字母、数字、点、下划线和短横线"));
      return;
    }
    const displayNameLength = Array.from(displayName.trim()).length;
    if (displayNameLength < 1 || displayNameLength > 50) {
      setError(uiText("显示名需要 1～50 个字符"));
      return;
    }
    const passwordLength = Array.from(password).length;
    if (passwordLength < 12 || passwordLength > 128) {
      setError(uiText("密码需要 12～128 个字符"));
      return;
    }
    if (password !== confirmPassword) {
      setError(uiText("两次输入的密码不一致"));
      return;
    }
    setSubmitting(true);
    try {
      onSuccess(await initializeSystem({
        setupCredential,
        email,
        timezone,
        enterpriseName,
        username,
        displayName,
        password
      }));
    } catch (reason) {
      setError(errorMessage(reason, uiText("初始化失败，请稍后重试。")));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthFrame>
      <section className={styles.card} aria-labelledby="bootstrap-title">
        <h1 className={styles.title} id="bootstrap-title">{uiText("初始化系统")}</h1>
        <p className={styles.description}>{uiText("设置管理员账号，创建你的企业工作空间。")}</p>
        <form className={styles.form} onSubmit={handleSubmit}>
          <label className={styles.field}>
            <span className={styles.label}>{uiText("初始化凭据")}</span>
            <AuthInput type="password" passwordLabel={uiText("初始化凭据")} value={setupCredential}
                       onChange={(event) => setSetupCredential(event.target.value)} autoComplete="off"
                       placeholder={uiText("由部署人员提供")} required/>
          </label>
          <label className={styles.field}>
            <span className={styles.label}>{uiText("企业名称")}</span>
            <AuthInput value={enterpriseName} onChange={(event) => setEnterpriseName(event.target.value)} required/>
          </label>
          <label className={styles.field}>
            <span className={styles.label}>{uiText("企业时区")}</span>
            <TimezoneSelect value={timezone} onChange={setTimezone} disabled={submitting}/>
          </label>
          <label className={styles.field}>
            <span className={styles.label}>{uiText("超级管理员用户名")}</span>
            <AuthInput value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username"
                       minLength={3} maxLength={64} required/>
          </label>
          <label className={styles.field}>
            <span className={styles.label}>{uiText("显示名称")}</span>
            <AuthInput value={displayName} onChange={(event) => setDisplayName(event.target.value)} required/>
          </label>
          <label className={styles.field}>
            <span className={styles.label}>{uiText("邮箱")}</span>
            <AuthInput type="email" value={email} onChange={(event) => setEmail(event.target.value)}
                       autoComplete="email" maxLength={254} required/>
          </label>
          <label className={styles.field}>
            <span className={styles.label}>{uiText("密码")}</span>
            <AuthInput type="password" value={password} onChange={(event) => setPassword(event.target.value)}
                       autoComplete="new-password" placeholder={uiText("12～128 个字符")} minLength={12} required/>
          </label>
          <label className={styles.field}>
            <span className={styles.label}>{uiText("确认密码")}</span>
            <AuthInput type="password" passwordLabel={uiText("确认密码")} value={confirmPassword}
                       onChange={(event) => setConfirmPassword(event.target.value)} autoComplete="new-password"
                       minLength={12} required/>
          </label>
          <ErrorFeedback error={error ?? ""}/>
          <Button className={styles.button} type="submit" disabled={submitting}>
            {submitting ? uiText("正在初始化…") : uiText("创建超级管理员")}
          </Button>
        </form>
      </section>
    </AuthFrame>
  );
}
