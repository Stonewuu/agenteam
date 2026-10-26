"use client";

import { IconAlertCircle, IconCheck, IconClock, IconKey, IconRefresh } from "@/components/ui/icons";
import { useT } from "@/lib/i18n/locale-provider";
import { feishuSetupStatus } from "../lib/feishu-setup-status";
import type { FeishuSetupJob } from "../types/feishu-setup";
import styles from "./feishu-setup-status.module.css";

export function FeishuSetupStatus({ job }: { job: FeishuSetupJob }) {
  const t = useT();
  const content = feishuSetupStatus(job);
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
      data-compact={job.canOpen || undefined}
      role={content.kind === "error" ? "alert" : "status"}
      aria-atomic="true"
    >
      {!job.canOpen && (
        <span className={styles.icon} data-icon-context="inherit" aria-hidden="true">
          <Icon size={32} className={content.kind === "processing" ? styles.spinning : undefined} />
        </span>
      )}
      <div className={styles.copy}>
        <p className={styles.name}>{job.name}</p>
        <h3 className={styles.title}>{t(content.title)}</h3>
        {!job.canOpen && content.detail && <p className={styles.description}>{t(content.detail)}</p>}
      </div>
      {!job.canOpen && content.hint && <p className={styles.hint}>{t(content.hint)}</p>}
    </section>
  );
}
