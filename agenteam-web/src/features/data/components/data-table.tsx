"use client";

import {useT} from "@/lib/i18n/locale-provider";

import type {DataField} from "../types/data";
import styles from "./data.module.css";

export function DataTable({fields, rows}: {
  fields: Pick<DataField, "name" | "label">[];
  rows: Record<string, unknown>[]
}) {
  return <div className={styles.tableScroll}>
    <table className={styles.results}>
      <thead>
      <tr>{fields.map((field) => <th key={field.name}>{field.label}</th>)}</tr>
      </thead>
      <tbody>{rows.map((row, index) => <tr key={index}>{fields.map((field) => <td key={field.name}>{<Cell
        value={row[field.name]}/>}</td>)}</tr>)}</tbody>
    </table>
  </div>;
}

function Cell({value}: { value: unknown }) {
  const t = useT();
  return value == null ? <span
    className={styles.emptyValue}>{t("空值")}</span> : typeof value === "object" ? JSON.stringify(value) : String(value);
}
