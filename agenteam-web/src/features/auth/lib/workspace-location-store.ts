type Location = {userId: string; revision: string; enterpriseId: string; path: string};
const namespace = "agenteamWorkspace";
let current: Location | null = null;
export const workspaceLocationChanged = "agenteam:workspace-location-changed";

export function activeEnterprise() {
  return current?.enterpriseId;
}

export function storedEnterprise(userId: string, revision: string, path: string): string | undefined {
  try {
    const historic = window.history.state?.[namespace] as Location | undefined;
    if (historic?.userId === userId && historic.revision === revision && historic.path === path) {
      return historic.enterpriseId;
    }
    const value = window.sessionStorage.getItem(`${namespace}:${userId}:${revision}`);
    return value || undefined;
  } catch (failure) {
    console.warn("无法恢复当前标签页的企业选择", failure);
    return current?.userId === userId && current.revision === revision ? current.enterpriseId : undefined;
  }
}

export function rememberWorkspace(userId: string, revision: string, enterpriseId: string, path: string) {
  current = {userId, revision, enterpriseId, path};
  try {
    window.sessionStorage.setItem(`${namespace}:${userId}:${revision}`, enterpriseId);
    window.history.replaceState({...window.history.state, [namespace]: current}, "", window.location.href);
  } catch (failure) {
    console.warn("无法保存当前标签页的企业选择", failure);
  }
}

export function resetWorkspaceLocation() {
  current = null;
}
