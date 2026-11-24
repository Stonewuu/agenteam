import type {PriceConfiguration} from "../types/model-pricing";

const ZERO = BigInt(0);
const ONE = BigInt(1);
const TWO = BigInt(2);
const MAX_PRICE_PER_MILLION = BigInt(1_000_000);
const MAX_RATE = BigInt(1_000_000);
const STANDARD_SCALE = 6;
const MAX_SCALE = 12;

type ParsedDecimal = {
  digits: bigint;
  scale: number;
};

function parseNonNegativeDecimal(raw: string): ParsedDecimal | null {
  const trimmed = raw.trim();
  if (!/^(?:\d+)(?:\.\d+)?$/.test(trimmed)) {
    return null;
  }
  const [whole, fraction = ""] = trimmed.split(".");
  return {
    digits: BigInt(`${whole}${fraction}`),
    scale: fraction.length,
  };
}

function pow10(exponent: number): bigint {
  return BigInt(`1${"0".repeat(exponent)}`);
}

function roundHalfUp(digits: bigint, currentScale: number, targetScale: number): bigint {
  if (currentScale <= targetScale) {
    return digits * pow10(targetScale - currentScale);
  }
  const factor = pow10(currentScale - targetScale);
  const quotient = digits / factor;
  const remainder = digits % factor;
  if (remainder * TWO >= factor) {
    return quotient + ONE;
  }
  return quotient;
}

function formatScaled(digits: bigint, scale: number): string {
  if (digits === ZERO) {
    return "0";
  }
  if (scale === 0) {
    return digits.toString();
  }
  const raw = digits.toString().padStart(scale + 1, "0");
  const split = raw.length - scale;
  const whole = raw.slice(0, split);
  const fraction = raw.slice(split).replace(/0+$/, "");
  if (!fraction) {
    return whole;
  }
  return `${whole}.${fraction}`;
}

/** 校验管理员输入的换算汇率：必须为大于 0 且不超过 1,000,000 的十进制数，最多 12 位小数。 */
export function isValidExchangeRate(raw: string): boolean {
  const parsed = parseNonNegativeDecimal(raw);
  if (!parsed || parsed.digits === ZERO || parsed.scale > MAX_SCALE) {
    return false;
  }
  const limit = MAX_RATE * pow10(parsed.scale);
  return parsed.digits <= limit;
}

/** 使用纯整数十进制乘法换算单价，不经过浮点数，避免出现精度尾差。 */
export function convertUnitPrice(price: string, rate: string): string | null {
  const parsedPrice = parseNonNegativeDecimal(price);
  const parsedRate = parseNonNegativeDecimal(rate);
  if (!parsedPrice || !parsedRate || parsedRate.digits === ZERO) {
    return null;
  }
  if (parsedPrice.digits === ZERO) {
    return "0";
  }
  const product = parsedPrice.digits * parsedRate.digits;
  const totalScale = parsedPrice.scale + parsedRate.scale;
  // 常规价格保留最多 6 位小数；小于 0.001 的极小单价保留最多 12 位小数，防止精度丢失或被舍入成 0。
  const threshold = pow10(Math.max(0, totalScale - 3));
  const useExtended = totalScale >= 3 && product < threshold;
  const targetScale = useExtended ? MAX_SCALE : STANDARD_SCALE;
  const rounded = roundHalfUp(product, totalScale, targetScale);
  const maxAllowed = MAX_PRICE_PER_MILLION * pow10(targetScale);
  if (rounded > maxAllowed) {
    return null;
  }
  return formatScaled(rounded, targetScale);
}

/** 按指定汇率将整份价格配置（含所有上下文与分时规则）换算到目标币种。 */
export function convertPriceConfiguration(
  configuration: PriceConfiguration,
  targetCurrency: string,
  rate: string,
  conversionNote?: string,
): {configuration: PriceConfiguration | null; overflow: boolean} {
  if (targetCurrency === configuration.currency) {
    return {configuration, overflow: false};
  }
  if (!isValidExchangeRate(rate)) {
    return {configuration: null, overflow: false};
  }
  const rules = [];
  for (const rule of configuration.rules) {
    const convertedRates = [];
    for (const item of rule.rates) {
      const converted = convertUnitPrice(item.pricePerMillion, rate);
      if (converted === null) {
        return {configuration: null, overflow: true};
      }
      convertedRates.push({...item, pricePerMillion: converted});
    }
    rules.push({...rule, rates: convertedRates});
  }
  const parts = [configuration.note?.trim(), conversionNote?.trim()].filter(Boolean);
  const combined = parts.join(" ");
  const note = combined ? Array.from(combined).slice(0, 500).join("") : null;
  return {
    configuration: {
      ...configuration,
      currency: targetCurrency,
      note,
      rules,
    },
    overflow: false,
  };
}
