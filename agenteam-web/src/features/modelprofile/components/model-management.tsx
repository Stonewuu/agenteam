"use client";

import {useEffect, useRef, useState} from "react";
import {useT} from "@/lib/i18n/locale-provider";
import {Button} from "@/components/ui/button";
import {PageHeader} from "@/components/ui/page-header";
import {DetailStatus} from "@/components/ui/detail-section";
import {Dialog, DialogActions, DialogCancel, useDialogControl} from "@/components/ui/dialog";
import {QueryState} from "@/components/ui/query-state";
import {SearchInput} from "@/components/ui/search-input";
import {SearchableSelect, type SearchableSelectOption} from "@/components/ui/searchable-select";
import {Collapsible, CollapsibleContent} from "@/components/ui/shadcn/collapsible";
import {toast} from "@/components/ui/toast";
import {
  IconCheck,
  IconChevronDown,
  IconCopy,
  IconDots,
  IconKey,
  IconPencil,
  IconPlayerPause,
  IconPlayerPlay,
  IconPlug,
  IconPlus,
  IconRefresh,
  IconRobot,
  IconStack,
  IconTrash,
  IconWallet
} from "@/components/ui/icons";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger
} from "@/components/ui/shadcn/dropdown-menu";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {ModelProviderDialog} from "./model-provider-dialog";
import {ModelProfileDialog} from "./model-profile-dialog";
import {ModelPriceDialog} from "./model-price-dialog";
import {modelProtocols} from "../lib/model-protocol";
import {formatTokenLength} from "../lib/model-token-length";
import type {ManagedModel, ModelProtocol, ModelProvider} from "../types/model-management";
import ui from "@/components/ui/surface.module.css";
import styles from "./model-management.module.css";

type DeleteTarget = { kind: "provider"; value: ModelProvider } | { kind: "model"; value: ManagedModel };
type ModelDialogTarget = { kind: "new"; providerId: string } | { kind: "edit"; model: ManagedModel };

// 提供方超过 5 个时，默认仅展开前 3 个，避免列表过长影响定位。
const AUTO_COLLAPSE_PROVIDER_THRESHOLD = 5;
const DEFAULT_VISIBLE_PROVIDER_COUNT = 3;
// 总览模式下单个提供方模型超过 8 个时先展示前 6 个，其余按需展开。
const MODEL_PREVIEW_THRESHOLD = 8;
const MODEL_PREVIEW_COUNT = 6;

function protocolLabel(protocol: ModelProtocol) {
  return modelProtocols.find((item) => item.value === protocol)?.label ?? protocol;
}

export function ModelManagement({enterpriseId, canManage}: { enterpriseId: string; canManage: boolean }) {
  const uiText = useT();
  const [refresh, setRefresh] = useState(0);
  const [providerDialog, setProviderDialog] = useState<ModelProvider | "new" | null>(null);
  const [modelDialog, setModelDialog] = useState<ModelDialogTarget | null>(null);
  const [deleting, setDeleting] = useState<DeleteTarget | null>(null);
  const [pricing, setPricing] = useState<ManagedModel | null>(null);
  const [selectedProviderId, setSelectedProviderId] = useState("");
  const [providerSelectOpen, setProviderSelectOpen] = useState(false);
  const [providerSelectQuery, setProviderSelectQuery] = useState("");
  const [enabledOnly, setEnabledOnly] = useState(false);
  const [query, setQuery] = useState("");
  const [expandedOverrides, setExpandedOverrides] = useState<Record<string, boolean>>({});
  const [expandedModelLists, setExpandedModelLists] = useState<Record<string, boolean>>({});
  const [copiedKey, setCopiedKey] = useState<string | null>(null);
  const copyTimer = useRef<number | undefined>(undefined);
  const statusAction = useFormAction();

  const providers = useApiQuery<ModelProvider[]>(organizationPath(enterpriseId, "/model-providers"), refresh);
  const models = useApiQuery<ManagedModel[]>(organizationPath(enterpriseId, "/models"), refresh);

  const providerList = providers.data ?? [];
  const modelList = models.data ?? [];
  const activeProviderId = selectedProviderId && providerList.some((item) => item.id === selectedProviderId)
    ? selectedProviderId
    : "";
  const hasInactive = providerList.some((item) => !item.enabled) || modelList.some((item) => !item.enabled);
  const activeEnabledOnly = enabledOnly && hasInactive;
  const normalizedQuery = query.trim().toLocaleLowerCase();

  const isGroupDefaultExpanded = (index: number) => {
    if (providerList.length <= AUTO_COLLAPSE_PROVIDER_THRESHOLD) {
      return true;
    }
    return index < DEFAULT_VISIBLE_PROVIDER_COUNT;
  };

  const isGroupExpanded = (providerId: string, index: number) => {
    if (normalizedQuery || activeProviderId) {
      return true;
    }
    if (Object.hasOwn(expandedOverrides, providerId)) {
      return expandedOverrides[providerId];
    }
    return isGroupDefaultExpanded(index);
  };

  const groups = providerList
    .filter((item) => !activeProviderId || item.id === activeProviderId)
    .filter((item) => !activeEnabledOnly || item.enabled)
    .map((item) => {
      const ownedModels = modelList
        .filter((entry) => entry.providerId === item.id)
        .filter((entry) => !activeEnabledOnly || (entry.enabled && entry.providerEnabled));
      if (!normalizedQuery) {
        return {provider: item, models: ownedModels, matchedProvider: true};
      }
      const providerHaystack = `${item.name} ${item.baseUrl} ${protocolLabel(item.protocol)}`.toLocaleLowerCase();
      const matchedProvider = providerHaystack.includes(normalizedQuery);
      const matchedModels = ownedModels.filter((entry) =>
        `${entry.name} ${entry.modelName}`.toLocaleLowerCase().includes(normalizedQuery)
      );
      return {
        provider: item,
        models: matchedProvider && matchedModels.length === 0 ? ownedModels : matchedModels,
        matchedProvider,
      };
    })
    .filter((group) => !normalizedQuery || group.matchedProvider || group.models.length > 0);

  const refreshing = Boolean(providers.data || models.data) && (providers.fetching || models.fetching);
  const allCollapsed = groups.length > 1 && groups.every((group, index) => !isGroupExpanded(group.provider.id, index));

  const formatProviderOption = (item: ModelProvider): SearchableSelectOption => ({
    value: item.id,
    label: `${item.name}（${item.modelCount}）${item.enabled ? "" : uiText(" · 已停用")}`,
  });

  const normalizedProviderQuery = providerSelectQuery.trim().toLocaleLowerCase();
  const providerSelectOptions = providerList
    .filter((item) => {
      if (!normalizedProviderQuery) {
        return true;
      }
      return `${item.name} ${item.baseUrl} ${protocolLabel(item.protocol)}`
        .toLocaleLowerCase()
        .includes(normalizedProviderQuery);
    })
    .map(formatProviderOption);

  const selectedProviderOption = activeProviderId
    ? (() => {
      const target = providerList.find((item) => item.id === activeProviderId);
      return target ? formatProviderOption(target) : null;
    })()
    : null;

  useEffect(() => {
    return () => {
      window.clearTimeout(copyTimer.current);
    };
  }, []);

  const saved = () => {
    setProviderDialog(null);
    setModelDialog(null);
    setDeleting(null);
    setRefresh((value) => value + 1);
  };

  function toggleGroup(providerId: string, index: number) {
    const currentlyExpanded = isGroupExpanded(providerId, index);
    setExpandedOverrides((previous) => ({
      ...previous,
      [providerId]: !currentlyExpanded,
    }));
  }

  function toggleAllGroups() {
    const nextExpanded = allCollapsed;
    const next: Record<string, boolean> = {};
    for (const group of groups) {
      next[group.provider.id] = nextExpanded;
    }
    setExpandedOverrides(next);
  }

  function toggleModelListExpansion(providerId: string) {
    setExpandedModelLists((previous) => ({
      ...previous,
      [providerId]: !previous[providerId],
    }));
  }

  async function copyText(key: string, text: string) {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedKey(key);
      window.clearTimeout(copyTimer.current);
      copyTimer.current = window.setTimeout(() => {
        setCopiedKey((previous) => (previous === key ? null : previous));
      }, 2000);
    } catch (error) {
      console.warn("Failed to copy model configuration value to clipboard", error);
      toast.error(uiText("复制失败，请手动复制。"));
    }
  }

  function toggleProvider(target: ModelProvider) {
    const nextEnabled = !target.enabled;
    void statusAction.execute(async () => {
      await statusAction.mutation.run(organizationPath(enterpriseId, `/model-providers/${encodeURIComponent(target.id)}`), {
        method: "PUT",
        revision: target.revision,
        body: {
          name: target.name,
          protocol: target.protocol,
          baseUrl: target.baseUrl,
          apiKey: null,
          enabled: nextEnabled,
        },
      });
      setRefresh((value) => value + 1);
    }, nextEnabled ? uiText("提供方已启用。") : uiText("提供方已停用。"));
  }

  function toggleModel(target: ManagedModel) {
    const nextEnabled = !target.enabled;
    void statusAction.execute(async () => {
      await statusAction.mutation.run(organizationPath(enterpriseId, `/models/${encodeURIComponent(target.id)}`), {
        method: "PUT",
        revision: target.revision,
        body: {
          providerId: target.providerId,
          name: target.name,
          modelName: target.modelName,
          capabilities: target.capabilities,
          enabled: nextEnabled,
        },
      });
      setRefresh((value) => value + 1);
    }, nextEnabled ? uiText("模型已启用。") : uiText("模型已停用。"));
  }

  const loadError = providers.error || models.error;
  const hasFilters = Boolean(normalizedQuery || activeProviderId || activeEnabledOnly);

  return <div className={styles.sections}>
    <PageHeader
      title={uiText("模型配置")}
      description={canManage ? uiText("配置模型服务，设置数字员工可使用的模型。") : uiText("查看已配置的模型服务和模型。")}
      actions={
        <div className={styles.headerActions}>
          <Button
            type="button"
            className={ui.button}
            disabled={refreshing}
            aria-label={uiText("刷新模型配置")}
            onClick={() => setRefresh((value) => value + 1)}
          >
            <IconRefresh size={16} className={refreshing ? styles.spinning : undefined}/>
            {uiText("刷新")}
          </Button>
          {canManage && (
            <Button type="button" className={ui.primary} onClick={() => setProviderDialog("new")}>
              <IconPlus size={16}/>
              {uiText("新增提供方")}
            </Button>
          )}
        </div>
      }
    />

    {providerList.length > 0 && (
      <div className={styles.controlsBar}>
        <div className={styles.controlsLeft}>
          <SearchInput
            className={styles.globalSearch}
            aria-label={uiText("搜索模型")}
            placeholder={uiText("搜索提供方、模型名称或标识…")}
            value={query}
            onClear={() => setQuery("")}
            onChange={(event) => setQuery(event.target.value)}
          />
          {providerList.length > 1 && (
            <div className={styles.providerFilter}>
              <SearchableSelect
                options={providerSelectOptions}
                value={selectedProviderOption}
                onChange={(option) => setSelectedProviderId(option?.value ?? "")}
                query={providerSelectQuery}
                onQueryChange={setProviderSelectQuery}
                open={providerSelectOpen}
                onOpenChange={setProviderSelectOpen}
                resetLabel={uiText("全部提供方（{0}）", [providerList.length])}
                searchLabel={uiText("搜索模型提供方")}
                emptyLabel={uiText("没有匹配的提供方。")}
                loading={providers.loading && providerList.length === 0}
                error={providers.error}
                onRetry={providers.retry}
              />
            </div>
          )}
          {hasInactive && (
            <Button
              type="button"
              className={styles.filterChip}
              aria-pressed={activeEnabledOnly}
              onClick={() => setEnabledOnly((value) => !value)}
            >
              {uiText("仅看已启用")}
            </Button>
          )}
        </div>
        {!normalizedQuery && !activeProviderId && groups.length > 1 && (
          <Button type="button" className={styles.collapseAllButton} onClick={toggleAllGroups}>
            {allCollapsed ? uiText("全部展开") : uiText("全部收起")}
          </Button>
        )}
      </div>
    )}

    <QueryState
      loading={providers.loading && providerList.length === 0}
      error={loadError}
      retry={() => {
        providers.retry();
        models.retry();
      }}
      hasData={groups.length > 0}
      empty={hasFilters ? uiText("没有匹配的提供方或模型。") : canManage ? uiText("添加提供方后，即可配置模型。") : uiText("暂无模型配置。")}
      emptyContent={
        <div className={ui.empty} data-query-empty>
          <span className={ui.emptyIcon} data-icon-surface>
            <IconPlug size={27} variant="Bulk"/>
          </span>
          <p>
            {hasFilters
              ? uiText("没有匹配的提供方或模型。")
              : canManage
                ? uiText("添加提供方后，即可配置模型。")
                : uiText("暂无模型配置。")}
          </p>
          {hasFilters ? (
            <Button
              type="button"
              className={ui.button}
              onClick={() => {
                setQuery("");
                setSelectedProviderId("");
                setEnabledOnly(false);
              }}
            >
              {uiText("清除搜索")}
            </Button>
          ) : canManage ? (
            <Button type="button" className={ui.primary} onClick={() => setProviderDialog("new")}>
              <IconPlus size={16}/>
              {uiText("新增提供方")}
            </Button>
          ) : null}
        </div>
      }
    >
      <div className={styles.providerGroups}>
        {groups.map(({provider: item, models: groupModels}, groupIndex) => {
          const expanded = isGroupExpanded(item.id, groupIndex);
          const urlCopied = copiedKey === `provider:${item.id}`;
          const shouldTruncateModels = !normalizedQuery
            && !activeProviderId
            && groupModels.length > MODEL_PREVIEW_THRESHOLD
            && !expandedModelLists[item.id];
          const displayedModels = shouldTruncateModels ? groupModels.slice(0, MODEL_PREVIEW_COUNT) : groupModels;
          const hiddenModelCount = groupModels.length - displayedModels.length;

          return (
            <section
              key={item.id}
              className={styles.providerGroup}
              aria-label={uiText("{0}的服务连接", [item.name])}
            >
              <Collapsible open={expanded} onOpenChange={() => toggleGroup(item.id, groupIndex)}>
                <header className={styles.groupHeader} data-expanded={expanded || undefined}>
                  <div className={styles.groupHeaderMain}>
                    {!normalizedQuery && !activeProviderId && (
                      <Button
                        type="button"
                        className={styles.collapseButton}
                        aria-expanded={expanded}
                        aria-label={expanded ? uiText("收起{0}的模型列表", [item.name]) : uiText("展开{0}的模型列表", [item.name])}
                        onClick={() => toggleGroup(item.id, groupIndex)}
                      >
                        <IconChevronDown size={16} className={styles.chevron} data-expanded={expanded || undefined}/>
                      </Button>
                    )}
                    <span
                      className={styles.providerIcon}
                      data-icon-surface
                      data-disabled-item={!item.enabled || undefined}
                    >
                      <IconPlug size={20}/>
                    </span>
                    <div className={styles.providerIdentity}>
                      <div className={styles.providerTitleRow}>
                        <h2>{item.name}</h2>
                        <DetailStatus tone={item.enabled ? "success" : "neutral"}>
                          {item.enabled ? uiText("已启用") : uiText("已停用")}
                        </DetailStatus>
                        <span className={styles.countBadge}>
                          {item.modelCount}{uiText(" 个模型")}
                        </span>
                      </div>
                      <div className={styles.providerDetails}>
                        <span className={styles.urlGroup}>
                          <span className={styles.providerUrl} title={item.baseUrl}>{item.baseUrl}</span>
                          <Button
                            type="button"
                            className={styles.copyButton}
                            data-copied={urlCopied || undefined}
                            aria-label={urlCopied ? uiText("已复制") : uiText("复制服务地址")}
                            title={urlCopied ? uiText("已复制") : uiText("复制服务地址")}
                            onClick={() => void copyText(`provider:${item.id}`, item.baseUrl)}
                          >
                            {urlCopied ? <IconCheck size={14}/> : <IconCopy size={14}/>}
                          </Button>
                        </span>
                        <div className={styles.providerMetaItems}>
                          <span className={styles.metaItem}>
                            <IconStack size={14}/>
                            <span>{protocolLabel(item.protocol)}</span>
                          </span>
                          <span className={styles.metaItem} data-tone={item.keyConfigured ? undefined : "warning"}>
                            <IconKey size={14}/>
                            <span>{item.keyConfigured ? uiText("已配置访问密钥") : uiText("未配置访问密钥")}</span>
                          </span>
                        </div>
                      </div>
                    </div>
                  </div>
                  {canManage && (
                    <div className={styles.groupActions}>
                      <Button
                        type="button"
                        className={styles.addModelButton}
                        onClick={() => setModelDialog({kind: "new", providerId: item.id})}
                      >
                        <IconPlus size={15}/>
                        {uiText("新增模型")}
                      </Button>
                      <Button
                        type="button"
                        className={styles.headerActionButton}
                        onClick={() => setProviderDialog(item)}
                      >
                        <IconPencil size={15}/>
                        {uiText("编辑连接")}
                      </Button>
                      <DropdownMenu>
                        <DropdownMenuTrigger
                          render={
                            <Button
                              type="button"
                              className={`icon-button ${styles.moreTrigger}`}
                              aria-label={uiText("{0}的更多操作", [item.name])}
                            >
                              <IconDots size={17}/>
                            </Button>
                          }
                        />
                        <DropdownMenuContent align="end" className="agenteam-menu agenteam-popup">
                          <DropdownMenuItem disabled={statusAction.busy} onClick={() => toggleProvider(item)}>
                            {item.enabled ? <IconPlayerPause size={16}/> : <IconPlayerPlay size={16}/>}
                            {item.enabled ? uiText("停用提供方") : uiText("启用提供方")}
                          </DropdownMenuItem>
                          <DropdownMenuSeparator/>
                          <DropdownMenuItem
                            variant="destructive"
                            disabled={item.modelCount > 0}
                            onClick={() => setDeleting({kind: "provider", value: item})}
                          >
                            <IconTrash size={16}/>
                            <span className={styles.menuItemCopy}>
                              {uiText("删除提供方")}
                              {item.modelCount > 0 && <small>{uiText("请先删除此提供方下的模型")}</small>}
                            </span>
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </div>
                  )}
                </header>

                <CollapsibleContent className="agenteam-disclosure-content" keepMounted inert={!expanded}>
                  <div className={styles.groupBody}>
                    {models.loading && !models.data ? (
                      <div className={styles.emptyModels}>
                        <p role="status">{uiText("正在加载…")}</p>
                      </div>
                    ) : groupModels.length === 0 ? (
                      <div className={styles.emptyModels}>
                        <p>
                          {hasFilters
                            ? uiText("没有匹配的模型。")
                            : canManage
                              ? uiText("此提供方下还没有模型，添加后即可在智能体中选择。")
                              : uiText("此提供方下还没有模型。")}
                        </p>
                        {!hasFilters && canManage && (
                          <Button
                            type="button"
                            className={styles.headerActionButton}
                            onClick={() => setModelDialog({kind: "new", providerId: item.id})}
                          >
                            <IconPlus size={15}/>
                            {uiText("新增模型")}
                          </Button>
                        )}
                      </div>
                    ) : (
                      <>
                        <div className={styles.modelList}>
                          {displayedModels.map((value) => {
                            const statusTone = !value.enabled ? "neutral" : !value.providerEnabled ? "warning" : "success";
                            const statusText = !value.enabled
                              ? uiText("已停用")
                              : !value.providerEnabled
                                ? uiText("提供方已停用")
                                : uiText("已启用");
                            const contextText = formatTokenLength(value.capabilities.maxContextTokens);
                            const outputText = formatTokenLength(value.capabilities.maxOutputTokens);
                            const hasReasoning = (value.capabilities.reasoningEfforts?.length ?? 0) > 0;
                            const copied = copiedKey === `model:${value.id}`;
                            return (
                              <article
                                className={styles.modelRow}
                                key={value.id}
                                data-inactive={!value.enabled || !value.providerEnabled || undefined}
                              >
                                <div className={styles.modelIdentity}>
                                  <span className={styles.modelIcon} data-icon-surface>
                                    <IconRobot size={19}/>
                                  </span>
                                  <div className={styles.modelCopy}>
                                    <div className={styles.modelTitleRow}>
                                      <h3>{value.name}</h3>
                                      {value.modelName === value.name && (
                                        <Button
                                          type="button"
                                          className={styles.copyButton}
                                          data-copied={copied || undefined}
                                          aria-label={copied ? uiText("已复制") : uiText("复制模型标识")}
                                          title={copied ? uiText("已复制") : uiText("复制模型标识")}
                                          onClick={() => void copyText(`model:${value.id}`, value.modelName)}
                                        >
                                          {copied ? <IconCheck size={14}/> : <IconCopy size={14}/>}
                                        </Button>
                                      )}
                                    </div>
                                    {value.modelName !== value.name && (
                                      <div className={styles.modelIdRow}>
                                        <code className={styles.modelId} title={value.modelName}>{value.modelName}</code>
                                        <Button
                                          type="button"
                                          className={styles.copyButton}
                                          data-copied={copied || undefined}
                                          aria-label={copied ? uiText("已复制") : uiText("复制模型标识")}
                                          title={copied ? uiText("已复制") : uiText("复制模型标识")}
                                          onClick={() => void copyText(`model:${value.id}`, value.modelName)}
                                        >
                                          {copied ? <IconCheck size={14}/> : <IconCopy size={14}/>}
                                        </Button>
                                      </div>
                                    )}
                                  </div>
                                </div>

                                <div className={styles.capabilities}>
                                  {contextText && <span>{uiText("上下文 {0}", [contextText])}</span>}
                                  {outputText && <span>{uiText("输出 {0}", [outputText])}</span>}
                                  <span>
                                    {value.capabilities.inputTypes.map((type) => (type === "image" ? uiText("图片") : uiText("文字"))).join("、") + uiText("输入")}
                                  </span>
                                  {value.capabilities.supportsTools && <span>{uiText("工具调用")}</span>}
                                  {hasReasoning && <span>{uiText("支持思考")}</span>}
                                </div>

                                <div className={styles.modelActions}>
                                  <DetailStatus tone={statusTone}>{statusText}</DetailStatus>
                                  {canManage && (
                                    <div className={styles.actionGroup}>
                                      <Button
                                        type="button"
                                        className={styles.rowActionButton}
                                        title={uiText("价格配置")}
                                        aria-label={uiText("{0}的价格配置", [value.name])}
                                        onClick={() => setPricing(value)}
                                      >
                                        <IconWallet size={15}/>
                                        <span>{uiText("价格")}</span>
                                      </Button>
                                      <Button
                                        type="button"
                                        className={styles.rowActionButton}
                                        title={uiText("编辑模型")}
                                        aria-label={uiText("编辑模型{0}", [value.name])}
                                        onClick={() => setModelDialog({kind: "edit", model: value})}
                                      >
                                        <IconPencil size={15}/>
                                        <span>{uiText("编辑")}</span>
                                      </Button>
                                      <DropdownMenu>
                                        <DropdownMenuTrigger
                                          render={
                                            <Button
                                              type="button"
                                              className="icon-button"
                                              aria-label={uiText("{0}的更多操作", [value.name])}
                                            >
                                              <IconDots size={17}/>
                                            </Button>
                                          }
                                        />
                                        <DropdownMenuContent align="end" className="agenteam-menu agenteam-popup">
                                          <DropdownMenuItem disabled={statusAction.busy} onClick={() => toggleModel(value)}>
                                            {value.enabled ? <IconPlayerPause size={16}/> : <IconPlayerPlay size={16}/>}
                                            {value.enabled ? uiText("停用模型") : uiText("启用模型")}
                                          </DropdownMenuItem>
                                          <DropdownMenuItem onClick={() => void copyText(`model:${value.id}`, value.modelName)}>
                                            <IconCopy size={16}/>
                                            {uiText("复制模型标识")}
                                          </DropdownMenuItem>
                                          <DropdownMenuSeparator/>
                                          <DropdownMenuItem
                                            variant="destructive"
                                            onClick={() => setDeleting({kind: "model", value})}
                                          >
                                            <IconTrash size={16}/>
                                            {uiText("删除模型")}
                                          </DropdownMenuItem>
                                        </DropdownMenuContent>
                                      </DropdownMenu>
                                    </div>
                                  )}
                                </div>
                              </article>
                            );
                          })}
                        </div>
                        {!normalizedQuery && !activeProviderId && groupModels.length > MODEL_PREVIEW_THRESHOLD && (
                          <div className={styles.modelOverflowBar}>
                            <Button
                              type="button"
                              className={styles.modelOverflowButton}
                              onClick={() => toggleModelListExpansion(item.id)}
                            >
                              {hiddenModelCount > 0
                                ? uiText("展开其余 {0} 个模型", [hiddenModelCount])
                                : uiText("收起部分模型")}
                            </Button>
                          </div>
                        )}
                      </>
                    )}
                  </div>
                </CollapsibleContent>
              </Collapsible>
            </section>
          );
        })}
      </div>
    </QueryState>

    {canManage && pricing && (
      <ModelPriceDialog
        enterpriseId={enterpriseId}
        model={pricing}
        onClose={() => setPricing(null)}
        onChanged={() => setRefresh((value) => value + 1)}
      />
    )}
    {canManage && providerDialog && (
      <ModelProviderDialog
        enterpriseId={enterpriseId}
        provider={providerDialog === "new" ? null : providerDialog}
        onClose={() => setProviderDialog(null)}
        onSaved={saved}
      />
    )}
    {canManage && modelDialog && (
      <ModelProfileDialog
        enterpriseId={enterpriseId}
        model={modelDialog.kind === "edit" ? modelDialog.model : null}
        providers={providerList}
        preferredProvider={modelDialog.kind === "new" ? modelDialog.providerId : modelDialog.model.providerId}
        onClose={() => setModelDialog(null)}
        onSaved={saved}
      />
    )}
    {canManage && deleting && (
      <DeleteModelConfiguration
        enterpriseId={enterpriseId}
        target={deleting}
        onClose={() => setDeleting(null)}
        onSaved={() => {
          if (deleting.kind === "provider" && selectedProviderId === deleting.value.id) {
            setSelectedProviderId("");
          }
          saved();
        }}
      />
    )}
  </div>;
}

function DeleteModelConfiguration({enterpriseId, target, onClose, onSaved}: {
  enterpriseId: string; target: DeleteTarget; onClose: () => void; onSaved: () => void;
}) {
  const uiText = useT();
  const action = useFormAction();
  const dialog = useDialogControl();
  return <Dialog title={uiText("删除“{0}”？", [target.value.name])} busy={action.busy} onClose={onClose}
                 dialogRef={dialog.ref}>
    <p className={ui.description}>{target.kind === "model"
      ? uiText("删除后，使用此模型的员工和对话需要重新选择模型。历史调用和费用记录会保留。")
      : uiText("删除后无法继续使用此提供方，历史调用和费用记录会保留。")}</p>
    <MutationFeedback action={action}/>
    <DialogActions className={ui.footer}>
      <DialogCancel className={ui.button} disabled={action.busy}>{uiText("取消")}</DialogCancel>
      <Button className={ui.danger} disabled={action.busy} onClick={() => void action.execute(async () => {
        const collection = target.kind === "provider" ? "model-providers" : "models";
        await action.mutation.run(organizationPath(enterpriseId, `/${collection}/${encodeURIComponent(target.value.id)}`), {
          method: "DELETE",
          revision: target.value.revision,
        });
        dialog.close(onSaved);
      }, "")}>{action.busy ? uiText("正在删除…") : uiText("删除")}</Button>
    </DialogActions>
  </Dialog>;
}
