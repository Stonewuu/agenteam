export type Memory = {
  id: string;
  revision: string;
  agentId: string;
  memoryKey: string;
  content: string;
  expiresAt: string;
  createdAt: string;
  updatedAt: string
};
export type MemoryAgent = {
  agentId: string;
  agentName: string;
  memoryCount: number;
  agentIcon: string | null;
  agentColor: string | null
};
export type MemoryContext = {
  agentId: string;
  agentName: string;
  enabled: boolean;
  canSave: boolean;
  allowedTopics: string[];
  unavailableReason: string | null;
  agentIcon: string | null;
  agentColor: string | null
};
export type MemorySource = { sourceMessageId: string; text: string };
