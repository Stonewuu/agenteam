import assert from "node:assert/strict";
import test from "node:test";
import {publicImageDigest} from "./community-registry.mjs";

test("公开镜像使用匿名授权和 HEAD 请求，核对内容摘要", async () => {
  const calls = [];
  const digest = `sha256:${"a".repeat(64)}`;
  const fetcher = async (url, options) => {
    calls.push({url: String(url), options});
    if (String(url).startsWith("https://auth.docker.io/token")) {
      return Response.json({token: "仅测试的匿名凭据"});
    }
    assert.equal(options.method, "HEAD");
    return new Response(null, {headers: {"docker-content-digest": digest}});
  };
  assert.equal(await publicImageDigest("docker.io/stonewuu/agenteam-backend:1.2.3", fetcher), digest);
  assert.equal(calls.length, 2);
  assert.equal(calls[0].options.headers, undefined);
});

test("只有明确不存在才返回空，授权、限流及服务故障不能冒充不存在", async () => {
  for (const status of [404, 401, 403, 429, 500]) {
    const fetcher = async (url) => String(url).startsWith("https://auth.docker.io/token")
      ? Response.json({token: "仅测试的匿名凭据"}) : new Response(null, {status});
    if (status === 404) {
      assert.equal(await publicImageDigest("docker.io/stonewuu/agenteam-frontend:1.2.3", fetcher), null);
    } else {
      await assert.rejects(publicImageDigest("docker.io/stonewuu/agenteam-frontend:1.2.3", fetcher), /检查失败/);
    }
  }
  await assert.rejects(publicImageDigest("example.com/another/image:latest"), /不属于当前项目/);
});
