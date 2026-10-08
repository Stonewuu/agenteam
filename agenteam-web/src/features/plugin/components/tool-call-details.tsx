"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {type ReactNode, useEffect, useMemo, useRef, useState} from "react";
import {Button} from "@/components/ui/button";
import {Disclosure, DisclosureSummary} from "@/components/ui/disclosure";
import {LoadingTransition} from "@/components/ui/loading-transition";
import {errorMessage, reportRequestFailure} from "@/lib/http/api-client";
import {readToolContentPreview} from "../api/tool-content";
import {selectToolCallDetails, type ToolDetailField} from "../lib/tool-call-details";
import {isPayloadRecord, parseToolPayload} from "../lib/tool-payload-format";
import type {ToolContentSource, ToolContentWindow, ToolReadingPosition} from "../types/tool-content";
import {ToolPayloadContent, useRawToolPayload} from "./tool-call-presentation";
import {ToolContentBoundary} from "./tool-content-boundary";
import {ToolContentReader} from "./tool-content-reader";
import {ToolContentVisibility} from "./tool-content-visibility";
import {ToolPayloadCopyButton} from "./tool-payload-copy-button";
import {ToolPayloadValue} from "./tool-payload-value";
import payloadStyles from "./tool-payload.module.css";
import styles from "./tool-call-details.module.css";

type Props = {
  input: string;
  result: string;
  inputSource?: ToolContentSource;
  resultSource?: ToolContentSource;
  children?: ReactNode;
};

/** 每条调用只保留一个附加参数入口；卡片是否展开仍由外层控制。 */
export function ToolCallDetails(props: Props) {
  const raw = useRawToolPayload();
  return <ToolContentBoundary key={String(raw)}><ToolContentVisibility>{(ready) => (
    <LoadedToolCallDetails {...props} raw={raw} onReady={ready}/>
  )}</ToolContentVisibility></ToolContentBoundary>;
}

function useToolCallDocument(value: string, source: ToolContentSource | undefined, raw: boolean) {
  const url = source?.url, part = source?.part, revision = source?.revision, readLimit = source?.readLimit;
  const stableSource = useMemo(() => url && part && revision !== undefined ? {
      url,
      part,
      revision,
      readLimit
    } : undefined,
    [url, part, revision, readLimit]);
  const key = JSON.stringify([value, stableSource, raw]);
  const [state, setState] = useState<{ key: string; document?: ToolContentWindow; error?: string } | null>(null);
  const [retry, setRetry] = useState(0);
  const remote = Boolean(value && stableSource);
  useEffect(() => {
    if (!value || !stableSource) {
      return;
    }
    const controller = new AbortController();
    readToolContentPreview(stableSource, raw, controller.signal).then((document) => {
      if (!controller.signal.aborted) {
        setState({key, document});
      }
    }).catch((failure) => {
      if (!controller.signal.aborted) {
        reportRequestFailure(failure, "GET", stableSource.url);
        setState({key, error: errorMessage(failure)});
      }
    });
    return () => controller.abort();
  }, [key, raw, retry, stableSource, value]);
  const current = state?.key === key ? state : null;
  return {
    source: stableSource,
    value: remote ? current?.document?.content ?? "" : value,
    document: current?.document,
    loading: remote && !current,
    error: current?.error,
    retry: () => {
      setState(null);
      setRetry((previous) => previous + 1);
    },
  };
}

function LoadedToolCallDetails({input, result, inputSource, resultSource, children, raw, onReady}: Props & {
  raw: boolean;
  onReady: () => void;
}) {
  const uiText = useT();
  const request = useToolCallDocument(input, inputSource, raw);
  const response = useToolCallDocument(result, resultSource, raw);
  const loading = request.loading || response.loading;
  const completeInput = !request.document || request.document.eof;
  const completeResult = !response.document || response.document.eof;
  const details = useMemo(() => selectToolCallDetails(completeInput ? request.value : "", completeResult ? response.value : ""),
    [completeInput, completeResult, request.value, response.value]);
  useEffect(() => {
    if (!loading) {
      onReady();
    }
  }, [loading, onReady]);
  const more = Boolean(details.additional.input || details.additional.result);
  return <LoadingTransition state={loading ? "loading" : "ready"}>
    {loading ? <p className={styles.notice} role="status">{uiText("正在读取内容…")}</p> :
      <div className={styles.details}>
        {raw ? <>
          {request.value && <DocumentContent title={uiText("调用参数")} data={request} raw/>}
          {response.value && <DocumentContent title={uiText("返回内容")} data={response} raw/>}
        </> : <>
          {details.fields.filter((field) => field.part === "input").map((field) => <DetailField
            key={`${field.part}:${field.key}`} field={field}/>)}
          {!completeInput && <DocumentContent title={uiText("调用内容")} data={request}/>}
          {request.error && <ReadError title={uiText("调用参数")} data={request}/>}
          {children}
          {details.fields.filter((field) => field.part === "result").map((field) => <DetailField
            key={`${field.part}:${field.key}`} field={field}/>)}
          {!completeResult && <DocumentContent title={uiText("执行结果")} data={response}/>}
          {response.error && <ReadError title={uiText("返回内容")} data={response}/>}
          {more && <Disclosure className={styles.more} keepMounted={false} animateContentHeight>
            <DisclosureSummary>{uiText("更多内容")}</DisclosureSummary>
            <div className={styles.additional}>
              {details.additional.input &&
                <AdditionalFields title={uiText("其他调用参数")} part="input" value={details.additional.input}
                                  data={request}/>}
              {details.additional.result &&
                <AdditionalFields title={uiText("其他返回内容")} part="result" value={details.additional.result}
                                  data={response}/>}
            </div>
          </Disclosure>}
        </>}
        {raw && <>
          {request.error && <ReadError title={uiText("调用参数")} data={request}/>}
          {response.error && <ReadError title={uiText("返回内容")} data={response}/>}
          {children}
        </>}
      </div>}
  </LoadingTransition>;
}

function DetailField({field}: { field: ToolDetailField }) {
  const uiText = useT();
  if (field.notice) {
    return <p className={field.error ? styles.error : styles.notice}
              role={field.error ? "alert" : undefined}>{uiText(field.notice)}</p>;
  }
  const text = typeof field.value === "string" ? field.value : undefined;
  const wide = field.code || field.value !== null && typeof field.value === "object"
    || text !== undefined && (text.length > 180 || text.includes("\n"));
  return <section className={styles.field} data-wide={wide || undefined} data-error={field.error || undefined}>
    <div className={`${payloadStyles.payloadHeading} ${styles.fieldHeading}`}>
      <h5>{uiText(field.label)}</h5>
      {wide && text && <ToolPayloadCopyButton title={uiText(field.label)} value={text} raw={false}/>}
    </div>
    <div className={styles.value}>{field.key === "stdout" && text === "" ?
      <span className={styles.notice}>{uiText("没有输出")}</span> : field.code && text !== undefined ? (
        <pre className={styles.code} tabIndex={0} aria-label={uiText(field.label)}>{text || uiText("（空内容）")}</pre>
      ) : <ToolPayloadValue value={field.value} field={field.key} columns={field.columns} expandDetails/>}</div>
  </section>;
}

type DocumentData = ReturnType<typeof useToolCallDocument>;

function ReadError({title, data}: { title: string; data: DocumentData }) {
  const uiText = useT();
  return <div className={styles.error} role="alert">
    <span>{title}：{localizeUiMessage(data.error ?? "", uiText)}</span><Button type="button"
                                                                              onClick={data.retry}>{uiText("重新加载")}</Button>
  </div>;
}

function DocumentContent({title, data, raw = false}: { title: string; data: DocumentData; raw?: boolean }) {
  const uiText = useT();
  const position = useRef<ToolReadingPosition>({offset: 0, top: 0, left: 0});
  const parsed = parseToolPayload(data.value);
  const preview = !data.source && isPayloadRecord(parsed) && parsed.truncated === true;
  return <section className={styles.document}>
    <header className={payloadStyles.payloadHeading}><h5>{title}</h5><ToolPayloadCopyButton title={title}
                                                                                            value={data.value}
                                                                                            source={data.source}
                                                                                            raw={raw}/></header>
    {preview && <p className={styles.notice}>{uiText("这里只包含部分内容。")}</p>}
    {data.source && data.document && !data.document.eof ? <ToolContentReader
        key={`${data.source.url}:${data.source.part}:${data.source.revision}:${raw}`}
        source={data.source} raw={raw} initialDocument={data.document} positionRef={position} onReady={() => {
      }}
        label={title} showActions={false}
        render={(value) => <ToolPayloadContent value={value} animateHeight={false} showAllFields={raw}/>}/>
      : <ToolPayloadContent value={data.value} animateHeight={false} showAllFields={raw}/>}
  </section>;
}

function AdditionalFields({title, part, value, data}: {
  title: string;
  part: "input" | "result";
  value: unknown;
  data: DocumentData
}) {
  const uiText = useT();
  return <section className={styles.document}>
    <header className={payloadStyles.payloadHeading}><h5>{title}</h5><ToolPayloadCopyButton
      title={part === "input" ? uiText("完整调用参数") : uiText("完整返回内容")} value={data.value} source={data.source}
      raw={false}/></header>
    <ToolPayloadValue value={value} expandDetails/>
  </section>;
}
