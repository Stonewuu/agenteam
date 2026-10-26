import assert from "node:assert/strict";
import { test } from "node:test";
import { loadSource } from "./i18n-test-runtime.mjs";

const { listenForFeishuAuthorization, returnFeishuAuthorization } = loadSource(
  "features/integration/lib/feishu-authorization-channel",
);
function transport() {
  const peers = new Set();
  const sent = [];
  const open = () => {
    const peer = {
      onmessage: null,
      close() {
        peers.delete(peer);
      },
      postMessage(data) {
        sent.push(data);
        for (const other of peers) {
          if (other !== peer) {
            other.onmessage?.({ data });
          }
        }
      },
    };
    peers.add(peer);
    return peer;
  };
  return { open, sent, peers };
}

test("授权完成只通知对应原窗口，收到确认后才关闭结果页", () => {
  const bus = transport();
  let refresh = 0;
  let closed = 0;
  const stopParent = listenForFeishuAuthorization("enterprise-app", () => refresh++, bus.open);
  const stopResult = returnFeishuAuthorization("enterprise-app", () => closed++, bus.open, "request-one");
  assert.equal(refresh, 1);
  assert.equal(closed, 1);
  assert.deepEqual(bus.sent[0], { type: "result", connectionId: "enterprise-app", requestId: "request-one" });
  stopParent();
  stopResult();
  assert.equal(bus.peers.size, 0);
});

test("无原窗口、其他应用和旧请求不会误关闭授权页", () => {
  const bus = transport();
  let refresh = 0;
  let closed = 0;
  const stopParent = listenForFeishuAuthorization("other-app", () => refresh++, bus.open);
  const stopResult = returnFeishuAuthorization("enterprise-app", () => closed++, bus.open, "request-two");
  const sender = bus.open();
  sender.postMessage({ type: "received", connectionId: "enterprise-app", requestId: "old-request" });
  sender.postMessage({ type: "received", connectionId: "other-app", requestId: "request-two" });
  sender.postMessage({ type: "result", success: true, accessToken: "untrusted" });
  assert.equal(refresh, 0);
  assert.equal(closed, 0);
  stopParent();
  stopResult();
  sender.close();
});

test("重复通知会重复确认，但只触发一次查询；清理后不再接收", () => {
  const bus = transport();
  let refresh = 0;
  const stop = listenForFeishuAuthorization("app", () => refresh++, bus.open);
  const sender = bus.open();
  const message = { type: "result", connectionId: "app", requestId: "request" };
  sender.postMessage(message);
  sender.postMessage(message);
  assert.equal(refresh, 1);
  assert.equal(bus.sent.filter((item) => item.type === "received").length, 2);
  stop();
  sender.postMessage({ ...message, requestId: "next" });
  assert.equal(refresh, 1);
  sender.close();
});
