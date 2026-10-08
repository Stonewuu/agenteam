import type {ConversationFileTarget} from "../types/conversation-files";

/** 与后端文件接口使用同一逻辑路径和编号，真正的访问权限仍由后端校验。 */
export function workspaceFileTarget(path: string): ConversationFileTarget | null {
  const logical = (path.startsWith("/workspace/") ? path.slice(11) : path.startsWith("/") ? path.slice(1) : path).replace(/\/$/, "");
  if (!logical || logical.length > 512 || /[\\\u0000-\u001f\u007f]/.test(logical)
    || logical.split("/").some((part) => !part || part === "." || part === "..")) {
    return null;
  }
  const encoded = btoa(String.fromCharCode(...new TextEncoder().encode(logical))).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
  return {id: "w." + encoded, name: logical.slice(logical.lastIndexOf("/") + 1), path: logical, source: "workspace"};
}
