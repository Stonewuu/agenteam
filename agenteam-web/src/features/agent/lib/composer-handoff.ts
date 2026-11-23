import type {Employee} from "@/features/employee/types/employee";
import type {WorkspaceProject} from "@/features/project/types/project";
import type {ConversationModelOptions, FileReference, MessageInput, ToolApprovalPolicy} from "../types/execution";
import {createConversationSubmission, type ConversationSubmission} from "./conversation-submissions";

export type ComposerHandoffInput = {
  userId: string;
  enterpriseId: string;
  employee: Employee;
  approvalPolicy: ToolApprovalPolicy;
  focusInput: boolean;
  modelOptions?: ConversationModelOptions;
  project?: WorkspaceProject | null;
  input: MessageInput;
  attachments: FileReference[];
  labels: string[];
};

export type ComposerHandoff = Omit<ComposerHandoffInput, "attachments" | "labels"> & {
  conversationId: string | null;
  status: "sending" | "failed" | "accepted";
  error: string;
  submission: ConversationSubmission;
};

/** 只整理本次输入供对话页发送，不在工作台或跨页面状态中发起请求。 */
export function prepareComposerHandoff(value: ComposerHandoffInput): ComposerHandoff {
  const {attachments, labels, ...input} = value;
  const submission = createConversationSubmission({
    scope: `${input.userId}:${input.enterpriseId}:new:${input.employee.agentId}`,
    text: input.input.text, attachments, labels, messages: [], queued: false
  });
  return {...input, conversationId: null, status: "sending", error: "", submission};
}
