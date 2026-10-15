import type {ConversationMessage} from "../types/execution";

export type ConversationReadingPosition = {top: number; anchorId: string | null; offset: number; atBottom: boolean};
const positions = new Map<string, ConversationReadingPosition>();

/** 只在当前页面会话内保存位置，不缓存正文；账号、企业、对话由调用方组成完整范围。 */
export function conversationReadingPosition(scope: string) {
  return positions.get(scope);
}

export function rememberConversationPosition(scope: string, value: ConversationReadingPosition) {
  positions.delete(scope);
  positions.set(scope, value);
  while (positions.size > 100) {
    positions.delete(positions.keys().next().value!);
  }
}

/** 只提示新到达的消息或内容变化；加载历史与评价变化不算新内容。 */
export function hasNewConversationContent(previous: readonly ConversationMessage[], current: readonly ConversationMessage[]) {
  if (!previous.length || !current.length) {
    return false;
  }
  const before = new Map(previous.map((message) => [message.id, message]));
  const previousTail = current.findIndex((message) => message.id === previous.at(-1)!.id);
  if (previousTail >= 0 && previousTail < current.length - 1) {
    return true;
  }
  return current.some((message) => {
    const old = before.get(message.id);
    return old && (old.content !== message.content || old.status !== message.status || old.blocks.length !== message.blocks.length
      || message.blocks.some((block, index) => block.id !== old.blocks[index]?.id || block.revision !== old.blocks[index]?.revision));
  });
}
