"use client";

import {useT} from "@/lib/i18n/locale-provider";

import Link from "next/link";
import {Suspense, useEffect, useState} from "react";
import {usePathname, useSearchParams} from "next/navigation";
import {ApiMutation} from "@/lib/http/api-client";
import {enterprisePath} from "@/features/auth/lib/identity-navigation";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import ui from "@/components/ui/surface.module.css";

/** 只有目标内容已经读取成功，才处理通知链接中的已读操作。 */
export function NotificationOpenedMarker(props: {
  enterpriseId: string;
  targetType: "conversation" | "schedule" | "todo" | "hire_request";
  targetId: string | null
}) {
  return <Suspense fallback={null}><OpenedNotice {...props} /></Suspense>;
}

function OpenedNotice({enterpriseId, targetType, targetId}: {
  enterpriseId: string;
  targetType: "conversation" | "schedule" | "todo" | "hire_request";
  targetId: string | null
}) {
  const uiText = useT();
  const params = useSearchParams();
  const pathname = usePathname();
  const notification = params.get("notification");
  const [mutation] = useState(() => new ApiMutation());
  const [failed, setFailed] = useState<string | null>(null);
  useEffect(() => {
    if (!notification || !targetId) {
      return;
    }
    const expected = enterprisePath(enterpriseId, targetType === "hire_request" ? "/employees" : `/${targetType === "conversation" ? "conversations" : targetType === "todo" ? "todos" : "schedules"}/${encodeURIComponent(targetId)}`);
    if (pathname !== expected) {
      return;
    }
    if (targetType === "hire_request" && (params.get("application") !== targetId || params.get("tab") !== "applications")) {
      return;
    }
    let active = true;
    void mutation.run(organizationPath(enterpriseId, `/notifications/${encodeURIComponent(notification)}/read`), {method: "POST"})
      .then(() => {
        if (active) {
          setFailed(null);
          window.dispatchEvent(new Event("agenteam:notifications-changed"));
        }
      })
      .catch(() => {
        if (active) {
          setFailed(notification);
        }
      });
    return () => {
      active = false;
    };
  }, [enterpriseId, targetType, targetId, notification, pathname, mutation, params]);
  return failed && failed === notification ?
    <p className={ui.error} role="alert">{uiText("通知尚未标为已读，可返回")}<Link
      href={enterprisePath(enterpriseId, "/notifications")}>{uiText("通知列表")}</Link>{uiText("重试。")}</p> : null;
}
