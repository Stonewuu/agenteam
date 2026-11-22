"use client";

import {createContext, type ReactNode, type RefObject, useContext, useLayoutEffect, useMemo, useState} from "react";
import {toolGlowLayout, type ToolGlowRect} from "../lib/tool-glow-layout";
import {toolBorderGlowPadding, type ToolBorderGlowConfig} from "./conversation-tool-border-glow-config";
import styles from "./conversation-tool-border-glow.module.css";

type GlowLayer = {host: HTMLDivElement | null};
const GlowLayerContext = createContext<GlowLayer | null>(null);

/** 嵌套调用共用最外层光效容器，避免内层画布再次被父列表裁切。 */
export function ConversationToolGlowLayer({children, viewportClassName}: {children: ReactNode; viewportClassName: string}) {
  const parent = useContext(GlowLayerContext);
  const [host, setHost] = useState<HTMLDivElement | null>(null);
  const value = useMemo(() => ({
    host: parent ? parent.host : host,
  }), [host, parent]);
  return <div className={styles.layerRoot}>
    {!parent && <div ref={setHost} className={styles.layer} aria-hidden="true"/>}
    <GlowLayerContext value={value}>
      <div className={viewportClassName}>{children}</div>
    </GlowLayerContext>
  </div>;
}

function clippingRect(element: HTMLElement, card: ToolGlowRect): ToolGlowRect | null {
  const style = getComputedStyle(element);
  const clipX = style.overflowX !== "visible" || style.clipPath !== "none";
  const clipY = style.overflowY !== "visible" || style.clipPath !== "none";
  if (!clipX && !clipY) {
    return null;
  }
  const rect = element.getBoundingClientRect();
  return {left: clipX ? rect.left + element.clientLeft : card.left,
    top: clipY ? rect.top + element.clientTop : card.top,
    width: clipX ? element.clientWidth : card.width, height: clipY ? element.clientHeight : card.height};
}

/** 光效只跟随卡片的位置和可见范围，不参与卡片尺寸及折叠高度计算。 */
export function useToolGlowLayer(card: HTMLDivElement | null, anchorRef: RefObject<HTMLDivElement | null>,
                                config: ToolBorderGlowConfig) {
  const layer = useContext(GlowLayerContext);
  // 卡片节点绑定后才定位；定位前由样式隐藏光效，避免先在容器起点闪现。
  useLayoutEffect(() => {
    const anchor = anchorRef.current;
    const host = layer?.host;
    if (!card || !anchor || !host) {
      return;
    }
    const ancestors: HTMLElement[] = [];
    for (let element = card.parentElement; element && element !== host.parentElement; element = element.parentElement) {
      ancestors.push(element);
    }
    const padding = Number.parseFloat(toolBorderGlowPadding(config));
    let frame: number | null = null;
    function update() {
      frame = null;
      const cardRect = card!.getBoundingClientRect();
      const boundaries = ancestors.map((element) => clippingRect(element, cardRect))
        .filter((rect): rect is ToolGlowRect => rect !== null);
      const layout = toolGlowLayout(cardRect, host!.getBoundingClientRect(), boundaries, padding);
      anchor!.hidden = !layout;
      if (!layout) {
        return;
      }
      Object.assign(anchor!.style, {
        left: `${layout.left}px`, top: `${layout.top}px`, width: `${layout.width}px`, height: `${layout.height}px`,
        clipPath: layout.clipPath, borderRadius: getComputedStyle(card!).borderRadius, visibility: "visible",
      });
    }
    function schedule() {
      if (frame === null) {
        frame = requestAnimationFrame(update);
      }
    }
    const sizeObserver = new ResizeObserver(schedule);
    sizeObserver.observe(card);
    sizeObserver.observe(host);
    // 父级折叠区域的尺寸也参与观察，不能只等待卡片自身或列表尺寸发生变化。
    for (const element of ancestors) {
      sizeObserver.observe(element);
    }
    window.addEventListener("scroll", schedule, true);
    window.addEventListener("resize", schedule);
    update();
    return () => {
      if (frame !== null) {
        cancelAnimationFrame(frame);
      }
      sizeObserver.disconnect();
      window.removeEventListener("scroll", schedule, true);
      window.removeEventListener("resize", schedule);
    };
  }, [anchorRef, card, config, layer]);
  return layer?.host;
}
