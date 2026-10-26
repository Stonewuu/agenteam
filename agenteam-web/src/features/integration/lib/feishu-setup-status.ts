import type { FeishuSetupJob } from "../types/feishu-setup";

export type FeishuSetupStatusKind = "processing" | "waiting" | "action" | "success" | "error" | "stopped";
export type FeishuSetupStatusContent = { kind: FeishuSetupStatusKind; title: string; detail: string; hint?: string };

/** 后台处理才显示加载动画；等待审批和本人操作使用各自的提示。 */
export function feishuSetupStatus(
  job: Pick<FeishuSetupJob, "kind" | "stage" | "message" | "canRetry">,
): FeishuSetupStatusContent {
  const processingHint = "可以暂时关闭窗口，后台会继续处理。";
  switch (job.stage) {
    case "queued":
      return { kind: "processing", title: "正在准备创建", detail: "请稍候，创建入口准备好后即可扫码或打开飞书。" };
    case "awaiting_scan":
      return { kind: "action", title: "请在飞书确认创建", detail: "确认后，群策会自动继续处理。" };
    case "preparing":
      return {
        kind: "processing",
        title: "正在核验应用",
        detail: "正在确认飞书应用与所属企业，请稍候。",
        hint: processingHint,
      };
    case "configuring":
      return {
        kind: "processing",
        title: "正在配置应用",
        detail:
          job.kind === "enterprise"
            ? "正在配置企业账号、通知与机器人管理，请稍候。"
            : "群策正在设置消息接收和交互功能，请稍候。",
        hint: processingHint,
      };
    case "publishing":
      return {
        kind: "processing",
        title: "正在提交发布",
        detail: "群策正在将应用提交到飞书，请稍候。",
        hint: processingHint,
      };
    case "waiting_approval":
      return {
        kind: "waiting",
        title: "等待飞书审核",
        detail: "审核通过后，群策会自动继续处理。",
        hint: "若飞书要求管理员审批，请联系企业管理员。",
      };
    case "binding":
      return {
        kind: "processing",
        title: "正在关联智能体",
        detail: "应用已发布，正在为机器人关联你选择的智能体。",
        hint: processingHint,
      };
    case "completed":
      return job.kind === "enterprise"
        ? { kind: "success", title: "企业应用已连接", detail: "完成管理授权后，即可快捷创建岗位机器人。" }
        : job.kind === "management"
          ? { kind: "success", title: "管理应用已连接", detail: "点击下方按钮，继续设置岗位机器人。" }
          : {
              kind: "success",
              title: "机器人创建完成",
              detail: "现在可以在飞书中与机器人对话。首次使用时，按提示绑定本人账号即可。",
            };
    case "authorization_required":
      return {
        kind: "action",
        title: "需要完成管理授权",
        detail: job.message,
        hint: "请先点击“授权管理应用”，在飞书确认后继续处理。",
      };
    case "failed":
      return {
        kind: "error",
        title: "当前步骤未完成",
        detail: job.message,
        hint: job.canRetry ? "处理提示中的问题后，点击“继续处理”重试。" : undefined,
      };
    case "registration_unknown":
      return { kind: "error", title: "请核对飞书创建结果", detail: job.message };
    case "expired":
      return { kind: "stopped", title: "创建入口已过期", detail: "请先确认飞书中是否已创建应用，再返回填写重新开始。" };
    case "cancelled":
      return { kind: "stopped", title: "创建已取消", detail: "重新开始前，请确认飞书是否已经创建了应用。" };
    default:
      return { kind: "action", title: job.message, detail: "" };
  }
}
