import type {Versioned} from "@/features/enterprise/types/organization";

export type Integration = Versioned & {
  configuration: Record<string, string | number | boolean>;
  enterpriseId: string;
  providerCode: string;
  providerName: string;
  name: string;
  externalTenantId: string | null;
  externalAppId: string;
  status: "draft" | "enabled" | "disabled";
  bindingEnabled: boolean;
  loginEnabled: boolean;
  messagingEnabled: boolean;
  secretConfigured: boolean;
  callbackUrl: string;
  loginUrl: string;
  lastCheckStatus: "not_checked" | "passed" | "failed";
  lastCheckedAt: string | null;
  lastCheckError: string | null;
};

export type IntegrationProvider = {
  code: string;
  name: string;
  identitySupported: boolean;
  messagesSupported: boolean;
  fields: {
    name: string;
    label: string;
    type: "text" | "number";
    required: boolean;
    minimum: number | null;
    maximum: number | null;
    defaultValue: string | number | null;
    hint: string | null;
  }[];
};

export function integrationPath(enterpriseId: string, system = false) {
  return `/api/v1/${system ? "system/" : ""}enterprises/${encodeURIComponent(enterpriseId)}/integrations`;
}
