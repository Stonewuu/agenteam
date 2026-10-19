import type {IdentityUser} from "../types/identity";
import {activeEnterprise} from "./workspace-location-store";
import {enterpriseEntryPath, validWorkspaceDestination, workspacePath} from "./workspace-path";

export function enterprisePath(enterpriseId: string, suffix = "") {
  const path = workspacePath(suffix);
  return activeEnterprise() === enterpriseId ? path : enterpriseEntryPath(enterpriseId, path);
}

export function landingPath(user: IdentityUser) {
  const selected = user.enterprises.find((enterprise) => enterprise.id === user.lastEnterpriseId) ?? user.enterprises[0];
  return selected ? enterpriseEntryPath(selected.id, "/workspace") : "/no-enterprise";
}

export function loginPath(returnTo = window.location.pathname + window.location.search + window.location.hash) {
  return `/login?returnTo=${encodeURIComponent(returnTo)}`;
}

export function requestedDestination(user: IdentityUser) {
  const value = new URLSearchParams(window.location.search).get("returnTo");
  if (value) {
    try {
      const destination = new URL(value, window.location.origin);
      const platformDestination = user.superAdmin && (destination.pathname === "/management/announcements"
        || editionNavigationExtension.platform.some((item) => item.path === destination.pathname
          && (!item.capability || user.capabilities.includes(item.capability))));
      if (destination.origin === window.location.origin && (platformDestination
        || validWorkspaceDestination(destination.pathname)
        || /^\/(?:enterprises\/[^/]+(?:\/|$)|open$|settings(?:\/|$)|invite$|register$)/.test(destination.pathname))) {
        return destination.pathname + destination.search + destination.hash;
      }
    } catch {
      // 无效返回位置使用当前用户真正可以访问的企业。
    }
  }
  return landingPath(user);
}
import {editionNavigationExtension} from "@/features/edition/navigation-extension";
