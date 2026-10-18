"use client";

import {useId, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore} from "react";
import {createMermaidPlugin} from "@streamdown/mermaid";
import {Streamdown, type CustomRendererProps, type IconMap, type MermaidErrorComponentProps} from "streamdown";
import {useT} from "@/lib/i18n/locale-provider";
import {Button} from "./button";
import {Dialog} from "./dialog";
import {IconChevronDown, IconHierarchy, IconMaximize, IconMinus, IconPlus, IconRefresh} from "./icons";
import styles from "./markdown-diagram.module.css";

const plugins = {mermaid: createMermaidPlugin({config: {securityLevel: "strict", fontFamily: "inherit"}})};
const previewControls = {code: false, mermaid: false};
const fullscreenControls = {code: false, mermaid: {copy: false, download: false, fullscreen: false, panZoom: true}};
const icons: Partial<IconMap> = {
  ZoomInIcon: ({size = 18}) => <IconPlus size={size}/>,
  ZoomOutIcon: ({size = 18}) => <IconMinus size={size}/>,
  RotateCcwIcon: ({size = 18}) => <IconRefresh size={size}/>,
};

function subscribeTheme(listener: () => void) {
  const observer = new MutationObserver(listener);
  observer.observe(document.documentElement, {attributes: true, attributeFilter: ["data-theme"]});
  return () => observer.disconnect();
}

function DiagramError({chart}: MermaidErrorComponentProps) {
  const uiText = useT();
  return <div className={styles.error}>
    <p>{uiText("图表暂时无法显示，请检查图表语法。")}</p>
    <pre><code>{chart}</code></pre>
  </div>;
}

/** 保留官方图表解析和缩放能力，只统一预览、折叠与平台弹窗。 */
function DiagramCanvas({code, fullscreen = false}: {code: string; fullscreen?: boolean}) {
  const uiText = useT();
  const dark = useSyncExternalStore(subscribeTheme,
    () => document.documentElement.dataset.theme === "dark", () => false);
  const options = useMemo(() => ({config: {theme: dark ? "dark" : "neutral", fontFamily: "inherit"},
    errorComponent: DiagramError}), [dark]);
  return <Streamdown key={dark ? "dark" : "light"} className={`${styles.canvas}${fullscreen ? ` ${styles.fullCanvas}` : ""}`}
                     mode="static" plugins={plugins} mermaid={options} icons={icons} skipHtml
                     controls={fullscreen ? fullscreenControls : previewControls}
                     translations={{zoomIn: uiText("放大"), zoomOut: uiText("缩小"), resetView: uiText("重置视图")}}>
    {`\`\`\`mermaid\n${code}\n\`\`\``}
  </Streamdown>;
}

export function MarkdownDiagram({code, isIncomplete}: CustomRendererProps) {
  const uiText = useT();
  const contentId = useId();
  const content = useRef<HTMLDivElement>(null);
  const [height, setHeight] = useState(240);
  const [expanded, setExpanded] = useState(false);
  const [fullscreen, setFullscreen] = useState(false);
  useLayoutEffect(() => {
    const element = content.current;
    if (!element) {
      return;
    }
    const measure = () => setHeight(element.offsetHeight);
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    measure();
    return () => observer.disconnect();
  }, [isIncomplete]);
  if (isIncomplete) {
    return <p className={styles.pending} role="status">{uiText("正在生成图表…")}</p>;
  }
  const canCollapse = height > 280;
  return <section className={styles.root} aria-label={uiText("图表")}>
    <div id={contentId} className={styles.viewport} style={{height: expanded ? height : Math.min(height, 280)}}>
      <div ref={content} className={styles.preview}><DiagramCanvas code={code}/></div>
      <Button type="button" variant="ghost" className={styles.openOverlay} onClick={() => setFullscreen(true)}
              aria-label={uiText("全屏查看图表")}/>
      <div className={styles.fade} data-visible={canCollapse && !expanded} aria-hidden="true"/>
    </div>
    <div className={styles.actions}>
      {canCollapse && <Button type="button" variant="ghost" size="sm" className={styles.action}
                             aria-expanded={expanded} aria-controls={contentId} onClick={() => setExpanded(!expanded)}>
        <IconChevronDown size={16} className={expanded ? styles.rotated : undefined}/>
        {uiText(expanded ? "收起图表" : "展开图表")}
      </Button>}
      <Button type="button" variant="ghost" size="sm" className={styles.action} onClick={() => setFullscreen(true)}>
        <IconMaximize size={16}/>{uiText("全屏查看")}
      </Button>
    </div>
    {fullscreen && <Dialog title={uiText("图表")} icon={<IconHierarchy size={20}/>}
                           onClose={() => setFullscreen(false)} className={styles.dialog} bodyClassName={styles.dialogBody} wide>
      <DiagramCanvas code={code} fullscreen/>
    </Dialog>}
  </section>;
}
