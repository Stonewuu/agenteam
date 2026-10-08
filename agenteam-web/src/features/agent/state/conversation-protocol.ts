import type {
  ContentBlock,
  ConversationFrame,
  ConversationMessage,
  ConversationSnapshot,
  ExecutionEvent,
  RunStep
} from "../types/execution";

/** 不完整数据只触发完整快照读取，不能作为执行失败插入消息。 */
export class SnapshotRequired extends Error {
  constructor(public readonly reason = "data_incomplete") {
    super("对话内容需要重新加载。");
    this.name = "SnapshotRequired";
  }
}

export function sequence(value: unknown): bigint {
  if (typeof value !== "string" || !/^(0|[1-9][0-9]{0,18})$/.test(value)) {
    throw new SnapshotRequired();
  }
  const parsed = BigInt(value);
  if (parsed > BigInt("9223372036854775807")) {
    throw new SnapshotRequired();
  }
  return parsed;
}

export function record(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new SnapshotRequired();
  }
  return value as Record<string, unknown>;
}

export function validateBlocks(blocks: ContentBlock[]) {
  if (!Array.isArray(blocks) || blocks.length > 500) {
    throw new SnapshotRequired();
  }
  const byId = new Map<string, ContentBlock>();
  const orders = new Set<number>();
  for (const value of blocks) {
    const block = record(value);
    if (typeof block.id !== "string" || !block.id || byId.has(block.id) || typeof block.text !== "string"
      || !Number.isSafeInteger(block.displayOrder) || (block.displayOrder as number) < 0 || orders.has(block.displayOrder as number)
      || sequence(block.revision) === BigInt(0) || !(block.parentBlockId === null || typeof block.parentBlockId === "string")) {
      throw new SnapshotRequired();
    }
    if (!["text", "thinking", "execution_summary", "tool", "subagent", "workflow", "citation", "attachment", "approval"].includes(block.type as string)
      || !["pending", "running", "waiting_approval", "completed", "failed", "cancelled", "skipped"].includes(block.status as string)) {
      throw new SnapshotRequired();
    }
    if (block.type === "tool") {
      const tool = record(block.tool);
      if (![tool.toolCallId, tool.name, tool.input, tool.result].every((item) => typeof item === "string")
        || !(tool.sourceKind === null || tool.sourceKind === "knowledge" || tool.sourceKind === "data")) {
        throw new SnapshotRequired();
      }
    }
    orders.add(block.displayOrder as number);
    byId.set(block.id, value);
  }
  for (const block of blocks) {
    const visited = new Set([block.id]);
    let parent = block.parentBlockId;
    while (parent !== null) {
      if (visited.has(parent) || !byId.has(parent)) {
        throw new SnapshotRequired();
      }
      visited.add(parent);
      parent = byId.get(parent)!.parentBlockId;
    }
  }
}

export function validateMessage(message: ConversationMessage) {
  const value = record(message);
  if (typeof value.id !== "string" || !value.id || typeof value.content !== "string" || !["user", "assistant", "system"].includes(value.role as string)
    || !["pending", "streaming", "completed", "failed", "cancelled"].includes(value.status as string)) {
    throw new SnapshotRequired();
  }
  validateBlocks(message.blocks);
}

export function validateRunStep(step: RunStep) {
  const value = record(step);
  if (![value.id, value.attemptId, value.kind, value.title].every((entry) => typeof entry === "string" && entry.length > 0)
    || !(value.parentStepId === null || typeof value.parentStepId === "string") || value.parentStepId === value.id
    || !Number.isSafeInteger(value.displayOrder) || (value.displayOrder as number) < 0
    || !(value.publicSummary === null || typeof value.publicSummary === "string")
    || !["pending", "running", "waiting_approval", "completed", "failed", "cancelled", "skipped"].includes(value.status as string)) {
    throw new SnapshotRequired();
  }
}

export function validateRunStepTree(steps: readonly RunStep[], allowMissingParents = false) {
  const byId = new Map<string, RunStep>();
  for (const step of steps) {
    validateRunStep(step);
    if (byId.has(step.id)) {
      throw new SnapshotRequired();
    }
    byId.set(step.id, step);
  }
  for (const step of steps) {
    const visited = new Set([step.id]);
    let parent = step.parentStepId;
    while (parent !== null) {
      if (visited.has(parent)) {
        throw new SnapshotRequired();
      }
      const value = byId.get(parent);
      if (!value) {
        if (allowMissingParents) {
          break;
        }
        throw new SnapshotRequired();
      }
      if (value.attemptId !== step.attemptId) {
        throw new SnapshotRequired();
      }
      visited.add(parent);
      parent = value.parentStepId;
    }
  }
}

export function validateSnapshot(snapshot: ConversationSnapshot) {
  const value = record(snapshot);
  const conversation = record(value.conversation);
  if (value.protocolVersion !== undefined && value.protocolVersion !== 1 && value.protocolVersion !== 2) {
    throw new SnapshotRequired("protocol_changed");
  }
  if (value.protocolVersion === 2) {
    if (value.streamCursor !== null) {
      const cursor = record(value.streamCursor);
      if (typeof cursor.generation !== "string" || !/^[a-zA-Z0-9-]{1,64}$/.test(cursor.generation)) {
        throw new SnapshotRequired();
      }
      sequence(cursor.sequence);
    }
    if (!Array.isArray(snapshot.liveSteps) || !Array.isArray(snapshot.approvals)) {
      throw new SnapshotRequired();
    }
    for (const value of snapshot.liveSteps) {
      record(value);
      sequence(value.sequence);
      validateRunStep(value.step);
      if (typeof value.runId !== "string" || typeof value.messageId !== "string") {
        throw new SnapshotRequired();
      }
    }
  }
  sequence(value.lastSequence);
  if (typeof conversation.id !== "string" || !conversation.id || typeof conversation.title !== "string" || !Array.isArray(value.messages) || typeof value.attachmentsEnabled !== "boolean") {
    throw new SnapshotRequired();
  }
  const ids = new Set<string>();
  for (const message of snapshot.messages) {
    validateMessage(message);
    if (ids.has(message.id)) {
      throw new SnapshotRequired();
    }
    ids.add(message.id);
  }
}

export function parseConversationFrame(frame: string): ConversationFrame | null {
  let id: string | undefined;
  let type = "";
  const lines: string[] = [];
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith(":")) {
      continue;
    }
    const colon = line.indexOf(":");
    if (colon < 0) {
      continue;
    }
    const name = line.slice(0, colon);
    const value = line.slice(colon + 1).replace(/^ /, "");
    if (name === "id") {
      id = value;
    } else if (name === "event") {
      type = value;
    } else if (name === "data") {
      lines.push(value);
    }
  }
  if (!lines.length) {
    return null;
  }
  let value: Record<string, unknown>;
  try {
    value = record(JSON.parse(lines.join("\n")));
  } catch {
    throw new SnapshotRequired();
  }
  if (type === "stream.ready" || type === "stream.reset") {
    if (id !== undefined) {
      throw new SnapshotRequired();
    }
    sequence(value.lastSequence);
    if (value.generation !== undefined && typeof value.generation !== "string") {
      throw new SnapshotRequired();
    }
    const generation = value.generation === undefined ? {} : {generation: value.generation as string};
    if (type === "stream.ready") {
      return {kind: "ready", lastSequence: value.lastSequence as string, ...generation};
    }
    if (!["events_expired", "sequence_invalid", "data_incomplete", "protocol_changed"].includes(value.reason as string)) {
      throw new SnapshotRequired();
    }
    return {
      kind: "reset", reason: value.reason as Extract<ConversationFrame, {
        kind: "reset";
      }>["reason"], lastSequence: value.lastSequence as string, ...generation
    };
  }
  if (value.protocolVersion !== 1 && value.protocolVersion !== 2) {
    throw new SnapshotRequired("protocol_changed");
  }
  if (value.protocolVersion === 2) {
    if (typeof value.generation !== "string" || !/^[a-zA-Z0-9-]{1,64}$/.test(value.generation)) {
      throw new SnapshotRequired();
    }
    sequence(value.databaseVersion);
  }
  if (!id || id !== value.sequence || type !== value.type || typeof value.eventId !== "string" || !value.eventId
    || typeof value.enterpriseId !== "string" || typeof value.conversationId !== "string" || typeof value.runId !== "string" || typeof value.createdAt !== "string") {
    throw new SnapshotRequired();
  }
  record(value.payload);
  sequence(id);
  return {kind: "event", event: value as ExecutionEvent};
}
