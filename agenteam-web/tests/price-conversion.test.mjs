import assert from "node:assert/strict";
import {test} from "node:test";
import {loadSource} from "./i18n-test-runtime.mjs";

const {isValidExchangeRate, convertUnitPrice, convertPriceConfiguration} = loadSource("features/modelprofile/lib/price-conversion");

test("十进制汇率换算避免浮点误差，并保留极小单价的精度", () => {
  assert.equal(convertUnitPrice("0.15", "7.2"), "1.08");
  assert.equal(convertUnitPrice("0.003", "6.6645"), "0.019994");
  assert.equal(convertUnitPrice("0", "7.25"), "0");
  assert.equal(convertUnitPrice("0.00001", "6.5"), "0.000065");
  assert.equal(convertUnitPrice("500000", "3"), null);
});

test("汇率校验只接受合法正数，并同步换算全部规则及备注", () => {
  assert.equal(isValidExchangeRate("6.6645"), true);
  assert.equal(isValidExchangeRate("0"), false);
  assert.equal(isValidExchangeRate("-1.2"), false);
  assert.equal(isValidExchangeRate("1e2"), false);
  assert.equal(isValidExchangeRate("1000001"), false);

  const original = {
    currency: "USD",
    timezone: "UTC",
    calendarValidThrough: null,
    sourceReference: "https://models.dev/api.json",
    sourceCheckedAt: "2026-11-25T05:00:00Z",
    note: "models.dev: deepseek/deepseek-flash",
    rules: [
      {
        name: "基础价格",
        priority: 0,
        defaultRule: true,
        contextMinTokens: 0,
        contextMaxTokens: null,
        serviceTier: "standard",
        times: [],
        dates: [],
        rates: [
          {meter: "input_uncached", cacheTtlSeconds: 0, pricePerMillion: "0.15"},
          {meter: "output", cacheTtlSeconds: 0, pricePerMillion: "0.6"},
        ],
      },
      {
        name: "上下文超过 200000 Token",
        priority: 10,
        defaultRule: false,
        contextMinTokens: 200001,
        contextMaxTokens: null,
        serviceTier: "standard",
        times: [],
        dates: [],
        rates: [
          {meter: "input_uncached", cacheTtlSeconds: 0, pricePerMillion: "0.3"},
          {meter: "output", cacheTtlSeconds: 0, pricePerMillion: "1.2"},
        ],
      },
    ],
  };

  const result = convertPriceConfiguration(original, "CNY", "7.2", "按参考汇率换算");
  assert.equal(result.overflow, false);
  assert.equal(result.configuration.currency, "CNY");
  assert.equal(result.configuration.rules[0].rates[0].pricePerMillion, "1.08");
  assert.equal(result.configuration.rules[0].rates[1].pricePerMillion, "4.32");
  assert.equal(result.configuration.rules[1].rates[0].pricePerMillion, "2.16");
  assert.equal(result.configuration.rules[1].rates[1].pricePerMillion, "8.64");
  assert.match(result.configuration.note, /按参考汇率换算/);
  // 原对象不被修改
  assert.equal(original.currency, "USD");
  assert.equal(original.rules[0].rates[0].pricePerMillion, "0.15");
});
