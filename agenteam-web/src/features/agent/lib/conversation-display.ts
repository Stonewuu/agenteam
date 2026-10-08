import type {ConversationState} from "../state/conversation-event-state";
import type {BlockStatus, ContentBlock, ConversationMessage, RunApproval} from "../types/execution";
import type {DisplayBlock, DisplayMessage} from "../types/conversation-display";
import {localizeSavedToolLabel} from "../../plugin/lib/tool-display-name";

/** 发起工具与子任务的关系来自公开块，不从工具正文或最近事件推断。 */
export function displayMessages(state: ConversationState | null): DisplayMessage[] {
  if (!state) {
    return [];
  }
  const repeatedRuns = new Set(state.snapshot.messages.filter((message) => message.role === "assistant" && message.attemptNo > 1).map((message) => message.runId));
  return state.snapshot.messages.map((message) => ({
    ...message,
    hasMultipleAttempts: message.runId !== null && repeatedRuns.has(message.runId),
    blocks: displayBlocks(message, state)
  }));
}

/** 查询结果晚到时，不能覆盖事件中已经更新的人工确认状态。 */
export function mergeRunApprovals(live: ReadonlyMap<string, RunApproval> | undefined, saved: RunApproval[]) {
  const values = new Map(live);
  for (const approval of saved) {
    const current = values.get(approval.id);
    if (!current || BigInt(approval.revision) >= BigInt(current.revision)) {
      values.set(approval.id, approval);
    }
  }
  return values;
}

/** 只把同父级、同一步骤且唯一对应的确认放入工具，不根据名称或显示顺序猜测。 */
export function mergeToolConfirmations(blocks: DisplayBlock[]): DisplayBlock[] {
  const values = blocks.map((block) => ({...block, blocks: mergeToolConfirmations(block.blocks)}));
  const tools = new Map<string, DisplayBlock[]>();
  for (const block of values) {
    if (block.kind === "tool" && block.stepId) {
      tools.set(block.stepId, [...(tools.get(block.stepId) ?? []), block]);
    }
  }
  return values.filter((block) => {
    if (block.kind !== "approval" || !block.stepId) {
      return true;
    }
    const matches = tools.get(block.stepId);
    if (matches?.length !== 1) {
      return true;
    }
    matches[0].blocks.push(block);
    matches[0].blocks.sort((a, b) => a.order - b.order);
    return false;
  });
}

/** 只归并同一父级相邻的大类，保留每个原始块、顺序和子树。 */
export function groupConversationBlocks(blocks: DisplayBlock[]) {
  const groups: Array<{ id: string; kind: DisplayBlock["kind"]; blocks: DisplayBlock[] }> = [];
  for (const block of [...blocks].sort((a, b) => a.order - b.order)) {
    const kind = isSubagentTool(block) ? "agent" : block.kind;
    const previous = groups[groups.length - 1];
    if (previous?.kind === kind && ["thinking", "text", "tool", "agent"].includes(kind)) {
      previous.blocks.push(block);
    } else {
      groups.push({id: block.id, kind, blocks: [block]});
    }
  }
  return groups;
}

/** 子任务调用保留原始工具身份，只在展示时使用子智能体版式。 */
export function isSubagentTool(block: DisplayBlock): boolean {
  // 平台插件可能使用同名工具；专用标题或真实子智能体节点共同确认框架调用身份。
  return block.kind === "tool" && block.sourceKind === null && ["agent_spawn", "agent_send"].includes(block.name)
    && (["子智能体", "研究任务"].includes(block.label) || block.blocks.some((child) => child.kind === "agent"));
}

export function toolBlockStatus(block: Extract<DisplayBlock, {
  kind: "tool"
}>, approvals?: ReadonlyMap<string, RunApproval>): BlockStatus {
  const confirmations = block.blocks.filter((child): child is Extract<DisplayBlock, {
    kind: "approval"
  }> => child.kind === "approval");
  if (block.status === "waiting_approval" && confirmations.length) {
    const statuses = confirmations.map((child) => approvals?.get(child.approvalId)?.status);
    if (statuses.some((status) => !status || status === "pending")) {
      return block.status;
    }
    if (statuses.includes("rejected")) {
      return "skipped";
    }
    if (statuses.includes("expired") || statuses.includes("revoked")) {
      return "cancelled";
    }
    return "pending";
  }
  return block.status;
}

/** 准备参数时尚未执行；已经完成确认的调用不能重新显示为准备内容。 */
export function toolStatusLabel(block: Extract<DisplayBlock, { kind: "tool" }>, status: BlockStatus): string {
  if (status === "pending" && block.status === "pending" && block.callStatus === "running" && block.resultStatus === "pending") {
    return "正在准备执行内容";
  }
  const labels: Record<BlockStatus, string> = {
    pending: "等待执行", running: "执行中", waiting_approval: "等待确认", completed: "已完成",
    failed: "执行失败", cancelled: "已停止", skipped: "已跳过",
  };
  return labels[status];
}

/** 旧工具摘要混有模型说明；仅保留待确认操作的影响，工作流说明仍由作者提供。 */
export function approvalDisplayText(approval: RunApproval, insideTool = false) {
  const tool = approval.kind === "tool" || (approval.kind !== "workflow" && insideTool);
  const description = approval.summary.description.trim();
  const notices = ["此工具可能修改外部数据，请核对全部参数后再决定。", "确认后将修改平台中的待办或定时任务，请核对操作内容。"];
  return {
    description: tool ? "" : description,
    notice: tool && approval.status === "pending" ? notices.find((notice) => description.endsWith(notice)) ?? "" : "",
  };
}

export function pendingToolConfirmationIds(blocks: DisplayBlock[], approvals: ReadonlyMap<string, RunApproval>): string[] {
  return blocks.flatMap((block) => [
    ...(block.kind === "tool" ? block.blocks.filter((child) => child.kind === "approval"
      && (approvals.get(child.approvalId)?.status ?? (child.status === "waiting_approval" ? "pending" : "resolved")) === "pending")
      .flatMap((child) => child.kind === "approval" ? [child.approvalId] : []) : []),
    ...pendingToolConfirmationIds(block.blocks, approvals),
  ]);
}

export function groupBlockStatus(blocks: DisplayBlock[], approvals?: ReadonlyMap<string, RunApproval>): BlockStatus {
  const statuses = blocks.flatMap((block) => block.kind === "tool" ? [toolBlockStatus(block, approvals)] : "status" in block ? [block.status] : []);
  for (const status of ["waiting_approval", "running", "pending", "failed", "cancelled"] as const) {
    if (statuses.includes(status)) {
      return status;
    }
  }
  return statuses.every((status) => status === "skipped") ? "skipped" : "completed";
}

function displayBlocks(message: ConversationMessage, state: ConversationState) {
  const sorted = [...message.blocks].sort((left, right) => left.displayOrder - right.displayOrder);

  function children(parent: string | null): DisplayBlock[] {
    return sorted.filter((block) => block.parentBlockId === parent).map(convert);
  }

  function convert(block: ContentBlock): DisplayBlock {
    const common = {id: block.id, order: block.displayOrder, stepId: block.stepId, blocks: children(block.id)};
    if (block.type === "text" || block.type === "thinking") {
      return {
        ...common, kind: block.type, content: block.text, status: block.status,
        animate: !state.replaying && state.liveBlocks.has(block.id) && block.status === "running"
      };
    }
    if (block.type === "execution_summary") {
      return {
        ...common, kind: "summary", label: block.label || "执行说明", content: block.text, status: block.status,
        animate: !state.replaying && state.liveBlocks.has(block.id) && block.status === "running"
      };
    }
    if (block.type === "subagent") {
      return {
        ...common,
        kind: "agent",
        label: block.label || "子智能体",
        status: block.status,
        summary: block.text,
        agentIcon: block.agentIcon,
        agentColor: block.agentColor
      };
    }
    if (block.type === "workflow") {
      return {
        ...common, kind: "workflow", label: block.label || "工作流", status: block.status,
        summary: block.text
      };
    }
    if (block.type === "tool" && block.tool) {
      return {
        ...common,
        kind: "tool", ...block.tool,
        status: block.status,
        label: localizeSavedToolLabel(block.label || "工具调用"),
        summary: block.text
      };
    }
    if (block.type === "approval" && block.approvalId) {
      return {
        ...common,
        kind: "approval",
        approvalId: block.approvalId,
        label: block.label || "确认操作",
        status: block.status,
        revision: block.revision
      };
    }
    if (block.type === "attachment" && block.file) {
      return {...common, kind: "file", file: block.file};
    }
    if (block.type === "citation" && block.citation) {
      return {...common, kind: "citation", citation: block.citation};
    }
    return {...common, kind: "status", label: block.text || block.label || "任务记录", status: block.status};
  }

  return children(null);
}
