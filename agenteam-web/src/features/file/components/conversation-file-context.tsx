"use client";

import {createContext, type ReactNode, useContext, useMemo} from "react";
import type {ConversationFileTarget} from "../types/conversation-files";

type FileViewer = {
  enterprise: string;
  conversation: string | null;
  openFile?: (file: ConversationFileTarget) => void;
};

const ConversationFileContext = createContext<FileViewer | null>(null);

export function ConversationFileProvider({enterprise, conversation, openFile, children}: FileViewer & {
  children: ReactNode
}) {
  const value = useMemo(() => ({enterprise, conversation, openFile}), [enterprise, conversation, openFile]);
  return <ConversationFileContext value={value}>{children}</ConversationFileContext>;
}

export function useConversationFileViewer() {
  return useContext(ConversationFileContext);
}
