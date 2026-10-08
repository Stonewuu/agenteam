import type {ConversationFile} from "../types/conversation-files";

type FormatDefinition = { id: string; hasTextSource: boolean; matches: (file: ConversationFile) => boolean };

/** 格式识别独立于界面；网页优先于后端的通用文本分类。 */
export const filePreviewFormats = [
  {
    id: "html", hasTextSource: true, matches: (file) => file.previewKind === "text"
      && (/\.(html?|xhtml)$/i.test(file.name) || file.mediaType.split(";", 1)[0].trim().toLowerCase() === "text/html")
  },
  {id: "markdown", hasTextSource: true, matches: (file) => file.previewKind === "markdown"},
  {id: "image", hasTextSource: false, matches: (file) => file.previewKind === "image"},
  {id: "video", hasTextSource: false, matches: (file) => file.previewKind === "video"},
  {id: "audio", hasTextSource: false, matches: (file) => file.previewKind === "audio"},
  {id: "pdf", hasTextSource: false, matches: (file) => file.previewKind === "pdf"},
  {
    id: "office",
    hasTextSource: false,
    matches: (file) => file.previewKind === "office" && /\.(docx|xlsx|pptx)$/i.test(file.name)
  },
  {id: "text", hasTextSource: true, matches: (file) => file.previewKind === "text"},
] as const satisfies readonly FormatDefinition[];

export type FilePreviewFormat = (typeof filePreviewFormats)[number]["id"];

export function findFilePreviewFormat(file: ConversationFile) {
  return file.directory ? undefined : filePreviewFormats.find((format) => format.matches(file));
}
