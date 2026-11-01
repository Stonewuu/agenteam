"use client";

import { useT } from "@/lib/i18n/locale-provider";
import { feishuSetupProgress, type FeishuSetupStep } from "../lib/feishu-setup-progress";
import type { FeishuManager, FeishuSetupJob } from "../types/feishu-setup";
import { IntegrationSetupSteps } from "./integration-setup-steps";

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
  return (
    <IntegrationSetupSteps
      steps={steps}
      current={progress.step}
      complete={progress.complete}
      attention={progress.attention}
    />
  );
}
