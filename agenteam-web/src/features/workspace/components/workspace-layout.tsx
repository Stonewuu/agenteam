"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {type ReactNode, useEffect, useState} from "react";
import {useParams, usePathname, useRouter} from "next/navigation";
import {loadIdentity} from "@/features/auth/api/identity-api";
import {loginPath} from "@/features/auth/lib/identity-navigation";
import {errorMessage} from "@/lib/http/api-client";
import {EnterpriseLayout} from "./enterprise-layout";
import {WorkspaceLoading} from "./workspace-loading";
import {LoadingTransition} from "@/components/ui/loading-transition";
import {SystemManagementGate} from "@/features/auth/components/system-management-gate";
import {platformManagementNavigation} from "../lib/navigation";

/** 企业、个人设置和平台管理共用布局，常规导航不会重新创建侧栏。 */
export function WorkspaceLayout({children}: { children: ReactNode }) {
  const uiText = useT();
  const params = useParams<{ enterpriseId?: string }>();
  const router = useRouter();
  const pathname = usePathname();
  const platformPage = localizeCatalog(platformManagementNavigation, uiText).find((item) => item.path === pathname);
  const [remembered, setRemembered] = useState<string | null>(params.enterpriseId ?? null);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  if (params.enterpriseId && params.enterpriseId !== remembered) {
    setRemembered(params.enterpriseId);
  }
  const enterpriseId = params.enterpriseId ?? remembered;
  useEffect(() => {
    if (enterpriseId !== null) {
      return;
    }
    const controller = new AbortController();
    loadIdentity(controller.signal).then((user) => {
      if (controller.signal.aborted) {
        return;
      }
      if (!user) {
        router.replace(loginPath());
        return;
      }
      const active = user.enterprises.filter((item) => item.status === "active");
      setRemembered(active.find((item) => item.id === user.lastEnterpriseId)?.id ?? active[0]?.id ?? "");
    }).catch((failure) => {
      if (!controller.signal.aborted) {
        console.error("读取工作空间失败", failure);
        setError(errorMessage(failure));
      }
    });
    return () => controller.abort();
  }, [enterpriseId, router, retry]);
  return <LoadingTransition state={enterpriseId === null ? error ? "error" : "loading" : "ready"}>
    {enterpriseId === "" ? platformPage ?
      <SystemManagementGate title={platformPage.label}>{() => children}</SystemManagementGate>
      : <main className="page-content">{children}</main> : enterpriseId ?
      <EnterpriseLayout key={enterpriseId} enterpriseId={enterpriseId}>{children}</EnterpriseLayout>
      : <WorkspaceLoading error={error || undefined} onRetry={() => {
        setError("");
        setRetry((value) => value + 1);
      }}/>}</LoadingTransition>;
}
