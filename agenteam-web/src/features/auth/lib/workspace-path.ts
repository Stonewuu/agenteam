/** 页面地址与企业接口地址分开，企业管理和系统管理使用不同前缀。 */
export function workspacePath(suffix = "/workspace") {
  const path = suffix || "/workspace";
  return path.replace(/^\/management(?=\/|\?|$)/, "/organization");
}

export function enterpriseEntryPath(enterpriseId: string, path = "/workspace") {
  return `/open?${new URLSearchParams({enterprise: enterpriseId, path: workspacePath(path)})}`;
}

export function workspaceParts(pathname: string) {
  const parts = pathname.split("/");
  if (parts[1] === "enterprises") {
    return parts.slice(3);
  }
  return parts.slice(1).map((value, index) => index === 0 && value === "organization" ? "management" : value);
}

export function validWorkspaceDestination(value: string) {
  if (!value.startsWith("/") || value.startsWith("//") || /[\\\u0000-\u001f]/.test(value)) {
    return false;
  }
  const path = value.split(/[?#]/, 1)[0];
  return /^\/(?:workspace|new-task|employees|environment|memories(?:\/[^/]+)?|conversations(?:\/[^/]+)?|todos(?:\/[^/]+)?|schedules(?:\/[^/]+)?|notifications(?:\/[^/]+)?|collaboration\/tasks(?:\/[^/]+)?|capabilities(?:\/(?:agents|skills|plugins|workflows|knowledge|data)(?:\/[^/]+)?)?|organization(?:\/[a-z-]+)?|settings(?:\/channels)?)$/.test(path);
}

export function resourceDestination(path: string): {type: string; id: string} | null {
  const parts = path.split("/").filter(Boolean);
  const direct: Record<string, string> = {conversations: "conversation", todos: "todo", schedules: "schedule",
    notifications: "notification", memories: "memory"};
  let type: string | undefined;
  let id: string | undefined;
  if (parts.length === 2 && direct[parts[0]]) {
    type = direct[parts[0]];
    id = parts[1];
  } else if (parts.length === 3 && parts[0] === "collaboration" && parts[1] === "tasks") {
    type = "collaboration";
    id = parts[2];
  } else if (parts.length === 3 && parts[0] === "capabilities") {
    type = ({agents: "agent", skills: "skill", plugins: "plugin", workflows: "workflow",
      knowledge: "knowledge", data: "data"} as Record<string, string>)[parts[1]];
    id = parts[2];
  }
  if (!type || !id) {
    return null;
  }
  try {
    return {type, id: decodeURIComponent(id)};
  } catch {
    return null;
  }
}
