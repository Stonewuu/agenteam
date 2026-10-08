"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";

import {useState, useSyncExternalStore} from "react";
import {ErrorFeedback} from "@/components/ui/error-feedback";
import {Dialog, DialogActions, DialogCancel, DialogForm} from "@/components/ui/dialog";
import {Toggle} from "@/components/ui/toggle";
import {IconDeviceDesktop, IconMoon, IconSun, IconUser} from "@/components/ui/icons";
import {useConfirmClose} from "@/features/workspace/components/use-confirm-close";
import {useBlockNavigation} from "@/features/workspace/components/navigation-guard";
import type {IdentityUser, Preferences} from "../types/identity";
import {useFormAction} from "../hooks/use-form-action";
import {loadIdentity} from "../api/identity-api";
import {applyTheme, currentTheme, subscribeTheme} from "../lib/theme";
import {passwordProblem} from "../lib/password-validation";
import styles from "./auth.module.css";
import settings from "./settings.module.css";

type SettingsProps = { user: IdentityUser; onChange: (user: IdentityUser) => void };

export function FormFeedback({action, onReload}: {
  action: ReturnType<typeof useFormAction>;
  onReload?: () => Promise<void>
}) {
  const uiText = useT();
  const counts = typeof action.details.counts === "object" && action.details.counts ? action.details.counts as Record<string, unknown> : {};
  const labels = {
    members: uiText("成员"),
    pendingInvitations: uiText("待接受邀请"),
    resourceGrants: uiText("资源授权"),
    quotaPolicies: uiText("次数规则"),
    openTodos: uiText("未结束待办")
  };
  const dependencies = Object.entries(labels).flatMap(([key, label]) => typeof counts[key] === "number" && Number.isSafeInteger(counts[key]) && (counts[key] as number) > 0 ? [`${label}：${counts[key]}`] : []);
  return <>
    <ErrorFeedback error={action.error} fieldErrors={action.fieldErrors} requestId={action.requestId}
                   onFieldChange={action.clearFieldError}/>
    {dependencies.length > 0 &&
      <ul className={styles.description}>{dependencies.map((detail) => <li key={detail}>{detail}</li>)}</ul>}
    {action.conflict && onReload && <Button className={styles.buttonSecondary} type="button" disabled={action.busy}
                                            onClick={() => void action.execute(onReload, uiText("已重新加载，请确认内容后再保存。"))}>{uiText("重新加载当前内容")}</Button>}
  </>;
}

export function ProfileSettings({user, onChange}: SettingsProps) {
  const uiText = useT();
  const [draft, setDraft] = useState<{ displayName: string; revision: string } | null>(null);
  const [emailOpen, setEmailOpen] = useState(false);
  const action = useFormAction();
  useBlockNavigation(Boolean(draft && draft.displayName !== user.displayName), action.busy);

  async function reload() {
    const latest = await loadIdentity();
    if (!latest) {
      throw new Error(uiText("请重新登录。"));
    }
    onChange(latest);
    setDraft(null);
  }

  return <section className={styles.section}><h2>{uiText("个人资料")}</h2>
    <form className={styles.form} onSubmit={(event) => {
      event.preventDefault();
      if (!draft) {
        return;
      }
      void action.execute(async () => {
        const saved = await action.mutation.run<IdentityUser>("/api/v1/me/profile", {
          method: "PATCH",
          revision: draft.revision,
          body: {displayName: draft.displayName}
        });
        onChange(saved);
        setDraft(null);
      });
    }}>
      <div className={settings.profileSummary}><span className="avatar large blue"><IconUser size={28}/></span>
        <div><h3>{user.displayName}</h3><p>{user.email || user.username}</p></div>
      </div>
      <label className={styles.field}><span className={styles.label}>{uiText("显示名")}</span><Input
        className={styles.input} value={draft?.displayName ?? user.displayName} disabled={action.busy} required
        maxLength={80} onChange={(event) => setDraft((current) => ({
        displayName: event.target.value,
        revision: current?.revision ?? user.revision
      }))}/></label>
      {user.capabilities.includes("account.email.change") && <div className={styles.field}><span className={styles.label}>{uiText("邮箱")}</span>
        <div className={settings.emailRow}>
          <span>{user.email ?? uiText("尚未设置邮箱")}{user.email && !user.emailVerified ? uiText(" · 未验证") : ""}</span><Button
          type="button" className={styles.textLink} onClick={() => setEmailOpen(true)}>{uiText("修改邮箱")}</Button>
        </div>
      </div>}
      <FormFeedback action={action} onReload={reload}/><Button className={styles.button}
                                                               disabled={action.busy || !draft || draft.displayName.trim() === user.displayName}>{action.busy ? uiText("正在保存…") : uiText("保存资料")}</Button>
    </form>
    {emailOpen && <EmailSettings user={user} onChange={onChange} onClose={() => setEmailOpen(false)}/>}</section>;
}

export function EmailSettings({user, onClose}: SettingsProps & { onClose: () => void }) {
  const uiText = useT();
  const [email, setEmail] = useState(user.email ?? "");
  const [password, setPassword] = useState("");
  const action = useFormAction();
  const closing = useConfirmClose(email !== (user.email ?? "") || Boolean(password), action.busy, onClose);
  return <><Dialog title={uiText("修改邮箱")} onClose={onClose} dialogRef={closing.dialogRef}
                   onRequestClose={closing.canClose} busy={action.busy}>
    <p className={styles.description}>{user.email ? uiText("当前邮箱：{0}", [user.email]) : uiText("尚未设置邮箱")}</p>
    <DialogForm className={styles.form} onSubmit={(event) => {
      event.preventDefault();
      void action.execute(async () => {
        await action.mutation.run("/api/v1/me/email-change", {
          method: "POST",
          body: {newEmail: email, currentPassword: password}
        });
        setPassword("");
      }, uiText("验证申请已提交，请稍后查看邮箱并打开验证链接。"));
    }}>
      <label className={styles.field}><span
        className={styles.label}>{user.emailVerified ? uiText("新邮箱") : uiText("验证邮箱")}</span><Input
        className={styles.input} type="email" autoComplete="email" value={email}
        onChange={(event) => setEmail(event.target.value)} disabled={action.busy} maxLength={254} required/></label>
      <label className={styles.field}><span className={styles.label}>{uiText("当前密码")}</span><Input
        className={styles.input} type="password" autoComplete="current-password" value={password}
        onChange={(event) => setPassword(event.target.value)} disabled={action.busy} required/></label>
      <FormFeedback action={action}/><DialogActions><DialogCancel className={styles.buttonSecondary}
                                                                  disabled={action.busy}>{uiText("取消")}</DialogCancel><Button
      className={styles.button}
      disabled={action.busy}>{action.busy ? uiText("正在提交…") : uiText("验证此邮箱")}</Button></DialogActions>
    </DialogForm>
  </Dialog>{closing.confirmation}</>;
}

export function PasswordSettings({onChange}: SettingsProps) {
  const uiText = useT();
  const [currentPassword, setCurrentPassword] = useState("");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const action = useFormAction();
  useBlockNavigation(Boolean(currentPassword || password || confirmation), action.busy);
  return <section className={styles.section}><h2>{uiText("账号安全")}</h2>
    <form className={styles.form} onSubmit={(event) => {
      event.preventDefault();
      const problem = passwordProblem(password, confirmation);
      if (problem) {
        action.setError(problem);
        return;
      }
      void action.execute(async () => {
        await action.mutation.run("/api/v1/me/password", {
          method: "POST",
          body: {currentPassword, newPassword: password}
        });
        setCurrentPassword("");
        setPassword("");
        setConfirmation("");
        const latest = await loadIdentity().catch((failure) => {
          console.error("更新密码后读取个人资料失败", failure);
          return null;
        });
        if (latest) {
          onChange(latest);
        }
      }, uiText("密码已更新，其他设备需要重新登录。"));
    }}>
      <h3 className={settings.formTitle}>{uiText("修改密码")}</h3><p
      className={settings.formDescription}>{uiText("修改后，其他设备上的登录将失效。")}</p>
      <label className={styles.field}><span className={styles.label}>{uiText("当前密码")}</span><Input
        className={styles.input} type="password" autoComplete="current-password" value={currentPassword}
        onChange={(event) => setCurrentPassword(event.target.value)} disabled={action.busy} required/></label>
      <label className={styles.field}><span className={styles.label}>{uiText("新密码")}</span><Input
        className={styles.input} type="password" autoComplete="new-password" aria-describedby="settings-password-hint"
        value={password} onChange={(event) => setPassword(event.target.value)} disabled={action.busy} required/><span
        id="settings-password-hint" className={settings.fieldHint}>{uiText("12～128 个字符。")}</span></label>
      <label className={styles.field}><span className={styles.label}>{uiText("确认新密码")}</span><Input
        className={styles.input} type="password" autoComplete="new-password" value={confirmation}
        onChange={(event) => setConfirmation(event.target.value)} disabled={action.busy} required/></label>
      <FormFeedback action={action}/><Button className={styles.button}
                                             disabled={action.busy}>{action.busy ? uiText("正在更新…") : uiText("更新密码")}</Button>
    </form>
  </section>;
}

export function ThemeSettings() {
  const uiText = useT();
  const preference = useSyncExternalStore(subscribeTheme, currentTheme, () => "system" as const);
  return <section className={styles.section}><h2>{uiText("外观")}</h2><p
    className={styles.description}>{uiText("选择阅读和工作时更舒适的界面。")}</p>
    <div className={settings.themeCards}>
      {([{value: "light", label: uiText("浅色"), icon: IconSun}, {
        value: "dark",
        label: uiText("深色"),
        icon: IconMoon
      }, {value: "system", label: uiText("跟随系统"), icon: IconDeviceDesktop}] as const).map(({
                                                                                                 value: theme,
                                                                                                 label,
                                                                                                 icon: Icon
                                                                                               }) => <Button
        type="button" key={theme} className={settings.themeCard} aria-pressed={preference === theme}
        onClick={() => applyTheme(theme)}><Icon size={29}/><span>{label}</span></Button>)}
    </div>
  </section>;
}

export function WorkPreferencesSettings({user, onChange, section}: SettingsProps & {
  section: "notifications" | "memory"
}) {
  const uiText = useT();
  const action = useFormAction();

  function change(field: "memoryEnabled" | "taskCompletionNotifications", value: boolean) {
    void action.execute(async () => {
      const saved = await action.mutation.run<Preferences>("/api/v1/me/preferences", {
        method: "PATCH",
        revision: user.preferences.revision,
        body: {[field]: value}
      });
      onChange({...user, preferences: saved});
    }, field === "memoryEnabled" ? value ? uiText("已允许使用保存的偏好。") : uiText("已停止使用保存的偏好。") : uiText("通知设置已更新。"));
  }

  return <section className={styles.section}>
    <h2>{section === "memory" ? uiText("个人偏好记忆") : uiText("通知偏好")}</h2>
    <div className={settings.preferenceRow}>
      <div><h3>{section === "notifications" ? uiText("任务完成提醒") : uiText("使用我保存的偏好")}</h3>
        <p>{section === "notifications" ? uiText("任务完成后，通过站内通知提醒我。") : uiText("在允许记忆的员工对话中，使用你明确保存的工作偏好。")}</p>
      </div>
      <Toggle label={section === "notifications" ? uiText("任务完成提醒") : uiText("使用我保存的偏好")} hideLabel
              checked={section === "notifications" ? user.preferences.taskCompletionNotifications : user.preferences.memoryEnabled}
              disabled={action.busy}
              onChange={(value) => change(section === "notifications" ? "taskCompletionNotifications" : "memoryEnabled", value)}/>
    </div>
    {section === "notifications" &&
      <p className={settings.preferenceNote}>{uiText("任务失败和需要确认的操作仍会提醒。")}</p>}
    <FormFeedback action={action} onReload={async () => {
      const latest = await loadIdentity();
      if (latest) {
        onChange(latest);
      }
    }}/>
  </section>;
}
