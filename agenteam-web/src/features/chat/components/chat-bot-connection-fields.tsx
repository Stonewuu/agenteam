"use client";

import {Field} from "@/components/ui/field";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Select} from "@/components/ui/select";
import {DetailHeading} from "@/components/ui/detail-section";
import {Guidance} from "@/components/ui/guidance";
import {IconRobot} from "@/components/ui/icons";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {useT} from "@/lib/i18n/locale-provider";
import type {useChatBotConnection} from "../hooks/use-chat-bot-connection";
import {ChatBotSetupGuide} from "./chat-bot-setup-guide";
import styles from "./chat.module.css";
import ui from "@/components/ui/surface.module.css";

export function ChatBotConnectionFields({connection, disabled, errors, canTest}: {
  connection: ReturnType<typeof useChatBotConnection>; disabled: boolean; errors: Record<string, string[]>; canTest: boolean;
}) {
  const t = useT();
  const locked = Boolean(connection.saved);
  const choices = connection.apps.data?.items.filter((value) => value.status === "enabled" || value.purpose === "chat_bot") ?? [];
  return <div className={styles.fields}>
    <DetailHeading title={t("机器人身份")} icon={<IconRobot size={18}/>} action={<ChatBotSetupGuide providerCode={connection.provider} disabled={disabled}/>}/>
    <Field label={t("平台")} required><Select value={connection.provider} disabled={disabled || locked} onChange={(event) => {
      connection.setProvider(event.target.value);
      connection.setConnectionId("");
      connection.setAppId("");
      connection.setSecret("");
    }}><option value="feishu">{t("飞书")}</option><option value="wecom_aibot">{t("企业微信")}</option></Select></Field>
    {!locked && connection.source === "new" && <Guidance title={t(connection.provider === "feishu" ? "准备飞书应用的机器人凭据" : "准备企微智能机器人的专用凭据")} icon={<IconRobot size={19}/>}>
      <p>{t(connection.provider === "feishu" ? "在飞书开发者后台开启应用的机器人能力，从“凭证与基础信息”复制下方两项。"
        : "使用企业微信智能机器人 API 模式提供的 Bot ID 和 Secret；普通自建应用的凭据不能用于群聊机器人。")}</p>
    </Guidance>}
    {!locked && canTest && <div><Button type="button" className={ui.button} disabled={disabled} onClick={() => connection.setSource(connection.source === "new" ? "existing" : "new")}>
      {t(connection.source === "new" ? "使用已有应用" : "添加新机器人")}
    </Button></div>}
    {connection.source === "new" || locked ? <div className={styles.limits}>
      <Field label={t(connection.provider === "feishu" ? "应用编号（App ID）" : "机器人编号（Bot ID）")} required error={errors.externalAppId?.join(" ")}>
        <Input name="externalAppId" required value={connection.saved?.externalAppId ?? connection.appId} disabled={disabled || locked} maxLength={191}
          autoComplete="off" onChange={(event) => connection.setAppId(event.target.value)}/>
      </Field>
      {(!locked || connection.saved?.status !== "enabled") && <Field label={t("机器人密钥（Secret）")} required={!locked} error={errors.secret?.join(" ")}
        hint={locked ? t("需要更正密钥时填写，留空继续使用已保存的密钥。") : undefined}>
        <Input name="secret" type="password" required={!locked} value={connection.secret} disabled={disabled} maxLength={8192}
          autoComplete="new-password" onChange={(event) => connection.setSecret(event.target.value)}/>
      </Field>}
    </div> : <QueryState {...connection.apps} hasData={choices.length > 0} empty={t(canTest ? "此平台暂无可用应用，请添加新机器人。" : "此平台暂无可用应用，请联系管理员配置机器人。")}>
      <Field label={t("选择应用")} required error={errors.connectionId?.join(" ")}><Select required value={connection.connectionId} disabled={disabled} onChange={(event) => connection.setConnectionId(event.target.value)}>
        <option value="">{t("请选择")}</option>{choices.map((value) => <option key={value.id} value={value.id}>{value.name}</option>)}
      </Select></Field><Pagination {...connection.apps} hasMore={connection.apps.data?.hasMore}/>
    </QueryState>}
  </div>;
}
