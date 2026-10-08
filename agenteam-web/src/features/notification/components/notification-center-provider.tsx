"use client";

import {createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useState} from "react";
import {useApiQuery} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {AnnouncementDialog} from "@/features/announcement/components/announcement-dialog";
import type {NotificationCenter} from "@/features/announcement/types/announcement";

type CenterState = {
  enterpriseId: string; permissions: string[]; data: NotificationCenter | null; loading: boolean; error: string;
  revision: number; changed: () => void;
};
const CenterContext = createContext<CenterState | null>(null);

export function notificationsChanged() {
  window.dispatchEvent(new Event("agenteam:notifications-changed"));
}

export function useNotificationCenter() {
  return useContext(CenterContext);
}

export function NotificationCenterProvider({enterpriseId, permissions, showAlerts, children}: {
  enterpriseId: string; permissions: string[]; showAlerts: boolean; children: ReactNode;
}) {
  const [revision, setRevision] = useState(0);
  const [dismissed, setDismissed] = useState<Set<string>>(() => new Set());
  const summary = useApiQuery<NotificationCenter>(organizationPath(enterpriseId, "/notifications/center"), revision);
  const changed = useCallback(() => setRevision((value) => value + 1), []);
  useEffect(() => {
    const update = () => {
      if (document.visibilityState === "visible") {
        changed();
      }
    };
    const timer = window.setInterval(update, 30000);
    window.addEventListener("agenteam:notifications-changed", changed);
    document.addEventListener("visibilitychange", update);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener("agenteam:notifications-changed", changed);
      document.removeEventListener("visibilitychange", update);
    };
  }, [changed]);
  const value = useMemo(() => ({
    enterpriseId, permissions, data: summary.data, loading: summary.loading,
    error: summary.error, revision, changed
  }), [enterpriseId, permissions, summary.data, summary.loading, summary.error, revision, changed]);
  const next = summary.data?.announcements.nextPopup;
  const key = next ? `${next.id}:${next.version}` : "";
  const dismiss = () => setDismissed((current) => new Set([...current, key]));
  return <CenterContext.Provider value={value}>{children}
    {showAlerts && next && !dismissed.has(key) && <AnnouncementDialog key={key} enterpriseId={enterpriseId} value={next}
                                                                      onClose={dismiss} onRead={() => {
      dismiss();
      notificationsChanged();
    }}/>}
  </CenterContext.Provider>;
}
