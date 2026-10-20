import {apiRequest} from "@/lib/http/api-client";

export type ChannelLoginOption = {key: string; name: string; providerCode: string; providerName: string};

export function startChannelLogin(key: string) {
  return apiRequest<{authorizationUrl: string}>(`/api/v1/auth/channel-login/${encodeURIComponent(key)}`, {
    method: "POST",
    body: {embeddedClient: /wxwork/i.test(navigator.userAgent)},
  });
}
