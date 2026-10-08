"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import {Fieldset} from "@/components/ui/fieldset";
import {Input} from "@/components/ui/input";
import {Checkbox} from "@/components/ui/checkbox";

import {type ComponentProps, type ReactNode, useEffect, useState} from "react";
import {Dialog, DialogActions, DialogCancel, useDialogControl} from "@/components/ui/dialog";
import {QueryState} from "@/components/ui/query-state";
import {apiRequest} from "@/lib/http/api-client";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {FormFeedback} from "@/features/auth/components/settings-forms";
import {entityOption, organizationPath} from "../api/organization-api";
import {useCollectionPage} from "../hooks/use-collection-page";
import type {EntityOption, Member, Role, Team} from "../types/organization";
import styles from "./organization.module.css";

export function Modal(props: ComponentProps<typeof Dialog>) {
  return <Dialog {...props} />;
}

export function CollectionStatus<T>({list, empty, children}: {
  list: ReturnType<typeof useCollectionPage<T>>;
  empty: string;
  children: ReactNode
}) {
  const uiText = useT();
  return <><QueryState {...list} hasData={Boolean(list.page?.items.length)}
                       empty={list.previous.length ? uiText("本页暂无记录，请返回上一页。") : empty}>{children}</QueryState>
    {(list.previous.length > 0 || list.page?.hasMore) &&
      <footer className={styles.pagination}><Button className={styles.secondary}
                                                    disabled={!list.previous.length || list.loading}
                                                    onClick={list.back}>{uiText("上一页")}</Button>
        <Button className={styles.secondary} disabled={!list.page?.hasMore || list.loading}
                onClick={list.next}>{uiText("下一页")}</Button></footer>}</>;
}

export function EntityPicker({
                               enterpriseId,
                               collection,
                               title,
                               selected,
                               onChange,
                               single = false,
                               maximum = 10,
                               disabled = false,
                               known = []
                             }: {
  enterpriseId: string;
  collection: "members" | "roles" | "teams";
  title: string;
  selected: string[];
  onChange: (ids: string[]) => void;
  single?: boolean;
  maximum?: number;
  disabled?: boolean;
  known?: EntityOption[];
}) {
  const uiText = useT();
  const [query, setQuery] = useState("");
  const list = useCollectionPage<Member | Role | Team>(enterpriseId, collection, query, 0, 30);
  const [resolved, setResolved] = useState<Record<string, EntityOption>>({});
  const [resolveError, setResolveError] = useState("");
  const knownById = new Map(known.map((value) => [value.id, value]));
  const options = (list.page?.items ?? []).map((item) => entityOption(collection, item));
  options.forEach((option) => knownById.set(option.id, option));
  Object.values(resolved).forEach((option) => {
    if (!knownById.has(option.id)) {
      knownById.set(option.id, option);
    }
  });
  const missing = selected.filter((id) => !knownById.has(id));
  const missingKey = missing.join("\n");
  useEffect(() => {
    if (!missingKey) {
      return;
    }
    const controller = new AbortController();
    const ids = missingKey.split("\n");
    if (ids.length > 10) {
      return;
    }
    Promise.all(ids.map(async (id) => entityOption(collection, await apiRequest<Member | Role | Team>(organizationPath(enterpriseId, `/${collection}/${encodeURIComponent(id)}`), {signal: controller.signal}))))
      .then((items) => {
        if (!controller.signal.aborted) {
          setResolved((values) => ({...values, ...Object.fromEntries(items.map((item) => [item.id, item]))}));
          setResolveError("");
        }
      })
      .catch(() => {
        if (!controller.signal.aborted) {
          setResolveError("部分已选记录暂时无法读取，请重新加载后再编辑。");
        }
      });
    return () => controller.abort();
  }, [enterpriseId, collection, missingKey]);

  return <Fieldset className={styles.picker} disabled={disabled}>
    <legend>{title}</legend>
    <div className={styles.selectionSummary}><span>{uiText("已选 ")}{selected.length}{uiText(" 项")}</span>
      {selected.length > 0 &&
        <Button className={styles.textButton} type="button" onClick={() => onChange([])}>{uiText("清空选择")}</Button>}
    </div>
    <div
      className={styles.selectedItems}>{selected.map((id) => knownById.get(id)).filter((item): item is EntityOption => Boolean(item)).map((item) =>
      <Button type="button" className={styles.selectionChip} key={item.id}
              aria-label={uiText("取消选择{0}", [item.name])}
              onClick={() => onChange(selected.filter((id) => id !== item.id))}>{item.name}<span
        aria-hidden="true"> ×</span></Button>)}</div>
    <Input className={styles.input} value={query} onChange={(event) => {
      list.first();
      setQuery(event.target.value);
    }} placeholder={uiText("查找{0}", [title])} aria-label={uiText("查找{0}", [title])}/>
    {resolveError && <p className={styles.error} role="alert">{localizeUiMessage(resolveError ?? "", uiText)}</p>}
    <div className={styles.pickerOptions} aria-busy={list.loading}>
      {list.loading ? <p role="status">{uiText("正在加载选项…")}</p> : list.error ?
        <p role="alert">{localizeUiMessage(list.error ?? "", uiText)}</p> : options.length ? options.map((item) =>
          <label className={styles.option} key={item.id}><Checkbox checked={selected.includes(item.id)}
                                                                   disabled={!selected.includes(item.id) && (item.disabled || (!single && selected.length >= maximum))}
                                                                   onCheckedChange={(checked) => onChange(checked ? single ? [item.id] : [...selected, item.id] : selected.filter((id) => id !== item.id))}/>
            <span>{item.name}{item.description && <small>{item.description}</small>}{item.disabled &&
              <small>{uiText("已停用")}</small>}</span></label>) : <p>{uiText("没有匹配的记录。")}</p>}
    </div>
    {(list.previous.length > 0 || list.page?.hasMore) && <div className={styles.pagination}>
      <Button className={styles.textButton} type="button" disabled={!list.previous.length || list.loading}
              onClick={list.back}>{uiText("上一页")}</Button>
      <Button className={styles.textButton} type="button" disabled={!list.page?.hasMore || list.loading}
              onClick={list.next}>{uiText("下一页")}</Button></div>}
  </Fieldset>;
}

export function ConfirmAction({title, description, path, method, revision, body, onClose, onDone}: {
  title: string;
  description: string;
  path: string;
  method: "POST" | "PATCH" | "DELETE";
  revision: string;
  body?: unknown;
  onClose: () => void;
  onDone: () => void;
}) {
  const uiText = useT();
  const action = useFormAction();
  const dialog = useDialogControl();
  return <Modal title={title} onClose={onClose} dialogRef={dialog.ref} busy={action.busy}><p
    className={styles.description}>{description}</p>
    <FormFeedback action={action} onReload={async () => {
      dialog.close(() => {
        onDone();
        onClose();
      });
    }}/>
    <DialogActions className={styles.formActions}><DialogCancel type="button" className={styles.secondary}
                                                                disabled={action.busy}>{uiText("取消")}</DialogCancel>
      <Button type="button" className={styles.primary} disabled={action.busy}
              onClick={() => void action.execute(async () => {
                await action.mutation.run(path, {method, revision, body});
                dialog.close(() => {
                  onDone();
                  onClose();
                });
              })}>{action.busy ? uiText("正在处理…") : uiText("确认")}</Button></DialogActions>
  </Modal>;
}

export function statusLabel(status: string) {
  return ({
    active: "启用",
    disabled: "停用",
    removed: "已移除",
    pending: "待接受",
    accepted: "已接受",
    expired: "已过期",
    revoked: "已撤回"
  } as Record<string, string>)[status] ?? "";
}

export function formatDate(value: string) {
  return new Date(value).toLocaleString("zh-CN", {hour12: false});
}
