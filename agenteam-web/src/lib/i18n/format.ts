export type TextFormatter =
  ((message: string, values?: readonly (string | number | boolean | null | undefined)[]) => string)
  & { formatLocale?: string };

/** 纯格式化函数未传入语言时保持中文，便于服务端和独立工具复用。 */
export const sourceText: TextFormatter = (message, values) => message.replace(/\{(\d+)\}/g, (token, index: string) => {
  return values && Number(index) < values.length ? String(values[Number(index)] ?? "") : token;
});
