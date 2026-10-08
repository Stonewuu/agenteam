"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {useState} from "react";
import {Select} from "@/components/ui/select";
import {Tabs} from "@/components/ui/tabs";
import {MotionPanel} from "@/components/ui/motion-panel";
import {Dialog, DialogAction, DialogActions, DialogCancel} from "@/components/ui/dialog";
import {IconPlus, IconUpload} from "@/components/ui/icons";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {useApiPage} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import type {DataConfig, ResourceDetail} from "@/features/resource/types/resource";
import type {ConnectionCheck} from "@/features/plugin/types/plugin";
import type {DataCollection} from "../types/data";
import {DataImportDialog} from "./data-import-dialog";
import {DataCollectionDialog, DataCollectionForm} from "./data-collection-dialog";
import {DataQueryForm} from "./data-query-dialog";
import ui from "@/components/ui/surface.module.css";
import styles from "./data.module.css";

export function DataCollectionsPanel({enterpriseId, detail, editable, queryable, dirty, onChecked}: {
  enterpriseId: string;
  detail: ResourceDetail;
  editable: boolean;
  queryable: boolean;
  dirty: boolean;
  onChecked: () => Promise<void>;
}) {
  const uiText = useT();
  const config = detail.draft as DataConfig;
  const resourceId = detail.resource.id;
  const action = useFormAction();
  const [refresh, setRefresh] = useState(0);
  const [check, setCheck] = useState<ConnectionCheck | null>(null);
  const [dialog, setDialog] = useState<{ kind: "import" | "edit"; collection: DataCollection | null } | null>(null);
  const [selected, setSelected] = useState("");
  const [tab, setTab] = useState(queryable ? "query" : "fields");
  const [fieldsDirty, setFieldsDirty] = useState(false);
  const [fieldsBusy, setFieldsBusy] = useState(false);
  const [pending, setPending] = useState<{ tab: string; selected: string } | null>(null);
  const path = organizationPath(enterpriseId, `/data/${encodeURIComponent(resourceId)}`);
  const list = useApiPage<DataCollection>(`${path}/collections`, refresh);
  const latest = check ?? detail.connectionCheck;
  const changed = () => {
    setDialog(null);
    setFieldsDirty(false);
    setRefresh((value) => value + 1);
  };
  const collection = list.data?.items.find((item) => item.id === selected) ?? list.data?.items[0];
  const switchView = (nextTab: string, nextSelected = selected) => {
    if (fieldsBusy) {
      return;
    }
    if (fieldsDirty) {
      setPending({tab: nextTab, selected: nextSelected});
    } else {
      setTab(nextTab);
      setSelected(nextSelected);
    }
  };
  return <section className={styles.panel}>
    <div className="form-section-title"><h2>{uiText("集合与查询")}</h2>
      <p>{uiText("选择要使用的数据集合，查看数据与字段。")}</p></div>
    <header className={styles.heading}>
      <div className={styles.collectionSelect}><Select aria-label={uiText("数据集合")} value={collection?.id ?? ""}
                                                       disabled={fieldsBusy || !list.data?.items.length}
                                                       onChange={(event) => switchView(tab, event.target.value)}>
        <option value="">{uiText("选择集合")}</option>
        {list.data?.items.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</Select></div>
      <div className={ui.actions}>
        {config.sourceType !== "file" && editable &&
          <Button type="button" className={ui.button} disabled={dirty || action.busy}
                  onClick={() => void action.execute(async () => {
                    setCheck(await action.mutation.run<ConnectionCheck>(`${path}/check`, {
                      method: "POST",
                      revision: detail.resource.revision,
                      timeoutMs: 15000
                    }));
                    await onChecked();
                  }, "")}>{action.busy ? uiText("正在检查…") : uiText("检查连接")}</Button>}
        {editable && <Button type="button" className={ui.primary} disabled={dirty || action.busy || fieldsBusy}
                             onClick={() => setDialog({
                               kind: config.sourceType === "file" ? "import" : "edit",
                               collection: null
                             })}>{config.sourceType === "file" ? <IconUpload size={16}/> :
          <IconPlus size={16}/>}{config.sourceType === "file" ? uiText("导入文件") : uiText("添加集合")}</Button>}
      </div>
    </header>
    {dirty && <p className={ui.description}>{uiText("请先保存上方修改，再操作集合。")}</p>}
    {latest && config.sourceType !== "file" &&
      <div className={ui.feedback}><p className={latest.success ? ui.description : ui.error}>{latest.summary}</p><span
        className={ui.description}>{uiText("上次检查：")}<EnterpriseDateTime value={latest.checkedAt}/></span></div>}
    <MutationFeedback action={action}/>
    <QueryState {...list} hasData={Boolean(collection)} empty={uiText("还没有数据集合。")}>
      {collection && <>
        <div className={styles.heading}><Tabs value={tab} onChange={(next) => switchView(next)}
                                              items={[...(queryable ? [{
                                                value: "query",
                                                label: uiText("查询预览")
                                              }] : []), {value: "fields", label: uiText("字段设置")}]}/>
          {config.sourceType === "file" && editable &&
            <Button type="button" className={ui.button} disabled={dirty || action.busy || fieldsBusy}
                    onClick={() => setDialog({kind: "import", collection})}>{uiText("更新文件")}</Button>}</div>
        <p
          className={ui.description}>{collection.fields.length}{uiText(" 个字段")}{collection.rowCount !== null ? uiText(" · {0} 行", [collection.rowCount]) : ""}</p>
        <MotionPanel value={`${collection.id}:${tab}`}>
          {tab === "query" && queryable ? collection.status === "active" ?
              <DataQueryForm key={`${collection.id}:${collection.activeGeneration}:${collection.revision}`}
                             enterpriseId={enterpriseId} resourceId={resourceId} collection={collection}
                             onReload={changed}/> : <p
                className={ui.empty}>{collection.status === "processing" ? uiText("数据正在处理，请稍后再查询。") : uiText("此集合当前不可查询。")}</p> :
            <DataCollectionForm key={`${collection.id}:${collection.revision}`} enterpriseId={enterpriseId}
                                resourceId={resourceId} sourceType={config.sourceType} initial={collection}
                                onSaved={changed} readOnly={!editable || dirty} onDirty={setFieldsDirty}
                                onBusy={setFieldsBusy}/>}
        </MotionPanel></>}
    </QueryState><Pagination {...list} hasMore={list.data?.hasMore}/>
    {dialog?.kind === "import" &&
      <DataImportDialog enterpriseId={enterpriseId} resourceId={resourceId} target={dialog.collection}
                        onClose={() => setDialog(null)} onSaved={changed}/>}
    {dialog?.kind === "edit" &&
      <DataCollectionDialog enterpriseId={enterpriseId} resourceId={resourceId} initial={dialog.collection}
                            sourceType={config.sourceType} onClose={() => setDialog(null)} onSaved={changed}/>}
    {pending &&
      <Dialog variant="discard" title={uiText("放弃未保存的字段修改？")} onClose={() => setPending(null)}><DialogActions
        className={ui.footer}><DialogCancel type="button"
                                            className={ui.button}>{uiText("继续编辑")}</DialogCancel><DialogAction
        type="button" className={ui.danger} onAction={(close) => close(() => {
        setTab(pending.tab);
        setSelected(pending.selected);
        setFieldsDirty(false);
        setPending(null);
      })}>{uiText("放弃修改")}</DialogAction></DialogActions></Dialog>}
  </section>;
}
