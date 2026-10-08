"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {PageHeader} from "@/components/ui/page-header";

import {useState} from "react";
import {Dialog, DialogActions, DialogCancel, useDialogControl} from "@/components/ui/dialog";
import {QueryState} from "@/components/ui/query-state";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {ModelProviderDialog} from "./model-provider-dialog";
import {ModelProfileDialog} from "./model-profile-dialog";
import {SearchInput} from "@/components/ui/search-input";
import {
  IconDots,
  IconKey,
  IconPencil,
  IconPlug,
  IconPlus,
  IconRefresh,
  IconRobot,
  IconTrash
} from "@/components/ui/icons";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger
} from "@/components/ui/shadcn/dropdown-menu";
import type {ManagedModel, ModelProvider} from "../types/model-management";
import ui from "@/components/ui/surface.module.css";
import styles from "./model-management.module.css";

type DeleteTarget = { kind: "provider"; value: ModelProvider } | { kind: "model"; value: ManagedModel };

export function ModelManagement({enterpriseId, canManage}: { enterpriseId: string; canManage: boolean }) {
  const uiText = useT();
  const [refresh, setRefresh] = useState(0);
  const [provider, setProvider] = useState<ModelProvider | "new" | null>(null);
  const [model, setModel] = useState<ManagedModel | "new" | null>(null);
  const [deleting, setDeleting] = useState<DeleteTarget | null>(null);
  const [filter, setFilter] = useState("");
  const [query, setQuery] = useState("");
  const [providerQuery, setProviderQuery] = useState("");
  const providers = useApiQuery<ModelProvider[]>(organizationPath(enterpriseId, "/model-providers"), refresh);
  const models = useApiQuery<ManagedModel[]>(organizationPath(enterpriseId, "/models"), refresh);
  const current = providers.data?.find((value) => value.id === filter) ?? providers.data?.[0];
  const providerOptions = providers.data?.filter((value) => value.name.toLocaleLowerCase().includes(providerQuery.trim().toLocaleLowerCase())) ?? [];
  const providerModels = models.data?.filter((value) => value.providerId === current?.id) ?? [];
  const visible = providerModels.filter((value) => `${value.name} ${value.modelName}`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const saved = () => {
    setProvider(null);
    setModel(null);
    setDeleting(null);
    setRefresh((value) => value + 1);
  };
  return <div className={styles.sections}><PageHeader title={uiText("模型配置")}
                                                      description={canManage ? uiText("连接模型服务，为数字员工选择合适的能力。") : uiText("查看已配置的模型服务和模型。")}
                                                      inlineActions actions={<><Button className="icon-button"
                                                                                       aria-label={uiText("刷新模型配置")}
                                                                                       onClick={() => setRefresh((value) => value + 1)}><IconRefresh
    size={18}/></Button></>}/>
    <div className={styles.layout}>
      <aside className={styles.providerRail} aria-label={uiText("模型提供方")}>
        <div className={styles.railHeading}><h2>{uiText("提供方")}</h2><span>{providers.data?.length}</span></div>
        {canManage && <Button type="button" className={`${ui.primary} ${styles.addProvider}`}
                              onClick={() => setProvider("new")}><IconPlus size={17}/>{uiText("新增提供方")}</Button>}
        {(providers.data?.length ?? 0) > 4 &&
          <SearchInput aria-label={uiText("搜索模型提供方")} placeholder={uiText("搜索提供方…")} value={providerQuery}
                       onChange={(event) => setProviderQuery(event.target.value)}/>}
        <div className={styles.providerList}><QueryState {...providers} hasData={providerOptions.length > 0}
                                                         empty={providerQuery ? uiText("没有匹配的提供方。") : uiText("还没有连接模型服务。")}>{providerOptions.map((value) =>
          <Button type="button" key={value.id} className={styles.providerItem} aria-pressed={current?.id === value.id}
                  onClick={() => {
                    setFilter(value.id);
                    setQuery("");
                  }}><span className={styles.providerIcon}><IconPlug
            size={21}/></span><span><strong>{value.name}</strong><small>{value.modelCount}{uiText(" 个模型")}{!value.enabled ? uiText(" · 已停用") : ""}</small></span></Button>)}</QueryState>
        </div>
      </aside>
      {current && <div className={styles.providerContent}>
        <section className={styles.connectionCard} aria-label={uiText("{0}的服务连接", [current.name])}>
          <div className={styles.connectionHeading}><span className="avatar purple"><IconPlug size={25}/></span>
            <div><h2>{current.name}</h2><p>{current.baseUrl}</p></div>
            {canManage && <DropdownMenu><DropdownMenuTrigger
              render={<Button className="icon-button" aria-label={uiText("{0}的更多操作", [current.name])}><IconDots
                size={19}/></Button>}/><DropdownMenuContent align="end"><DropdownMenuItem
              onClick={() => setProvider(current)}><IconPencil size={16}/>{uiText("编辑连接")}
            </DropdownMenuItem><DropdownMenuItem disabled={current.modelCount > 0} onClick={() => setDeleting({
              kind: "provider",
              value: current
            })}><IconTrash size={16}/>{uiText("删除提供方")}</DropdownMenuItem></DropdownMenuContent></DropdownMenu>}
          </div>
          <div className={styles.connectionFooter}><span><IconKey
            size={15}/>{current.keyConfigured ? uiText("已配置访问密钥") : uiText("未配置访问密钥")}</span><span
            className="badge neutral">{current.enabled ? uiText("已启用") : uiText("已停用")}</span>{canManage &&
            <Button className={ui.button} onClick={() => setProvider(current)}><IconPencil
              size={15}/>{uiText("配置连接")}</Button>}</div>
        </section>
        <section className={styles.section} aria-labelledby="models-title">
          <div className={styles.toolbar}><h2 id="models-title">{uiText("模型")}{models.data &&
            <span className={styles.count}>{providerModels.length}</span>}</h2>{canManage &&
            <Button className={ui.primary} onClick={() => setModel("new")}><IconPlus size={16}/>{uiText("新增模型")}
            </Button>}</div>
          <SearchInput aria-label={uiText("搜索模型")} placeholder={uiText("搜索模型名称…")} value={query}
                       onChange={(event) => setQuery(event.target.value)}/>
          <QueryState {...models} error={models.error === providers.error ? "" : models.error}
                      hasData={visible.length > 0}
                      empty={query ? uiText("没有匹配的模型。") : canManage ? uiText("此提供方下还没有模型，添加后即可在智能体中选择。") : uiText("此提供方下还没有模型。")}>
            <div className={styles.modelList}>{visible.map((value) => <article className={styles.modelRow}
                                                                               key={value.id}><span
              className={styles.modelIcon}><IconRobot size={23}/></span>
              <div className={styles.modelCopy}><h3>{value.name}</h3><p>{value.modelName}</p>
                <div className={styles.capabilities}>
                  <span>{value.capabilities.inputTypes.map((type) => type === "image" ? uiText("图片") : uiText("文字")).join("、") + uiText("输入")}</span>{value.capabilities.supportsTools &&
                  <span>{uiText("工具调用")}</span>}</div>
              </div>
              <div className={styles.modelActions}><span
                className="badge neutral">{!value.enabled ? uiText("已停用") : !value.providerEnabled ? uiText("提供方已停用") : uiText("已启用")}</span>{canManage &&
                <Button className="icon-button" title={uiText("编辑模型")}
                        aria-label={uiText("编辑模型{0}", [value.name])} onClick={() => setModel(value)}><IconPencil
                  size={17}/></Button>}{canManage && <DropdownMenu><DropdownMenuTrigger
                render={<Button className="icon-button" aria-label={uiText("{0}的更多操作", [value.name])}><IconDots
                  size={18}/></Button>}/><DropdownMenuContent align="end"><DropdownMenuItem disabled={value.inUse}
                                                                                            onClick={() => setDeleting({
                                                                                              kind: "model",
                                                                                              value
                                                                                            })}><IconTrash
                size={16}/>{uiText("删除模型")}</DropdownMenuItem></DropdownMenuContent></DropdownMenu>}</div>
            </article>)}</div>
          </QueryState>
        </section>
      </div>}
      {!current && !providers.loading && !providers.error &&
        <p className={ui.empty}>{canManage ? uiText("添加提供方后，即可配置模型。") : uiText("暂无模型配置。")}</p>}
    </div>
    {canManage && provider &&
      <ModelProviderDialog enterpriseId={enterpriseId} provider={provider === "new" ? null : provider}
                           onClose={() => setProvider(null)} onSaved={saved}/>}
    {canManage && model && <ModelProfileDialog enterpriseId={enterpriseId} model={model === "new" ? null : model}
                                               providers={providers.data ?? []}
                                               preferredProvider={current?.id ?? ""} onClose={() => setModel(null)}
                                               onSaved={saved}/>}
    {canManage && deleting &&
      <DeleteModelConfiguration enterpriseId={enterpriseId} target={deleting} onClose={() => setDeleting(null)}
                                onSaved={() => {
                                  if (deleting.kind === "provider" && filter === deleting.value.id) {
                                    setFilter("");
                                  }
                                  saved();
                                }}/>}
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
    <p className={ui.description}>{uiText("删除后无法恢复，需要使用时可以重新添加。")}</p><MutationFeedback
    action={action}/>
    <DialogActions className={ui.footer}><DialogCancel className={ui.button}
                                                       disabled={action.busy}>{uiText("取消")}</DialogCancel>
      <Button className={ui.danger} disabled={action.busy} onClick={() => void action.execute(async () => {
        const collection = target.kind === "provider" ? "model-providers" : "models";
        await action.mutation.run(organizationPath(enterpriseId, `/${collection}/${encodeURIComponent(target.value.id)}`), {
          method: "DELETE", revision: target.value.revision,
        });
        dialog.close(onSaved);
      }, "")}>{action.busy ? uiText("正在删除…") : uiText("删除")}</Button></DialogActions>
  </Dialog>;
}
