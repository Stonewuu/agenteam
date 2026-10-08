export type ChannelBinding = {
  id: string | null;
  connectionId: string;
  connectionName: string;
  providerCode: string;
  providerName: string;
  status: "unbound" | "active" | "disabled";
  displayName: string | null;
  receiveEnabled: boolean;
  externalLoginEnabled: boolean;
  revision: string | null;
  bindingAvailable: boolean;
  loginAvailable: boolean;
  messagingAvailable: boolean;
};

export type ChannelAuthorizationReview = {
  authorizationId: string;
  enterpriseId: string;
  connectionName: string;
  providerName: string;
  username: string;
  localDisplayName: string;
  externalDisplayName: string | null;
  externalSubjectId: string;
  expiresAt: string;
  loginAvailable: boolean;
};

export const channelsPath = (enterprise: string) => `/api/v1/enterprises/${encodeURIComponent(enterprise)}/me/channels`;
