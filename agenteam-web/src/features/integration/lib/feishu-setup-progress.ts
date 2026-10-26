import type { FeishuManager, FeishuSetupJob } from "../types/feishu-setup";

export type FeishuSetupStep = "connect" | "authorize" | "create" | "configure" | "complete";

/** 管理应用就绪后还要授权和创建岗位；只有岗位绑定完成才算整个流程完成。 */
export function feishuSetupProgress(
  job?: Pick<FeishuSetupJob, "kind" | "stage" | "connectionId"> | null,
  manager?: Pick<FeishuManager, "authorized"> | null,
  mode: "enterprise" | "bot" = "bot",
) {
  if (mode === "enterprise") {
    const connected = (!job && Boolean(manager)) || job?.stage === "completed";
    const complete = connected && Boolean(manager?.authorized);
    const step: FeishuSetupStep = complete
      ? "complete"
      : connected
        ? "authorize"
        : job?.connectionId
          ? "configure"
          : "connect";
    return {
      step,
      complete,
      attention: Boolean(job && ["failed", "registration_unknown", "expired"].includes(job.stage)),
    };
  }
  const complete = job?.kind === "bot" && job.stage === "completed";
  let step: FeishuSetupStep;
  if (complete) {
    step = "complete";
  } else if (job && job.kind !== "bot" && job.stage !== "completed") {
    step = "connect";
  } else if (job?.stage === "authorization_required") {
    step = "authorize";
  } else if (job?.kind === "bot") {
    step =
      ["configuring", "publishing", "waiting_approval", "binding"].includes(job.stage) || job.connectionId
        ? "configure"
        : "create";
  } else if (manager || job?.stage === "completed") {
    step = manager?.authorized ? "create" : "authorize";
  } else {
    step = "connect";
  }
  const attention = Boolean(
    job && ["failed", "authorization_required", "registration_unknown", "expired"].includes(job.stage),
  );
  return { step, complete, attention };
}
