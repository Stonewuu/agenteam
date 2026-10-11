"use client";

import {useRef} from "react";
import {Combobox} from "@base-ui/react/combobox";
import {Button} from "./button";
import {useFieldContext} from "./field";
import {useControlDisabled} from "./fieldset";
import {IconCheck, IconChevronDown, IconSearch, IconX} from "./icons";
import {LoadingSurface, useLoadingSurface} from "./loading-transition";
import {useT} from "@/lib/i18n/locale-provider";
import ui from "./surface.module.css";
import styles from "./searchable-select.module.css";

export type SearchableSelectOption = {value: string; label: string};

/** 搜索结果由调用方提供，保留服务端筛选、加载与重试，键盘操作由基础组件处理。 */
export function SearchableSelect({
  options, value, onChange, query, onQueryChange, open, onOpenChange,
  resetLabel, searchLabel, emptyLabel, loading, error, onRetry, disabled,
}: {
  options: SearchableSelectOption[];
  value: SearchableSelectOption | null;
  onChange: (value: SearchableSelectOption | null) => void;
  query: string;
  onQueryChange: (query: string) => void;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  resetLabel: string;
  searchLabel: string;
  emptyLabel: string;
  loading: boolean;
  error?: string;
  onRetry: () => void;
  disabled?: boolean;
}) {
  const t = useT();
  const field = useFieldContext();
  const inactive = useControlDisabled(disabled);
  const input = useRef<HTMLInputElement>(null);
  const surface = useLoadingSurface();
  const reset = {value: "", label: resetLabel};
  const visible = [reset, ...(!loading && !error ? options : [])];
  // 已选值不在本次结果中时仍保留显示，但不把它混入搜索结果。
  const items = value && !visible.some((option) => option.value === value.value) ? [...visible, value] : visible;
  return <span className="select-control">
    <Combobox.Root items={items} filteredItems={visible} filter={null} value={value ?? reset}
      isItemEqualToValue={(option, selected) => option.value === selected.value}
      open={open && !inactive} disabled={inactive} inputValue={query}
      onInputValueChange={onQueryChange}
      onOpenChange={(next) => {
        if (next) {
          onQueryChange("");
        }
        onOpenChange(next);
      }}
      onValueChange={(next) => onChange(next?.value ? next : null)}>
      <Combobox.Trigger className={`select-trigger ${styles.trigger}`} aria-labelledby={field?.labelId}
        aria-describedby={field?.describedBy} aria-invalid={field?.invalid || undefined}>
        <span title={value?.label ?? resetLabel}>{value?.label ?? resetLabel}</span>
        <Combobox.Icon className="select-chevron"><IconChevronDown size={16}/></Combobox.Icon>
      </Combobox.Trigger>
      <Combobox.Portal>
        <Combobox.Positioner sideOffset={6} align="start" className={`agenteam-positioner ${styles.positioner}`}>
          <Combobox.Popup ref={surface} className={`select-menu agenteam-popup agenteam-glass ${styles.popup}`}
            aria-label={searchLabel} initialFocus={input} onKeyDown={(event) => {
              if (event.key === "Escape") {
                event.stopPropagation();
              }
            }}>
            <LoadingSurface>
              <div className={styles.search}>
                <span className="search-input" data-custom-clear>
                  <IconSearch size={18}/>
                  <Combobox.Input ref={input} type="search" className={ui.input} aria-label={searchLabel}
                    placeholder={t("按名称查找")}/>
                  {query && <Button type="button" className="icon-button" aria-label={t("清除搜索")} onClick={() => {
                    input.current?.focus();
                    onQueryChange("");
                  }}><IconX size={16}/></Button>}
                </span>
              </div>
              <Combobox.List className={`select-options ${styles.list}`} aria-busy={loading}>
                {(option: SearchableSelectOption) => <Combobox.Item key={option.value} value={option} className={`select-option ${styles.option}`}>
                  <span title={option.label}>{option.label}</span>
                  <Combobox.ItemIndicator className="select-check"><IconCheck size={16}/></Combobox.ItemIndicator>
                </Combobox.Item>}
              </Combobox.List>
              <Combobox.Status className={styles.status}>
                {loading ? t("正在加载选项…") : error || (options.length === 0 ? emptyLabel : "")}
              </Combobox.Status>
              {error && !loading && <div className={styles.retry}>
                <Button type="button" className={`${ui.secondary} ${ui.small}`} onClick={onRetry}>{t("重试")}</Button>
              </div>}
            </LoadingSurface>
          </Combobox.Popup>
        </Combobox.Positioner>
      </Combobox.Portal>
    </Combobox.Root>
  </span>;
}
