export const tokenLabels: Record<string, string> = {
  input: "全部输入", input_uncached: "普通输入", output: "输出", cache_read: "缓存读取", cache_write: "缓存创建",
  reasoning: "其中思考", tool_use_prompt: "其中工具输入", total: "合计 Token",
};

export const usagePurposes: Record<string, string> = {
  agent: "员工对话", subagent: "子智能体", workflow: "工作流", compaction: "上下文整理", preview: "预览", title: "标题生成",
};

export const priceReasons: Record<string, string> = {
  PRICE_NOT_CONFIGURED: "本次调用时尚未配置价格。",
  PRICE_CALENDAR_EXPIRED: "本次调用日期不在价格日历范围内，请补充日期配置。",
  SERVICE_TIER_NOT_REPORTED: "提供方未返回实际服务档位，暂时无法确定适用价格。",
  CONTEXT_NOT_REPORTED: "提供方未返回完整输入用量，暂时无法确定适用的价格区间。",
  PRICE_RULE_NOT_FOUND: "没有匹配本次时间、输入量和服务档位的价格规则。",
  PRICE_RATE_NOT_CONFIGURED: "部分用量项目尚未设置单价。",
  USAGE_INCOMPLETE: "提供方未返回完整用量，金额仅包含已确认的部分。",
  INVALID_USAGE: "提供方返回的各项用量不一致，暂时无法计算费用。",
  UNSUPPORTED_METER: "本次调用包含文本价格规则未覆盖的用量，暂时无法完整计费。",
};

export function tokenCount(value: string | null | undefined, locale: string): string | null {
  return value == null ? null : BigInt(value).toLocaleString(locale);
}

/** 大数和小额费用使用整数运算显示；完整原值保留在详情中。 */
export function usageAmount(value: string, locale: string): string {
  const [whole, fraction = ""] = value.split(".");
  if (BigInt(whole) === BigInt(0) && !/[1-9]/.test(fraction.slice(0, 6)) && /[1-9]/.test(fraction)) {
    return "< 0.000001";
  }
  const scale = BigInt(1000000);
  let units = BigInt(whole) * scale + BigInt(fraction.padEnd(6, "0").slice(0, 6));
  if ((fraction[6] ?? "0") >= "5") {
    units += BigInt(1);
  }
  const tail = String(units % scale).padStart(6, "0").replace(/0+$/, "");
  return `${(units / scale).toLocaleString(locale)}${tail ? `.${tail}` : ""}`;
}
