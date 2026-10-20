const accept = "application/vnd.oci.image.index.v1+json,application/vnd.oci.image.manifest.v1+json,"
  + "application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.docker.distribution.manifest.v2+json";
const tokens = new Map();

/** 匿名检查公开镜像，授权响应只保存在内存，不写入日志或附件。 */
export async function publicImageDigest(reference, fetcher = fetch) {
  const match = reference.match(/^docker\.io\/(stonewuu\/agenteam-(?:backend|frontend|sandbox-server|sandbox-office|deployment))(?::([A-Za-z0-9_.-]+)|@(sha256:[a-f0-9]{64}))$/);
  if (!match) {
    throw new Error("镜像引用不属于当前项目。");
  }
  const [, repository, tag, digest] = match;
  let token = tokens.get(repository);
  if (!token || token.expires < Date.now()) {
    const url = new URL("https://auth.docker.io/token");
    url.searchParams.set("service", "registry.docker.io");
    url.searchParams.set("scope", `repository:${repository}:pull`);
    const response = await fetcher(url, {signal: AbortSignal.timeout(30000), redirect: "error"});
    if (!response.ok) {
      throw new Error(`公开镜像授权失败，HTTP 状态 ${response.status}。`);
    }
    const data = await response.json();
    if (typeof data.token !== "string" || !data.token) {
      throw new Error("公开镜像授权没有返回有效凭据。");
    }
    token = {value: data.token, expires: Date.now() + 60000};
    tokens.set(repository, token);
  }
  const response = await fetcher(`https://registry-1.docker.io/v2/${repository}/manifests/${digest ?? tag}`, {
    method: "HEAD", headers: {Accept: accept, Authorization: `Bearer ${token.value}`},
    signal: AbortSignal.timeout(30000), redirect: "error",
  });
  if (response.status === 404) {
    return null;
  }
  if (!response.ok) {
    throw new Error(`公开镜像检查失败，HTTP 状态 ${response.status}；没有把访问失败当成镜像不存在。`);
  }
  const result = response.headers.get("docker-content-digest");
  if (!/^sha256:[a-f0-9]{64}$/.test(result ?? "")) {
    throw new Error("镜像仓库没有返回有效内容摘要。");
  }
  return result;
}
