"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Button} from "@/components/ui/button";

import {PageHeader} from "@/components/ui/page-header";

import {useCallback, useEffect, useState} from "react";
import {useRouter, useSearchParams} from "next/navigation";
import {loadIdentity} from "../api/identity-api";
import type {IdentityUser} from "../types/identity";
import {loginPath} from "../lib/identity-navigation";
import {WorkspacePageLoading} from "@/features/workspace/components/workspace-loading";
import {LoadingTransition} from "@/components/ui/loading-transition";
import {PasswordSettings, ProfileSettings, ThemeSettings, WorkPreferencesSettings} from "./settings-forms";
import {errorMessage} from "@/lib/http/api-client";
import {Tabs} from "@/components/ui/tabs";
import {MotionPanel} from "@/components/ui/motion-panel";
import {IconBell, IconBookmark, IconLanguage, IconLock, IconPlug, IconSun, IconUser} from "@/components/ui/icons";
import {useEnterpriseIdentity} from "./enterprise-gate";
import {LocalReauthentication} from "./local-reauthentication";
import {ChannelSettingsPanel} from "@/features/integration/components/channel-settings-panel";
import {LanguageSettings} from "./language-settings";
import {SavedMemorySettings} from "@/features/memory/components/saved-memory-settings";
import {editionClientExtension} from "@/features/edition/client-extension";
import Link from "next/link";
import ui from "@/components/ui/surface.module.css";
import styles from "./settings.module.css";

const sections = [{value: "profile", label: "个人资料", icon: IconUser}, {value: "channels", label: "企业账号绑定", icon: IconPlug}, {
  value: "security",
  label: "账号安全",
  icon: IconLock
}, {value: "language", label: "语言", icon: IconLanguage}, {
  value: "appearance",
  label: "外观",
  icon: IconSun
}, {value: "notifications", label: "通知偏好", icon: IconBell}, {
  value: "memory",
  label: "个人偏好记忆",
  icon: IconBookmark
}];

export function SettingsPage() {
  const uiText = useT();
  const EnterpriseCreateDialog = editionClientExtension.enterpriseCreation;
  const router = useRouter();
  const enterpriseIdentity = useEnterpriseIdentity();
  const params = useSearchParams();
  const requested = params.get("tab") ?? "profile";
  const [user, setUser] = useState<IdentityUser | null>(null);
  const [error, setError] = useState("");
  const [refresh, setRefresh] = useState(0);
  const available = localizeCatalog(sections, uiText).filter((item) =>
    (item.value !== "security" || user?.authenticationMethod === "channel" || Boolean(user?.capabilities.includes("account.password.change")))
    && (item.value !== "channels" || user?.authenticationMethod === "channel" || Boolean(user?.capabilities.includes("account.external.bind"))));
  const tab = available.some((item) => item.value === requested) ? requested : "profile";
  const [creatingEnterprise, setCreatingEnterprise] = useState(false);
  const [reauthenticate, setReauthenticate] = useState(false);
  const enterpriseId = enterpriseIdentity?.context.enterprise.id ?? user?.lastEnterpriseId;
  useEffect(() => {
    const refreshIdentity = () => setRefresh((value) => value + 1);
    window.addEventListener("agenteam:identity-changed", refreshIdentity);
    return () => window.removeEventListener("agenteam:identity-changed", refreshIdentity);
  }, []);
  const changed = useCallback((next: IdentityUser) => {
    setUser((current) => {
      if (!current || current.id !== next.id) {
        return next;
      }
      const atLeast = (left: string, right: string) => left.length > right.length || (left.length === right.length && left >= right);
      const profile = atLeast(next.revision, current.revision) ? next : current;
      const preferences = atLeast(next.preferences.revision, current.preferences.revision) ? next.preferences : current.preferences;
      return {...profile, preferences};
    });
    window.dispatchEvent(new Event("agenteam:identity-changed"));
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    loadIdentity(controller.signal).then((identity) => {
      if (controller.signal.aborted) {
        return;
      }
      if (!identity) {
        router.replace(loginPath());
        return;
      }
      setUser(identity);
    }).catch((failure) => {
      if (!controller.signal.aborted) {
        console.error("读取个人设置失败", failure);
        setError(errorMessage(failure));
      }
    });
    return () => controller.abort();
  }, [router, refresh]);
  return <>
    <PageHeader title={uiText("个人设置")} description={uiText("按你的习惯，安排自己的工作空间。")}
                icon={<IconUser size={26} variant="Bulk"/>} actions={<>{user && !user.enterprises.length &&
      <div className={ui.actions}><Link className={ui.button}
                                        href="/no-enterprise">{uiText("返回")}</Link>{EnterpriseCreateDialog && user.capabilities.includes("enterprise.create") &&
        <Button className={ui.primary} onClick={() => setCreatingEnterprise(true)}>{uiText("新建企业")}</Button>}
      </div>}</>}/>
    <LoadingTransition state={user ? "ready" : error ? "error" : "loading"}>{!user ? error ?
        <div className={ui.empty}><p role="alert">{localizeUiMessage(error ?? "", uiText)}</p><Button
          className={ui.button} onClick={() => {
          setError("");
          setRefresh((value) => value + 1);
        }}>{uiText("重新加载")}</Button></div> : <WorkspacePageLoading layout="settings" embedded showHeading={false}/> :
      <div className={styles.layout} key={user.id}>
        <aside className={styles.navigation}><Tabs value={tab}
                                                   onChange={(value) => window.history.pushState(null, "", `/settings?tab=${value}`)}
                                                   items={available} orientation="vertical" variant="navigation"
                                                   label={uiText("设置分类")}/></aside>
        <MotionPanel value={tab} className={styles.panel}>
          {tab === "channels" && (enterpriseId ? <ChannelSettingsPanel key={enterpriseId} enterpriseId={enterpriseId} superAdmin={user.superAdmin}/>
            : <p>{uiText("请先加入企业，再绑定企业账号。")}</p>)}
          <div hidden={tab === "channels"}>{user.authenticationMethod === "channel" ?
            <div className={ui.form}><p>{uiText("修改个人设置前，请验证当前账号密码。")}</p>
              <Button onClick={() => setReauthenticate(true)}>{uiText("验证账号密码")}</Button></div> : <>
          <div hidden={tab !== "profile"}><ProfileSettings user={user} onChange={changed}/></div>
          {user.capabilities.includes("account.password.change") &&
            <div hidden={tab !== "security"}><PasswordSettings user={user} onChange={changed}/></div>}
          <div hidden={tab !== "appearance"}><ThemeSettings/></div>
          <div hidden={tab !== "language"}><LanguageSettings user={user} onChange={changed}/></div>
          <div hidden={tab !== "notifications"}><WorkPreferencesSettings user={user} onChange={changed}
                                                                         section="notifications"/></div>
          <div hidden={tab !== "memory"}><WorkPreferencesSettings user={user} onChange={changed}
                                                                  section="memory"/>{tab === "memory" &&
            <SavedMemorySettings user={user}/>}</div>
          </>}</div>
        </MotionPanel>
      </div>}</LoadingTransition>
    {creatingEnterprise && user && EnterpriseCreateDialog && user.capabilities.includes("enterprise.create") &&
      <EnterpriseCreateDialog actor={{id: user.id, name: user.displayName}} onClose={() => setCreatingEnterprise(false)}
                              onCreated={() => setRefresh((value) => value + 1)}/>}
    {reauthenticate && <LocalReauthentication onClose={() => setReauthenticate(false)} onSuccess={() => {
      setReauthenticate(false);
      setRefresh((value) => value + 1);
    }}/>}
  </>;
}
