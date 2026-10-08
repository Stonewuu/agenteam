import type {
  ContentBlock,
  ConversationFrame,
  ConversationMessage,
  ConversationSnapshot,
  ExecutionEvent,
  Run,
  RunApproval
} from "../types/execution";
import {
  record,
  sequence,
  SnapshotRequired,
  validateBlocks,
  validateMessage,
  validateRunStep,
  validateRunStepTree,
  validateSnapshot
} from "./conversation-protocol";
import type {LiveRunStep} from "../lib/conversation-steps";

export type ConversationState = {
  snapshot: ConversationSnapshot;
  replaying: boolean;
  liveBlocks: ReadonlySet<string>;
  latestRun: Run | null;
  approvals: ReadonlyMap<string, RunApproval>;
  steps: ReadonlyMap<string, LiveRunStep>;
};
const one = BigInt(1);
const terminal = new Set(["completed", "failed", "cancelled"]);

export function fromSnapshot(snapshot: ConversationSnapshot): ConversationState {
  validateSnapshot(snapshot);
  return {
    snapshot, replaying: Boolean(snapshot.activeRun), liveBlocks: new Set(), latestRun: snapshot.activeRun,
    approvals: new Map((snapshot.approvals ?? []).map((value) => [value.id, value])),
    steps: new Map((snapshot.liveSteps ?? []).map((value) => [value.step.id, value]))
  };
}

export function streamSequence(snapshot: ConversationSnapshot): string {
  return snapshot.protocolVersion === 2 ? snapshot.streamCursor?.sequence ?? "0" : snapshot.lastSequence;
}

export function applyConversationFrame(state: ConversationState, frame: ConversationFrame, enterpriseId: string): ConversationState {
  if (frame.kind === "reset") {
    throw new SnapshotRequired(frame.reason);
  }
  if (frame.kind === "ready") {
    if (state.snapshot.protocolVersion === 2 && frame.generation !== state.snapshot.streamCursor?.generation) {
      throw new SnapshotRequired();
    }
    if (sequence(frame.lastSequence) > sequence(streamSequence(state.snapshot))) {
      throw new SnapshotRequired();
    }
    return {...state, replaying: false};
  }
  const event = frame.event;
  if (event.enterpriseId !== enterpriseId || event.conversationId !== state.snapshot.conversation.id
    || event.protocolVersion !== (state.snapshot.protocolVersion ?? 1)) {
    throw new SnapshotRequired();
  }
  if (event.protocolVersion === 2 && event.generation !== state.snapshot.streamCursor?.generation) {
    throw new SnapshotRequired();
  }
  const current = sequence(streamSequence(state.snapshot));
  const next = sequence(event.sequence);
  if (next <= current) {
    return state;
  }
  if (next !== current + one) {
    throw new SnapshotRequired();
  }
  let snapshot = state.snapshot;
  let latestRun = state.latestRun;
  let liveBlocks = state.liveBlocks;
  let approvals = state.approvals;
  let steps = state.steps;
  switch (event.type) {
    case "message.created": {
      validateMessage(event.payload);
      if (event.payload.runId !== event.runId || snapshot.messages.some((message) => message.id === event.payload.id)) {
        throw new SnapshotRequired();
      }
      snapshot = {...snapshot, messages: [...snapshot.messages, event.payload]};
      break;
    }
    case "block.updated": {
      const {messageId, block} = event.payload;
      record(block);
      snapshot = updateMessage(snapshot, messageId, event, (message) => {
        const previous = message.blocks.find((value) => value.id === block.id);
        const invalidRevision = event.protocolVersion === 2
          ? sequence(block.revision) <= (previous ? sequence(previous.revision) : BigInt(0))
          : sequence(block.revision) !== (previous ? sequence(previous.revision) + one : one);
        if (invalidRevision
          || previous && (previous.displayOrder !== block.displayOrder || previous.parentBlockId !== block.parentBlockId || previous.type !== block.type)) {
          throw new SnapshotRequired();
        }
        const blocks = previous ? message.blocks.map((value) => value.id === block.id ? block : value) : [...message.blocks, block];
        validateBlocks(blocks);
        return updateBlocks(message, blocks);
      });
      if (!state.replaying && (block.type === "text" || block.type === "thinking" || block.type === "execution_summary")) {
        liveBlocks = new Set([...liveBlocks, block.id]);
      }
      break;
    }
    case "message.delta": {
      const payload = event.payload;
      snapshot = updateMessage(snapshot, payload.messageId, event, (message) => {
        const block = message.blocks.find((value) => value.id === payload.blockId);
        if (!block || (block.type !== "text" && block.type !== "thinking") || block.revision !== payload.baseRevision || sequence(payload.revision) !== sequence(block.revision) + one
          || typeof payload.delta !== "string" || new TextEncoder().encode(payload.delta).length > 4096) {
          throw new SnapshotRequired();
        }
        if (block.text.length + payload.delta.length > 200000) {
          throw new SnapshotRequired();
        }
        return updateBlocks(message, message.blocks.map((value) => value.id === block.id
          ? {...value, text: value.text + payload.delta, revision: payload.revision, status: "running"} : value));
      });
      if (!state.replaying) {
        liveBlocks = new Set([...liveBlocks, payload.blockId]);
      }
      break;
    }
    case "run.created":
    case "run.started":
    case "run.resumed":
    case "run.waiting_approval":
    case "run.cancelling":
    case "run.completed":
    case "run.failed":
    case "run.cancelled": {
      const run = event.payload;
      if (run.id !== event.runId || run.conversationId !== event.conversationId
        || run.lastSequence !== (event.protocolVersion === 2 ? event.databaseVersion : event.sequence)
        || !["queued", "running", "waiting_approval", "cancelling", ...terminal].includes(run.status)) {
        throw new SnapshotRequired();
      }
      const ended = terminal.has(run.status);
      const activeRun = ended ? snapshot.activeRun?.id === run.id ? null : snapshot.activeRun : run;
      if (!ended || !activeRun || activeRun.id === run.id) {
        latestRun = run;
      }
      snapshot = {
        ...snapshot, activeRun,
        conversation: {
          ...snapshot.conversation,
          activeRunId: activeRun?.id ?? null,
          canContinue: false,
          updatedAt: event.createdAt
        },
        messages: snapshot.messages.map((message) => message.id === run.outputMessageId
          ? {
            ...message,
            status: ended ? run.status as "completed" | "failed" | "cancelled" : run.status === "queued" ? "pending" : "streaming",
            updatedAt: event.createdAt
          } : message)
      };
      break;
    }
    case "attempt.updated": {
      const attempt = event.payload;
      record(attempt);
      const message = snapshot.messages.find((value) => value.id === attempt.outputMessageId);
      if (!message || message.runId !== event.runId || message.role !== "assistant" || message.attemptNo !== attempt.attemptNo
        || !terminal.has(attempt.status)) {
        throw new SnapshotRequired();
      }
      snapshot = {
        ...snapshot, messages: snapshot.messages.map((value) => value.id === message.id
          ? {
            ...value,
            status: attempt.status as "completed" | "failed" | "cancelled",
            updatedAt: event.createdAt
          } : value)
      };
      break;
    }
    case "approval.created":
    case "approval.resolved": {
      const approval = event.payload;
      record(approval);
      record(approval.summary);
      if (typeof approval.id !== "string" || !approval.id || sequence(approval.revision) === BigInt(0)
        || !["pending", "approved", "rejected", "expired", "revoked"].includes(approval.status)) {
        throw new SnapshotRequired();
      }
      const previous = approvals.get(approval.id);
      if (previous && sequence(approval.revision) < sequence(previous.revision)) {
        throw new SnapshotRequired();
      }
      approvals = new Map(approvals).set(approval.id, approval);
      break;
    }
    case "step.updated": {
      const step = event.payload;
      validateRunStep(step);
      const previous = steps.get(step.id);
      if (previous && (previous.runId !== event.runId || previous.step.attemptId !== step.attemptId
        || previous.step.parentStepId !== step.parentStepId || previous.step.displayOrder !== step.displayOrder)) {
        throw new SnapshotRequired();
      }
      const owner = snapshot.messages.find((message) => message.role === "assistant" && message.runId === event.runId && message.blocks.some((block) => block.stepId === step.id));
      const messageId = owner?.id ?? previous?.messageId ?? (latestRun?.id === event.runId ? latestRun.outputMessageId : null);
      if (!messageId) {
        throw new SnapshotRequired();
      }
      steps = new Map(steps).set(step.id, {runId: event.runId, messageId, sequence: event.sequence, step});
      validateRunStepTree([...steps.values()].filter((value) => value.runId === event.runId).map((value) => value.step), true);
      break;
    }
    default:
      throw new SnapshotRequired();
  }
  if (event.protocolVersion === 2) {
    return {
      ...state, approvals, steps, snapshot: {
        ...snapshot,
        lastSequence: sequence(event.databaseVersion) > sequence(snapshot.lastSequence) ? event.databaseVersion : snapshot.lastSequence,
        streamCursor: {generation: event.generation, sequence: event.sequence}
      }, latestRun, liveBlocks
    };
  }
  return {
    ...state, approvals, steps, snapshot: {
      ...snapshot, lastSequence: event.sequence,
      activeRun: snapshot.activeRun ? {...snapshot.activeRun, lastSequence: event.sequence} : null
    }, latestRun, liveBlocks
  };
}

function updateMessage(snapshot: ConversationSnapshot, id: string, event: ExecutionEvent, update: (message: ConversationMessage) => ConversationMessage): ConversationSnapshot {
  const current = snapshot.messages.find((message) => message.id === id);
  if (!current || current.runId !== event.runId || current.role !== "assistant" || terminal.has(current.status)) {
    throw new SnapshotRequired();
  }
  const changed = {...update(current), status: "streaming" as const, updatedAt: event.createdAt};
  return {...snapshot, messages: snapshot.messages.map((message) => message.id === id ? changed : message)};
}

function updateBlocks(message: ConversationMessage, blocks: ContentBlock[]): ConversationMessage {
  const sorted = [...blocks].sort((left, right) => left.displayOrder - right.displayOrder);
  const content = sorted.filter((block) => block.type === "text" && block.parentBlockId === null).map((block) => block.text).join("\n\n");
  return {...message, blocks: sorted, content};
}
