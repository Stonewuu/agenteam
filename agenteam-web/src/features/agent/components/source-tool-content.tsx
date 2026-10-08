"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {ToolPayloadValue} from "@/features/plugin/components/tool-payload-value";
import {isPayloadRecord} from "@/features/plugin/lib/tool-payload-format";
import type {DisplayBlock} from "../types/conversation-display";
import styles from "./source-tool-content.module.css";

type Tool = Extract<DisplayBlock, { kind: "tool" }>;

/** 展示资料读取的业务内容，资料种类来自服务端登记，不能靠插件名称猜测。 */
export function SourceToolContent({block, value}: { block: Tool; value: unknown }) {
  const uiText = useT();
  const result = isPayloadRecord(value) ? value : {};
  const empty = block.sourceKind === "knowledge" && Array.isArray(result.citations) && !result.citations.length ? uiText("没有找到相关资料。")
    : block.name === "data_collections" && Array.isArray(result.items) && !result.items.length ? uiText("没有可读取的数据集合。")
      : block.sourceKind === "data" && Array.isArray(result.rows) && !result.rows.length ? uiText("没有符合条件的数据。") : "";
  return <div className={styles.content}>
    {result.isError !== true && empty && <p>{empty}</p>}
    {result.truncated === true && typeof result.fileId === "string" &&
      <p className={styles.notice}>{uiText("完整结果已保存为附件。")}</p>}
    <ToolPayloadValue value={value} expandDetails/>
  </div>;
}
