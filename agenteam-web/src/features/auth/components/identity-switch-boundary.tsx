"use client";

import {createContext, type ReactNode, useCallback, useContext, useEffect, useRef, useState} from "react";
import {usePathname} from "next/navigation";
import {Button} from "@/components/ui/button";
import {IconArrowLeft, IconUserCircle} from "@/components/ui/icons";
import {ApiError, apiRequest, clearCsrf, errorMessage} from "@/lib/http/api-client";
import {acceptRequestIdentity, broadcastIdentityChange, identityChangedEvent, identityTabId, requestIdentity} from "@/lib/http/request-identity";
import {useT} from "@/lib/i18n/locale-provider";
import {useGuardedNavigation} from "@/features/workspace/components/navigation-guard";
import {enterpriseEntryPath} from "../lib/workspace-path";
import type {ImpersonationStatus} from "../types/identity";
import ui from "@/components/ui/surface.module.css";
import styles from "./identity-switch.module.css";

type SwitchState = {
  status: ImpersonationStatus | null;
  busy: boolean;
  error: string;
  restore: () => Promise<void>;
  registerHeader: () => () => void;
};
const IdentitySwitchContext = createContext<SwitchState | null>(null);

/** 位于所有页面之外，目标用户无企业或没有页面权限时仍可返回管理员。 */
export function IdentitySwitchBoundary({children}: {children: ReactNode}) {
  const pathname = usePathname();
  const [status, setStatus] = useState<ImpersonationStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [headers, setHeaders] = useState(0);
  const refreshing = useRef(false);

  const registerHeader = useCallback(() => {
    setHeaders(value => value + 1);
    return () => setHeaders(value => value - 1);
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    const reload = () => {
      if (!refreshing.current) {
        refreshing.current = true;
        window.location.reload();
      }
    };
    async function refresh() {
      if (document.visibilityState === "hidden") {
        return;
      }
      try {
        const next = await apiRequest<ImpersonationStatus>("/api/v1/auth/impersonation", {signal: controller.signal});
        if (controller.signal.aborted) {
          return;
        }
        if (requestIdentity() && requestIdentity() !== next.identityRevision) {
          reload();
          return;
        }
        acceptRequestIdentity(next.identityRevision);
        setStatus(next);
      } catch (failure) {
        if (controller.signal.aborted) {
          return;
        }
        if (failure instanceof ApiError && [401, 403].includes(failure.status)) {
          setStatus(null);
        } else {
          console.error("检查当前登录身份失败", failure);
        }
      }
    }
    const storage = (event: StorageEvent) => {
      if (event.key === "agenteam:identity-change") {
        reload();
      }
    };
    const channel = typeof BroadcastChannel === "undefined" ? null : new BroadcastChannel("agenteam-identity");
    if (channel) {
      channel.onmessage = event => {
        if (event.data?.source !== identityTabId) {
          reload();
        }
      };
    }
    window.addEventListener(identityChangedEvent, reload);
    window.addEventListener("storage", storage);
    window.addEventListener("focus", refresh);
    document.addEventListener("visibilitychange", refresh);
    void refresh();
    return () => {
      controller.abort();
      channel?.close();
      window.removeEventListener(identityChangedEvent, reload);
      window.removeEventListener("storage", storage);
      window.removeEventListener("focus", refresh);
      document.removeEventListener("visibilitychange", refresh);
    };
  }, [pathname]);

  async function restore() {
    const previous = status?.impersonation;
    if (busy || !previous) {
      return;
    }
    setBusy(true);
    setError("");
    try {
      const restored = await apiRequest<ImpersonationStatus>("/api/v1/auth/impersonation/stop", {method: "POST"});
      acceptRequestIdentity(restored.identityRevision);
      clearCsrf();
      broadcastIdentityChange();
      window.location.replace(enterpriseEntryPath(previous.enterpriseId, previous.returnPath));
    } catch (failure) {
      console.error("返回管理员身份失败", failure);
      setError(errorMessage(failure));
      setBusy(false);
    }
  }

  const state = {status, busy, error, restore, registerHeader};
  return <IdentitySwitchContext.Provider value={state}>
    {children}
    {headers === 0 && status?.impersonation && <div className={styles.floating}><IdentitySwitchActions state={state}/></div>}
  </IdentitySwitchContext.Provider>;
}

/** 账户菜单提供正常页面的返回入口，没有菜单的错误页面由外层负责。 */
export function useAccountIdentitySwitch() {
  const state = useContext(IdentitySwitchContext);
  const registerHeader = state?.registerHeader;
  useEffect(() => registerHeader?.(), [registerHeader]);
  return state;
}

function IdentitySwitchActions({state}: {state: SwitchState}) {
  const t = useT();
  const guarded = useGuardedNavigation();
  const acting = state.status?.impersonation;
  if (!acting) {
    return null;
  }
  const label = t("正在以 {0} 身份操作", [acting.displayName]);
  const actionLabel = state.busy ? t("正在返回…") : t("返回管理员");
  return <div className={styles.controls} data-identity-switch>
    <span className={styles.identity} role="status" aria-label={label} title={`${label} (${acting.username})`}>
      <IconUserCircle size={18}/><span className={styles.name}>{t("代登录中")}</span>
    </span>
    <Button className={`${ui.button} ${styles.returnButton}`} disabled={state.busy} aria-label={actionLabel}
      title={actionLabel} onClick={() => {
        const action = () => void state.restore();
        if (guarded) {
          guarded(action);
        } else {
          action();
        }
      }}>
      <IconArrowLeft size={16}/><span className={styles.returnText}>{actionLabel}</span>
    </Button>
    {state.error && <span role="alert" className={styles.error}>{state.error}</span>}
  </div>;
}
