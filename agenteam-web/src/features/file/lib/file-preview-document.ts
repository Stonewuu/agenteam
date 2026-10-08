export const maximumPreviewBytes = 2 * 1024 * 1024;
export const previewPageBytes = 256 * 1024;

export type FilePreviewTextPage = {
  revision: string;
  sizeBytes: number;
  content: string;
  startOffset: number;
  endOffset: number;
  eof: boolean;
};

type ReadPage = (offset: number, revision?: string) => Promise<FilePreviewTextPage>;

/** 每片固定同一个正文版本；全部读完才交给渲染器，避免展示截断或混合版本的文档。 */
export async function readFilePreviewDocument(readPage: ReadPage, signal: AbortSignal, maximumBytes = maximumPreviewBytes) {
  const parts: string[] = [];
  let offset = 0;
  let revision: string | undefined;
  let size: number | undefined;
  while (true) {
    signal.throwIfAborted();
    const page = await readPage(offset, revision);
    signal.throwIfAborted();
    if (page.sizeBytes > maximumBytes) {
      throw new Error("文件较大，暂时无法完整预览，请查看原文或下载文件。");
    }
    if (!Number.isSafeInteger(page.sizeBytes) || page.sizeBytes < 0 || !page.revision
      || page.startOffset !== offset || !Number.isSafeInteger(page.endOffset) || page.endOffset < offset || page.endOffset > page.sizeBytes
      || revision !== undefined && page.revision !== revision || size !== undefined && page.sizeBytes !== size) {
      throw new Error("文件内容已经变化，请重新加载预览。");
    }
    if (new TextEncoder().encode(page.content).length !== page.endOffset - offset
      || (page.eof ? page.endOffset !== page.sizeBytes : page.endOffset <= offset || page.endOffset >= page.sizeBytes)) {
      throw new Error("未能读取完整文件，请重新加载预览。");
    }
    parts.push(page.content);
    if (page.eof) {
      return parts.join("");
    }
    revision = page.revision;
    size = page.sizeBytes;
    offset = page.endOffset;
  }
}
