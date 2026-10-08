export function agentBackendUrl(path: string) {
  const baseUrl = process.env.AGENT_BACKEND_URL ?? "http://localhost:8080";
  return `${baseUrl.replace(/\/+$/, "")}${path}`;
}
