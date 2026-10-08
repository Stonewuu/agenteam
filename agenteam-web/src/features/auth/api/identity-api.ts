import {ApiError, ApiMutation, apiRequest} from "@/lib/http/api-client";
import type {BootstrapInput, EnterpriseContext, IdentityUser} from "../types/identity";

export const bootstrapStatus = (signal?: AbortSignal) => apiRequest<{
  initialized: boolean
}>("/api/v1/auth/bootstrap-status", {signal});

export async function loadIdentity(signal?: AbortSignal): Promise<IdentityUser | null> {
  try {
    return await apiRequest<IdentityUser>("/api/v1/auth/me", {signal});
  } catch (error) {
    // 空数据库没有可用身份，由入口继续读取初始化状态并显示对应表单。
    if (error instanceof ApiError && (error.status === 401
      || (error.status === 503 && error.code === "SYSTEM_NOT_INITIALIZED"))) {
      return null;
    }
    throw error;
  }
}

/** 已有登录身份时直接进入工作空间；未登录或尚未初始化时读取当前初始化状态。 */
export async function loadAuthEntry(signal?: AbortSignal): Promise<{ kind: "authenticated"; user: IdentityUser } | {
  kind: "login" | "setup"
}> {
  signal?.throwIfAborted();
  const user = await loadIdentity(signal);
  signal?.throwIfAborted();
  if (user) {
    return {kind: "authenticated", user};
  }
  const status = await bootstrapStatus(signal);
  signal?.throwIfAborted();
  return {kind: status.initialized ? "login" : "setup"};
}

export const signIn = (identifier: string, password: string) => apiRequest<IdentityUser>("/api/v1/auth/login", {
  method: "POST",
  body: {identifier, password}
});
export const initializeSystem = (body: BootstrapInput) => apiRequest<IdentityUser>("/api/v1/auth/bootstrap", {
  method: "POST",
  body
});
export const signOut = () => apiRequest<void>("/api/v1/auth/logout", {method: "POST"});
export const loadEnterpriseContext = (id: string, signal?: AbortSignal) => apiRequest<EnterpriseContext>(`/api/v1/enterprises/${encodeURIComponent(id)}/context`, {signal});
export const rememberEnterprise = (id: string, signal?: AbortSignal) => new ApiMutation().run<EnterpriseContext>(`/api/v1/enterprises/${encodeURIComponent(id)}/select`, {
  method: "POST",
  signal
});

export const requestPasswordReset = (identifier: string) => apiRequest<{
  success: boolean
}>("/api/v1/auth/password-reset-request", {method: "POST", body: {identifier}});
export const resetPassword = (token: string, newPassword: string) => apiRequest<{
  success: boolean
}>("/api/v1/auth/password-reset", {method: "POST", body: {token, newPassword}});
export const verifyEmail = (token: string) => apiRequest<{
  success: boolean
}>("/api/v1/auth/email-verify", {method: "POST", body: {token}});
