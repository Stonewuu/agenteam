import type {BlockStatus, Citation, ConversationMessage, FileReference, RunStep} from "./execution";

export type DisplayBlock = (
  | { kind: "text"; id: string; content: string; status: BlockStatus; order: number; animate: boolean }
  | { kind: "thinking"; id: string; content: string; status: BlockStatus; order: number; animate: boolean }
  | {
  kind: "summary";
  id: string;
  label: string;
  content: string;
  status: BlockStatus;
  order: number;
  animate: boolean
}
  | {
  kind: "agent";
  id: string;
  label: string;
  status: BlockStatus;
  summary: string;
  order: number;
  agentIcon?: string | null;
  agentColor?: string | null
}
  | { kind: "workflow"; id: string; label: string; status: BlockStatus; summary: string; order: number }
  | {
  kind: "tool";
  id: string;
  toolCallId: string;
  name: string;
  sourceKind: "knowledge" | "data" | null;
  label: string;
  summary: string;
  input: string;
  result: string;
  status: BlockStatus;
  callStatus: BlockStatus;
  resultStatus: BlockStatus;
  order: number
}
  | {
  kind: "approval";
  id: string;
  approvalId: string;
  label: string;
  status: BlockStatus;
  revision: string;
  order: number
}
  | { kind: "file"; id: string; file: FileReference; order: number }
  | { kind: "citation"; id: string; citation: Citation; order: number }
  | { kind: "status"; id: string; label: string; status: BlockStatus; order: number }
  | { kind: "step"; id: string; label: string; summary: string; status: BlockStatus; order: number }
  ) & { blocks: DisplayBlock[]; stepId?: string | null; step?: RunStep };

export type DisplayMessage = Omit<ConversationMessage, "blocks"> & {
  blocks: DisplayBlock[];
  hasMultipleAttempts: boolean
};
