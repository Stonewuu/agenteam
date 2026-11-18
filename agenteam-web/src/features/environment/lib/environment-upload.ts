import {apiRequest, errorMessage} from "@/lib/http/api-client";
import {addParentDirectories, type UploadContents, type UploadFile} from "./environment-upload-selection";

export async function uploadContents(base: string, path: string, contents: UploadContents, signal: AbortSignal,
  onActive: (relative: string) => void, localReadError: string) {
  let changed = false;
  const failed: UploadFile[] = [];
  // 一个请求内统一检查目录数量。后续批次及重试可以复用已创建的父目录。
  const batchSize = 256;
  for (let i = 0; i < contents.directories.length; i += batchSize) {
    signal.throwIfAborted();
    const directories = contents.directories.slice(i, i + batchSize);
    try {
      const result = await apiRequest<{created: number}>(base + "/files/upload-directories",
        {method: "POST", body: {path, directories}, signal, timeoutMs: 120000});
      changed ||= result.created > 0;
    } catch (failure) {
      signal.throwIfAborted();
      return {changed, remaining: contents, error: errorMessage(failure), uploaded: 0};
    }
  }

  for (const item of contents.files) {
    signal.throwIfAborted();
    onActive(item.path);
    let file: File;
    try {
      file = await item.read();
    } catch (failure) {
      signal.throwIfAborted();
      console.error("Could not read local file for upload", failure);
      failed.push({...item, error: localReadError});
      continue;
    }
    signal.throwIfAborted();
    const split = contents.directories.length ? item.path.lastIndexOf("/") : -1;
    const name = item.path.slice(split + 1);
    const parent = split < 0 ? path : path + "/" + item.path.slice(0, split);
    try {
      await apiRequest(base + "/files/content?" + new URLSearchParams({path: parent, name}),
        {method: "PUT", rawBody: file, signal, timeoutMs: 120000});
      changed = true;
    } catch (failure) {
      signal.throwIfAborted();
      failed.push({...item, error: errorMessage(failure)});
    }
  }
  const retryDirectories = new Set<string>();
  if (contents.directories.length) {
    for (const file of failed) {
      addParentDirectories(retryDirectories, file.path);
    }
  }
  const remaining = failed.length ? {directories: [...retryDirectories], files: failed} : null;
  return {changed, remaining, uploaded: contents.files.length - failed.length};
}
