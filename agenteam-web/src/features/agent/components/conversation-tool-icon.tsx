import {
  IconArrowsSplit,
  IconBook2,
  IconCalendarClock,
  IconCheckbox,
  IconCollaboration,
  IconDatabase,
  IconFileText,
  IconLanguage,
  IconLayoutDashboard,
  IconPlug,
} from "@/components/ui/icons";
import {savedToolBuiltinCode} from "@/features/plugin/lib/tool-display-name";
import type {DisplayBlock} from "../types/conversation-display";

const builtinIcons: Record<string, typeof IconFileText> = {
  platform_basics: IconLayoutDashboard,
  todo_management: IconCheckbox,
  schedule_management: IconCalendarClock,
  web_read: IconLanguage,
  workspace_files: IconFileText,
  group_collaboration: IconCollaboration,
  workflow_management: IconArrowsSplit,
};

/** 使用调用记录中的原始分类，界面语言和执行状态不会改变工具图标。 */
export function ConversationToolIcon({label, sourceKind}: Pick<Extract<DisplayBlock, {kind: "tool"}>, "label" | "sourceKind">) {
  const Icon = sourceKind === "knowledge" ? IconBook2 : sourceKind === "data" ? IconDatabase
    : builtinIcons[savedToolBuiltinCode(label) ?? ""] ?? IconPlug;
  return <Icon size={18}/>;
}
