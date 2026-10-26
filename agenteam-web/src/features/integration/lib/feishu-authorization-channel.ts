export const feishuAuthorizationChannel = "agenteam.feishu-management-result";
type Notice = { type: "result" | "received"; connectionId: string; requestId: string };
type Channel = Pick<BroadcastChannel, "postMessage" | "close" | "onmessage">;
type OpenChannel = (name: string) => Channel;

function notice(value: unknown): value is Notice {
  if (!value || typeof value !== "object") {
    return false;
  }
  const item = value as Partial<Notice>;
  return (
    (item.type === "result" || item.type === "received") &&
    typeof item.connectionId === "string" &&
    item.connectionId.length > 0 &&
    typeof item.requestId === "string" &&
    item.requestId.length > 0
  );
}

/** 通知只触发服务端查询，不能代替授权结果或携带令牌。 */
export function listenForFeishuAuthorization(
  connectionId: string,
  refresh: () => void,
  open: OpenChannel = (name) => new BroadcastChannel(name),
) {
  const channel = open(feishuAuthorizationChannel);
  const received = new Set<string>();
  channel.onmessage = ({ data }: MessageEvent<unknown>) => {
    if (!notice(data) || data.type !== "result" || data.connectionId !== connectionId) {
      return;
    }
    if (!received.has(data.requestId)) {
      received.add(data.requestId);
      refresh();
    }
    channel.postMessage({ type: "received", connectionId, requestId: data.requestId } satisfies Notice);
  };
  return () => channel.close();
}

export function returnFeishuAuthorization(
  connectionId: string,
  received: () => void,
  open: OpenChannel = (name) => new BroadcastChannel(name),
  requestId = crypto.randomUUID(),
) {
  const channel = open(feishuAuthorizationChannel);
  let acknowledged = false;
  channel.onmessage = ({ data }: MessageEvent<unknown>) => {
    if (
      !acknowledged &&
      notice(data) &&
      data.type === "received" &&
      data.connectionId === connectionId &&
      data.requestId === requestId
    ) {
      acknowledged = true;
      received();
    }
  };
  channel.postMessage({ type: "result", connectionId, requestId } satisfies Notice);
  return () => channel.close();
}
