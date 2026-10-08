import {apiRequest} from "@/lib/http/api-client";
import type {ApiPage} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import type {ConversationFile, ConversationFileScope} from "../types/conversation-files";

export function conversationFilesPath(enterprise: string, conversation: string) {
  return organizationPath(enterprise, "/conversations/" + encodeURIComponent(conversation) + "/files");
}

export function conversationFilePath(enterprise: string, conversation: string, file: string) {
  return conversationFilesPath(enterprise, conversation) + "/" + encodeURIComponent(file);
}

export function listConversationFiles(enterprise: string, conversation: string, scope: ConversationFileScope, path: string, cursor: string | null, signal: AbortSignal) {
  const query = new URLSearchParams({scope, path, limit: "100"});
  if (cursor) {
    query.set("cursor", cursor);
  }
  return apiRequest<ApiPage<ConversationFile>>(conversationFilesPath(enterprise, conversation) + "?" + query, {signal});
}
