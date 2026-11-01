"use client";

import { IconAlertCircle, IconCheck, IconClock, IconKey, IconRefresh } from "@/components/ui/icons";
import { useT } from "@/lib/i18n/locale-provider";
import styles from "./integration-setup-status.module.css";

export type SetupStatusContent = {
  kind: "processing" | "waiting" | "action" | "success" | "error" | "stopped";
  title: string;
  detail: string;
  hint?: string;
};

export function IntegrationSetupStatus({
  name,
  content,
  compact = false,
}: {
  name: string;
  content: SetupStatusContent;
  compact?: boolean;
}) {
  const t = useT();
  const Icon =
    content.kind === "processing"
      ? IconRefresh
      : content.kind === "success"
        ? IconCheck
        : content.kind === "error"
          ? IconAlertCircle
          : content.kind === "action"
            ? IconKey
            : IconClock;
  return (
    <section
      className={styles.status}
      data-kind={content.kind}
      data-compact={compact || undefined}
      role={content.kind === "error" ? "alert" : "status"}
      aria-atomic="true"
    >
      {!compact && (
        <span className={styles.icon} data-icon-context="inherit" aria-hidden="true">
          <Icon size={32} className={content.kind === "processing" ? styles.spinning : undefined} />
        </span>
      )}
      <div className={styles.copy}>
        <p className={styles.name}>{name}</p>
        <h3 className={styles.title}>{t(content.title)}</h3>
        {!compact && content.detail && <p className={styles.description}>{t(content.detail)}</p>}
      </div>
      {!compact && content.hint && <p className={styles.hint}>{t(content.hint)}</p>}
    </section>
  );
}
