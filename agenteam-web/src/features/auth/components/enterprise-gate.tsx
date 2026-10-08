"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {WorkspaceLoading} from "@/features/workspace/components/workspace-loading";
import {LoadingTransition} from "@/components/ui/loading-transition";


import Link from "next/link";
import {createContext, type ReactNode, useContext, useEffect, useState} from "react";
import {useRouter} from "next/navigation";
import {loadEnterpriseContext, loadIdentity, rememberEnterprise, signOut} from "../api/identity-api";
import {enterprisePath, loginPath} from "../lib/identity-navigation";
import type {EnterpriseContext, IdentityUser} from "../types/identity";
import {ApiError, errorMessage} from "@/lib/http/api-client";
import {EnterpriseErrorPage} from "./enterprise-error-page";
import {EnterpriseTimezone} from "./enterprise-date-time";
import {RememberUserPage} from "@/features/workspace/components/return-to-workspace";

type Loaded = { kind: "ready"; user: IdentityUser; context: EnterpriseContext; refreshError?: string };
type State = Loaded | { kind: "loading" } | {
  kind: "error";
  message: string;
  user: IdentityUser | null;
  temporary: boolean
};
const EnterpriseIdentity = createContext<Loaded | null>(null);

export function useEnterpriseIdentity() {
  return useContext(EnterpriseIdentity);
}

type EnterpriseGateProps = {
  enterpriseId: string;
  permission?: string | string[];
  onUnavailable?: (user: IdentityUser) => ReactNode;
  children: (identity: Loaded) => ReactNode;
};

export function EnterpriseGate(props: EnterpriseGateProps) {
  const uiText = useT();
  const identity = useContext(EnterpriseIdentity);
  if (!identity || identity.context.enterprise.id !== props.enterpriseId) {
    return <LoadingTransition><LoadEnterpriseIdentity {...props} /></LoadingTransition>;
  }
  const required = typeof props.permission === "string" ? [props.permission] : props.permission ?? [];
  if (required.length && !required.some((value) => identity.context.permissions.includes(value))) {
    return <section className="empty"><h1>{uiText("无法访问此内容")}</h1>
      <p>{uiText("当前账号没有此页面的访问权限，请联系企业管理员。")}</p><Link className="button"
                                                                             href={enterprisePath(props.enterpriseId, "/workspace")}>{uiText("返回工作台")}</Link>
    </section>;
  }
  return props.children(identity);
}

function LoadEnterpriseIdentity({enterpriseId, permission, onUnavailable, children}: EnterpriseGateProps) {
  const uiText = useT();
  const router = useRouter();
  const permissionKey = typeof permission === "string" ? permission : permission?.join(",") ?? "";
  const [state, setState] = useState<State>({kind: "loading"});
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    let loading = false;
    let refreshPending = false;
    let remembered = false;
    let accessLost = false;
    let loginRedirected = false;

    function requireLogin() {
      if (controller.signal.aborted || loginRedirected) {
        return;
      }
      loginRedirected = true;
      accessLost = true;
      setState({kind: "loading"});
      router.replace(loginPath());
    }

    async function load() {
      if (loading || controller.signal.aborted || accessLost) {
        return;
      }
      loading = true;
      let user: IdentityUser | null = null;
      try {
        user = await loadIdentity(controller.signal);
        if (controller.signal.aborted || accessLost) {
          return;
        }
        if (!user) {
          requireLogin();
          return;
        }
        const context = await loadEnterpriseContext(enterpriseId, controller.signal);
        if (controller.signal.aborted || accessLost) {
          return;
        }
        if (permissionKey && !permissionKey.split(",").some((required) => context.permissions.includes(required))) {
          setState({
            kind: "error",
            message: "当前账号无法打开此页面，请选择其他入口或联系企业管理员。",
            user,
            temporary: false
          });
          return;
        }
        setState({kind: "ready", user, context});
        if (!remembered) {
          remembered = true;
          void rememberEnterprise(enterpriseId, controller.signal).catch((error) => {
            if (!controller.signal.aborted) {
              console.error("保存最近使用的企业失败", {enterpriseId, error});
            }
          });
        }
      } catch (error) {
        if (controller.signal.aborted || loginRedirected) {
          return;
        }
        console.error("读取企业登录信息失败", {enterpriseId, error});
        if (error instanceof ApiError && error.status === 401) {
          requireLogin();
          return;
        }
        setState((current) => {
          const temporary = !(error instanceof ApiError) || error.status === 0 || error.status >= 500;
          if (temporary && current.kind === "ready" && current.context.enterprise.id === enterpriseId) {
            return {...current, refreshError: "连接暂时中断，请稍后重试。"};
          }
          return {kind: "error", message: errorMessage(error), user, temporary};
        });
      } finally {
        loading = false;
        if (refreshPending && !controller.signal.aborted && !accessLost) {
          refreshPending = false;
          void load();
        }
      }
    }

    const denied = (event: Event) => {
      const detail = (event as CustomEvent<{ enterpriseId: string; loginRequired?: boolean }>).detail;
      if (detail.enterpriseId !== enterpriseId) {
        return;
      }
      if (detail.loginRequired) {
        requireLogin();
        return;
      }
      // 立即卸载原企业内容和事件连接，不能继续展示可操作的旧权限状态。
      accessLost = true;
      setState((current) => ({
        kind: "error", message: "当前操作无法继续，请重新加载页面或联系企业管理员。", temporary: false,
        user: current.kind === "ready" || current.kind === "error" ? current.user : null
      }));
    };
    const resume = () => {
      if (!document.hidden && !accessLost) {
        if (loading) {
          refreshPending = true;
          return;
        }
        void load();
      }
    };
    window.addEventListener("agenteam:access-denied", denied);
    window.addEventListener("agenteam:identity-changed", resume);
    document.addEventListener("visibilitychange", resume);
    const timer = window.setInterval(resume, 30000);
    void load();
    return () => {
      controller.abort();
      window.clearInterval(timer);
      window.removeEventListener("agenteam:access-denied", denied);
      window.removeEventListener("agenteam:identity-changed", resume);
      document.removeEventListener("visibilitychange", resume);
    };
  }, [enterpriseId, permissionKey, router, retry]);

  function reload() {
    setState({kind: "loading"});
    setRetry((value) => value + 1);
  }

  if (state.kind === "ready" && state.context.enterprise.id === enterpriseId) {
    return <EnterpriseIdentity.Provider value={state}><EnterpriseTimezone value={state.context.enterprise.timezone}>
      <RememberUserPage enterpriseId={enterpriseId} userId={state.user.id}/>{children(state)}{state.refreshError &&
      <div className="connection-notice" role="status">
        <span>{localizeUiMessage(state.refreshError ?? "", uiText)}</span><Button className="button small"
                                                                                  onClick={() => setRetry((value) => value + 1)}>{uiText("重新连接")}</Button>
      </div>}</EnterpriseTimezone></EnterpriseIdentity.Provider>;
  }
  if (state.kind !== "error") {
    return <WorkspaceLoading/>;
  }
  if (onUnavailable && state.user?.superAdmin) {
    return onUnavailable(state.user);
  }
  if (state.temporary) {
    return <WorkspaceLoading error={state.message} onRetry={reload}/>;
  }
  return <EnterpriseErrorPage enterpriseId={enterpriseId} message={state.message} user={state.user}
                              onRetry={reload}
                              onSwitchEnterprise={(id) => router.push(enterprisePath(id))}
                              onSignOut={async () => {
                                await signOut();
                                router.replace("/login");
                              }}/>;
}
