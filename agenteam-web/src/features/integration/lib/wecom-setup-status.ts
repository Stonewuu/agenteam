import type { WecomSetupJob } from "../types/wecom-setup";
import type { SetupStatusContent } from "../components/integration-setup-status";

export function wecomSetupStatus(job: WecomSetupJob): SetupStatusContent {
  switch (job.stage) {
    case "queued":
      return { kind: "processing", title: "正在准备创建", detail: "请稍候，正在准备企微创建入口。" };
    case "awaiting_scan":
      return { kind: "action", title: "请在企微确认创建", detail: "请使用已接入企业的企微账号扫码。" };
    case "verifying":
      return {
        kind: "processing",
        title: "正在核验所属企业",
        detail: "请稍候，群策正在核对机器人所属的企微企业。",
        hint: "可以暂时关闭窗口，后台会继续处理。",
      };
    case "binding":
      return {
        kind: "processing",
        title: "正在关联智能体",
        detail: "企业核验已通过，正在完成机器人接入。",
        hint: "可以暂时关闭窗口，后台会继续处理。",
      };
    case "completed":
      return { kind: "success", title: "机器人已接入", detail: "可以在企微私聊机器人。同一企业账号只需绑定一次。" };
    case "failed":
      return { kind: "error", title: "接入暂未完成", detail: job.message || "请检查后重试。" };
    case "expired":
      return { kind: "stopped", title: "二维码已过期", detail: "请重新获取创建入口。" };
    case "cancelled":
      return { kind: "stopped", title: "创建流程已取消", detail: "已经创建的企微机器人仍会保留。" };
  }
}
