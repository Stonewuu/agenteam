import type {ComponentType} from "react";
import type {IconFileText} from "@/components/ui/icons";

/** 新标签只需实现此接口，不需要改动对话布局或其他标签。 */
export type ConversationSidebarPanelProps = {
  enterprise: string;
  conversation: string | null;
  active: boolean;
  running: boolean;
  refreshKey: string;
  state: unknown;
  onStateChange: (value: unknown) => void;
  onOpenTab: (tab: ConversationSidebarTab) => void;
};

export type ConversationSidebarTab = {
  id: string;
  label: string;
  /** 内置标签随界面语言翻译，文件名等用户内容保留原文。 */
  localizeLabel?: boolean;
  title?: string;
  icon: typeof IconFileText;
  panel: ComponentType<ConversationSidebarPanelProps>;
  initialState?: unknown;
  closable?: boolean;
};
