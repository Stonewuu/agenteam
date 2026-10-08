import type {ConversationState} from "../state/conversation-event-state";
import type {ContentBlock, ConversationFrame} from "../types/execution";

const fileTools = new Set(["write_file", "edit_file", "export_file"]);

function isFileTool(block: ContentBlock) {
  return block.type === "tool" && block.tool !== null && fileTools.has(block.tool.name);
}

/** 在事件通过协议校验后调用；新调用展开侧栏，成功结果单独通知文件列表刷新。 */
export function fileToolActivity(state: ConversationState, frame: ConversationFrame): {
  openKey: string | null;
  refreshKey: string | null
} | null {
  if (state.replaying || frame.kind !== "event") {
    return null;
  }
  const event = frame.event;
  if (event.type === "message.created" && event.payload.role === "assistant") {
    const block = event.payload.blocks.find(isFileTool);
    if (!block) {
      return null;
    }
    const key = `${event.payload.id}:${block.id}`;
    return {openKey: key, refreshKey: block.status === "completed" ? key : null};
  }
  if (event.type !== "block.updated" || !isFileTool(event.payload.block)) {
    return null;
  }
  const {messageId, block} = event.payload;
  const previous = state.snapshot.messages.find((message) => message.id === messageId)?.blocks
    .find((value) => value.id === block.id);
  const key = `${messageId}:${block.id}`;
  const openKey = previous && isFileTool(previous) ? null : key;
  const refreshKey = block.status === "completed" && previous?.status !== "completed" ? key : null;
  return openKey || refreshKey ? {openKey, refreshKey} : null;
}
