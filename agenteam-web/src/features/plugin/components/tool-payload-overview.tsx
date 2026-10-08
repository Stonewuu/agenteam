"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {type ReactNode, useContext, useLayoutEffect, useMemo, useRef, useState} from "react";
import {Disclosure, DisclosureSummary} from "@/components/ui/disclosure";
import {EnterpriseTimezone} from "@/features/auth/components/enterprise-date-time";
import {abbreviatePayloadText, summarizeToolPayload, type ToolPayloadPart} from "../lib/tool-payload-summary";
import {payloadFieldLabel} from "../lib/tool-payload-format";
import {ToolPayloadValue} from "./tool-payload-value";
import styles from "./tool-payload.module.css";

export function ToolPayloadOverview({value, part, raw, complete = true, fullContent, renderValue}: {
  value: string;
  part: ToolPayloadPart;
  raw: boolean;
  complete?: boolean;
  fullContent: ReactNode;
  renderValue?: (value: unknown) => ReactNode;
}) {
  const uiText = useT();
  const timezone = useContext(EnterpriseTimezone);
  const preview = useRef<HTMLDivElement>(null);
  const [clipped, setClipped] = useState(false);
  const summary = useMemo(() => raw ? null : summarizeToolPayload(value, part, complete, timezone, uiText, uiText.formatLocale), [value, part, raw, complete, timezone, uiText]);
  const rawPreview = raw ? abbreviatePayloadText(value, 260, 4) : null;
  useLayoutEffect(() => {
    const content = preview.current;
    if (!content) {
      return;
    }
    const observer = new ResizeObserver(() => {
      const nodes = content.querySelectorAll<HTMLElement>("[data-summary-text]");
      setClipped(Array.from(nodes).some((node) => node.scrollHeight > node.clientHeight + 1));
    });
    observer.observe(content);
    return () => observer.disconnect();
  }, [value, part, raw, complete, timezone]);
  const more = clipped || (summary ? summary.more : !complete || rawPreview!.shortened);
  return <div className={styles.overview}>
    <div ref={preview}>{summary ? summary.fields.length ? <dl className={styles.overviewFields}>
        {summary.fields.map((field) => <div key={field.key} className={styles.overviewField}
                                            data-error={field.error || undefined}>
          <dt data-summary-text>{payloadFieldLabel(field.key, uiText)}</dt>
          <dd data-summary-text>{field.text}</dd>
        </div>)}
      </dl> : <p className={styles.overviewText} data-summary-text>{summary.text}</p>
      : <pre className={styles.overviewRaw} data-summary-text>{rawPreview!.text}</pre>}</div>
    {more && <Disclosure className={styles.overviewMore} keepMounted={false} animateContentHeight>
      <DisclosureSummary>{uiText("更多内容")}</DisclosureSummary>
      <div className={styles.fullDetails} tabIndex={0} role="region"
           aria-label={part === "input" ? uiText("完整调用参数") : uiText("完整返回内容")}>{!clipped && summary?.details !== undefined
        ? renderValue ? renderValue(summary.details) : <ToolPayloadValue value={summary.details} expandDetails/>
        : fullContent}</div>
    </Disclosure>}
  </div>;
}
