import type {ComponentType} from "react";
import type {ConversationFile} from "./conversation-files";

export type FilePreviewRendererProps = { file: ConversationFile; url: string };
export type FilePreviewRenderer = {
  id: string;
  matches: (file: ConversationFile) => boolean;
  hasTextSource: boolean;
  component: ComponentType<FilePreviewRendererProps>;
};
