"use client";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {Dialog, DialogActions, DialogCancel, DialogForm} from "@/components/ui/dialog";
import {Field} from "@/components/ui/field";
import {Input} from "@/components/ui/input";
import {Select} from "@/components/ui/select";
import {IconBell} from "@/components/ui/icons";
import {ChannelIdentity} from "./channel-identity";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import type {ChannelDeliveryDetail} from "@/features/notification/types/channel-delivery";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useApiPage} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import type {Integration} from "../types/integration";
import ui from "@/components/ui/surface.module.css";

type Recipient = {id: string; name: string};

export function IntegrationTestMessageDialog({path, value, onClose, onSent}: {
  path: string; value: Integration; onClose: () => void; onSent: (deliveryId: string) => void;
}) {
  const t = useT();
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<Recipient | null>(null);
  const action = useFormAction();
  const base = `${path}/${encodeURIComponent(value.id)}`;
  const recipients = useApiPage<Recipient>(`${base}/recipients?search=${encodeURIComponent(search)}`);
  const choices = recipients.data?.items ?? [];
  return <Dialog title={t("发送测试通知")} icon={<IconBell size={21}/>} onClose={onClose} busy={action.busy}>
    <DialogForm className={ui.form} onSubmit={(event) => {
      event.preventDefault();
      if (!selected) {
        return;
      }
      void action.execute(async () => {
        const result = await action.mutation.run<ChannelDeliveryDetail>(`${base}/test-messages`, {
          method: "POST", revision: value.revision, body: {recipientUserId: selected.id}
        });
        onSent(result.delivery.id);
      }, "");
    }}>
      <ChannelIdentity name={value.name} providerName={value.providerName} providerCode={value.providerCode}/>
      <p className={ui.description}>{t("向已绑定并允许接收的成员发送一条测试通知。")}</p>
      <Field label={t("搜索成员")}><Input type="search" value={search} onChange={(event) => setSearch(event.target.value)} disabled={action.busy}/></Field>
      <QueryState {...recipients} hasData={Boolean(choices.length || selected)} empty={t("没有符合条件的成员，请先完成账号绑定并允许接收通知。")}>
        <Field label={t("接收成员")} required error={action.fieldErrors.recipientUserId?.join(" ")}>
          <Select name="recipientUserId" required value={selected?.id ?? ""} disabled={action.busy || recipients.loading} onChange={(event) => {
            setSelected(choices.find((choice) => choice.id === event.target.value) ?? null);
          }}>
            <option value="">{t("请选择成员")}</option>
            {selected && !choices.some((choice) => choice.id === selected.id) && <option value={selected.id}>{selected.name}</option>}
            {choices.map((choice) => <option key={choice.id} value={choice.id}>{choice.name}</option>)}
          </Select>
        </Field>
      </QueryState>
      <Pagination {...recipients} hasMore={recipients.data?.hasMore}/>
      <MutationFeedback action={action}/>
      <DialogActions><DialogCancel className={ui.button} disabled={action.busy}>{t("取消")}</DialogCancel>
        <Button className={ui.primary} disabled={action.busy || !selected}><IconBell size={16}/>{action.busy ? t("正在提交…") : t("发送测试通知")}</Button></DialogActions>
    </DialogForm>
  </Dialog>;
}
