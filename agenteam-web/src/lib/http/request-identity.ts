let revision: string | undefined;
export const identityTabId = crypto.randomUUID();
export const identityChangedEvent = "agenteam:session-identity-changed";

export function requestIdentity() {
  return revision;
}

export function acceptRequestIdentity(value: string | null | undefined) {
  revision = value || undefined;
}

export function identityRequestRejected() {
  window.dispatchEvent(new Event(identityChangedEvent));
}

export function broadcastIdentityChange() {
  try {
    const channel = new BroadcastChannel("agenteam-identity");
    channel.postMessage({source: identityTabId});
    channel.close();
  } catch (failure) {
    console.warn("Failed to broadcast the sign-in identity change", failure);
  }
  try {
    window.localStorage.setItem("agenteam:identity-change", crypto.randomUUID());
  } catch (failure) {
    console.warn("Failed to notify other tabs of the identity change", failure);
  }
}
