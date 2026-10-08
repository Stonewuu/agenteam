export type FilePreviewKind = "text" | "markdown" | "image" | "video" | "audio" | "pdf" | "office" | "unsupported";

export type ConversationFile = {
  id: string;
  name: string;
  path: string;
  directory: boolean;
  source: "workspace" | "uploaded" | "generated";
  mediaType: string;
  sizeBytes: number;
  modifiedAt: string;
  revision: string;
  previewKind: FilePreviewKind;
};

export type ConversationFileScope = "workspace" | "context";
export type FileBrowserState = { path: string; selected: ConversationFile | null };

/** 消息只需提供真实编号和文件名，完整元数据由预览接口读取。 */
export type ConversationFileTarget = Pick<ConversationFile, "id" | "name" | "path"> & Partial<ConversationFile>;
