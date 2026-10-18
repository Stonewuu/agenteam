import type {Conversation} from "../types/execution";

/** 基本信息按数据库版本合并，导航分组保留其独立名称。 */
export function mergeConversationMetadata(current: Conversation, incoming: Conversation): Conversation {
  if (current.id !== incoming.id || BigInt(incoming.revision) < BigInt(current.revision)) {
    return current;
  }
  if (current.collaboration && !incoming.collaboration) {
    return current;
  }
  return {...current, ...incoming};
}

export function mergeConversationMetadataList(current: Conversation[], incoming: readonly Conversation[]): Conversation[] {
  const updates = new Map(incoming.map(value => [value.id, value]));
  return current.map(value => {
    const next = updates.get(value.id);
    return next ? mergeConversationMetadata(value, next) : value;
  });
}
