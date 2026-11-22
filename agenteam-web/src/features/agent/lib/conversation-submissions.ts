import type {DisplayBlock, DisplayMessage} from "../types/conversation-display";
import type {FileReference, RunAccepted} from "../types/execution";

export type ConversationSubmission = {
  id: string;
  scope: string;
  knownMessageIds: string[];
  input: DisplayMessage;
  reply: DisplayMessage;
  queued: boolean;
  accepted?: RunAccepted;
  inputObserved: boolean;
  replyObserved: boolean;
};

/** 本地消息只负责即时显示，服务器消息和任务状态仍由对话事件维护。 */
export function createConversationSubmission({scope, text, attachments, labels, messages, queued}: {
  scope: string; text: string; attachments: FileReference[]; labels: string[];
  messages: DisplayMessage[]; queued: boolean;
}): ConversationSubmission {
  const id = crypto.randomUUID();
  const now = new Date().toISOString();
  const common = {
    runId: null, attemptNo: 1, status: "pending" as const, feedback: null,
    createdAt: now, updatedAt: now, hasMultipleAttempts: false, localDelivery: "sending" as const
  };
  const blocks: DisplayBlock[] = labels.map((label, order) => ({
    kind: "status", id: `${id}:selection:${order}`, label, status: "completed", order, blocks: []
  }));
  return {
    id, scope, queued, knownMessageIds: messages.map(message => message.id), inputObserved: false, replyObserved: false,
    input: {...common, id: `${id}:input`, role: "user", content: text, attachments, blocks},
    reply: {...common, id: `${id}:reply`, role: "assistant", content: "", attachments: [], blocks: []}
  };
}

function submissionInput(messages: DisplayMessage[], submission: ConversationSubmission) {
  if (submission.accepted) {
    return messages.find(message => message.id === submission.accepted!.inputMessageId);
  }
  // 排队时事件可能先于发送响应到达，只匹配提交之后新增且内容和附件一致的唯一消息。
  const candidates = messages.filter(message => message.role === "user" && !submission.knownMessageIds.includes(message.id)
    && message.content === submission.input.content
    && message.attachments.length === submission.input.attachments.length
    && message.attachments.every(file => submission.input.attachments.some(value => value.id === file.id)));
  return candidates.length === 1 ? candidates[0] : undefined;
}

/** 已经读到的消息不再用本地内容补回，避免刷新或历史分页使旧消息重新出现。 */
export function observeConversationSubmissions(submissions: ConversationSubmission[], scope: string, messages: DisplayMessage[]) {
  let changed = false;
  const next = submissions.map(submission => {
    if (submission.scope !== scope || !submission.accepted) {
      return submission;
    }
    const inputObserved = submission.inputObserved || messages.some(message => message.id === submission.accepted!.inputMessageId);
    const replyObserved = submission.replyObserved || messages.some(message => message.id === submission.accepted!.outputMessageId);
    if (inputObserved === submission.inputObserved && replyObserved === submission.replyObserved) {
      return submission;
    }
    changed = true;
    return {...submission, inputObserved, replyObserved};
  });
  return changed ? next : submissions;
}

/** 接口返回后使用消息编号对应，保留显示键，避免气泡和等待动画重新挂载。 */
export function mergeConversationSubmissions(messages: DisplayMessage[], submissions: ConversationSubmission[], scope: string) {
  const result = [...messages];
  for (const submission of submissions) {
    if (submission.scope !== scope) {
      continue;
    }
    const input = submissionInput(messages, submission);
    const accepted = submission.accepted;
    const reply = messages.find(message => accepted ? message.id === accepted.outputMessageId
      : input?.runId && message.role === "assistant" && message.runId === input.runId);
    const delivery = accepted ? "accepted" : "sending";
    if (input) {
      result[result.findIndex(message => message.id === input.id)] = {...input, clientId: submission.input.id};
    } else if (!submission.inputObserved) {
      const local: DisplayMessage = {...submission.input, id: accepted?.inputMessageId ?? submission.input.id,
        clientId: submission.input.id, runId: accepted?.runId ?? null, localDelivery: delivery,
        status: accepted ? "completed" : "pending"};
      const replyIndex = reply ? result.findIndex(message => message.id === reply.id) : -1;
      result.splice(replyIndex < 0 ? result.length : replyIndex, 0, local);
    }
    if (reply) {
      result[result.findIndex(message => message.id === reply.id)] = {...reply, clientId: submission.reply.id};
    } else if (!submission.queued && accepted?.status !== "waiting_turn" && !submission.replyObserved) {
      const inputIndex = result.findIndex(message => message.clientId === submission.input.id);
      if (inputIndex >= 0) {
        result.splice(inputIndex + 1, 0, {...submission.reply, id: accepted?.outputMessageId ?? submission.reply.id,
          clientId: submission.reply.id, runId: accepted?.runId ?? input?.runId ?? null,
          localDelivery: input ? "accepted" : delivery});
      }
    }
  }
  return result;
}
