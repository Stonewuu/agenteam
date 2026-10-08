"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useMemo, useRef} from "react";
import {ToolContentReader} from "@/features/plugin/components/tool-content-reader";
import {ToolContentBoundary} from "@/features/plugin/components/tool-content-boundary";
import type {ToolReadingPosition} from "@/features/plugin/types/tool-content";
import type {FilePreviewRendererProps} from "../types/file-preview";
import styles from "./conversation-files.module.css";

function ready() {
}

export function TextFilePreview({file, url}: FilePreviewRendererProps) {
  const uiText = useT();
  const position = useRef<ToolReadingPosition>({offset: 0, top: 0, left: 0});
  const source = useMemo(() => ({
    url: url + "/text",
    part: "result" as const,
    revision: file.revision,
    readLimit: 32768
  }), [url, file.revision]);
  return <div className={styles.textReader}>
    <ToolContentBoundary><ToolContentReader source={source} raw={false} label={uiText("文件内容")}
                                            positionRef={position} onReady={ready}
                                            render={(value) => <pre className={styles.plainText}
                                                                    tabIndex={0}>{value}</pre>}/></ToolContentBoundary>
  </div>;
}
