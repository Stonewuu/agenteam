import type {ModelSelection, ReasoningEffort} from "@/features/modelprofile/lib/reasoning-effort";

export type RunStatus = "queued" | "running" | "waiting_approval" | "cancelling" | "completed" | "failed" | "cancelled";
export type ToolApprovalPolicy = "default" | "auto_approve" | "full_access";
export type BlockStatus = "pending" | "running" | "waiting_approval" | "completed" | "failed" | "cancelled" | "skipped";
export type Run = {
  id: string;
  conversationId: string;
  inputMessageId: string;
  outputMessageId: string;
  status: RunStatus;
  mode: "interactive" | "preview" | "scheduled" | "manual_schedule";
  currentAttemptNo: number;
  hasStepErrors: boolean;
  startedAt: string | null;
  finishedAt: string | null;
  nextAttemptAt: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  canRetry: boolean;
  lastSequence: string;
};
export type ContentBlock = {
  id: string;
  type: "text" | "thinking" | "execution_summary" | "tool" | "subagent" | "workflow" | "citation" | "attachment" | "approval";
  parentBlockId: string | null;
  displayOrder: number;
  revision: string;
  text: string;
  status: BlockStatus;
  stepId: string | null;
  approvalId: string | null;
  label: string | null;
  file: FileReference | null;
  citation: Citation | null;
  agentIcon?: string | null;
  agentColor?: string | null;
  tool: {
    toolCallId: string;
    name: string;
    sourceKind: "knowledge" | "data" | null;
    input: string;
    result: string;
    callStatus: Exclude<BlockStatus, "waiting_approval" | "skipped">;
    resultStatus: Exclude<BlockStatus, "skipped">;
  } | null;
};
export type FileReference = {
  id: string;
  name: string;
  mediaType: string;
  sizeBytes: number;
  status: "pending" | "uploaded" | "scanning" | "ready" | "rejected" | "deleted";
  errorCode: string | null;
  createdAt: string;
};
export type Citation = {
  chunkId: string;
  documentId: string;
  fileId: string;
  generation: number;
  name: string;
  page: number | null;
  section: string | null;
  excerpt: string;
};
export type ConversationMessage = {
  id: string;
  runId: string | null;
  attemptNo: number;
  role: "user" | "assistant" | "system";
  content: string;
  status: "pending" | "streaming" | "completed" | "failed" | "cancelled";
  blocks: ContentBlock[];
  attachments: FileReference[];
  feedback: "positive" | "negative" | null;
  createdAt: string;
  updatedAt: string;
};
export type Conversation = {
  projectId?: string | null;
  modelSelection?: ModelSelection | null;
  approvalPolicy?: ToolApprovalPolicy;
  id: string;
  revision: string;
  createdAt: string;
  updatedAt: string;
  title: string;
  agentId: string | null;
  agentName: string;
  agentIcon: string | null;
  agentColor: string | null;
  mode: "normal" | "preview";
  favorite: boolean;
  status: "active" | "archived" | "deleted";
  activeRunId: string | null;
  canContinue: boolean;
  unavailableReason: string | null;
};
export type ConversationSnapshot = {
  conversation: Conversation;
  messages: ConversationMessage[];
  activeRun: Run | null;
  lastSequence: string;
  hasOlderMessages: boolean;
  nextBeforeMessageId: string | null;
  attachmentsEnabled: boolean;
  protocolVersion?: 1 | 2;
  streamCursor?: {
    generation: string;
    sequence: string;
  } | null;
  liveSteps?: {
    runId: string;
    messageId: string;
    sequence: string;
    step: RunStep;
  }[];
  approvals?: RunApproval[];
};
export type MessageHistory = {
  messages: ConversationMessage[];
  hasMore: boolean;
  nextBeforeMessageId: string | null;
};
export type RunAccepted = {
  conversationId: string;
  runId: string;
  inputMessageId: string;
  outputMessageId: string;
  status: "queued";
  lastSequence: string;
};
export type MessageInput = {
  text: string;
  attachmentIds: string[];
  skillVersionIds: string[];
  knowledgeReferences: {
    documentId: string;
    generation: number;
  }[];
  links: string[];
  modelSelection?: ModelSelection | null;
};
export type ConversationModelOption = {
  id: string;
  name: string;
  modelName: string;
  reasoningEfforts: ReasoningEffort[];
  available: boolean;
  unavailableReason: string | null;
};
export type ConversationModelOptions = {
  configurable: boolean;
  defaultSelection: ModelSelection | null;
  selection: ModelSelection | null;
  models: ConversationModelOption[];
};
export type ConversationUpdate = {
  title?: string;
  favorite?: boolean;
  status?: "active" | "archived";
};
export type RunAttempt = {
  id: string;
  attemptNo: number;
  outputMessageId: string;
  status: Exclude<RunStatus, "cancelling">;
  startedAt: string | null;
  finishedAt: string | null;
  errorSummary: string | null;
};
export type WorkflowStepDetails = {
  invocationId: string;
  resourceId: string;
  versionId: string | null;
  nodeId: string | null;
  nodeType: "start" | "agent" | "skill" | "tool" | "condition" | "transform" | "approval" | "parallel" | "join" | "end" | null;
};
export type RunStep = {
  id: string;
  parentStepId: string | null;
  attemptId: string;
  kind: string;
  title: string;
  displayOrder: number;
  status: BlockStatus;
  publicSummary: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  workflow: WorkflowStepDetails | null;
};
export type RunApproval = {
  id: string;
  runId: string;
  stepId: string;
  requestHash: string;
  revision: string;
  kind?: "tool" | "workflow";
  status: "pending" | "approved" | "rejected" | "expired" | "revoked";
  expiresAt: string;
  summary: {
    title: string;
    target: string;
    description: string;
    content: string;
    irreversibleEffect: string | null;
  };
};
type EventPayloads = {
  "run.created": Run;
  "run.started": Run;
  "run.resumed": Run;
  "run.waiting_approval": Run;
  "run.cancelling": Run;
  "run.completed": Run;
  "run.failed": Run;
  "run.cancelled": Run;
  "message.created": ConversationMessage;
  "message.delta": {
    messageId: string;
    blockId: string;
    baseRevision: string;
    revision: string;
    delta: string;
  };
  "block.updated": {
    messageId: string;
    block: ContentBlock;
  };
  "step.updated": RunStep;
  "attempt.updated": RunAttempt;
  "approval.created": RunApproval;
  "approval.resolved": RunApproval;
};
type EventIdentity = {
  eventId: string;
  enterpriseId: string;
  conversationId: string;
  runId: string;
  sequence: string;
  createdAt: string;
} & ({
  protocolVersion: 1;
} | {
  protocolVersion: 2;
  generation: string;
  databaseVersion: string;
});
export type ExecutionEvent = EventIdentity & {
  [K in keyof EventPayloads]: {
    type: K;
    payload: EventPayloads[K];
  };
}[keyof EventPayloads];
export type ConversationFrame = {
  kind: "event";
  event: ExecutionEvent;
} | {
  kind: "ready";
  lastSequence: string;
  generation?: string;
} | {
  kind: "reset";
  reason: "events_expired" | "sequence_invalid" | "data_incomplete" | "protocol_changed";
  lastSequence: string;
  generation?: string;
};
