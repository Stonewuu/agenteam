/** 内容安全策略必须先于文件正文解析，文件中的后续策略不能放宽这些限制。 */
const previewPolicy = [
  "default-src 'none'",
  "script-src 'unsafe-inline'",
  "style-src 'unsafe-inline'",
  "img-src data: blob:",
  "font-src data:",
  "media-src data: blob:",
  "connect-src 'none'",
  "frame-src 'none'",
  "object-src 'none'",
  "base-uri 'none'",
  "form-action 'none'",
].join("; ");

// 只允许文件内部交互，不授予主页面、存储、弹窗、下载或顶层导航权限。
export const htmlPreviewSandbox = "allow-scripts";

export function buildHtmlPreviewDocument(content: string) {
  // 先固定页内链接的基础地址，再禁止文件正文改写它，避免锚点跳到主应用。
  return '<!doctype html><meta charset="utf-8"><base href="about:srcdoc"><meta http-equiv="Content-Security-Policy" content="' + previewPolicy
    + '"><meta name="referrer" content="no-referrer">' + content;
}
