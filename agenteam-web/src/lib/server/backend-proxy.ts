import "server-only";
import {NextResponse} from "next/server";
import {agentBackendUrl} from "./agent-backend";

type HeadersWithCookies = Headers & {
  getSetCookie?: () => string[];
};

export async function proxyBackendRequest(request: Request, path: string) {
  try {
    return await forwardBackendRequest(request, path);
  } catch (failed) {
    if (request.signal.aborted) {
      return new Response(null, {status: 499});
    }
    throw failed;
  }
}

async function forwardBackendRequest(request: Request, path: string) {
  const requestHeaders = new Headers();
  copyRequestHeader(request.headers, requestHeaders, "accept");
  copyRequestHeader(request.headers, requestHeaders, "content-type");
  copyRequestHeader(request.headers, requestHeaders, "cookie");
  copyRequestHeader(request.headers, requestHeaders, "last-event-id");
  copyRequestHeader(request.headers, requestHeaders, "origin");
  copyRequestHeader(request.headers, requestHeaders, "referer");
  copyRequestHeader(request.headers, requestHeaders, "x-csrf-token");
  copyRequestHeader(request.headers, requestHeaders, "idempotency-key");
  copyRequestHeader(request.headers, requestHeaders, "if-match");
  copyRequestHeader(request.headers, requestHeaders, "range");
  copyRequestHeader(request.headers, requestHeaders, "if-range");

  const method = request.method.toUpperCase();
  const hasBody = method !== "GET" && method !== "HEAD";
  let body: Uint8Array | undefined;
  if (hasBody) {
    const fileUpload = method === "PUT" && /^\/api\/v1\/enterprises\/[^/]+\/files\/[^/]+\/content$/.test(path.split("?")[0]);
    body = await boundedBody(request, (fileUpload ? 20 : 1) * 1024 * 1024);
    if (!body) {
      return NextResponse.json({
          error: {
            code: "REQUEST_TOO_LARGE",
            message: "提交内容过大，请缩小后重试。",
            requestId: crypto.randomUUID(),
            fieldErrors: {},
            details: {}
          }
        },
        {status: 413, headers: {"Cache-Control": "no-store"}});
    }
  }
  const response = await fetch(agentBackendUrl(path), {
    method,
    headers: requestHeaders,
    body: body as BodyInit | undefined,
    cache: "no-store",
    redirect: "manual",
    signal: request.signal,
  });

  const responseHeaders = new Headers();
  for (const [name, value] of response.headers.entries()) {
    if (!["content-length", "content-encoding", "transfer-encoding", "connection", "set-cookie"]
      .includes(name.toLowerCase())) {
      responseHeaders.append(name, value);
    }
  }
  const setCookies = (response.headers as HeadersWithCookies).getSetCookie?.()
    ?? (response.headers.get("set-cookie") ? [response.headers.get("set-cookie") as string] : []);
  setCookies.forEach((cookie) => responseHeaders.append("set-cookie", cookie));

  // 未解压的媒体保留准确长度，播放器可结合字节范围定位内容。
  const length = response.headers.get("content-length");
  if (length && (!response.headers.has("content-encoding") || method === "HEAD")) {
    responseHeaders.set("content-length", length);
  }

  return new Response(response.status === 204 ? null : response.body, {
    status: response.status,
    headers: responseHeaders,
  });
}

async function boundedBody(request: Request, maximum: number): Promise<Uint8Array | undefined> {
  if (Number(request.headers.get("content-length")) > maximum) {
    return undefined;
  }
  if (!request.body) {
    return new Uint8Array();
  }
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    for (; ;) {
      const {value, done} = await reader.read();
      if (done) {
        break;
      }
      total += value.byteLength;
      if (total > maximum) {
        await reader.cancel();
        return undefined;
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const result = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return result;
}

function copyRequestHeader(source: Headers, target: Headers, name: string) {
  const value = source.get(name);
  if (value) {
    target.set(name, value);
  }
}
