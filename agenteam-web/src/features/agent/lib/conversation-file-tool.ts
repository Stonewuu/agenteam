import {toolPayloadFile} from "@/features/plugin/lib/tool-payload-format";
import {workspaceFileTarget} from "@/features/file/lib/conversation-file-target";
import type {ConversationFileTarget} from "@/features/file/types/conversation-files";
import type {DisplayBlock} from "../types/conversation-display";

const actions: Record<string, { label: string; completed: string }> = {
  view_image: {label: "查看图片", completed: "已查看图片"},
  write_file: {label: "创建文件", completed: "已创建文件"},
  edit_file: {label: "编辑文件", completed: "已修改文件"},
  export_file: {label: "导出文件", completed: "文件已可下载"},
};

export function fileToolAction(block: DisplayBlock) {
  return block.kind === "tool" && block.sourceKind === null && /^文件\s*[·：:]/.test(block.label) && Object.hasOwn(actions, block.name) ? actions[block.name] : undefined;
}

export function fileToolPresentation(block: DisplayBlock) {
  const action = fileToolAction(block);
  if (!action || block.kind !== "tool") {
    return null;
  }
  const input = toolPayloadFile(block.input);
  const result = toolPayloadFile(block.result);
  const path = result?.path ?? input?.path;
  let file: ConversationFileTarget | null = path ? workspaceFileTarget(path) : null;
  if (block.name === "export_file" && result?.fileId && /^[A-Za-z0-9_-]{1,100}$/.test(result.fileId) && (result.name || file)) {
    const name = result.name ?? file!.name;
    file = {id: "f." + result.fileId, name, path: path ?? name, source: "generated"};
  }
  return {
    ...action,
    file,
    inputPath: input?.path,
    image: block.name === "view_image",
    width: result?.width,
    height: result?.height,
    revision: result?.revision
  };
}

/** 仅归并同一父级、连续同类的文件调用，不跨过回复正文和其他执行步骤。 */
export function groupFileToolBlocks(blocks: DisplayBlock[]) {
  const groups: { id: string; action: string | null; blocks: DisplayBlock[] }[] = [];
  for (const block of blocks) {
    const action = fileToolAction(block) ? (block as Extract<DisplayBlock, { kind: "tool" }>).name : null;
    const previous = groups.at(-1);
    if (action && previous?.action === action) {
      previous.blocks.push(block);
    } else {
      groups.push({id: block.id, action, blocks: [block]});
    }
  }
  return groups;
}
