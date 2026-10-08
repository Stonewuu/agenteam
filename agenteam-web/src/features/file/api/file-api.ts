import {ApiError, ApiMutation, apiRequest, reportRequestFailure} from "@/lib/http/api-client";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import type {FileReference} from "@/features/agent/types/execution";
import {fileFailureText} from "../lib/file-status";

export type FileUpload = {
  fileId: string;
  uploadUrl: string;
  method: "PUT";
  headers: Record<string, string>;
  expiresAt: string
};
export type FileDownload = { url: string; expiresAt: string; name: string };

export function localFileUrl(value: string) {
  const url = new URL(value, window.location.origin);
  if (url.origin !== window.location.origin || !url.pathname.startsWith("/api/v1/")) {
    throw new Error("文件地址暂时不可用，请稍后重试。");
  }
  return url.pathname + url.search;
}

export type FilePurpose = "skill_import" | "attachment" | "knowledge" | "data_import";
export const fileAccept: Record<FilePurpose, string> = {
  skill_import: ".json,.md,.txt",
  attachment: ".pdf,.docx,.xlsx,.pptx,.txt,.md",
  knowledge: ".pdf,.docx,.txt,.md",
  data_import: ".csv"
};

export function validateUploadFile(file: File, purpose: FilePurpose) {
  const extension = file.name.slice(file.name.lastIndexOf(".")).toLowerCase();
  const max = purpose === "skill_import" ? 1 : purpose === "data_import" ? 10 : 20;
  if (!fileAccept[purpose].split(",").includes(extension) || file.size === 0 || file.size > max * 1024 * 1024) {
    throw new Error(`请选择不超过 ${max} 兆字节的 ${fileAccept[purpose].replaceAll(".", "").toUpperCase().replaceAll(",", "、")} 文件。`);
  }
  const media: Record<string, string> = {
    ".pdf": "application/pdf",
    ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    ".json": "application/json",
    ".csv": "text/csv",
    ".txt": "text/plain",
    ".md": "text/markdown"
  };
  return media[extension];
}

export async function uploadFile({
                                   enterpriseId,
                                   file,
                                   purpose,
                                   resourceId = null,
                                   mutation,
                                   signal,
                                   onPrepared,
                                   existingId = null
                                 }: {
  enterpriseId: string;
  file: File;
  purpose: FilePurpose;
  resourceId?: string | null;
  mutation: ApiMutation;
  signal: AbortSignal;
  onPrepared: (fileId: string) => void;
  existingId?: string | null;
}): Promise<FileReference> {
  const mediaType = validateUploadFile(file, purpose);
  const bytes = await file.arrayBuffer();
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  const sha256 = Array.from(new Uint8Array(digest), (value) => value.toString(16).padStart(2, "0")).join("");
  let fileId = existingId;
  let current: FileReference | null = null;
  if (fileId) {
    current = await apiRequest<FileReference>(organizationPath(enterpriseId, `/files/${encodeURIComponent(fileId)}`), {signal});
  }
  if (current?.status === "ready" || current?.status === "scanning") {
    return current;
  }
  if (current?.status === "rejected" || current?.status === "deleted") {
    throw new Error(fileFailureText(current));
  }
  if (!fileId) {
    const prepared = await mutation.run<FileUpload>(organizationPath(enterpriseId, "/files"), {
      method: "POST", signal,
      body: {purpose, resourceId, name: file.name, sizeBytes: file.size, mediaType, sha256}
    });
    fileId = prepared.fileId;
    onPrepared(fileId);
  }
  if (current?.status !== "uploaded") {
    await apiRequest<void>(organizationPath(enterpriseId, `/files/${encodeURIComponent(fileId)}/content`), {
      method: "PUT",
      rawBody: file,
      signal,
      timeoutMs: 120000
    });
  }
  return mutation.run<FileReference>(organizationPath(enterpriseId, `/files/${encodeURIComponent(fileId)}/complete`),
    {method: "POST", body: {sizeBytes: file.size, sha256}, signal});
}

export async function downloadFile(enterpriseId: string, fileId: string) {
  const download = await apiRequest<FileDownload>(organizationPath(enterpriseId, `/files/${encodeURIComponent(fileId)}/download`));
  await saveDownload(download);
}

export async function saveDownload(download: FileDownload) {
  const path = localFileUrl(download.url);
  try {
    const response = await fetch(path, {credentials: "same-origin", cache: "no-store"});
    if (!response.ok) {
      const body = await response.json().catch(() => null);
      throw new ApiError(response.status, body?.error?.code ?? "FILE_UNAVAILABLE", body?.error?.message ?? "文件暂时无法下载，请稍后重试。");
    }
    const file = await response.blob();
    if (!file.size) {
      throw new ApiError(502, "FILE_CONTENT_EMPTY", "未能读取文件内容，请稍后重新下载。", {}, {}, response.headers.get("X-Request-Id") ?? undefined);
    }
    const url = URL.createObjectURL(file);
    try {
      const link = document.createElement("a");
      link.href = url;
      link.download = download.name;
      document.body.append(link);
      link.click();
      link.remove();
    } finally {
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
  } catch (error) {
    reportRequestFailure(error, "GET", path);
    throw error;
  }
}
