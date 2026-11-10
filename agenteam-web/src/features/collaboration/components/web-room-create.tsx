"use client";

import {useState} from "react";
import {useRouter} from "next/navigation";
import {Button} from "@/components/ui/button";
import {Dialog, DialogActions, DialogCancel, DialogForm} from "@/components/ui/dialog";
import {Field} from "@/components/ui/field";
import {Input} from "@/components/ui/input";
import {SearchInput} from "@/components/ui/search-input";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import {IconCheck, IconCircle, IconCollaboration} from "@/components/ui/icons";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useApiPage} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import {roomApi, roomUrl, type RoomCandidate} from "../types/web-room";
import ui from "@/components/ui/surface.module.css";
import styles from "./web-room.module.css";

export function WebRoomCreate({enterprise, onClose}: {enterprise: string; onClose: () => void}) {
  const t = useT();
  const router = useRouter();
  const action = useFormAction();
  const [title, setTitle] = useState("");
  const [query, setQuery] = useState("");
  const [selected, setSelected] = useState<RoomCandidate[]>([]);
  const [onlySelected, setOnlySelected] = useState(false);
  const candidates = useApiPage<RoomCandidate>(roomApi(enterprise) + "/room-candidates?query=" + encodeURIComponent(query), 0, 200, 15);
  const shown = onlySelected ? selected.filter(person => (person.name + " " + person.description).toLocaleLowerCase()
    .includes(query.trim().toLocaleLowerCase())) : candidates.data?.items ?? [];
  function toggle(person: RoomCandidate) {
    setSelected(previous => previous.some(member => member.id === person.id) ? previous.filter(member => member.id !== person.id)
      : previous.length < 8 ? [...previous, person] : previous);
  }

  return <Dialog title={t("新建协作对话")} icon={<IconCollaboration size={22}/>} onClose={onClose} busy={action.busy} className={styles.createDialog}>
    <DialogForm className={styles.createForm} onSubmit={event => {
      event.preventDefault();
      if (selected.length < 2 || action.busy) {
        return;
      }
      void action.execute(async () => {
        const result = await action.mutation.run<{roomId: string}>(roomApi(enterprise) + "/rooms", {method: "POST",
          body: {title: title.trim(), agentIds: selected.map(person => person.id)}});
        router.replace(roomUrl(result.roomId));
      }, t("协作对话已创建。"));
    }}>
      <Field label={t("对话名称")}><Input className={ui.input} maxLength={160} value={title} disabled={action.busy}
        placeholder={t("选填，例如：采购报价比较")} onChange={event => setTitle(event.target.value)}/></Field>
      <section className={styles.memberSection} aria-label={t("邀请成员")}>
        <div className={styles.inviteHeader}><div><h3>{t("邀请成员")}</h3><small>{t("选择 2 至 8 位")}</small></div>
          <Button type="button" className={styles.selectedCount} aria-pressed={onlySelected} disabled={!selected.length && !onlySelected}
            title={t(onlySelected ? "显示全部成员" : "只看已选成员")} onClick={() => {
              setOnlySelected(!onlySelected);
              setQuery("");
            }}>
            {t("已选 {0} 位", [String(selected.length)])}</Button></div>
        <div className={styles.memberPanel}>
          <SearchInput className={styles.memberSearch} value={query} onChange={event => setQuery(event.target.value)} onClear={() => setQuery("")}
            placeholder={t(onlySelected ? "搜索已选成员" : "搜索已雇佣的智能体")} aria-label={t("搜索成员")}/>
          <QueryState {...candidates} loading={!onlySelected && candidates.loading} error={onlySelected ? "" : candidates.error}
            hasData={shown.length > 0} empty={t(onlySelected ? "没有匹配的已选成员。" : "没有找到可用的已雇佣智能体。")}>
            <div className={styles.picks}>{shown.map(person => {
              const checked = selected.some(member => member.id === person.id);
              return <Button key={person.id} type="button" className={styles.pick} aria-label={person.name} aria-pressed={checked}
                disabled={action.busy || !checked && selected.length >= 8} onClick={() => toggle(person)}>
                <ResourceAvatar icon={person.icon} color={person.color} size="small"/>
                <span className={styles.pickText}><strong>{person.name}</strong>{person.description && <small>{person.description}</small>}</span>
                <span className={styles.pickMark} aria-hidden="true">{checked ? <IconCheck size={20} variant="Bold"/> : <IconCircle size={20}/>}</span>
              </Button>;
            })}</div>
          </QueryState>
          {!onlySelected && <Pagination {...candidates} hasMore={candidates.data?.hasMore}/>}
        </div>
      </section>
      <MutationFeedback action={action}/>
      <DialogActions><DialogCancel className={ui.button} disabled={action.busy}>{t("取消")}</DialogCancel>
        <Button type="submit" className={ui.primary} disabled={action.busy || selected.length < 2}>{t("创建对话")}</Button></DialogActions>
    </DialogForm>
  </Dialog>;
}
