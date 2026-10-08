export type ToolContentSource = { url: string; part: "input" | "result"; revision: string; readLimit?: number };

export type ToolContentWindow = {
  path: string; revision: string; sizeBytes: number; totalLines: number; content: string;
  startLine: number; endLine: number; startOffset: number; endOffset: number;
  nextCursor: string | null; rangeComplete: boolean; eof: boolean;
};

/** 只保存阅读位置，不把已关闭的工具全文留在消息状态中。 */
export type ToolReadingPosition = { offset: number; top: number; left: number };
