import type {Conversation, ConversationNavigationItem} from "../types/execution";

export function navigationConversation(item: ConversationNavigationItem): Conversation {
  return {...item.conversation, navigationKey: item.key, title: item.title, updatedAt: item.updatedAt,
    favorite: item.favorite, collaboration: item.collaboration, matchedRole: item.matchedRole};
}

let warned = false;
const temporary = new Map<string, string>();
export const conversationPreferenceChanged = "agenteam:conversation-preference-changed";

function storageFailure(failure: unknown) {
  if (!warned) {
    warned = true;
    console.warn("无法保存对话查看偏好，本次页面仍可正常使用。", failure);
  }
}

export function readConversationPreference(key: string): string {
  if (typeof window === "undefined") {
    return "";
  }
  try {
    return temporary.get(key) ?? window.localStorage.getItem(key) ?? "";
  } catch (failure) {
    storageFailure(failure);
    return temporary.get(key) ?? "";
  }
}

export function saveConversationPreference(key: string, value: string) {
  try {
    window.localStorage.setItem(key, value);
    temporary.delete(key);
  } catch (failure) {
    storageFailure(failure);
    temporary.set(key, value);
  }
  window.dispatchEvent(new Event(conversationPreferenceChanged));
}

export function rolePreferenceKey(user: string, enterprise: string, task: string) {
  return `agenteam:conversation-role:${user}:${enterprise}:${task}`;
}

export function conversationTarget(conversation: Conversation, user: string, enterprise: string): string {
  const group = conversation.collaboration;
  if (!group || conversation.matchedRole) {
    return conversation.id;
  }
  const last = readConversationPreference(rolePreferenceKey(user, enterprise, group.taskId));
  return group.roles.some(role => role.conversationId === last) ? last : group.ownerConversationId
    ?? group.roles.find(role => role.conversationId)?.conversationId ?? conversation.id;
}

export function conversationSelected(conversation: Conversation, id: string | null): boolean {
  return conversation.id === id || Boolean(id && conversation.collaboration?.roles.some(role => role.conversationId === id));
}

/** 刷新只更新已有条目内容，新任务置顶；执行中的角色变化不打乱正在阅读的列表。 */
export function retainConversationOrder(previous: Conversation[], incoming: Conversation[]): Conversation[] {
  const key = (value: Conversation) => value.navigationKey ?? value.id;
  const next = new Map(incoming.map(value => [key(value), value]));
  const old = new Set(previous.map(key));
  return [...incoming.filter(value => !old.has(key(value))), ...previous.flatMap(value => next.has(key(value)) ? [next.get(key(value))!] : [])];
}
