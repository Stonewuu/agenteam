import type {ConversationMessage} from "../types/execution";

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
