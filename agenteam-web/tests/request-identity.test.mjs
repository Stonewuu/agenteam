import assert from "node:assert/strict";
import {test} from "node:test";
import {loadSource} from "./i18n-test-runtime.mjs";

const api = loadSource("lib/http/api-client");
const identity = loadSource("lib/http/request-identity");

test("防伪令牌更新不能让旧请求改用切换后的身份，身份冲突只提交一次", async t => {
  const previous = globalThis.window;
  const events = [];
  const writes = [];
  globalThis.window = {setTimeout, clearTimeout, dispatchEvent: event => events.push(event)};
  t.mock.method(console, "error", () => {});
  identity.acceptRequestIdentity("original");
  api.clearCsrf();
  let csrfCount = 0;
  t.mock.method(globalThis, "fetch", async (path, options) => {
    if (path === "/api/v1/auth/csrf") {
      return Response.json({data: {token: `csrf-${++csrfCount}`}});
    }
    writes.push({identity: options.headers.get("X-Identity-Revision"), body: options.body,
      csrf: options.headers.get("X-CSRF-Token")});
    if (writes.length === 1) {
      identity.acceptRequestIdentity("changed");
      return Response.json({error: {code: "CSRF_INVALID", message: "请刷新令牌"}}, {status: 403});
    }
    return Response.json({error: {code: "IDENTITY_CHANGED", message: "请刷新身份"}}, {status: 409});
  });
  try {
    await assert.rejects(api.apiRequest("/api/v1/enterprises/team/members", {method: "POST", body: {name: "test"}}),
      {code: "IDENTITY_CHANGED", status: 409});
    assert.equal(writes.length, 2);
    assert.deepEqual(writes.map(value => value.identity), ["original", "original"]);
    assert.deepEqual(writes.map(value => value.csrf), ["csrf-1", "csrf-2"]);
    assert.equal(writes[0].body, writes[1].body);
    assert.equal(events.filter(event => event.type === identity.identityChangedEvent).length, 1);
    writes.length = 0;
    t.mock.method(globalThis, "fetch", async (_path, options) => {
      writes.push(options.headers.get("X-Identity-Revision"));
      return Response.json({error: {code: "IDENTITY_CHANGED", message: "请刷新身份"}}, {status: 409});
    });
    await assert.rejects(api.apiRequest("/api/v1/auth/impersonation/stop", {method: "POST"}), {code: "IDENTITY_CHANGED"});
    assert.deepEqual(writes, ["changed"]);
  } finally {
    identity.acceptRequestIdentity(undefined);
    api.clearCsrf();
    if (previous === undefined) {
      delete globalThis.window;
    } else {
      globalThis.window = previous;
    }
  }
});
