"use client";

import {useEffect, useRef, useState} from "react";
import {usePathname, useRouter} from "next/navigation";
import {loadEnterpriseContext} from "../api/identity-api";
import {enterprisePath} from "../lib/identity-navigation";
import {errorMessage} from "@/lib/http/api-client";

/** 取得目标企业的当前资格后才改变地址；失败和已离开的请求不能覆盖原页面。 */
export function useEnterpriseSwitch() {
  const router = useRouter();
  const pathname = usePathname();
  const request = useRef<AbortController | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => () => request.current?.abort(), [pathname]);

  async function toEnterprise(id: string, suffix: string, beforeNavigate?: (action: () => void) => void) {
    if (request.current) {
      return;
    }
    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    setError("");
    try {
      const context = await loadEnterpriseContext(id, controller.signal);
      if (controller.signal.aborted) {
        return;
      }
      const has = (permission: string) => context.permissions.includes(permission);
      const page = suffix.split("/")[1];
      const allowed = page === "management" ? has("admin.view") : page === "capabilities" ? has("capabilities.view") : page === "conversations" ? has("conversation.view")
        : page === "schedules" ? has("schedule.view") : page === "todos" ? has("todo.view") : page === "employees" ? ["agent.market_view", "agent.run", "agent.hire", "agent.hire_approve"].some(has)
          : page === "notifications" || has("workspace.view");
      const destination = allowed ? enterprisePath(id, suffix) : has("workspace.view") ? enterprisePath(id, "/workspace")
        : has("admin.view") ? enterprisePath(id, "/management") : has("capabilities.view") ? enterprisePath(id, "/capabilities") : "/settings";
      const navigate = () => router.push(destination);
      if (beforeNavigate) {
        beforeNavigate(navigate);
      } else {
        navigate();
      }
    } catch (failure) {
      if (!controller.signal.aborted) {
        setError(errorMessage(failure));
      }
    } finally {
      request.current = null;
      setBusy(false);
    }
  }

  return {busy, error, toEnterprise};
}
