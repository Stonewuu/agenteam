import {IconFolder, IconPaperclip} from "@/components/ui/icons";
import {ConversationFileBrowser} from "@/features/file/components/conversation-file-browser";
import type {ConversationSidebarPanelProps, ConversationSidebarTab} from "../types/conversation-sidebar";

function WorkspaceFilesPanel(props: ConversationSidebarPanelProps) {
  return <ConversationFileBrowser {...props} scope="workspace"/>;
}

function ContextFilesPanel(props: ConversationSidebarPanelProps) {
  return <ConversationFileBrowser {...props} scope="context"/>;
}

/** 标签通过注册表扩展，侧栏布局不依赖具体业务面板。 */
export const conversationSidebarTabs: readonly ConversationSidebarTab[] = [
  {id: "files", label: "文件", localizeLabel: true, icon: IconFolder, panel: WorkspaceFilesPanel},
  {id: "context", label: "上下文", localizeLabel: true, icon: IconPaperclip, panel: ContextFilesPanel},
];
