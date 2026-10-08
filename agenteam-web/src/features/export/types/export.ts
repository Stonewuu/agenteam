export type ExportJob = {
  id: string;
  kind: "export" | "file_scan" | "document_parse";
  status: "queued" | "leased" | "completed" | "failed" | "cancelled" | "expired";
  resultFileId: string | null;
  errorSummary: string | null;
  expiresAt: string | null;
  snapshotAt: string | null;
  rowCount: number | null;
  createdAt: string;
  updatedAt: string
};
