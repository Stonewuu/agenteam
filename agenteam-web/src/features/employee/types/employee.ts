export type Employee = {
  agentId: string; name: string; description: string; businessRole: string; icon: string; color: string;
  welcomeMessage: string; suggestedQuestions: string[]; attachmentsEnabled: boolean;
  examples: string[]; tags: string[]; hireId: string | null; hireRevision: string | null;
  applicationId: string | null; applicationRevision: string | null;
  hireStatus: "none" | "pending" | "active" | "paused" | "terminated";
  requiresApproval: boolean; canHire: boolean; canResume: boolean; canRun: boolean; unavailableReason: string | null;
  skills: { id: string; name: string; description: string }[];
};

export type HireApplication = {
  id: string;
  revision: string;
  createdAt: string;
  updatedAt: string;
  agentId: string;
  agentName: string;
  agentIcon: string | null;
  agentColor: string | null;
  applicant: { id: string; displayName: string };
  status: "pending" | "approved" | "rejected" | "withdrawn" | "expired";
  requestNote: string;
  decisionNote: string | null;
  expiresAt: string;
  allowedActions: ("withdraw" | "approve" | "reject")[];
};
