"use client";

import {useT} from "@/lib/i18n/locale-provider";

import Link from "next/link";
import {type FormEvent, useEffect, useState} from "react";
import {Button} from "@/components/ui/button";
import {ErrorFeedback} from "@/components/ui/error-feedback";
import {Field} from "@/components/ui/field";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {LoadingTransition} from "@/components/ui/loading-transition";
import {IconArrowRight, IconBuilding, IconCheck, IconClock, IconMail, IconUser} from "@/components/ui/icons";
import {Tabs, TabsContent, TabsList, TabsTrigger} from "@/components/ui/shadcn/tabs";
import {ApiError, apiRequest, errorMessage} from "@/lib/http/api-client";
import {loadIdentity, signOut} from "../api/identity-api";
import {useMailLink} from "../hooks/use-mail-link";
import {useFormAction} from "../hooks/use-form-action";
import {landingPath} from "../lib/identity-navigation";
import {passwordProblem} from "../lib/password-validation";
import type {IdentityUser} from "../types/identity";
import {AuthInput} from "./auth-input";
import {AuthFrame} from "./auth-frame";
import {LoginForm} from "./login-form";
import {FormFeedback} from "./settings-forms";
import invitationStyles from "./invitation.module.css";

type Preview = { enterpriseName: string; inviterName: string; maskedEmail: string | null; expiresAt: string };

export function InvitationPage({allowCode = false}: { allowCode?: boolean }) {
  const uiText = useT();
  const link = useMailLink();
  const [entered, setEntered] = useState("");
  const [code, setCode] = useState("");
  const [ignoreLink, setIgnoreLink] = useState(false);
  const token = code || (ignoreLink ? null : link.token);
  return (
    <AuthFrame back className={invitationStyles.page}>
      <AnimatedHeight preserveControlShadows>
        <LoadingTransition state={!link.loaded ? "loading" : token ? "invitation" : "code"}>
          {!link.loaded ? <p className={invitationStyles.intro} role="status">{uiText("正在读取邀请…")}</p> : token ? (
            <InvitationForm
              key={`${link.version}:${token}`}
              token={token}
              initialRegister={allowCode}
              onChangeCode={() => {
                setCode("");
                setEntered("");
                setIgnoreLink(true);
              }}
            />
          ) : (
            <section>
              <h1 className={invitationStyles.heading}>{uiText("加入你的团队")}</h1>
              <p className={invitationStyles.intro}>{uiText("填写邀请人提供的邀请码，继续加入企业。")}</p>
              <form className={invitationStyles.form} onSubmit={(event) => {
                event.preventDefault();
                setCode(entered.trim());
              }}>
                <Field label={uiText("邀请码")} required>
                  <AuthInput name="token" placeholder={uiText("粘贴邀请码")} value={entered}
                             onChange={(event) => setEntered(event.target.value)} autoComplete="off" spellCheck={false}
                             maxLength={100} required/>
                </Field>
                <ErrorFeedback error={link.error}/>
                <Button type="submit" className={invitationStyles.primary}>{uiText("查看邀请")}<IconArrowRight
                  size={17}/></Button>
              </form>
            </section>
          )}
        </LoadingTransition>
      </AnimatedHeight>
    </AuthFrame>
  );
}

function InvitationForm({token, initialRegister, onChangeCode}: {
  token: string;
  initialRegister: boolean;
  onChangeCode: () => void;
}) {
  const uiText = useT();
  const [user, setUser] = useState<IdentityUser | null>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [register, setRegister] = useState(initialRegister);
  const [loginBusy, setLoginBusy] = useState(false);
  const [username, setUsername] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [error, setError] = useState("");
  const [joined, setJoined] = useState<IdentityUser | null>(null);
  const [refresh, setRefresh] = useState(0);
  const action = useFormAction();

  useEffect(() => {
    const controller = new AbortController();

    async function load() {
      let current: IdentityUser | null = null;
      try {
        current = await loadIdentity(controller.signal);
        if (controller.signal.aborted) {
          return;
        }
        setUser(current);
        const invitation = await apiRequest<Preview>("/api/v1/invitations/preview", {
          method: "POST", body: {token}, signal: controller.signal,
        });
        if (!controller.signal.aborted) {
          setPreview(invitation);
          setError("");
        }
      } catch (failure) {
        if (controller.signal.aborted) {
          return;
        }
        if (current && failure instanceof ApiError && failure.code === "INVITATION_ACCEPTED") {
          try {
            const accepted = await apiRequest<IdentityUser>("/api/v1/invitations/accept", {
              method: "POST", body: {token}, signal: controller.signal,
            });
            if (!controller.signal.aborted) {
              setJoined(accepted);
            }
          } catch (acceptFailure) {
            if (!controller.signal.aborted) {
              setError(errorMessage(acceptFailure));
            }
          }
        } else {
          setError(errorMessage(failure));
        }
      } finally {
        if (!controller.signal.aborted) {
          setLoaded(true);
        }
      }
    }

    void load();
    return () => controller.abort();
  }, [token, refresh]);

  function accept(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!user) {
      if (username.trim().toLowerCase() === "test") {
        const input = event.currentTarget.elements.namedItem("username");
        if (input instanceof HTMLInputElement) {
          input.focus();
        }
        return;
      }
      const problem = passwordProblem(password, confirmation);
      if (problem) {
        action.setError(problem);
        return;
      }
    }
    void action.execute(async () => {
      const accepted = await apiRequest<IdentityUser>("/api/v1/invitations/accept", {
        method: "POST", body: user ? {token} : {token, username, displayName, password},
      });
      setJoined(accepted);
      setPassword("");
      setConfirmation("");
    }, "");
  }

  if (joined) {
    return (
      <section className={invitationStyles.result}>
        <span className={invitationStyles.emblem} aria-hidden="true"><IconCheck size={26}/></span>
        <h1 className={invitationStyles.heading}>{uiText("已加入企业")}</h1>
        <p className={invitationStyles.intro}
           role="status">{preview ? uiText("你已加入「{0}」，现在可以进入工作空间。", [preview.enterpriseName]) : uiText("现在可以进入你的工作空间。")}</p>
        <Link className={invitationStyles.primary} href={landingPath(joined)}>{uiText("进入工作空间")}<IconArrowRight
          size={17}/></Link>
      </section>
    );
  }
  return (
    <section>
      <h1
        className={invitationStyles.heading}>{loaded && !preview ? uiText("暂时无法接受邀请") : uiText("加入你的团队")}</h1>
      <LoadingTransition state={!loaded ? "loading" : preview ? "ready" : "error"}>
        {!loaded ? <p className={invitationStyles.intro} role="status">{uiText("正在读取邀请…")}</p> : <>
          <ErrorFeedback error={error}/>
          {preview ? <>
            <div className={invitationStyles.summary}>
              <div className={invitationStyles.enterprise}>
                <span className={invitationStyles.emblem} aria-hidden="true"><IconBuilding size={24}
                                                                                           variant="Bulk"/></span>
                <div>
                  <p className={invitationStyles.inviter}>{preview.inviterName}{uiText(" 邀请你加入")}</p>
                  <h2>{preview.enterpriseName}</h2>
                </div>
              </div>
              <div className={invitationStyles.metadata}>
                {preview.maskedEmail &&
                  <p><IconMail size={16}/><span>{uiText("受邀邮箱：")}{preview.maskedEmail}</span></p>}
                <p><IconClock size={16}/><span>{uiText("有效期至 ")}
                  <time dateTime={preview.expiresAt}>{new Date(preview.expiresAt).toLocaleString(uiText.formatLocale, {
                    year: "numeric",
                    month: "2-digit",
                    day: "2-digit",
                    hour: "2-digit",
                    minute: "2-digit",
                    hour12: false,
                  })}</time></span></p>
              </div>
            </div>
            {user ? <form className={invitationStyles.form} onSubmit={accept}>
              <div className={invitationStyles.account}>
                <IconUser size={22}/>
                <div><p className={invitationStyles.accountLabel}>{uiText("将以此账号加入")}</p>
                  <strong>{user.displayName}</strong></div>
              </div>
              <FormFeedback action={action}/>
              <Button className={invitationStyles.primary}
                      disabled={action.busy}>{action.busy ? uiText("正在加入…") : uiText("接受邀请并加入")}<IconArrowRight
                size={17}/></Button>
            </form> : (
              <Tabs className={invitationStyles.tabs} value={register ? "register" : "login"}
                    onValueChange={(value) => {
                      setRegister(value === "register");
                      action.resetFeedback();
                    }}>
                <TabsList className={invitationStyles.tabList} aria-label={uiText("选择接受邀请的方式")}
                          activateOnFocus>
                  <TabsTrigger className={invitationStyles.tab} value="register"
                               disabled={action.busy || loginBusy}>{uiText("创建账号")}</TabsTrigger>
                  <TabsTrigger className={invitationStyles.tab} value="login"
                               disabled={action.busy || loginBusy}>{uiText("已有账号")}</TabsTrigger>
                </TabsList>
                <TabsContent className={invitationStyles.panel} value="register" keepMounted>
                  <form className={invitationStyles.form} onSubmit={accept} onInput={action.resetFeedback}>
                    <Field label={uiText("用户名")} required
                           error={username.trim().toLowerCase() === "test" ? uiText("此用户名已保留，请使用其他用户名。") : action.fieldErrors.username?.join(" ")}
                           hint={uiText("3～64 个字符，可用字母、数字、点、下划线或短横线。")}>
                      <AuthInput name="username" placeholder={uiText("设置登录用户名")} value={username}
                                 onChange={(event) => setUsername(event.target.value)} autoComplete="username"
                                 pattern="[A-Za-z0-9._\-]{3,64}" maxLength={64} disabled={action.busy} required/>
                    </Field>
                    <Field label={uiText("显示名（选填）")} error={action.fieldErrors.displayName?.join(" ")}>
                      <AuthInput name="displayName" placeholder={uiText("你希望大家如何称呼你")} value={displayName}
                                 onChange={(event) => setDisplayName(event.target.value)} autoComplete="nickname"
                                 maxLength={50} disabled={action.busy}/>
                    </Field>
                    <Field label={uiText("密码")} required hint={uiText("12～128 个字符。")}
                           error={action.fieldErrors.password?.join(" ")}>
                      <AuthInput name="password" type="password" placeholder={uiText("设置你的登录密码")}
                                 value={password} onChange={(event) => setPassword(event.target.value)}
                                 autoComplete="new-password" maxLength={256} disabled={action.busy} required/>
                    </Field>
                    <Field label={uiText("确认密码")} required>
                      <AuthInput name="confirmation" type="password" passwordLabel={uiText("确认密码")}
                                 placeholder={uiText("再次输入密码")} value={confirmation}
                                 onChange={(event) => setConfirmation(event.target.value)} autoComplete="new-password"
                                 maxLength={256} disabled={action.busy} required/>
                    </Field>
                    <FormFeedback action={action}/>
                    <Button className={invitationStyles.primary}
                            disabled={action.busy}>{action.busy ? uiText("正在注册…") : uiText("注册并加入企业")}<IconArrowRight
                      size={17}/></Button>
                  </form>
                </TabsContent>
                <TabsContent className={invitationStyles.panel} value="login" keepMounted>
                  <LoginForm embedded onBusyChange={setLoginBusy} onSuccess={(identity) => {
                    setUser(identity);
                    setLoaded(false);
                    setRefresh((value) => value + 1);
                  }}/>
                </TabsContent>
              </Tabs>
            )}
          </> : <Button type="button" className={invitationStyles.textAction} onClick={() => {
            setLoaded(false);
            setRefresh((value) => value + 1);
          }}>{uiText("重新加载邀请")}</Button>}
          {user && <div className={invitationStyles.accountActions}>
            <Button type="button" className={invitationStyles.textAction} disabled={action.busy}
                    onClick={() => void action.execute(async () => {
                      await signOut();
                      setUser(null);
                      setLoaded(false);
                      setRefresh((value) => value + 1);
                    }, "")}>{uiText("切换账号")}</Button>
            <Link className={invitationStyles.textAction} href={landingPath(user)}>{uiText("返回工作空间")}</Link>
          </div>}
        </>}
      </LoadingTransition>
      <div className={invitationStyles.footer}>
        <Button type="button" className={invitationStyles.textAction} disabled={action.busy || loginBusy}
                onClick={onChangeCode}>{uiText("使用其他邀请码")}</Button>
      </div>
    </section>
  );
}
