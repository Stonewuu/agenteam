"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {type ComponentProps, useLayoutEffect, useRef} from "react";
import {Button} from "@/components/ui/button";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import {IconChevronDown} from "@/components/ui/icons";
import styles from "./employee-picker-button.module.css";

export function EmployeePickerButton({name, icon, color, loading = false, expanded = false, disabled, onClick}: {
  name?: string; icon?: string; color?: string; loading?: boolean; expanded?: boolean;
  disabled?: boolean; onClick: ComponentProps<typeof Button>["onClick"];
}) {
  const uiText = useT();
  const button = useRef<HTMLButtonElement>(null);
  const measure = useRef<HTMLSpanElement>(null);

  useLayoutEffect(() => {
    const element = button.current;
    const content = measure.current;
    const toolbar = element?.parentElement;
    if (!element || !content || !toolbar) {
      return;
    }

    function resize() {
      const control = element!;
      const sizing = content!;
      const row = toolbar!;
      const style = getComputedStyle(control);
      const padding = Number.parseFloat(style.paddingLeft) + Number.parseFloat(style.paddingRight)
        + Number.parseFloat(style.borderLeftWidth) + Number.parseFloat(style.borderRightWidth);
      const children = Array.from(row.children).filter((child) => getComputedStyle(child).display !== "none");
      const gap = Number.parseFloat(getComputedStyle(row).columnGap) || 0;
      const occupied = children.filter((child) => child !== control).reduce((total, child) => total + child.getBoundingClientRect().width, 0);
      const available = Math.max(58, row.clientWidth - occupied - gap * Math.max(0, children.length - 1));
      const avatar = sizing.querySelector(".avatar");
      const arrow = sizing.lastElementChild;
      const contentGap = Number.parseFloat(getComputedStyle(sizing).columnGap) || 0;
      const compactWidth = padding + (avatar?.getBoundingClientRect().width ?? 0)
        + (arrow?.getBoundingClientRect().width ?? 14) + contentGap;
      const compact = Boolean(avatar) && available < compactWidth + contentGap + 16;
      const naturalWidth = compact ? compactWidth : sizing.getBoundingClientRect().width + padding;
      const nextWidth = Math.ceil(Math.min(naturalWidth, available));
      // 宽度只使用当前名称所需的空间；像素值变化由样式平滑过渡。
      control.style.setProperty("--employee-picker-width", `${nextWidth}px`);
      control.toggleAttribute("data-compact", compact);
    }

    resize();
    const observer = new ResizeObserver(resize);
    observer.observe(toolbar);
    observer.observe(content);
    for (const child of toolbar.children) {
      if (child !== element) {
        observer.observe(child);
      }
    }
    return () => observer.disconnect();
  });

  const identity = <>
    {icon ? <ResourceAvatar icon={icon} color={color} size="small"/>
      : <span className={`avatar small ${styles.placeholderAvatar}`} aria-hidden="true"/>}
    {loading ? <span className={styles.loadingName}/> :
      <span className={styles.name}>{name || uiText("选择员工")}</span>}
    <IconChevronDown size={14}/>
  </>;

  return <Button ref={button} className={styles.trigger} data-composer-part="employee" type="button"
                 disabled={disabled || loading}
                 aria-label={loading ? uiText("正在加载员工") : name ? uiText("选择员工：{0}", [name]) : uiText("选择员工")}
                 aria-busy={loading || undefined} aria-haspopup="dialog" aria-expanded={expanded}
                 title={loading ? undefined : name || uiText("选择员工")} onClick={onClick}>
    <span className={styles.content}>{identity}</span>
    <span ref={measure} className={styles.measure} aria-hidden="true">{identity}</span>
  </Button>;
}
