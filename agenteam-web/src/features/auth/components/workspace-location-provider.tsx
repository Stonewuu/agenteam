"use client";

import {createContext, type ReactNode, useContext, useEffect, useRef, useState} from "react";
import {usePathname, useRouter, useSearchParams} from "next/navigation";
import {loadEnterpriseContext, loadIdentity, signOut} from "../api/identity-api";
import type {EnterpriseContext, IdentityUser} from "../types/identity";
import {enterpriseEntryPath, resourceDestination, validWorkspaceDestination, workspacePath} from "../lib/workspace-path";
import {rememberWorkspace, storedEnterprise} from "../lib/workspace-location-store";
import {loginPath} from "../lib/identity-navigation";
import {ApiError, apiRequest, errorMessage} from "@/lib/http/api-client";
import {EnterpriseErrorPage} from "./enterprise-error-page";
import {LoadingTransition} from "@/components/ui/loading-transition";
import {WorkspaceLoading} from "@/features/workspace/components/workspace-loading";

type Location = {user: IdentityUser; enterpriseId: string; context: EnterpriseContext | null};
const WorkspaceLocation = createContext<Location | null>(null);

export function useWorkspaceLocation() {
  const value = useContext(WorkspaceLocation);
  if (!value) {
    throw new Error("页面尚未取得当前企业。");
  }
  return value;
}

/** 企业保存在当前标签页，旧路径和外部详情只在进入时解析，不改变后端接口范围。 */
export function WorkspaceLocationProvider({children}: {children: ReactNode}) {
  const pathname = usePathname();
  const search = useSearchParams();
  const query = search.toString();
  const router = useRouter();
  const key = pathname + (query ? `?${query}` : "");
  const [location, setLocation] = useState<Location | null>(null);
  const locationRef = useRef<Location | null>(null);
  const resolvedRefreshRef = useRef(-1);
  const lastFocusRefreshRef = useRef(0);
  const [failure, setFailure] = useState<{key: string; message: string; enterprise: string; user: IdentityUser | null} | null>(null);
  const [refresh, setRefresh] = useState(0);
  const explicit = pathname.startsWith("/enterprises/") || pathname === "/open";
  const remembered = location && typeof window !== "undefined"
    ? storedEnterprise(location.user.id, location.user.identityRevision ?? "", key) : undefined;
  const matches = !explicit && location !== null
    && (location.context === null || !remembered || remembered === location.enterpriseId);
  const failed = failure?.key === key;

  useEffect(() => {
    const reload = () => setRefresh(value => value + 1);
    const softReload = () => {
      const now = Date.now();
      if (now - lastFocusRefreshRef.current < 3000) {
        return;
      }
      lastFocusRefreshRef.current = now;
      reload();
    };
    const visible = () => {
      if (document.visibilityState === "visible") {
        softReload();
      }
    };
    window.addEventListener("focus", softReload);
    window.addEventListener("agenteam:identity-changed", reload);
    window.addEventListener("agenteam:access-denied", reload);
    document.addEventListener("visibilitychange", visible);
    return () => {
      window.removeEventListener("focus", softReload);
      window.removeEventListener("agenteam:identity-changed", reload);
      window.removeEventListener("agenteam:access-denied", reload);
      document.removeEventListener("visibilitychange", visible);
    };
  }, []);

  useEffect(() => {
    const forceResolve = refresh !== resolvedRefreshRef.current;
    const current = locationRef.current;
    const globalRoute = pathname.startsWith("/management/") || pathname === "/settings";
    const currentRemembered = current && typeof window !== "undefined"
      ? storedEnterprise(current.user.id, current.user.identityRevision ?? "", key) : undefined;
    // 同标签页同企业内的常规跳转直接复用已确认的身份与企业上下文，仅在强制刷新、切企业或显式入口时重新拉取。
    if (!forceResolve && !explicit && current !== null
      && (current.context !== null || globalRoute)
      && (!currentRemembered || currentRemembered === current.enterpriseId)) {
      if (current.enterpriseId) {
        rememberWorkspace(current.user.id, current.user.identityRevision ?? "", current.enterpriseId, key);
      }
      return;
    }
    resolvedRefreshRef.current = refresh;
    const controller = new AbortController();
    let user: IdentityUser | null = null;
    let enterprise = "";
    async function resolve() {
      try {
        user = await loadIdentity(controller.signal);
        if (!user) {
          router.replace(loginPath(key));
          return;
        }
        let destination = key;
        if (pathname === "/open") {
          const input = new URLSearchParams(query);
          enterprise = input.get("enterprise") ?? "";
          destination = input.get("path") ?? "/workspace";
          if (!enterprise || !validWorkspaceDestination(destination)) {
            throw new Error("此链接已无法使用，请重新打开工作空间。");
          }
        } else if (pathname.startsWith("/enterprises/")) {
          const parts = pathname.split("/");
          enterprise = decodeURIComponent(parts[2] ?? "");
          destination = workspacePath("/" + parts.slice(3).join("/") || "/workspace");
          if (destination === "/") {
            destination = "/workspace";
          }
          destination += query ? `?${query}` : "";
          if (!enterprise || !validWorkspaceDestination(destination)) {
            throw new Error("此链接已无法使用，请重新打开工作空间。");
          }
        }
        const resource = resourceDestination(destination.split(/[?#]/, 1)[0]);
        // 同一标签页内已绑定企业的导航沿用该范围；外部、刷新和明确入口重新定位详情。
        if (resource && (!current || explicit)) {
          const found = await apiRequest<{enterpriseId: string}>(
            `/api/v1/navigation/resource?${new URLSearchParams(resource)}`, {signal: controller.signal});
          if (enterprise && found.enterpriseId !== enterprise) {
            throw new Error("当前账号无法打开此内容。");
          }
          enterprise = found.enterpriseId;
        }
        const available = user.enterprises.filter(item => item.status === "active");
        if (!enterprise) {
          const stored = storedEnterprise(user.id, user.identityRevision ?? "", key);
          enterprise = stored
            ?? available.find(item => item.id === user?.lastEnterpriseId)?.id ?? available[0]?.id ?? "";
        }
        const global = pathname.startsWith("/management/") || pathname === "/settings";
        if (!enterprise) {
          if (global) {
            const nextLocation = {user, enterpriseId: "", context: null};
            locationRef.current = nextLocation;
            lastFocusRefreshRef.current = Date.now();
            setLocation(nextLocation);
          } else {
            router.replace("/no-enterprise");
          }
          return;
        }
        let context: EnterpriseContext;
        try {
          context = await loadEnterpriseContext(enterprise, controller.signal);
        } catch (error) {
          if (global && error instanceof ApiError && [403, 404].includes(error.status)) {
            const nextLocation = {user, enterpriseId: "", context: null};
            locationRef.current = nextLocation;
            lastFocusRefreshRef.current = Date.now();
            setLocation(nextLocation);
            setFailure(null);
            return;
          }
          throw error;
        }
        controller.signal.throwIfAborted();
        if (explicit && destination === "/workspace" && !context.permissions.includes("workspace.view")) {
          destination = context.permissions.includes("admin.view") ? "/organization"
            : context.permissions.includes("conversation.view") ? "/conversations"
              : context.permissions.some(value => ["agent.market_view", "agent.run", "agent.hire"].includes(value)) ? "/employees"
                : context.permissions.includes("capabilities.view") ? "/capabilities" : "/settings";
        }
        rememberWorkspace(user.id, user.identityRevision ?? "", enterprise, destination);
        const nextLocation = {user, enterpriseId: enterprise, context};
        locationRef.current = nextLocation;
        lastFocusRefreshRef.current = Date.now();
        setLocation(nextLocation);
        setFailure(null);
        if (explicit) {
          router.replace(destination);
        }
      } catch (error) {
        if (controller.signal.aborted) {
          return;
        }
        if (error instanceof ApiError && error.status === 401) {
          router.replace(loginPath(key));
          return;
        }
        console.error("Failed to read the organization for the current page", error);
        setFailure({key, message: errorMessage(error), enterprise, user});
      }
    }
    void resolve();
    return () => controller.abort();
  }, [pathname, query, refresh, router, explicit, key]);

  return <LoadingTransition state={failed ? "error" : matches ? "ready" : "loading"}>
    {failed ? <EnterpriseErrorPage enterpriseId={failure.enterprise} user={failure.user} message={failure.message}
      onRetry={() => setRefresh(value => value + 1)}
      onSwitchEnterprise={enterprise => router.replace(enterpriseEntryPath(enterprise))}
      onSignOut={async () => {
        await signOut();
        window.location.replace("/login");
      }}/> : matches ? <WorkspaceLocation.Provider value={location}>{children}</WorkspaceLocation.Provider>
      : <WorkspaceLoading/>}
  </LoadingTransition>;
}
