"use client";

import {useState} from "react";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {Button} from "@/components/ui/button";
import {Checkbox} from "@/components/ui/checkbox";
import {Field} from "@/components/ui/field";
import {Input} from "@/components/ui/input";
import {IconUserCircle, IconX, IconInbox, IconPlug} from "@/components/ui/icons";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useApiPage} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import type {ScheduleChannel, ScheduleRecipient} from "../types/schedule";
import styles from "./schedule-notification.module.css";
import ui from "@/components/ui/surface.module.css";
import presentation from "./schedule-presentation.module.css";

export function ScheduleRecipientPicker({enterpriseId, selected, onChange, error, disabled}: {
  enterpriseId: string; selected: ScheduleRecipient[]; onChange: (value: ScheduleRecipient[]) => void; error?: string; disabled: boolean;
}) {
  const t = useT();
  const [query, setQuery] = useState("");
  const list = useApiPage<ScheduleRecipient>(organizationPath(enterpriseId, `/schedule-recipients?query=${encodeURIComponent(query)}`));
  function togglePerson(person: ScheduleRecipient, checked: boolean) {
    onChange(checked ? [...selected, {...person, channels: []}] : selected.filter(value => value.userId !== person.userId));
  }
  function toggleChannel(person: ScheduleRecipient, channel: ScheduleChannel, checked: boolean) {
    onChange(selected.map(value => value.userId !== person.userId ? value : {...value, channels: checked
      ? [...value.channels.filter(current => current.providerCode !== channel.providerCode), channel]
      : value.channels.filter(current => current.connectionId !== channel.connectionId)}));
  }
  return <div className={styles.recipients}>
    <Field label={t("接收人")} required error={error} hint={t("每位接收人都会收到站内通知，也可以选择其已绑定的企业消息渠道。最多选择 50 人。") }>
      <Input name="action.config.recipients" type="search" value={query} placeholder={t("搜索接收成员")}
        disabled={disabled} onChange={event => setQuery(event.target.value)}/>
    </Field>
    <AnimatedHeight preserveControlShadows>
      {selected.length > 0 && <div className={styles.selection} aria-label={t("已选接收人")}>
        {selected.map(person => <div key={person.userId} className={styles.selected}>
          <div><strong className={styles.selectedName}><IconUserCircle size={18}/>{person.name}</strong><div className={presentation.channels}>
            <span><IconInbox size={13}/>{t("站内通知")}</span>{person.channels.map(channel => <span key={channel.connectionId}><IconPlug size={13}/>{channel.name}</span>)}
          </div></div>
          <Button type="button" disabled={disabled} aria-label={t("移除{0}", [person.name])}
            className="icon-button" onClick={() => onChange(selected.filter(value => value.userId !== person.userId))}><IconX size={16}/></Button>
        </div>)}
      </div>}
    </AnimatedHeight>
    <QueryState {...list} hasData={Boolean(list.data?.items.length)} empty={t("没有符合条件的接收成员。")}>
      <div className={styles.choices}>{list.data?.items.map(person => {
        const current = selected.find(value => value.userId === person.userId);
        return <div className={styles.choice} data-selected={Boolean(current)} key={person.userId}>
          <label className={styles.check}><Checkbox checked={Boolean(current)} disabled={disabled || !current && selected.length >= 50}
            onCheckedChange={checked => togglePerson(person, checked === true)}/><IconUserCircle size={19}/><strong>{person.name}</strong></label>
          <AnimatedHeight preserveControlShadows>{current && person.channels.length > 0 && <div className={styles.channels}>
            {person.channels.map(channel => <label className={styles.check} key={channel.connectionId}>
              <Checkbox disabled={disabled} checked={current.channels.some(value => value.connectionId === channel.connectionId)}
                onCheckedChange={checked => toggleChannel(person, channel, checked === true)}/>
              <IconPlug size={16}/><span>{channel.name}{channel.providerName ? <small>{channel.providerName}</small> : null}</span>
            </label>)}
          </div>}</AnimatedHeight>
        </div>;
      })}</div>
    </QueryState>
    <Pagination {...list} hasMore={list.data?.hasMore}/>
    {selected.length > 0 && <p className={ui.description}>{t("已选择 {0} 人", [selected.length])}</p>}
  </div>;
}
