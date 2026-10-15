"use client";

import type {ReactNode} from "react";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {Button} from "@/components/ui/button";
import {IconChevronDown, IconSettings} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import ui from "@/components/ui/surface.module.css";
import styles from "./chat.module.css";

export function ChatAdvancedSettings({open, onOpenChange, children}: {open: boolean; onOpenChange: (open: boolean) => void; children: ReactNode}) {
  const t = useT();
  return <div className={styles.advanced}>
    <Button type="button" className={ui.button} aria-expanded={open} onClick={() => onOpenChange(!open)}>
      <IconSettings size={16}/>{t("高级设置")}<IconChevronDown size={15} style={{transform: open ? "rotate(180deg)" : undefined}}/>
    </Button>
    <AnimatedHeight preserveControlShadows>{open && <div className={`${styles.fields} ${styles.advancedBody}`}>{children}</div>}</AnimatedHeight>
  </div>;
}
