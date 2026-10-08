/** K 表示一千、M 表示一百万；保留准确值，不把已有配置近似取整。 */
export function parseTokenLength(value: string): number | null {
  const match = /^(\d+(?:\.\d+)?|\d{1,3}(?:,\d{3})+(?:\.\d+)?)\s*([km])?$/i.exec(value.trim());
  if (!match) {
    return null;
  }
  const amount = Number(match[1].replaceAll(",", ""));
  const multiplier = match[2]?.toLowerCase() === "m" ? 1_000_000 : match[2] ? 1_000 : 1;
  const result = amount * multiplier;
  return Number.isSafeInteger(result) ? result : null;
}

export function formatTokenLength(value: number | null | undefined): string {
  if (value == null) {
    return "";
  }
  if (value >= 1_000_000 && value % 1_000_000 === 0) {
    return `${value / 1_000_000}M`;
  }
  if (value >= 1_000 && value % 1_000 === 0) {
    return `${value / 1_000}K`;
  }
  return value.toLocaleString("en-US");
}

export function tokenLengthError(value: string, label: string, max: number): string | undefined {
  if (!value.trim()) {
    return `请选择或填写${label}。`;
  }
  const parsed = parseTokenLength(value);
  if (parsed === null) {
    return "请输入整数，或使用 K、M 简写，例如 128K。";
  }
  if (parsed < 128 || parsed > max) {
    return `${label}须在 128 至 ${max.toLocaleString("en-US")} 之间。`;
  }
}
