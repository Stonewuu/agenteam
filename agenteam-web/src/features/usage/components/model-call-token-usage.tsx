"use client";

import {IconAlertCircle, IconArrowDown, IconArrowUp, IconDatabase, IconFileText, IconPlug, IconSparkles, IconStack} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import {tokenCount} from "../lib/model-usage-display";
import type {ModelCall} from "../types/model-usage";
import styles from "./model-call-token-usage.module.css";

const parts = {
  input: [
    {meter: "input_uncached", label: "普通输入", icon: IconFileText},
    {meter: "cache_read", label: "缓存读取", icon: IconDatabase},
    {meter: "cache_write", label: "缓存创建", icon: IconStack},
    {meter: "tool_use_prompt", label: "其中工具输入", icon: IconPlug},
  ],
  output: [{meter: "reasoning", label: "其中思考", icon: IconSparkles}],
};

export function ModelCallTokenUsage({call}: {call: ModelCall}) {
  const t = useT();
  const total = call.tokens.total;
  return <div className={styles.usage}>
    <div className={styles.groups}>
      <TokenGroup call={call} direction="input"/>
      <TokenGroup call={call} direction="output"/>
    </div>
    <div className={styles.footer}>
      {total && total.state !== "not_applicable" && <p className={styles.total}><IconStack size={16}/>
        <span>{t("合计 Token")}</span><strong>{tokenCount(total.value, t.formatLocale) ?? t("未返回")}</strong></p>}
      <p className={styles.guide}><IconAlertCircle size={15}/>{t("缓存和思考用量已包含在对应总量中，无需再次相加。")}</p>
    </div>
  </div>;
}

function TokenGroup({call, direction}: {call: ModelCall; direction: "input" | "output"}) {
  const t = useT();
  const Icon = direction === "input" ? IconArrowDown : IconArrowUp;
  const applicable = parts[direction].filter(({meter}) => call.tokens[meter] && call.tokens[meter].state !== "not_applicable");
  return <section className={styles.group} data-direction={direction} data-has-parts={applicable.length > 0}>
    <div className={styles.heading}>
      <span className={styles.icon} data-icon-context="inherit"><Icon size={20}/></span>
      <div><h4>{direction === "input" ? t("全部输入") : t("输出")}</h4>
        <p>{direction === "input" ? t("模型读取的内容") : t("模型生成的内容")}</p></div>
    </div>
    <p className={styles.value}><strong>{tokenCount(call.tokens[direction]?.value, t.formatLocale) ?? t("未返回")}</strong><span>Token</span></p>
    {applicable.length > 0 && <dl className={styles.parts}>
      {applicable.map(({meter, label, icon: PartIcon}) => <div key={meter}>
        <dt><PartIcon size={14}/>{t(label)}</dt><dd>{tokenCount(call.tokens[meter].value, t.formatLocale) ?? t("未返回")}</dd>
      </div>)}
      {direction === "input" && Object.entries(call.cacheWriteDetails).map(([ttl, count]) => <div className={styles.cacheDetail} key={ttl}>
        <dt>{t("创建缓存 · {0} 秒", [ttl])}</dt><dd>{tokenCount(count, t.formatLocale)}</dd>
      </div>)}
    </dl>}
  </section>;
}
