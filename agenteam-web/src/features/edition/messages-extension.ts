/** 翻译通过独立数据入口装配，避免服务端加载客户端组件。 */
export const editionMessages: Readonly<Record<string, string>> = {};
export const editionBackendMessages: Readonly<Record<"zh-CN" | "en" | "legacy", Readonly<Record<string, string>>>> = {
  "zh-CN": {}, en: {}, legacy: {},
};
