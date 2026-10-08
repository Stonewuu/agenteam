"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useContext, useState} from "react";
import {Disclosure, DisclosureSummary} from "@/components/ui/disclosure";
import {Collapsible, CollapsibleContent, CollapsibleTrigger} from "@/components/ui/shadcn/collapsible";
import {IconChevronRight} from "@/components/ui/icons";
import {MarkdownContent} from "@/components/ui/markdown-content";
import {EnterpriseTimezone} from "@/features/auth/components/enterprise-date-time";
import {
  formatPayloadScalar,
  isAdditionalPayloadField,
  isPayloadMarkdown,
  isPayloadRecord,
  parseToolPayload,
  payloadColumns,
  payloadEntries,
  payloadFieldLabel,
  payloadTextPart
} from "../lib/tool-payload-format";
import styles from "./tool-payload.module.css";

type ValueProps = {
  value: unknown;
  field?: string;
  timezone?: string | null;
  depth?: number;
  showAllFields?: boolean;
  columns?: unknown;
  expandDetails?: boolean
};

export function ToolPayloadValue(props: Omit<ValueProps, "depth" | "timezone">) {
  const uiText = useT();
  const timezone = useContext(EnterpriseTimezone);
  if (hasManyFields(props.value)) {
    return <pre className={styles.raw} tabIndex={0}
                aria-label={uiText("完整工具内容")}>{JSON.stringify(props.value, null, 2)}</pre>;
  }
  return <Value {...props} timezone={timezone} depth={0}/>;
}

/** 字段很多时展示完整文本，避免为每一个字段创建组件。 */
function hasManyFields(value: unknown) {
  const pending: { value: unknown; depth: number }[] = [{value, depth: 0}];
  let count = 0;
  while (pending.length) {
    const item = pending.pop()!;
    if (++count > 300 || item.depth > 12) {
      return true;
    }
    if (item.value && typeof item.value === "object") {
      const values = Object.values(item.value);
      if (values.length + pending.length + count > 300) {
        return true;
      }
      for (const child of values) {
        pending.push({value: child, depth: item.depth + 1});
      }
    }
  }
  return false;
}

function Value({value, field = "", timezone, depth = 0, showAllFields, columns, expandDetails}: ValueProps) {
  const uiText = useT();
  if (value !== null && typeof value === "object") {
    if (depth >= 5) {
      if (expandDetails) {
        return <pre className={styles.raw} tabIndex={0}
                    aria-label={uiText("完整工具内容")}>{JSON.stringify(value, null, 2)}</pre>;
      }
      return <DeepValue value={value} field={field} timezone={timezone} showAllFields={showAllFields} columns={columns}
                        expandDetails={expandDetails}/>;
    }
    if (Array.isArray(value)) {
      return <List value={value} field={field} timezone={timezone} depth={depth} showAllFields={showAllFields}
                   columns={columns} expandDetails={expandDetails}/>;
    }
    if (isPayloadRecord(value)) {
      return <Fields value={value} timezone={timezone} depth={depth} showAllFields={showAllFields}
                     expandDetails={expandDetails}/>;
    }
  }
  const text = formatPayloadScalar(value, field, timezone, uiText, uiText.formatLocale);
  const longText = typeof value === "string" && (value.length > 320 || value.split("\n").length > 10);
  if (!showAllFields && typeof value === "string" && isPayloadMarkdown(value, field)) {
    return <div className={styles.richText} tabIndex={longText ? 0 : undefined}>
      <MarkdownContent content={value} mode="static" className={styles.markdown} allowImages={false}
                       allowRelativeLinks={false}/>
    </div>;
  }
  return <span className={value === null || value === undefined || value === "" ? styles.empty : styles.text}
               tabIndex={longText ? 0 : undefined}>{text}</span>;
}

function Fields({value, timezone, depth = 0, showAllFields, expandDetails}: ValueProps & {
  value: Record<string, unknown>
}) {
  const uiText = useT();
  const entries = payloadEntries(value, showAllFields);
  const zone = typeof value.timezone === "string" ? value.timezone : timezone;
  const primary = entries.filter(([key]) => showAllFields || expandDetails || !isAdditionalPayloadField(key));
  const secondary = showAllFields || expandDetails || !primary.length ? [] : entries.filter(([key]) => isAdditionalPayloadField(key));
  const visible = primary.length ? primary : entries;
  const rows = (items: [string, unknown][]) => <dl className={styles.fields}>{items.map(([key, item]) => <div
    className={styles.field} key={key} data-complex={item !== null && typeof item === "object" || undefined}>
    <dt>{payloadFieldLabel(key, uiText)}</dt>
    <dd><Value value={item} field={key} timezone={zone} depth={depth + 1} showAllFields={showAllFields}
               columns={key === "rows" ? value.fields : undefined} expandDetails={expandDetails}/></dd>
  </div>)}</dl>;
  if (!entries.length) {
    return <span className={styles.empty}>{uiText("没有内容")}</span>;
  }
  return <>
    {rows(visible)}
    {secondary.length > 0 && <Disclosure className={styles.additional}
                                         keepMounted={false}><DisclosureSummary>{uiText("更多内容")}</DisclosureSummary>{rows(secondary)}
    </Disclosure>}
  </>;
}

function List({value, field, timezone, depth = 0, showAllFields, columns, expandDetails}: ValueProps & {
  value: unknown[]
}) {
  const uiText = useT();
  if (!value.length) {
    return <span className={styles.empty}>{uiText("暂无条目")}</span>;
  }
  const displayed = value;
  const tableColumns = field === "content" && value.some((item) => payloadTextPart(item) !== null) ? null : payloadColumns(value, columns, uiText);
  return <div className={styles.list}>
    {tableColumns ? <div className={styles.tableScroll} tabIndex={0} role="region"
                         aria-label={uiText("{0}，{1} 条", [payloadFieldLabel(field ?? "items", uiText), value.length])}>
      <table className={styles.table}>
        <thead>
        <tr>{tableColumns.map((column) => <th key={column.key} scope="col">{column.label}</th>)}</tr>
        </thead>
        <tbody>{displayed.map((item, index) => <tr key={index}>{tableColumns.map((column) => <td key={column.key}>
          <Value value={(item as Record<string, unknown>)[column.key]} field={column.key}
                 timezone={isPayloadRecord(item) && typeof item.timezone === "string" ? item.timezone : timezone}
                 depth={depth + 1} showAllFields={showAllFields} expandDetails={expandDetails}/>
        </td>)}</tr>)}</tbody>
      </table>
    </div> : <ol className={styles.items}>{displayed.map((item, index) => {
      const text = field === "content" ? payloadTextPart(item) : null;
      return <li key={index}><Value value={text === null ? item : parseToolPayload(text)} field={field}
                                    timezone={timezone} depth={depth + 1} showAllFields={showAllFields}
                                    expandDetails={expandDetails}/></li>;
    })}</ol>}
  </div>;
}

function DeepValue(props: ValueProps) {
  const uiText = useT();
  const [open, setOpen] = useState(false);
  return <Collapsible open={open} onOpenChange={setOpen} className={styles.additional}>
    <CollapsibleTrigger className="agenteam-disclosure-trigger"><IconChevronRight
      size={13}/><span>{uiText("更多内容")}</span></CollapsibleTrigger>
    <CollapsibleContent className="agenteam-disclosure-content"><Value {...props} depth={0}/></CollapsibleContent>
  </Collapsible>;
}
