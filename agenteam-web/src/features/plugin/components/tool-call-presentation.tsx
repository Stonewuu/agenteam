"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {createContext, type ReactNode, useContext, useEffect, useId, useMemo, useRef, useState} from "react";
import {Tooltip} from "@base-ui/react/tooltip";
import {Button} from "@/components/ui/button";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {IconEye, IconListDetails} from "@/components/ui/icons";
import {
  formatRawToolPayload,
  omitRepeatedPayloadFields,
  parseToolPayload,
  type PayloadRecord
} from "../lib/tool-payload-format";
import {ToolPayloadValue} from "./tool-payload-value";
import {ToolContentReader} from "./tool-content-reader";
import {ToolContentVisibility} from "./tool-content-visibility";
import {ToolContentBoundary} from "./tool-content-boundary";
import {ToolPayloadCopyButton} from "./tool-payload-copy-button";
import {ToolPayloadOverview} from "./tool-payload-overview";
import type {ToolPayloadPart} from "../lib/tool-payload-summary";
import type {ToolContentSource, ToolReadingPosition} from "../types/tool-content";
import styles from "./tool-payload.module.css";

const RawToolPayload = createContext(false);

export function useRawToolPayload() {
  return useContext(RawToolPayload);
}

/** 每次调用独立切换；嵌套工具拥有自己的模式，不影响父调用。 */
export function ToolCallPresentation({children, heading, compact = false, renderLayout, onModeChange}: {
  children: ReactNode;
  heading?: ReactNode;
  compact?: boolean;
  renderLayout?: (parts: { modeButton: ReactNode; content: ReactNode }) => ReactNode;
  onModeChange?: () => void;
}) {
  const uiText = useT();
  const [raw, setRaw] = useState(false);
  const contentId = useId();
  const modeLabel = raw ? uiText("返回易读模式") : uiText("查看原始调用");
  const button = <Button type="button"
                         className={[styles.modeButton, compact ? styles.modeIconButton : ""].filter(Boolean).join(" ")}
                         aria-label={modeLabel} aria-pressed={raw} aria-controls={contentId} onClick={() => {
    setRaw(!raw);
    onModeChange?.();
  }}>
    {raw ? <IconListDetails size={compact ? 16 : 14}/> : <IconEye size={compact ? 16 : 14}/>}{!compact && modeLabel}
  </Button>;
  const modeButton = compact ? <Tooltip.Root>
    <Tooltip.Trigger delay={350} render={button}/>
    <Tooltip.Portal><Tooltip.Positioner side="bottom" align="end" sideOffset={6}
                                        className={styles.modeTooltipPositioner}>
      <Tooltip.Popup className={`agenteam-glass ${styles.modeTooltip}`}>{modeLabel}</Tooltip.Popup>
    </Tooltip.Positioner></Tooltip.Portal>
  </Tooltip.Root> : button;
  const content = <div id={contentId} className={styles.sections}>{children}</div>;
  return <RawToolPayload value={raw}>
    {renderLayout ? renderLayout({modeButton, content}) :
      <div className={styles.presentation} data-tool-presentation={raw ? "raw" : "readable"}>
        <div className={styles.toolbar}>{heading && <div className={styles.heading}>{heading}</div>}{modeButton}</div>
        {content}
      </div>}
  </RawToolPayload>;
}

export function ToolPayload({title, value, renderValue, source, part = source?.part ?? "result", repeatedFields}: {
  title: string;
  value: string;
  renderValue?: (value: unknown) => ReactNode;
  source?: ToolContentSource;
  part?: ToolPayloadPart;
  repeatedFields?: PayloadRecord;
}) {
  const raw = useContext(RawToolPayload);
  const titleId = useId();
  const readablePosition = useRef<ToolReadingPosition>({offset: 0, top: 0, left: 0});
  const rawPosition = useRef<ToolReadingPosition>({offset: 0, top: 0, left: 0});
  const url = source?.url, sourcePart = source?.part, revision = source?.revision, readLimit = source?.readLimit;
  const stableSource = useMemo(() => url && sourcePart && revision !== undefined ? {
    url,
    part: sourcePart,
    revision,
    readLimit
  } : undefined, [url, sourcePart, revision, readLimit]);
  const displayValue = (content: string) => raw ? content : omitRepeatedPayloadFields(content, repeatedFields);
  const displayed = displayValue(value);
  if (!displayed) {
    return null;
  }
  return <section className={styles.payloadSection} aria-labelledby={titleId}>
    <header className={styles.payloadHeading}><h5 id={titleId}>{title}</h5>
      <ToolPayloadCopyButton title={title} value={value} source={stableSource} raw={raw}/>
    </header>
    <ToolContentBoundary key={`${revision ?? ""}:${raw}`}><ToolContentVisibility>{(ready) => stableSource ?
      <ToolContentReader key={`${stableSource.url}:${stableSource.part}:${stableSource.revision}:${raw}`}
                         source={stableSource} raw={raw} showActions={false}
                         positionRef={raw ? rawPosition : readablePosition} onReady={ready}
                         render={(content) => <ContentView value={displayValue(content)} raw={raw}
                                                           renderValue={renderValue} expandDetails/>}
                         renderPreview={(document, fullContent) => <ToolPayloadOverview
                           value={displayValue(document.startOffset === 0 ? document.content : value)}
                           part={part} raw={raw} complete={document.startOffset === 0 && document.eof}
                           fullContent={fullContent} renderValue={renderValue}/>}/>
      : <ToolPayloadContent value={displayed} renderValue={renderValue} onReady={ready} animateHeight={false}
                            summaryPart={part}/>}</ToolContentVisibility></ToolContentBoundary>
  </section>;
}

/** 当前模式才进行解析和格式化，不同时保留两棵大型内容结构。 */
export function ToolPayloadContent({
                                     value,
                                     showAllFields = false,
                                     renderValue,
                                     onReady,
                                     animateHeight = true,
                                     summaryPart
                                   }: {
  value: string;
  showAllFields?: boolean;
  renderValue?: (value: unknown) => ReactNode;
  onReady?: () => void;
  animateHeight?: boolean;
  summaryPart?: ToolPayloadPart;
}) {
  const raw = useContext(RawToolPayload);
  useEffect(() => {
    onReady?.();
  }, [onReady]);
  const content = summaryPart ?
    <ToolPayloadOverview value={value} part={summaryPart} raw={raw} renderValue={renderValue}
                         fullContent={<ContentView value={value} raw={raw} showAllFields={showAllFields}
                                                   renderValue={renderValue} expandDetails/>}/>
    : <div className={styles.surface}><ContentView value={value} raw={raw} showAllFields={showAllFields}
                                                   renderValue={renderValue}/></div>;
  return animateHeight ? <AnimatedHeight>{content}</AnimatedHeight> : content;
}

function ContentView({raw, ...props}: {
  raw: boolean;
  value: string;
  showAllFields?: boolean;
  renderValue?: (value: unknown) => ReactNode;
  expandDetails?: boolean
}) {
  return <ToolContentBoundary key={String(raw)}>{raw ? <RawValue value={props.value}/> :
    <ReadableValue {...props} />}</ToolContentBoundary>;
}

function ReadableValue({value, showAllFields, renderValue, expandDetails}: {
  value: string;
  showAllFields?: boolean;
  renderValue?: (value: unknown) => ReactNode;
  expandDetails?: boolean
}) {
  const parsed = useMemo(() => parseToolPayload(value), [value]);
  return <div className={styles.readable}>{renderValue ? renderValue(parsed) :
    <ToolPayloadValue value={parsed} showAllFields={showAllFields} expandDetails={expandDetails}/>}</div>;
}

function RawValue({value}: { value: string }) {
  const uiText = useT();
  const original = useMemo(() => formatRawToolPayload(value), [value]);
  return <pre className={styles.raw} tabIndex={0} aria-label={uiText("原始调用内容")}>{original}</pre>;
}
