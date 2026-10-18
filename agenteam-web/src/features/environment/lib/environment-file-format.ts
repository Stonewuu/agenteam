export function formatFileSize(size: number) {
  if (size < 1024) {
    return `${size} B`;
  }
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)} KB`;
  }
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

export function fileKind(name: string): "image" | "code" | "archive" | "text" {
  if (/\.(png|jpe?g|gif|webp|bmp)$/i.test(name)) {
    return "image";
  }
  if (/\.(zip|tar|gz|7z|rar)$/i.test(name)) {
    return "archive";
  }
  if (/\.(py|js|jsx|ts|tsx|java|json|ya?ml|html?|css|sql|sh|xml|toml)$/i.test(name)) {
    return "code";
  }
  return "text";
}
