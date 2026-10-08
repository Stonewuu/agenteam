import {apiRequest} from "@/lib/http/api-client";
import {type FilePreviewTextPage, previewPageBytes, readFilePreviewDocument} from "../lib/file-preview-document";

export function loadFilePreviewDocument(url: string, signal: AbortSignal) {
  return readFilePreviewDocument((offset, revision) => {
    const query = new URLSearchParams({offset: String(offset), limit: String(previewPageBytes)});
    if (revision !== undefined) {
      query.set("revision", revision);
    }
    return apiRequest<FilePreviewTextPage>(url + "/text?" + query, {signal});
  }, signal);
}
