"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {IconArrowLeft} from "@/components/ui/icons";
import {useEffect, useRef, useState} from "react";
import {usePathname, useRouter} from "next/navigation";
import type {EnterpriseContext} from "@/features/auth/types/identity";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {ApiError, apiRequest, errorMessage} from "@/lib/http/api-client";
import {useGuardedNavigation} from "./navigation-guard";

const collections = new Set(["conversations", "schedules", "todos"]);
const pages = new Set(["workspace", "new-task", "employees", ...collections]);

function storageKey(enterprise: string, user: string) {
  return `agenteam:last-user-page:${user}:${enterprise}`;
}

function userPage(pathname: string, enterprise: string) {
  const prefix = `/enterprises/${encodeURIComponent(enterprise)}/`;
  if (!pathname.startsWith(prefix)) {
    return null;
  }
  const suffix = pathname.slice(prefix.length);
  const parts = suffix.split("/");
  if (!pages.has(parts[0]) || parts.length > 2 || parts.length === 2 && (!collections.has(parts[0]) || !parts[1])) {
    return null;
  }
  return suffix;
}

export function RememberUserPage({enterpriseId, userId}: { enterpriseId: string; userId: string }) {
  const pathname = usePathname();
  useEffect(() => {
    const value = userPage(pathname, enterpriseId);
    if (!value) {
      return;
    }
    try {
      window.localStorage.setItem(storageKey(enterpriseId, userId), value);
    } catch { /* 无法保存导航偏好时仍正常使用当前页面。 */
    }
  }, [enterpriseId, userId, pathname]);
  return null;
}

export function ReturnToWorkspace({context, userId, className}: {
  context: EnterpriseContext;
  userId: string;
  className: string
}) {
  const uiText = useT();
  const router = useRouter();
  const pathname = usePathname();
  const guarded = useGuardedNavigation();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const request = useRef<AbortController | null>(null);
  useEffect(() => () => request.current?.abort(), [pathname]);
  const enterprise = context.enterprise.id;
  const root = `/enterprises/${encodeURIComponent(enterprise)}`;
  const go = (path: string) => {
    const action = () => router.push(path);
    if (guarded) {
      guarded(action);
    } else {
      action();
    }
  };

  async function navigate() {
    if (request.current) {
      return;
    }
    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    setError("");
    let suffix = "workspace";
    try {
      const stored = window.localStorage.getItem(storageKey(enterprise, userId));
      if (stored && userPage(`${root}/${stored}`, enterprise)) {
        suffix = stored;
      }
    } catch { /* 没有导航偏好时打开工作台。 */
    }
    const [page, id] = suffix.split("/");
    const required = page === "workspace" ? ["workspace.view"] : page === "new-task" ? ["agent.run"] : page === "employees" ? ["agent.market_view", "agent.run", "agent.hire", "agent.hire_approve"]
      : [page === "conversations" ? "conversation.view" : page === "schedules" ? "schedule.view" : "todo.view"];
    if (!required.some((permission) => context.permissions.includes(permission))) {
      suffix = "workspace";
    }
    try {
      if (id && suffix !== "workspace") {
        await apiRequest(organizationPath(enterprise, `/${page}/${id}`), {signal: controller.signal});
      }
      if (controller.signal.aborted) {
        return;
      }
      go(`${root}/${suffix}`);
    } catch (failure) {
      if (controller.signal.aborted) {
        return;
      }
      if (failure instanceof ApiError && [403, 404].includes(failure.status)) {
        go(`${root}/workspace`);
      } else {
        setError(errorMessage(failure));
      }
    } finally {
      request.current = null;
      setBusy(false);
    }
  }

  return (
    <>
      <Button className={className} type="button" disabled={busy} onClick={() => {
        void navigate();
      }}>
        <IconArrowLeft size={17}/>
        {busy ? uiText("正在打开…") : uiText("返回工作空间")}
      </Button>
      {error && <p role="alert">{localizeUiMessage(error ?? "", uiText)}</p>}
    </>
  );
}
