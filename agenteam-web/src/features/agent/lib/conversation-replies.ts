import type {ConversationRow, DisplayMessage} from "../types/conversation-display";

export function conversationRowId(message: DisplayMessage) {
  if (message.role === "assistant") {
    if (message.inputMessageId) {
      return `reply:${message.inputMessageId}`;
    }
    if (message.runId) {
      return `run:${message.runId}`;
    }
  }
  return message.id;
}

/** 每条提问只占一个回复位置。各次尝试保留自己的消息和执行编号，操作始终指向最新回复。 */
export function groupConversationReplies(messages: DisplayMessage[]): ConversationRow[] {
  const inputs = new Map<string, string>();
  for (const message of messages) {
    const input = message.role === "user" ? message.id : message.inputMessageId;
    if (message.runId && input) {
      inputs.set(message.runId, input);
    }
  }
  const rows: (DisplayMessage | DisplayMessage[])[] = [];
  const attemptsByReply = new Map<string, DisplayMessage[]>();
  for (const message of messages) {
    if (message.role !== "assistant" || !message.runId) {
      rows.push(message);
      continue;
    }
    const input = inputs.get(message.runId);
    const reply = input && !message.inputMessageId ? {...message, inputMessageId: input} : message;
    const key = conversationRowId(reply);
    const attempts = attemptsByReply.get(key);
    if (attempts) {
      attempts.push(reply);
    } else {
      const first = [reply];
      attemptsByReply.set(key, first);
      rows.push(first);
    }
  }
  return rows.map(row => {
    if (!Array.isArray(row)) {
      return row;
    }
    const latest = row[row.length - 1];
    return row.length === 1 ? latest : {...latest, clientId: row[0].clientId, previousAttempts: row.slice(0, -1)};
  });
}
