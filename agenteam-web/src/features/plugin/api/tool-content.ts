import {apiRequest} from "@/lib/http/api-client";
import type {ToolContentSource, ToolContentWindow} from "../types/tool-content";

export function readToolContent(source: ToolContentSource, raw: boolean, offset: number, backwards: boolean, signal: AbortSignal, revision?: string) {
  const query = new URLSearchParams({
    part: source.part,
    view: raw ? "raw" : "readable",
    offset: String(offset),
    backwards: String(backwards),
    limit: String(source.readLimit ?? 32768)
  });
  if (revision) {
    query.set("revision", revision);
  }
  return apiRequest<ToolContentWindow>(`${source.url}?${query}`, {signal});
}

/** 正文能放入原有六个分段的预算时补齐后解析，避免把较长文件正文当成不完整文本。 */
export async function readToolContentPreview(source: ToolContentSource, raw: boolean, signal: AbortSignal): Promise<ToolContentWindow> {
  const first = await readToolContent(source, raw, 0, false, signal);
  signal.throwIfAborted();
  if (first.startOffset !== 0 || first.endOffset < 0 || first.endOffset > first.sizeBytes
    || (first.eof ? first.endOffset !== first.sizeBytes : first.endOffset <= 0 || first.endOffset >= first.sizeBytes)) {
    throw new Error("工具内容位置不正确，请重新展开查看。");
  }
  if (first.eof || first.sizeBytes > 6 * 32768) {
    return first;
  }
  const remainder = await readToolContent({...source, readLimit: Math.max(4, first.sizeBytes - first.endOffset)}, raw,
    first.endOffset, false, signal, first.revision);
  signal.throwIfAborted();
  if (remainder.revision !== first.revision || remainder.sizeBytes !== first.sizeBytes
    || remainder.startOffset !== first.endOffset || remainder.endOffset !== first.sizeBytes || !remainder.eof) {
    throw new Error("工具内容已经变化，请重新展开查看。");
  }
  return {
    ...first, content: first.content + remainder.content, endOffset: remainder.endOffset, endLine: remainder.endLine,
    nextCursor: null, rangeComplete: true, eof: true
  };
}

/** 复制时从头读取完整正文，后续分片固定版本，避免拼入变化后的内容。 */
export async function readFullToolContent(source: ToolContentSource, raw: boolean, signal: AbortSignal, revision?: string): Promise<string> {
  const parts: string[] = [];
  let offset = 0;
  let expectedRevision = revision;
  let expectedSize: number | undefined;
  while (true) {
    signal.throwIfAborted();
    const page = await readToolContent(source, raw, offset, false, signal, expectedRevision);
    signal.throwIfAborted();
    if (page.startOffset !== offset || page.endOffset < offset || page.endOffset > page.sizeBytes
      || expectedRevision !== undefined && page.revision !== expectedRevision
      || expectedSize !== undefined && page.sizeBytes !== expectedSize) {
      throw new Error("工具内容已经变化，请重新复制。");
    }
    if (page.eof ? page.endOffset !== page.sizeBytes : page.endOffset <= offset || page.endOffset >= page.sizeBytes) {
      throw new Error("未能读取完整内容，请重新复制。");
    }
    parts.push(page.content);
    if (page.eof) {
      return parts.join("");
    }
    expectedRevision = page.revision;
    expectedSize = page.sizeBytes;
    offset = page.endOffset;
  }
}

export function searchToolContent(source: ToolContentSource, raw: boolean, query: string, startLine: number, revision: string, signal: AbortSignal) {
  const parameters = new URLSearchParams({
    part: source.part,
    view: raw ? "raw" : "readable",
    query,
    startLine: String(startLine),
    revision
  });
  return apiRequest<{
    matches: { line: number; excerpt: ToolContentWindow }[];
    nextLine: number;
    complete: boolean
  }>(`${source.url}/search?${parameters}`, {signal});
}

export function toolContentDownload(source: ToolContentSource, raw: boolean) {
  return `${source.url}/download?${new URLSearchParams({part: source.part, view: raw ? "raw" : "readable"})}`;
}
