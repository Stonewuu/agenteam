import {fieldErrorMessages} from "@/lib/form-errors";

type ErrorBody = {
  code: string;
  message: string;
  fieldErrors?: Record<string, string[]>;
  details?: Record<string, unknown>;
  requestId?: string;
};

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly fieldErrors: Record<string, string[]> = {},
    public readonly details: Record<string, unknown> = {},
    public readonly requestId?: string,
    cause?: unknown,
  ) {
    super(message, {cause});
    this.name = "ApiError";
  }
}

const loggedErrors = new WeakSet<Error>();

/** 日志只记录定位请求的必要信息，不包含请求正文、查询参数或凭据。 */
export function reportRequestFailure(error: unknown, method: string, path: string) {
  // 未登录及身份入口的未初始化状态由页面处理，不记录为控制台异常。
  if (error instanceof ApiError && (error.status === 401
    || (method === "GET" && path === "/api/v1/auth/me"
      && error.status === 503 && error.code === "SYSTEM_NOT_INITIALIZED"))) {
    return;
  }
  if (error instanceof Error && loggedErrors.has(error)) {
    return;
  }
  if (error instanceof Error) {
    loggedErrors.add(error);
  }
  const causes: string[] = [];
  let current = error;
  const visited = new Set<unknown>();
  while (current instanceof Error && !visited.has(current)) {
    visited.add(current);
    causes.push(current.stack ?? `${current.name}: ${current.message}`);
    current = current.cause;
  }
  console.error("接口请求失败", {
    method,
    path: path.split(/[?#]/)[0],
    requestId: error instanceof ApiError ? error.requestId : undefined
  }, causes.join("\n原因：\n"));
}

type ApiOptions = {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  revision?: string;
  requestKey?: string;
  signal?: AbortSignal;
  timeoutMs?: number;
  rawBody?: Blob;
};

let csrf: string | undefined;
let csrfLoading: Promise<string> | undefined;

export function clearCsrf() {
  csrf = undefined;
  csrfLoading = undefined;
}

async function csrfToken(): Promise<string> {
  if (csrf) {
    return csrf;
  }
  if (!csrfLoading) {
    csrfLoading = withTimeout(undefined, async (signal) => {
      const response = await fetch("/api/v1/auth/csrf", {cache: "no-store", credentials: "same-origin", signal});
      const token = (await parseApiResponse<{ token: string }>(response)).token;
      csrf = token;
      return token;
    }).finally(() => {
      csrfLoading = undefined;
    });
  }
  return csrfLoading;
}

export async function apiRequest<T>(path: string, options: ApiOptions = {}): Promise<T> {
  if (!path.startsWith("/api/v1/")) {
    throw new Error("请求地址不属于当前应用。");
  }
  const method = options.method ?? "GET";
  const headers = new Headers({Accept: "application/json"});
  if (options.body !== undefined) {
    headers.set("Content-Type", "application/json");
  }
  if (options.rawBody) {
    headers.set("Content-Type", "application/octet-stream");
  }
  if (options.rawBody && options.body !== undefined) {
    throw new Error("同一次请求不能同时提交文件和表单正文。");
  }
  if (options.revision !== undefined) {
    headers.set("If-Match", `"${options.revision}"`);
  }
  if (options.requestKey) {
    headers.set("Idempotency-Key", options.requestKey);
  }
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      if (method !== "GET") {
        headers.set("X-CSRF-Token", await csrfToken());
      }
      const result = await withTimeout(options.signal, async (signal) => {
        const response = await fetch(path, {
          method, headers, cache: "no-store", credentials: "same-origin", signal,
          body: options.rawBody ?? (options.body === undefined ? undefined : JSON.stringify(options.body)),
        });
        return parseApiResponse<T>(response);
      }, options.timeoutMs);
      if (method !== "GET" && ["/api/v1/auth/login", "/api/v1/auth/bootstrap", "/api/v1/auth/logout", "/api/v1/auth/password-reset",
        "/api/v1/auth/reauthenticate", "/api/v1/me/password", "/api/v1/invitations/accept"].includes(path)) {
        clearCsrf();
      }
      return result;
    } catch (error) {
      // 切换页面或收起面板后，请求结果已不再需要，不再记录失败或处理过期响应。
      if (options.signal?.aborted || (error instanceof DOMException && error.name === "AbortError")) {
        throw error;
      }
      const failure = error instanceof ApiError ? error : new ApiError(0, "NETWORK_ERROR", "暂时无法连接。请重试确认本次操作结果。", {}, {}, undefined, error);
      reportRequestFailure(failure, method, path);
      if (error instanceof ApiError && error.code === "CSRF_INVALID" && attempt === 0) {
        // 服务端在业务处理之前拒绝，取得当前会话令牌后仅重试这一份原始请求。
        clearCsrf();
        continue;
      }
      if (error instanceof ApiError) {
        notifyAccessFailure(path, error);
        if (error.status === 401) {
          clearCsrf();
        }
        throw error;
      }
      throw failure;
    }
  }
  throw new ApiError(403, "CSRF_INVALID", "登录状态已变化，请重新加载页面。");
}

export async function parseApiResponse<T>(response: Response): Promise<T> {
  if (response.status === 204) {
    return undefined as T;
  }
  let body: { data?: T; error?: ErrorBody };
  try {
    body = await response.json();
  } catch (error) {
    // 取消也可能发生在读取响应正文时，必须保留类型，交由请求取消逻辑处理。
    if (error instanceof DOMException && error.name === "AbortError") {
      throw error;
    }
    throw new ApiError(response.status, "INVALID_RESPONSE", "服务暂时无法返回结果，请稍后重试。", {}, {}, response.headers.get("X-Request-Id") ?? undefined, error);
  }
  if (!response.ok) {
    const error = body.error;
    throw new ApiError(response.status, error?.code ?? "REQUEST_FAILED", error?.message ?? "操作暂时无法完成，请稍后重试。", error?.fieldErrors, error?.details, error?.requestId ?? response.headers.get("X-Request-Id") ?? undefined);
  }
  if (!Object.prototype.hasOwnProperty.call(body, "data")) {
    throw new ApiError(502, "INVALID_RESPONSE", "服务暂时无法返回结果，请重试确认本次操作。");
  }
  return body.data as T;
}

async function withTimeout<T>(external: AbortSignal | undefined, operation: (signal: AbortSignal) => Promise<T>, timeoutMs = 30000): Promise<T> {
  const controller = new AbortController();
  const cancel = () => controller.abort();
  let timedOut = false;
  if (external?.aborted) {
    controller.abort();
  }
  external?.addEventListener("abort", cancel, {once: true});
  const timeout = window.setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  try {
    return await operation(controller.signal);
  } catch (error) {
    if (timedOut) {
      throw new ApiError(0, "REQUEST_TIMEOUT", "请求等待时间较长，请重试确认本次操作结果。", {}, {}, undefined, error);
    }
    throw error;
  } finally {
    window.clearTimeout(timeout);
    external?.removeEventListener("abort", cancel);
  }
}

export function notifyAccessFailure(path: string, error: ApiError) {
  // 需要验证本地密码时保留当前表单，由操作入口完成验证后继续。
  if (error.code === "LOCAL_REAUTH_REQUIRED") {
    return;
  }
  const enterprise = /^\/api\/v1\/enterprises\/([^/?]+)(?:\/|\?|$)/.exec(path)?.[1];
  // 字段许可变化由当前数据页重新加载集合后处理。
  const fieldUnavailable = error.status === 403 && error.code === "DATA_FIELD_UNAVAILABLE";
  if (enterprise && (error.status === 401 || (error.status === 403 && !fieldUnavailable) || error.code === "ENTERPRISE_UNAVAILABLE")) {
    window.dispatchEvent(new CustomEvent("agenteam:access-denied", {
      detail: {
        enterpriseId: decodeURIComponent(enterprise),
        loginRequired: error.status === 401
      }
    }));
  }
}

/** 每个提交表单保存一个实例；结果未确定时保留请求键，用户重试不会重复写入。 */
export class ApiMutation {
  private pending: { content: string; key: string } | undefined;

  async run<T>(path: string, options: Omit<ApiOptions, "requestKey" | "rawBody">): Promise<T> {
    const content = JSON.stringify([path, options.method, options.revision, options.body]);
    if (this.pending?.content !== content) {
      this.pending = {content, key: crypto.randomUUID()};
    }
    const attempt = this.pending;
    try {
      const value = await apiRequest<T>(path, {...options, requestKey: attempt.key});
      if (this.pending === attempt) {
        this.pending = undefined;
      }
      return value;
    } catch (error) {
      if (error instanceof ApiError && error.status >= 400 && error.status < 500 && error.code !== "REQUEST_IN_PROGRESS") {
        if (this.pending === attempt) {
          this.pending = undefined;
        }
      }
      throw error;
    }
  }
}

export function errorMessage(error: unknown, fallback = "操作暂时无法完成，请稍后重试。") {
  if (error instanceof ApiError) {
    const fields = fieldErrorMessages(error.fieldErrors).slice(0, 3);
    return fields.length ? fields.join("\n") : error.message;
  }
  return error instanceof Error ? error.message : fallback;
}
