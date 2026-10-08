"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {createContext, type ReactNode, useContext, useEffect, useState} from "react";
import {useRouter} from "next/navigation";
import {loadEnterpriseContext, loadIdentity} from "@/features/auth/api/identity-api";
import {loginPath} from "@/features/auth/lib/identity-navigation";
import type {EnterpriseContext, IdentityUser} from "@/features/auth/types/identity";
import {errorMessage} from "@/lib/http/api-client";
import {PlatformShell} from "@/features/workspace/components/platform-shell";
import {WorkspaceLoading} from "@/features/workspace/components/workspace-loading";
import {LoadingTransition} from "@/components/ui/loading-transition";
import styles from "@/components/ui/surface.module.css";
import {useEnterpriseIdentity} from "./enterprise-gate";

type ManagementIdentity = { user: IdentityUser; context: EnterpriseContext | null };
type ManagementProps = { title: string; capability?: string; children: (context: EnterpriseContext | null, user: IdentityUser) => ReactNode };
const SystemIdentity = createContext<ManagementIdentity | null>(null);

/** 平台设置仅依赖系统管理员身份，企业上下文只供侧栏导航使用。 */
export function SystemManagementGate(props: ManagementProps) {
  const enterpriseIdentity = useEnterpriseIdentity();
  const systemIdentity = useContext(SystemIdentity);
  const identity = enterpriseIdentity ?? systemIdentity;
  return identity ? <ManagementContent {...props} identity={identity}/> : <LoadSystemManagementIdentity {...props} />;
}

function ManagementContent({title, capability, children, identity}: ManagementProps & { identity: ManagementIdentity }) {
  const uiText = useT();
  return <PlatformShell user={identity.user} context={identity.context} area="management" title={title}>
    {identity.user.superAdmin ? !capability || identity.user.capabilities.includes(capability) ? children(identity.context, identity.user) :
      <p role="alert" className={styles.empty}>{uiText("此页面暂时不可访问，请选择侧栏中的其他入口。")}</p> :
      <p role="alert" className={styles.empty}>{uiText("只有系统超级管理员可以访问此页面。")}</p>}
  </PlatformShell>;
}

function LoadSystemManagementIdentity(props: ManagementProps) {
  const router = useRouter();
  const [user, setUser] = useState<IdentityUser | null>(null);
  const [context, setContext] = useState<EnterpriseContext | null>(null);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    let loading = false;
    let refreshPending = false;

    async function load() {
      if (loading || controller.signal.aborted) {
        return;
      }
      loading = true;
      try {
        const identity = await loadIdentity(controller.signal);
        if (controller.signal.aborted) {
          return;
        }
        if (!identity) {
          setUser(null);
          router.replace(loginPath());
          return;
        }
        const choices = identity.enterprises.filter((enterprise) => enterprise.status === "active");
        const enterprise = choices.find((choice) => choice.id === identity.lastEnterpriseId) ?? choices[0];
        let navigationContext: EnterpriseContext | null = null;
        if (enterprise) {
          try {
            navigationContext = await loadEnterpriseContext(enterprise.id, controller.signal);
          } catch (failure) {
            if (controller.signal.aborted) {
              return;
            }
            console.error("读取平台管理侧栏的企业信息失败", {
              method: "GET",
              path: `/api/v1/enterprises/${enterprise.id}/context`
            }, failure);
          }
        }
        if (!controller.signal.aborted) {
          setContext(navigationContext);
          setUser(identity);
          setError("");
        }
      } catch (failure) {
        if (!controller.signal.aborted) {
          console.error("读取平台管理身份失败", {method: "GET", path: "/api/v1/auth/me"}, failure);
          setError(errorMessage(failure));
        }
      } finally {
        loading = false;
        if (refreshPending && !controller.signal.aborted) {
          refreshPending = false;
          void load();
        }
      }
    }

    const refresh = () => {
      if (!document.hidden) {
        if (loading) {
          refreshPending = true;
          return;
        }
        void load();
      }
    };
    void load();
    const timer = window.setInterval(refresh, 30000);
    window.addEventListener("agenteam:identity-changed", refresh);
    document.addEventListener("visibilitychange", refresh);
    return () => {
      controller.abort();
      window.clearInterval(timer);
      window.removeEventListener("agenteam:identity-changed", refresh);
      document.removeEventListener("visibilitychange", refresh);
    };
  }, [router, retry]);

  return <LoadingTransition state={user ? "ready" : error ? "error" : "loading"}>{user ?
    <SystemIdentity.Provider value={{user, context}}>
      <ManagementContent {...props} identity={{user, context}}/>
    </SystemIdentity.Provider> :
    <WorkspaceLoading error={error || undefined} onRetry={() => setRetry((value) => value + 1)}/>}</LoadingTransition>;
}
