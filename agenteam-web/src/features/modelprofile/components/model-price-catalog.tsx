"use client";

import {useState} from "react";
import {Dialog, DialogCancel, useDialogControl} from "@/components/ui/dialog";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Select} from "@/components/ui/select";
import {Field} from "@/components/ui/field";
import {DetailStatus} from "@/components/ui/detail-section";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {SearchInput} from "@/components/ui/search-input";
import {SearchableSelect, type SearchableSelectOption} from "@/components/ui/searchable-select";
import {QueryState, Pagination} from "@/components/ui/query-state";
import {IconSearch, IconPlus, IconBook2, IconRefresh, IconWallet} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {PriceConfigurationDetails} from "./price-configuration-details";
import {convertPriceConfiguration, isValidExchangeRate} from "../lib/price-conversion";
import {
  priceMeters,
  type CatalogPrice,
  type ExchangeRateQuote,
  type PriceCatalog,
  type PriceConfiguration,
  type PriceVersion
} from "../types/model-pricing";
import type {ManagedModel} from "../types/model-management";
import ui from "@/components/ui/surface.module.css";
import styles from "./model-pricing.module.css";

function currencyBadgeTone(currency: string): "accent" | "neutral" {
  if (currency === "CNY") {
    return "accent";
  }
  return "neutral";
}

export function ModelPriceCatalog({enterpriseId, model, onSaved}: {
  enterpriseId: string; model: ManagedModel; onSaved: (version: PriceVersion) => void;
}) {
  const t = useT();
  const [choosing, setChoosing] = useState(false);
  const [selected, setSelected] = useState<CatalogPrice | null>(null);
  const path = organizationPath(enterpriseId, `/models/${encodeURIComponent(model.id)}/price-versions`);
  const query = useApiQuery<PriceCatalog>(`${path}/catalog?suggested=true`);
  const match = query.data?.items.length === 1 ? query.data.items[0] : null;
  return <><section className={styles.catalogSection} aria-label={t("公开价格目录")}>
    <div className={styles.toolbar}>
      <div className={styles.ruleCopy}>
        <strong>{t("从公开目录配置价格")}</strong>
        <p>{t("支持人民币（CNY）与美元（USD）公开单价，也可按实时汇率换算成其他币种生成草稿。")}</p>
      </div>
      <Button type="button" className={ui.button} onClick={() => setChoosing(true)}>
        <IconSearch size={17}/>{t("搜索并选择价格")}
      </Button>
    </div>
    <QueryState
      {...query}
      hasData={Boolean(query.data)}
      empty={t("未匹配到价格，可搜索其他模型。")}
      loadingContent={<p className={styles.hint} role="status">{t("正在查找模型价格…")}</p>}
      emptyContent={<p className={styles.hint}>{t("未匹配到价格，可搜索其他模型。")}</p>}
    >
      {match ? <div className={styles.stack}>
        <CatalogPriceSummary value={match}/>
        <CatalogNotice value={query.data}/>
        <div className={styles.actions}>
          <Button type="button" className={ui.primary} onClick={() => setSelected(match)}>
            <IconPlus size={17}/>{t("一键配置价格")}
          </Button>
        </div>
      </div> : <p className={styles.hint}>
        {query.data?.items.length
          ? t("找到多个来源价格，请搜索并选择适用的一项。")
          : t("未匹配到价格，可搜索其他模型。")}
      </p>}
    </QueryState>
  </section>
    {choosing && <CatalogSearchDialog
      path={path}
      model={model}
      initialProviders={query.data?.providers ?? []}
      initialCurrencies={query.data?.currencies ?? []}
      onClose={() => setChoosing(false)}
      onSaved={(value) => {
        setChoosing(false);
        onSaved(value);
      }}
    />}
    {selected && <CatalogImportDialog
      path={path}
      modelName={model.name}
      price={selected}
      stale={query.data?.stale ?? false}
      onClose={() => setSelected(null)}
      onSaved={(value) => {
        setSelected(null);
        onSaved(value);
      }}
    />}</>;
}

function CatalogSearchDialog({path, model, initialProviders, initialCurrencies, onClose, onSaved}: {
  path: string;
  model: ManagedModel;
  initialProviders: PriceCatalog["providers"];
  initialCurrencies: string[];
  onClose: () => void;
  onSaved: (version: PriceVersion) => void;
}) {
  const t = useT();
  const [search, setSearch] = useState(model.modelName);
  const [currency, setCurrency] = useState("");
  const [offset, setOffset] = useState(0);
  const [selected, setSelected] = useState<CatalogPrice | null>(null);
  const [provider, setProvider] = useState<PriceCatalog["providers"][number] | null>(null);
  const [providerOpen, setProviderOpen] = useState(false);
  const [providerQuery, setProviderQuery] = useState("");

  const catalogUrl = `${path}/catalog?query=${encodeURIComponent(search)}&provider=${encodeURIComponent(provider?.id ?? "")}&currency=${encodeURIComponent(currency)}&offset=${offset}`;
  const query = useApiQuery<PriceCatalog>(catalogUrl, 0, 300);

  const availableCurrencies = query.data?.currencies.length ? query.data.currencies : initialCurrencies;
  const availableProviders = query.data?.providers ?? initialProviders;
  const providers = provider && !availableProviders.some((item) => item.id === provider.id)
    ? [provider, ...availableProviders]
    : availableProviders;

  const normalizedProviderQuery = providerQuery.trim().toLocaleLowerCase();
  const providerOptions: SearchableSelectOption[] = providers
    .filter((item) => !normalizedProviderQuery || item.name.toLocaleLowerCase().includes(normalizedProviderQuery))
    .map((item) => ({value: item.id, label: item.name}));
  const selectedProviderOption: SearchableSelectOption | null = provider
    ? {value: provider.id, label: provider.name}
    : null;

  return <><Dialog
    title={t("选择公开价格目录")}
    icon={<IconBook2 size={21}/>}
    wide
    onClose={onClose}
    footer={<DialogCancel className={ui.button}>{t("关闭")}</DialogCancel>}
  >
    <div className={styles.stack}>
      <p>{t("为 {0} 选择价格", [model.name])}</p>
      <div className={styles.catalogFilters}>
        <Field label={t("搜索模型或服务商")}>
          <SearchInput
            value={search}
            maxLength={100}
            aria-label={t("搜索模型或服务商")}
            placeholder={t("输入名称、模型编码或服务商，支持模糊搜索")}
            onClear={() => {
              setSearch("");
              setOffset(0);
            }}
            onChange={(event) => {
              setSearch(event.target.value);
              setOffset(0);
            }}
          />
        </Field>
        {availableCurrencies.length > 0 && (
          <Field label={t("币种")}>
            <Select
              className={ui.select}
              value={currency}
              onChange={(event) => {
                setCurrency(event.target.value);
                setProvider(null);
                setOffset(0);
              }}
            >
              <option value="">{t("全部币种")}</option>
              {availableCurrencies.map((code) => (
                <option value={code} key={code}>
                  {code === "CNY" ? t("CNY · 人民币") : code === "USD" ? t("USD · 美元") : code}
                </option>
              ))}
            </Select>
          </Field>
        )}
        <Field label={t("价格服务商")}>
          <SearchableSelect
            options={providerOptions}
            value={selectedProviderOption}
            onChange={(option) => {
              setProvider(option ? {id: option.value, name: option.label} : null);
              setOffset(0);
            }}
            query={providerQuery}
            onQueryChange={setProviderQuery}
            open={providerOpen}
            onOpenChange={setProviderOpen}
            resetLabel={t("全部服务商")}
            searchLabel={t("搜索价格服务商")}
            emptyLabel={t("没有匹配的服务商。")}
            loading={query.loading && providers.length === 0}
            error={query.error}
            onRetry={query.retry}
          />
        </Field>
      </div>
      <QueryState
        {...query}
        hasData={Boolean(query.data?.items.length)}
        empty={t("没有找到可导入的价格，请更换关键词或清空搜索。")}
      >
        <CatalogNotice value={query.data}/>
        <div className={styles.stack}>
          {query.data?.items.map((price) => (
            <article className={styles.catalogResult} key={`${price.sourceId}:${price.providerId}:${price.modelId}`}>
              <CatalogPriceSummary value={price}/>
              <div className={styles.actions}>
                <Button type="button" className={ui.button} onClick={() => setSelected(price)}>
                  {t("查看并配置")}
                </Button>
              </div>
            </article>
          ))}
        </div>
      </QueryState>
      <Pagination
        previous={offset > 0 ? [offset] : []}
        hasMore={query.data?.hasMore}
        loading={query.loading}
        back={() => setOffset(Math.max(0, offset - 20))}
        next={() => setOffset(offset + 20)}
      />
    </div>
  </Dialog>
    {selected && <CatalogImportDialog
      path={path}
      modelName={model.name}
      price={selected}
      stale={query.data?.stale ?? false}
      onClose={() => setSelected(null)}
      onSaved={onSaved}
    />}</>;
}

function CatalogImportDialog({path, modelName, price, stale, onClose, onSaved}: {
  path: string; modelName: string; price: CatalogPrice; stale: boolean;
  onClose: () => void; onSaved: (version: PriceVersion) => void;
}) {
  const t = useT();
  const action = useFormAction();
  const dialog = useDialogControl();
  const sourceCurrency = price.configuration.currency;
  const [targetCurrency, setTargetCurrency] = useState(sourceCurrency);
  const [rateRefresh, setRateRefresh] = useState(0);
  const [manualRates, setManualRates] = useState<Record<string, string>>({});

  const needsConversion = targetCurrency !== sourceCurrency;
  const ratePairKey = `${sourceCurrency}->${targetCurrency}`;
  const rateQuery = useApiQuery<ExchangeRateQuote>(
    needsConversion
      ? `${path}/exchange-rate?from=${encodeURIComponent(sourceCurrency)}&to=${encodeURIComponent(targetCurrency)}`
      : null,
    rateRefresh,
  );

  const hasManualRate = Object.hasOwn(manualRates, ratePairKey);
  const rateInput = hasManualRate ? manualRates[ratePairKey] : (rateQuery.data?.rate ?? "");

  const currencies = Array.from(
    new Set([sourceCurrency, "CNY", "USD", "EUR", "HKD", "JPY", "GBP", ...Intl.supportedValuesOf("currency")])
  );

  const conversionNote = needsConversion && isValidExchangeRate(rateInput)
    ? (!hasManualRate && rateQuery.data?.sourceName
      ? t("按参考汇率 1 {0} = {1} {2}（{3}{4}）换算自原 {0} 公开价。", [
        sourceCurrency,
        rateInput.trim(),
        targetCurrency,
        rateQuery.data.sourceName,
        rateQuery.data.effectiveDate ? ` · ${rateQuery.data.effectiveDate}` : "",
      ])
      : t("按汇率 1 {0} = {1} {2} 换算自原 {0} 公开价。", [sourceCurrency, rateInput.trim(), targetCurrency]))
    : undefined;

  const converted = needsConversion
    ? convertPriceConfiguration(price.configuration, targetCurrency, rateInput, conversionNote)
    : {configuration: price.configuration, overflow: false};

  const rateError = needsConversion
    ? (!rateInput.trim()
      ? (rateQuery.loading ? undefined : t("请填写换算汇率。"))
      : !isValidExchangeRate(rateInput)
        ? t("汇率须为大于 0 且不超过 1000000 的数字，最多 12 位小数。")
        : converted.overflow
          ? t("换算后的单价超过允许上限，请核对汇率。")
          : undefined)
    : undefined;

  const effectiveConfiguration: PriceConfiguration = converted.configuration ?? price.configuration;
  const canSubmit = !action.busy && (!needsConversion || Boolean(converted.configuration));

  return <Dialog
    title={t("为 {0} 配置价格", [modelName])}
    wide
    onClose={onClose}
    busy={action.busy}
    dialogRef={dialog.ref}
    footer={<>
      <DialogCancel className={ui.button} disabled={action.busy}>{t("取消")}</DialogCancel>
      <Button
        type="button"
        className={ui.primary}
        disabled={!canSubmit}
        onClick={() => void action.execute(async () => {
          const value = await action.mutation.run<PriceVersion>(path, {
            method: "POST",
            body: {configuration: effectiveConfiguration},
          });
          dialog.close(() => onSaved(value));
        }, "")}
      >
        <IconPlus size={17}/>{action.busy ? t("正在保存…") : t("生成价格草稿")}
      </Button>
    </>}
  >
    <div className={styles.stack}>
      <CatalogPriceSummary value={price} configurationOverride={effectiveConfiguration}/>

      <section className={styles.conversionCard} aria-label={t("导入币种与汇率换算")}>
        <div className={styles.columns}>
          <Field label={t("导入币种")} icon={<IconWallet size={16}/>}>
            <Select
              className={ui.select}
              value={targetCurrency}
              disabled={action.busy}
              onChange={(event) => {
                setTargetCurrency(event.target.value);
              }}
            >
              {currencies.map((code) => (
                <option value={code} key={code}>
                  {code === sourceCurrency ? t("{0}（原目录币种）", [code]) : code}
                </option>
              ))}
            </Select>
          </Field>
          {needsConversion && (
            <Field
              label={t("换算汇率（1 {0} = ? {1}）", [sourceCurrency, targetCurrency])}
              error={rateError}
            >
              <div className={styles.rateInputRow}>
                <Input
                  className={ui.input}
                  type="text"
                  inputMode="decimal"
                  value={rateInput}
                  disabled={action.busy}
                  placeholder={rateQuery.loading ? t("正在获取实时汇率…") : t("输入汇率，例如 7.2")}
                  onChange={(event) => {
                    setManualRates((previous) => ({...previous, [ratePairKey]: event.target.value}));
                  }}
                />
                <Button
                  type="button"
                  className={ui.button}
                  disabled={action.busy || rateQuery.fetching}
                  aria-label={t("刷新参考汇率")}
                  onClick={() => {
                    setManualRates((previous) => {
                      const next = {...previous};
                      delete next[ratePairKey];
                      return next;
                    });
                    setRateRefresh((value) => value + 1);
                  }}
                >
                  <IconRefresh size={16}/>{t("刷新汇率")}
                </Button>
              </div>
            </Field>
          )}
        </div>
        <AnimatedHeight preserveControlShadows>
          {needsConversion && (
            <p className={styles.hint}>
              {rateQuery.loading
                ? t("正在获取实时参考汇率…")
                : rateQuery.error
                  ? t("暂时无法获取实时汇率，请手动填写换算汇率。")
                  : rateQuery.data
                    ? t("参考汇率：1 {0} = {1} {2} · 来源：{3}{4}{5}", [
                      sourceCurrency,
                      rateQuery.data.rate,
                      targetCurrency,
                      rateQuery.data.sourceName,
                      rateQuery.data.effectiveDate ? `（${rateQuery.data.effectiveDate}）` : "",
                      rateQuery.data.stale ? t(" · 当前为缓存汇率，请核对") : "",
                    ])
                    : null}
            </p>
          )}
        </AnimatedHeight>
      </section>

      <p className={styles.hint}>
        {needsConversion && converted.configuration
          ? t("单价已按 1 {0} = {1} {2} 换算为 {2} 草稿，保存后仍可继续修改，发布后才用于新请求。", [
            sourceCurrency,
            rateInput.trim(),
            targetCurrency,
          ])
          : t("这些是所选服务商的公开 {0} 价格，可能与实际合同不同。草稿可以修改，发布后才用于新请求。", [sourceCurrency])}
      </p>
      {stale && <p className={styles.hint}>{t("暂时未能更新目录，请核对下方价格及来源时间。")}</p>}
      {price.configuration.sourceCheckedAt && (
        <p className={styles.hint}>
          {t("来源获取时间：{0}", [new Date(price.configuration.sourceCheckedAt).toLocaleString(t.formatLocale)])}
        </p>
      )}
      {price.notices.map((notice) => <p className={styles.hint} key={notice}>{t(notice)}</p>)}
      <PriceConfigurationDetails value={effectiveConfiguration}/>
      <MutationFeedback action={action}/>
    </div>
  </Dialog>;
}

function CatalogPriceSummary({value, configurationOverride}: {
  value: CatalogPrice; configurationOverride?: PriceConfiguration;
}) {
  const t = useT();
  const configuration = configurationOverride ?? value.configuration;
  const rates = configuration.rules.find((rule) => rule.defaultRule)?.rates ?? [];
  const extraRules = configuration.rules.filter((rule) => !rule.defaultRule);
  const hasTimeRule = extraRules.some((rule) => rule.times.length > 0);
  const hasContextRule = extraRules.some((rule) => rule.contextMinTokens > 0);
  return <div className={styles.catalogCopy}>
    <div className={styles.catalogHeaderRow}>
      <div className={styles.ruleCopy}>
        <strong>{value.modelName}</strong>
        <p>{value.providerName} · {value.modelId}</p>
      </div>
      <div className={styles.catalogBadges}>
        <DetailStatus tone={currencyBadgeTone(configuration.currency)}>{configuration.currency}</DetailStatus>
        {value.sourceName && <span className={styles.sourceTag}>{value.sourceName}</span>}
      </div>
    </div>
    <dl className={styles.catalogRates}>
      {priceMeters.filter((meter) => meter.value !== "cache_write").map((meter) => (
        <div key={meter.value}>
          <dt>{t(meter.label)}</dt>
          <dd>{rates.find((rate) => rate.meter === meter.value)?.pricePerMillion ?? t("未提供")}</dd>
        </div>
      ))}
    </dl>
    <p className={styles.hint}>
      {t("{0} / 每百万 Token", [configuration.currency])}
      {extraRules.length > 0 && ` · ${
        hasTimeRule && !hasContextRule
          ? t("另有 {0} 个分时价格规则", [extraRules.length])
          : hasContextRule && !hasTimeRule
            ? t("另有 {0} 个按输入长度划分的价格区间", [extraRules.length])
            : t("另有 {0} 条进阶价格规则", [extraRules.length])
      }`}
    </p>
  </div>;
}

function CatalogNotice({value}: {value: PriceCatalog | null}) {
  const t = useT();
  if (!value) {
    return null;
  }
  return <p className={styles.hint}>
    {value.stale ? t("暂时未能更新全部目录，请核对下方价格及来源时间。") : null}
    {t("来源获取时间：{0}", [new Date(value.fetchedAt).toLocaleString(t.formatLocale)])}
  </p>;
}
