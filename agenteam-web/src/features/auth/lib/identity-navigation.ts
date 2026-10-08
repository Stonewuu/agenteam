import type {IdentityUser} from "../types/identity";

export function enterprisePath(enterpriseId: string, suffix = "") {
  return `/enterprises/${encodeURIComponent(enterpriseId)}${suffix}`;
}

export function landingPath(user: IdentityUser) {
  const selected = user.enterprises.find((enterprise) => enterprise.id === user.lastEnterpriseId) ?? user.enterprises[0];
  return selected ? enterprisePath(selected.id, "/workspace") : "/no-enterprise";
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
      if (destination.origin === window.location.origin && (platformDestination || /^\/(?:enterprises\/[^/]+(?:\/|$)|settings(?:\/|$)|invite$|register$)/.test(destination.pathname))) {
        return destination.pathname + destination.search + destination.hash;
      }
    } catch {
      // 无效返回位置使用当前用户真正可以访问的企业。
    }
  }
  return landingPath(user);
}
import {editionNavigationExtension} from "@/features/edition/navigation-extension";
