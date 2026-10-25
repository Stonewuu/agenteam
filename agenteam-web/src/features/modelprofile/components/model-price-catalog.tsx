"use client";

import {useState} from "react";
import {Dialog, DialogCancel, useDialogControl} from "@/components/ui/dialog";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Select} from "@/components/ui/select";
import {Field} from "@/components/ui/field";
import {QueryState, Pagination} from "@/components/ui/query-state";
import {IconSearch, IconPlus, IconBook2} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {PriceConfigurationDetails} from "./price-configuration-details";
import {priceMeters, type CatalogPrice, type PriceCatalog, type PriceVersion} from "../types/model-pricing";
import type {ManagedModel} from "../types/model-management";
import ui from "@/components/ui/surface.module.css";
import styles from "./model-pricing.module.css";

export function ModelPriceCatalog({enterpriseId, model, onSaved}: {
  enterpriseId: string; model: ManagedModel; onSaved: (version: PriceVersion) => void;
}) {
  const t = useT();
  const [choosing, setChoosing] = useState(false);
  const [selected, setSelected] = useState<CatalogPrice | null>(null);
  const path = organizationPath(enterpriseId, `/models/${encodeURIComponent(model.id)}/price-versions`);
  const query = useApiQuery<PriceCatalog>(`${path}/catalog?suggested=true`);
  const match = query.data?.items.length === 1 ? query.data.items[0] : null;
  return <><section className={styles.catalogSection} aria-label={t("models.dev 价格")}>
    <div className={styles.toolbar}><div className={styles.ruleCopy}><strong>{t("从 models.dev 配置价格")}</strong>
      <p>{t("使用公开单价生成草稿，再确认发布。")}</p></div>
      <Button className={ui.button} onClick={() => setChoosing(true)}><IconSearch size={17}/>{t("搜索并选择价格")}</Button></div>
    <QueryState {...query} hasData={Boolean(query.data)} empty={t("未匹配到价格，可搜索其他模型。")}
      loadingContent={<p className={styles.hint} role="status">{t("正在查找模型价格…")}</p>}
      emptyContent={<p className={styles.hint}>{t("未匹配到价格，可搜索其他模型。")}</p>}>
      {match ? <div className={styles.stack}>
        <CatalogPriceSummary value={match}/>
        <CatalogNotice value={query.data}/>
        <div className={styles.actions}><Button className={ui.primary} onClick={() => setSelected(match)}>
          <IconPlus size={17}/>{t("一键配置价格")}</Button></div>
      </div> : <p className={styles.hint}>{query.data?.items.length ? t("找到多个来源价格，请搜索并选择适用的一项。")
        : t("未匹配到价格，可搜索其他模型。")}</p>}
    </QueryState>
  </section>
    {choosing && <CatalogSearchDialog path={path} model={model} initialProviders={query.data?.providers ?? []}
      onClose={() => setChoosing(false)} onSaved={(value) => {
      setChoosing(false);
      onSaved(value);
    }}/>}
    {selected && <CatalogImportDialog path={path} modelName={model.name} price={selected} stale={query.data?.stale ?? false}
      onClose={() => setSelected(null)} onSaved={(value) => {
        setSelected(null);
        onSaved(value);
      }}/>}</>;
}

function CatalogSearchDialog({path, model, initialProviders, onClose, onSaved}: {
  path: string; model: ManagedModel; initialProviders: PriceCatalog["providers"];
  onClose: () => void; onSaved: (version: PriceVersion) => void;
}) {
  const t = useT();
  const [search, setSearch] = useState(model.modelName);
  const [offset, setOffset] = useState(0);
  const [selected, setSelected] = useState<CatalogPrice | null>(null);
  const [provider, setProvider] = useState<PriceCatalog["providers"][number] | null>(null);
  const query = useApiQuery<PriceCatalog>(`${path}/catalog?query=${encodeURIComponent(search)}&provider=${encodeURIComponent(provider?.id ?? "")}&offset=${offset}`, 0, 300);
  const available = query.data?.providers ?? initialProviders;
  const providers = provider && !available.some((item) => item.id === provider.id) ? [provider, ...available] : available;
  return <><Dialog title={t("选择 models.dev 价格")} icon={<IconBook2 size={21}/>} wide onClose={onClose}
    footer={<DialogCancel className={ui.button}>{t("关闭")}</DialogCancel>}>
    <div className={styles.stack}>
      <p>{t("为 {0} 选择价格", [model.name])}</p>
      <div className={styles.columns}><Field label={t("搜索模型或服务商")}><Input className={ui.input} type="search" value={search} maxLength={100}
        placeholder={t("输入名称、模型编码或服务商，支持模糊搜索")}
        onChange={(event) => {
          setSearch(event.target.value);
          setOffset(0);
        }}/></Field>
        <Field label={t("价格服务商")}><Select className={ui.select} value={provider?.id ?? ""} onChange={(event) => {
          setProvider(providers.find((item) => item.id === event.target.value) ?? null);
          setOffset(0);
        }}><option value="">{t("全部服务商")}</option>{providers.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}</Select></Field></div>
      <QueryState {...query} hasData={Boolean(query.data?.items.length)} empty={t("没有找到可导入的价格，请更换关键词或清空搜索。") }>
        <CatalogNotice value={query.data}/>
        <div className={styles.stack}>{query.data?.items.map((price) => <article className={styles.catalogResult}
          key={`${price.providerId}:${price.modelId}`}>
          <CatalogPriceSummary value={price}/>
          <div className={styles.actions}><Button className={ui.button} onClick={() => setSelected(price)}>
            {t("查看并配置")}</Button></div>
        </article>)}</div>
      </QueryState>
      <Pagination previous={offset > 0 ? [offset] : []} hasMore={query.data?.hasMore} loading={query.loading}
        back={() => setOffset(Math.max(0, offset - 20))} next={() => setOffset(offset + 20)}/>
    </div>
  </Dialog>{selected && <CatalogImportDialog path={path} modelName={model.name} price={selected} stale={query.data?.stale ?? false}
    onClose={() => setSelected(null)} onSaved={onSaved}/>}</>;
}

function CatalogImportDialog({path, modelName, price, stale, onClose, onSaved}: {
  path: string; modelName: string; price: CatalogPrice; stale: boolean;
  onClose: () => void; onSaved: (version: PriceVersion) => void;
}) {
  const t = useT();
  const action = useFormAction();
  const dialog = useDialogControl();
  return <Dialog title={t("为 {0} 配置价格", [modelName])} wide onClose={onClose} busy={action.busy} dialogRef={dialog.ref}
    footer={<><DialogCancel className={ui.button} disabled={action.busy}>{t("取消")}</DialogCancel>
      <Button className={ui.primary} disabled={action.busy} onClick={() => void action.execute(async () => {
        const value = await action.mutation.run<PriceVersion>(path, {method: "POST", body: {configuration: price.configuration}});
        dialog.close(() => onSaved(value));
      }, "")}><IconPlus size={17}/>{action.busy ? t("正在保存…") : t("生成价格草稿")}</Button></>}>
    <div className={styles.stack}>
      <CatalogPriceSummary value={price}/>
      <p className={styles.hint}>{t("这些是所选服务商的公开美元价格，可能与实际合同不同。草稿可以修改，发布后才用于新请求。")}</p>
      {stale && <p className={styles.hint}>{t("暂时未能更新目录，请核对下方价格及来源时间。")}</p>}
      {price.configuration.sourceCheckedAt && <p className={styles.hint}>{t("来源获取时间：{0}", [new Date(price.configuration.sourceCheckedAt).toLocaleString(t.formatLocale)])}</p>}
      {price.notices.map((notice) => <p className={styles.hint} key={notice}>{t(notice)}</p>)}
      <PriceConfigurationDetails value={price.configuration}/>
      <MutationFeedback action={action}/>
    </div>
  </Dialog>;
}

function CatalogPriceSummary({value}: {value: CatalogPrice}) {
  const t = useT();
  const rates = value.configuration.rules.find((rule) => rule.defaultRule)?.rates ?? [];
  return <div className={styles.catalogCopy}>
    <div className={styles.ruleCopy}><strong>{value.modelName}</strong>
      <p>{value.providerName} · {value.modelId}</p></div>
    <dl className={styles.catalogRates}>{priceMeters.filter((meter) => meter.value !== "cache_write").map((meter) =>
      <div key={meter.value}><dt>{t(meter.label)}</dt><dd>{rates.find((rate) => rate.meter === meter.value)?.pricePerMillion ?? t("未提供")}</dd></div>)}</dl>
    <p className={styles.hint}>{t("USD / 每百万 Token")}
      {value.configuration.rules.length > 1 && ` · ${t("另有 {0} 个按输入长度划分的价格区间", [value.configuration.rules.length - 1])}`}</p>
  </div>;
}

function CatalogNotice({value}: {value: PriceCatalog | null}) {
  const t = useT();
  if (!value) {
    return null;
  }
  return <p className={styles.hint}>{value.stale ? t("暂时未能更新目录，请核对下方价格及来源时间。") : null}
    {t("来源获取时间：{0}", [new Date(value.fetchedAt).toLocaleString(t.formatLocale)])}</p>;
}
