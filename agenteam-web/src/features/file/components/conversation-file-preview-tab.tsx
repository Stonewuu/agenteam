"use client";

import {IconEye} from "@/components/ui/icons";
import type {ConversationSidebarPanelProps, ConversationSidebarTab} from "@/features/agent/types/conversation-sidebar";
import type {ConversationFileTarget} from "../types/conversation-files";
import {ConversationFilePreview} from "./conversation-file-preview";

type PreviewTabState = { file: ConversationFileTarget; mode: "source" | "preview" };

function FilePreviewPanel({
                            enterprise,
                            conversation,
                            active,
                            state,
                            onStateChange,
                            refreshKey
                          }: ConversationSidebarPanelProps) {
  const current = state as PreviewTabState;
  if (!conversation || !active || !current?.file) {
    return null;
  }
  return <ConversationFilePreview enterprise={enterprise} conversation={conversation} file={current.file}
                                  mode={current.mode} refreshKey={refreshKey}
                                  onModeChange={(mode) => onStateChange({...current, mode})}/>;
}

export function createFilePreviewTab(file: ConversationFileTarget): ConversationSidebarTab {
  return {
    id: "file-preview:" + file.id, label: file.name, title: file.path, icon: IconEye, panel: FilePreviewPanel,
    closable: true, initialState: {file, mode: "preview"} satisfies PreviewTabState
  };
}
