export type KnowledgeDocument = {
  id: string; revision: string; name: string; fileId: string; sizeBytes: number; activeFileId: string | null;
  activeGeneration: number; pendingGeneration: number; status: "queued" | "processing" | "ready" | "failed";
  chunkCount: number; pageCount: number | null; errorCode: string | null; errorSummary: string | null;
  processedAt: string | null; createdAt: string; updatedAt: string
};
export type DocumentOption = {
  kind: "document";
  documentId: string;
  generation: number;
  name: string;
  knowledgeName: string
};
