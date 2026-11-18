export function formatFileSize(size: number | null) {
  if (size == null) {
    return "—";
  }
  if (size < 1024) {
    return `${size} B`;
  }
  const units = ["KB", "MB", "GB", "TB", "PB"];
  let value = size / 1024;
  let index = 0;
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index++;
  }
  return `${value.toFixed(1)} ${units[index]}`;
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
