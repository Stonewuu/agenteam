"use client";

import {useState} from "react";
import {Dialog, DialogCancel, useDialogControl} from "@/components/ui/dialog";
import {Button} from "@/components/ui/button";
import {QueryState} from "@/components/ui/query-state";
import {DetailSection} from "@/components/ui/detail-section";
import {IconChartBar, IconPlus, IconEye, IconCopy, IconPencil, IconTrash, IconHistory, IconCheck} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {ModelPriceEditor} from "./model-price-editor";
import {PricePreviewDialog} from "./price-preview-dialog";
import {PriceConfigurationDetails} from "./price-configuration-details";
import {priceMeters, shiftPrice, type PriceVersion} from "../types/model-pricing";
import type {ManagedModel} from "../types/model-management";
import ui from "@/components/ui/surface.module.css";
import styles from "./model-pricing.module.css";

export function ModelPriceDialog({enterpriseId, model, onClose, onChanged}: {
  enterpriseId: string; model: ManagedModel; onClose: () => void; onChanged: () => void;
}) {
  const t = useT();
  const [refresh, setRefresh] = useState(0);
  const [before, setBefore] = useState<string[]>([]);
  const [detailId, setDetailId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const path = organizationPath(enterpriseId, `/models/${encodeURIComponent(model.id)}/price-versions`);
  const versions = useApiQuery<PriceVersion[]>(`${path}${before.length ? `?before=${before.at(-1)}` : ""}`, refresh);
  const current = useApiQuery<PriceVersion | null>(`${path}/current`, refresh);
  function saved(value?: PriceVersion) {
    setCreating(false);
    setDetailId(value?.id ?? null);
    setRefresh((count) => count + 1);
    onChanged();
  }
  return <><Dialog title={t("{0} · 价格配置", [model.name])} icon={<IconChartBar size={21}/>} wide onClose={onClose}
    footer={<DialogCancel className={ui.button}>{t("关闭")}</DialogCancel>}>
    <div className={styles.stack}>
      <div className={styles.toolbar}><div className={styles.ruleCopy}><strong>{t("价格版本")}</strong>
        <p>{current.data ? t("当前生效：版本 {0} · {1}", [current.data.versionNo, current.data.currency]) :
          current.loading ? t("正在读取当前价格…") : current.error ? t("当前价格暂时无法读取") : t("尚未发布价格")}</p></div>
        <Button className={ui.primary} onClick={() => setCreating(true)}><IconPlus size={17}/>{t("新建草稿")}</Button></div>
      {current.data && !versions.data?.some((value) => value.id === current.data?.id) && <Button className={ui.button}
        onClick={() => setDetailId(current.data!.id)}>{t("查看当前生效价格")}</Button>}
      <QueryState {...versions} hasData={Boolean(versions.data?.length)} empty={t("还没有价格配置。创建草稿后即可设置单价。") }>
        <div className={styles.stack}>{versions.data?.map((value) => <article className={styles.version} key={value.id}>
          <div className={styles.versionCopy}><strong>{t("版本 {0}", [value.versionNo])} · {value.currency}</strong>
            <small>{value.effectiveFrom ? t("生效于 {0}", [new Date(value.effectiveFrom).toLocaleString(t.formatLocale)]) :
              t("创建于 {0}", [new Date(value.createdAt).toLocaleString(t.formatLocale)])}</small></div>
          <div className={styles.actions}><span className="badge neutral">{value.status === "draft" ? t("草稿") : current.data?.id === value.id ? t("当前生效") : t("已发布")}</span>
            <Button className={ui.button} onClick={() => setDetailId(value.id)}><IconEye size={16}/>{t("查看")}</Button></div>
        </article>)}</div>
      </QueryState>
      {(before.length > 0 || versions.data?.length === 50) && <div className={styles.actions}>
        <Button className={ui.button} disabled={!before.length || versions.loading} onClick={() => setBefore(before.slice(0, -1))}>{t("上一页")}</Button>
        <Button className={ui.button} disabled={versions.data?.length !== 50 || versions.loading}
          onClick={() => setBefore([...before, versions.data!.at(-1)!.versionNo])}>{t("下一页")}</Button>
      </div>}
    </div>
  </Dialog>
    {creating && <ModelPriceEditor enterpriseId={enterpriseId} modelId={model.id} version={null} onClose={() => setCreating(false)} onSaved={saved}/>}
    {detailId && <PriceVersionDialog key={detailId} enterpriseId={enterpriseId} model={model} versionId={detailId}
      onClose={() => setDetailId(null)} onChanged={saved}/>}
  </>;
}

function PriceVersionDialog({enterpriseId, model, versionId, onClose, onChanged}: {
  enterpriseId: string; model: ManagedModel; versionId: string; onClose: () => void; onChanged: (value?: PriceVersion) => void;
}) {
  const t = useT();
  const [refresh, setRefresh] = useState(0);
  const [editing, setEditing] = useState(false);
  const [preview, setPreview] = useState(false);
  const [confirm, setConfirm] = useState<"publish" | "delete" | null>(null);
  const action = useFormAction();
  const dialog = useDialogControl();
  const path = organizationPath(enterpriseId, `/models/${encodeURIComponent(model.id)}/price-versions`);
  const version = useApiQuery<PriceVersion>(`${path}/${encodeURIComponent(versionId)}`, refresh);
  const current = useApiQuery<PriceVersion | null>(`${path}/current`, refresh);
  const value = version.data;
  return <><Dialog title={value ? t("{0} · 价格版本 {1}", [model.name, value.versionNo]) : t("价格详情")}
    onClose={onClose} wide busy={action.busy} dialogRef={dialog.ref}
    footer={<><DialogCancel className={ui.button} disabled={action.busy}>{t("关闭")}</DialogCancel>
      {value?.status === "draft" && <Button className={ui.primary} disabled={action.busy} onClick={() => setConfirm("publish")}><IconCheck size={16}/>{t("发布价格")}</Button>}</>}>
    <QueryState {...version} hasData={Boolean(value)} empty={t("价格版本不存在或无法访问。") }>
      {value?.configuration && <div className={styles.stack}>
        <div className={styles.toolbar}><span className="badge neutral">{value.status === "draft" ? t("草稿") : t("已发布")}</span>
          <div className={styles.actions}><Button className={ui.button} onClick={() => setPreview(true)} disabled={action.busy}><IconChartBar size={16}/>{t("计算预览")}</Button>
            {value.status === "draft" && <Button className={ui.button} onClick={() => setEditing(true)} disabled={action.busy}><IconPencil size={16}/>{t("编辑")}</Button>}
            <Button className={ui.button} disabled={action.busy} onClick={() => void action.execute(async () => {
              const created = await action.mutation.run<PriceVersion>(path, {method: "POST", body: {copyFromVersionId: value.id}});
              dialog.close(() => onChanged(created));
            }, "")}><IconCopy size={16}/>{t("复制为草稿")}</Button>
            {value.status === "draft" && <Button className="icon-button" aria-label={t("删除草稿")} onClick={() => setConfirm("delete")} disabled={action.busy}><IconTrash size={17}/></Button>}
          </div></div>
        <PriceConfigurationDetails value={value.configuration}/><MutationFeedback action={action}/>
      </div>}
    </QueryState>
  </Dialog>
    {editing && value && <ModelPriceEditor enterpriseId={enterpriseId} modelId={model.id} version={value} onClose={() => setEditing(false)} onSaved={() => {
      setEditing(false);
      setRefresh((count) => count + 1);
    }}/>}
    {preview && value && <PricePreviewDialog enterpriseId={enterpriseId} modelId={model.id} version={value} onClose={() => setPreview(false)}/>}
    {confirm && value && <PricePublicationDialog modelName={model.name} version={value} operation={confirm} current={current}
      path={`${path}/${encodeURIComponent(value.id)}`} onClose={() => setConfirm(null)} onDone={() => {
        setConfirm(null);
        dialog.close(() => onChanged());
      }}/>}</>;
}

function PricePublicationDialog({modelName, version, operation, current, path, onClose, onDone}: {
  modelName: string; version: PriceVersion; operation: "publish" | "delete"; current: ReturnType<typeof useApiQuery<PriceVersion | null>>;
  path: string; onClose: () => void; onDone: (value?: PriceVersion) => void;
}) {
  const t = useT();
  const action = useFormAction();
  const dialog = useDialogControl();
  const deleting = operation === "delete";
  const [showPrevious, setShowPrevious] = useState(false);
  const previousRules = current.data?.configuration?.rules ?? [];
  const nextRules = version.configuration?.rules ?? [];
  const changedRules = nextRules.filter((rule) => JSON.stringify(rule) !== JSON.stringify(previousRules.find((old) => old.name === rule.name)));
  const removedRules = previousRules.filter((rule) => !nextRules.some((next) => next.name === rule.name));
  return <><Dialog title={deleting ? t("删除价格草稿？") : t("发布价格版本 {0}？", [version.versionNo])} onClose={onClose} busy={action.busy}
    dialogRef={dialog.ref} wide={!deleting} footer={<><DialogCancel className={ui.button} disabled={action.busy}>{t("取消")}</DialogCancel>
      <Button className={deleting ? ui.danger : ui.primary} disabled={action.busy || (!deleting && (current.loading || Boolean(current.error)))}
        onClick={() => void action.execute(async () => {
          const changed = await action.mutation.run<PriceVersion>(`${path}${deleting ? "" : "/publish"}`, {method: deleting ? "DELETE" : "POST", revision: version.revision});
          dialog.close(() => onDone(deleting ? undefined : changed));
        }, "")}>{action.busy ? t("正在提交…") : deleting ? t("删除草稿") : t("确认发布")}</Button></>}>
    <div className={styles.stack}><strong>{modelName} · {version.currency}</strong>
      <p>{deleting ? t("删除后无法恢复此草稿。") : t("发布后立即用于新请求。已发生的用量和金额保留原价格。")}</p>
      {!deleting && <QueryState {...current} hasData={!current.loading && !current.error} empty={t("当前价格暂时无法读取")}>
        <DetailSection title={t("与当前价格对比")} icon={<IconHistory size={20}/>}>
          <table className={styles.table}><thead><tr><th>{t("基础单价 · 每百万 Token")}</th><th>{t("当前")}</th><th>{t("本次发布")}</th></tr></thead><tbody>
            {priceMeters.map((meter) => <tr key={meter.value}><td>{t(meter.label)}</td><td className={styles.amount}>
              {shiftPrice(current.data?.configuration?.rules.find((rule) => rule.defaultRule)?.rates.find((rate) => rate.meter === meter.value && rate.cacheTtlSeconds === 0)?.pricePerMillion ?? t("未配置"), 0)} {current.data?.currency}</td>
              <td className={styles.amount}>{shiftPrice(version.configuration?.rules.find((rule) => rule.defaultRule)?.rates.find((rate) => rate.meter === meter.value && rate.cacheTtlSeconds === 0)?.pricePerMillion ?? t("未配置"), 0)} {version.currency}</td></tr>)}
            <tr><td>{t("规则数量")}</td><td>{current.data?.configuration?.rules.length ?? 0}</td><td>{version.configuration?.rules.length ?? 0}</td></tr>
            <tr><td>{t("时区")}</td><td>{current.data?.timezone ?? "—"}</td><td>{version.timezone}</td></tr>
          </tbody></table>
        </DetailSection>
        {(changedRules.length > 0 || removedRules.length > 0) && <div className={styles.hint}>
          {changedRules.length > 0 && <p>{t("新增或调整的规则：{0}", [changedRules.map((rule) => rule.name).join("、")])}</p>}
          {removedRules.length > 0 && <p>{t("移除的规则：{0}", [removedRules.map((rule) => rule.name).join("、")])}</p>}
        </div>}
        {current.data?.configuration && <Button type="button" className={ui.button} onClick={() => setShowPrevious(true)}>{t("查看当前完整价目")}</Button>}
        {version.configuration && <PriceConfigurationDetails value={version.configuration}/>}
      </QueryState>}
      <MutationFeedback action={action}/>
    </div>
  </Dialog>{showPrevious && current.data?.configuration && <Dialog title={t("当前生效 · 版本 {0}", [current.data.versionNo])} wide onClose={() => setShowPrevious(false)}
    footer={<DialogCancel className={ui.button}>{t("关闭")}</DialogCancel>}><PriceConfigurationDetails value={current.data.configuration}/></Dialog>}</>;
}
