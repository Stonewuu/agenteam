"use client";

import {useT} from "@/lib/i18n/locale-provider";

import type {ReactNode} from "react";
import {Button} from "./button";
import {IconLayoutGrid, IconList} from "./icons";
import styles from "./collection-view.module.css";

export type CollectionView = "grid" | "list";

export function CollectionViewToggle({value, onChange}: {
  value: CollectionView;
  onChange: (value: CollectionView) => void;
}) {
  const uiText = useT();
  return <div className={styles.toggle} data-view={value} role="group" aria-label={uiText("显示方式")}>
    <Button type="button" aria-label={uiText("卡片视图")} title={uiText("卡片视图")} aria-pressed={value === "grid"}
            onClick={() => onChange("grid")}><IconLayoutGrid size={18}/></Button>
    <Button type="button" aria-label={uiText("表格视图")} title={uiText("表格视图")} aria-pressed={value === "list"}
            onClick={() => onChange("list")}><IconList size={18}/></Button>
  </div>;
}

export function CollectionTable({label, className = "", children}: {
  label: string;
  className?: string;
  children: ReactNode;
}) {
  return <div className={styles.tableFrame}>
    <div className={styles.tableScroll} role="region" aria-label={label} tabIndex={0}>
      <table className={`${styles.table} ${className}`} aria-label={label}>{children}</table>
    </div>
  </div>;
}
