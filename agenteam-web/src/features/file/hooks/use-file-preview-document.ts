"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useEffect, useState} from "react";
import {errorMessage, reportRequestFailure} from "@/lib/http/api-client";
import {loadFilePreviewDocument} from "../api/file-preview";
import {maximumPreviewBytes} from "../lib/file-preview-document";

export function useFilePreviewDocument(url: string, sizeBytes: number) {
  const uiText = useT();
  const key = url + ":" + sizeBytes;
  const [result, setResult] = useState<{ key: string; content: string | null; error: string } | null>(null);
  const tooLarge = sizeBytes > maximumPreviewBytes;
  useEffect(() => {
    if (tooLarge) {
      return;
    }
    const controller = new AbortController();
    loadFilePreviewDocument(url, controller.signal).then((content) => {
      if (!controller.signal.aborted) {
        setResult({key, content, error: ""});
      }
    }).catch((failure) => {
      if (!controller.signal.aborted) {
        reportRequestFailure(failure, "GET", url + "/text");
        setResult({key, content: null, error: errorMessage(failure)});
      }
    });
    return () => controller.abort();
  }, [url, key, tooLarge]);
  if (tooLarge) {
    return {content: null, error: uiText("文件较大，暂时无法完整预览，请查看原文或下载文件。")};
  }
  return result?.key === key ? {content: result.content, error: result.error} : {content: null, error: ""};
}
