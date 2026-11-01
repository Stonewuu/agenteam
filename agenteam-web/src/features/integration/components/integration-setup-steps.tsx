"use client";

import { IconAlertCircle, IconCheck } from "@/components/ui/icons";
import { useT } from "@/lib/i18n/locale-provider";
import styles from "./integration-setup-steps.module.css";

export function IntegrationSetupSteps({
  steps,
  current,
  complete = false,
  attention = false,
}: {
  steps: readonly { id: string; label: string }[];
  current: string;
  complete?: boolean;
  attention?: boolean;
}) {
  const t = useT();
  const currentIndex = steps.findIndex((step) => step.id === current);
  return (
    <ol className={styles.steps} aria-label={t("创建进度")}>
      {steps.map((step, index) => {
        const completed = complete || index < currentIndex;
        const active = index === currentIndex;
        const needsAttention = active && attention;
        const state = completed ? "complete" : active ? (needsAttention ? "attention" : "current") : "pending";
        const status = completed ? t("已完成") : active ? t("当前步骤") : t("尚未开始");
        return (
          <li
            key={step.id}
            className={styles.step}
            data-state={state}
            aria-current={active ? "step" : undefined}
            aria-label={step.label + "，" + status}
          >
            <span className={styles.marker} data-icon-context="inherit" aria-hidden="true">
              {completed ? <IconCheck size={32} /> : needsAttention ? <IconAlertCircle size={32} /> : index + 1}
            </span>
            <span className={styles.label}>{step.label}</span>
          </li>
        );
      })}
    </ol>
  );
}
