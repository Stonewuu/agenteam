"use client";

import { IconAlertCircle, IconCheck } from "@/components/ui/icons";
import { useT } from "@/lib/i18n/locale-provider";
import { feishuSetupProgress, type FeishuSetupStep } from "../lib/feishu-setup-progress";
import type { FeishuManager, FeishuSetupJob } from "../types/feishu-setup";
import styles from "./feishu-setup-steps.module.css";

export function FeishuSetupSteps({
  job,
  manager,
  mode = "bot",
}: {
  job?: FeishuSetupJob | null;
  manager?: FeishuManager;
  mode?: "enterprise" | "bot";
}) {
  const t = useT();
  const progress = feishuSetupProgress(job, manager, mode);
  const steps: { id: FeishuSetupStep; label: string }[] =
    mode === "enterprise"
      ? [
          { id: "connect", label: t("连接飞书") },
          { id: "configure", label: t("配置发布") },
          { id: "authorize", label: t("管理授权") },
          { id: "complete", label: t("完成") },
        ]
      : [
          { id: "connect", label: t("连接飞书") },
          { id: "authorize", label: t("管理授权") },
          { id: "create", label: t("创建机器人") },
          { id: "configure", label: t("配置发布") },
          { id: "complete", label: t("完成") },
        ];
  const current = steps.findIndex((step) => step.id === progress.step);
  return (
    <ol className={styles.steps} data-mode={mode} aria-label={t("创建进度")}>
      {steps.map((step, index) => {
        const completed = progress.complete || index < current;
        const active = index === current;
        const attention = active && progress.attention;
        const state = completed ? "complete" : active ? (attention ? "attention" : "current") : "pending";
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
              {completed ? <IconCheck size={32} /> : attention ? <IconAlertCircle size={32} /> : index + 1}
            </span>
            <span className={styles.label}>{step.label}</span>
          </li>
        );
      })}
    </ol>
  );
}
