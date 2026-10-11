export type PriceRate = {meter: "input_uncached" | "output" | "cache_read" | "cache_write"; cacheTtlSeconds: number; pricePerMillion: string};
export type PriceTime = {weekday: number; startMinute: number; endMinute: number};
export type PriceDate = {localDate: string; kind: "include" | "exclude"; note: string | null};
export type PriceRule = {
  name: string; priority: number; defaultRule: boolean; contextMinTokens: number; contextMaxTokens: number | null;
  serviceTier: string; times: PriceTime[]; dates: PriceDate[]; rates: PriceRate[];
};
export type PriceConfiguration = {
  currency: string; timezone: string; calendarValidThrough: string | null; sourceReference: string | null;
  sourceCheckedAt: string | null; note: string | null; rules: PriceRule[];
};
export type PriceVersion = {
  id: string; modelProfileId: string; versionNo: string; status: "draft" | "published"; revision: string;
  currency: string; timezone: string; effectiveFrom: string | null; createdAt: string; publishedAt: string | null;
  calendarValidThrough: string | null; configuration: PriceConfiguration | null;
};
export type PricePreview = {
  status: string; reason: string | null; ruleId: string | null; ruleName: string | null; currency: string | null;
  totalAmount: string | null; knownAmount: string | null;
  items: {meter: PriceRate["meter"]; cacheTtlSeconds: number; quantity: string; pricePerMillion: string; amount: string}[];
};
export type CatalogPrice = {
  providerId: string; providerName: string; modelId: string; modelName: string; matched: boolean;
  configuration: PriceConfiguration; notices: string[];
};
export type PriceCatalog = {
  items: CatalogPrice[]; total: number; offset: number; hasMore: boolean; fetchedAt: string; stale: boolean;
  providers: {id: string; name: string}[];
};

export const priceMeters: {value: PriceRate["meter"]; label: string}[] = [
  {value: "input_uncached", label: "普通输入"}, {value: "output", label: "输出"},
  {value: "cache_read", label: "缓存读取"}, {value: "cache_write", label: "缓存创建"},
];

export function initialPriceRule(defaultRule = false): PriceRule {
  return {name: defaultRule ? "基础价格" : "", priority: defaultRule ? 0 : 10, defaultRule, contextMinTokens: 0,
    contextMaxTokens: null, serviceTier: "standard", times: [], dates: [], rates: []};
}

/** 只移动十进制小数点，切换计价单位时不经过 JavaScript 浮点数。 */
export function shiftPrice(value: string, places: number): string {
  if (!/^\d+(\.\d*)?$/.test(value)) {
    return value;
  }
  const [whole, fraction = ""] = value.split(".");
  const digits = whole + fraction;
  const position = whole.length + places;
  const shifted = position <= 0 ? `0.${"0".repeat(-position)}${digits}`
    : position >= digits.length ? digits + "0".repeat(position - digits.length)
      : `${digits.slice(0, position)}.${digits.slice(position)}`;
  const [left, right = ""] = shifted.split(".");
  const result = `${left.replace(/^0+(?=\d)/, "")}${right.replace(/0+$/, "") ? `.${right.replace(/0+$/, "")}` : ""}`;
  return result || "0";
}
