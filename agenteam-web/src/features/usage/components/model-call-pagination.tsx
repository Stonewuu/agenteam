"use client";

import {Button} from "@/components/ui/button";
import {Select} from "@/components/ui/select";
import {IconChevronLeft, IconChevronRight} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import {tokenCount} from "../lib/model-usage-display";
import ui from "@/components/ui/surface.module.css";
import styles from "./model-usage.module.css";

export function ModelCallPagination({page, pageSize, onPageSizeChange, count, total, loading, error, hasMore, back, next}: {
  page: number; pageSize: number; onPageSizeChange: (size: number) => void; count: number; total?: string;
  loading: boolean; error: string; hasMore: boolean; back: () => void; next: () => void;
}) {
  const t = useT();
  const amount = total === undefined ? null : BigInt(total);
  const pageCount = amount === null ? null : amount === BigInt(0) ? BigInt(1) : (amount + BigInt(pageSize - 1)) / BigInt(pageSize);
  const start = (page - 1) * pageSize + (count ? 1 : 0);
  const end = (page - 1) * pageSize + count;
  const format = (value: string | number | bigint) => tokenCount(String(value), t.formatLocale) ?? "0";
  return <nav className={styles.callPagination} aria-label={t("调用明细分页")}>
    <div className={styles.pageSummary} aria-live="polite">
      {loading ? t("正在加载…") : error ? t("本页暂时无法读取") : count ?
        (amount === null ? t("第 {0}–{1} 条", [format(start), format(end)]) : t("第 {0}–{1} 条，共 {2} 条", [format(start), format(end), format(amount)]))
        : t("共 {0} 条", [format(amount ?? 0)])}
    </div>
    <div className={styles.pageControls}>
      <label className={styles.pageSize}><span>{t("每页")}</span>
        <Select value={pageSize} aria-label={t("每页条数")} disabled={loading} onChange={(event) => onPageSizeChange(Number(event.target.value))}>
          {[10, 20, 50].map((size) => <option key={size} value={size}>{t("{0} 条", [size])}</option>)}
        </Select>
      </label>
      <span className={styles.pageIndex}>{pageCount === null ? t("第 {0} 页", [page]) : t("第 {0} / {1} 页", [page, format(pageCount)])}</span>
      <Button type="button" className={ui.button} disabled={loading || page === 1} onClick={back}>
        <IconChevronLeft size={15}/>{t("上一页")}</Button>
      <Button type="button" className={ui.button} disabled={loading || Boolean(error) || !hasMore} onClick={next}>
        {t("下一页")}<IconChevronRight size={15}/></Button>
    </div>
  </nav>;
}
