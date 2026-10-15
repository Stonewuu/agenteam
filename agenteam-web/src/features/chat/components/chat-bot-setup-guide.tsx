"use client";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {DetailSection} from "@/components/ui/detail-section";
import {Dialog, DialogActions, DialogCancel} from "@/components/ui/dialog";
import {IconBook2, IconExternalLink, IconKey, IconMessages, IconRobot, IconShield} from "@/components/ui/icons";
import {Guidance} from "@/components/ui/guidance";
import {Tabs} from "@/components/ui/tabs";
import {useT} from "@/lib/i18n/locale-provider";
import ui from "@/components/ui/surface.module.css";
import styles from "./chat.module.css";

export function ChatBotSetupGuide({providerCode, disabled = false}: {providerCode?: string; disabled?: boolean}) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState<"feishu" | "wecom_aibot">("feishu");
  const feishu = (providerCode ?? selected) === "feishu";
  return <>
    <Button type="button" className={ui.button} disabled={disabled} onClick={() => setOpen(true)}><IconBook2 size={16}/>{t("机器人配置指南")}</Button>
    {open && <Dialog title={t("机器人配置指南")} icon={<IconRobot size={22}/>} onClose={() => setOpen(false)}>
      <div className={ui.form}>
        {!providerCode && <Tabs value={selected} onChange={setSelected} label={t("机器人平台")} items={[
          {value: "feishu", label: t("飞书")}, {value: "wecom_aibot", label: t("企业微信")},
        ]}/>}
        <AnimatedHeight preserveControlShadows><div className={ui.form}>
        <DetailSection title={t("1. 准备平台机器人")} icon={<IconKey size={19}/>}>
          <p>{t(feishu ? "在飞书开发者后台创建企业自建应用并开启机器人能力，复制该应用的 App ID（应用编号）和 App Secret（应用密钥）。"
            : "在企业微信中准备支持 API 模式（通过接口接收和回复消息）的智能机器人，复制 Bot ID（机器人编号）和 Secret（专用密钥）。普通自建应用的 AgentId 不能用于此处。")}</p>
          {feishu && <a className={ui.button} href="https://open.feishu.cn/app" target="_blank" rel="noreferrer"><IconExternalLink size={16}/>{t("打开飞书开发者后台")}</a>}
        </DetailSection>
        <DetailSection title={t("2. 在群策关联智能体")} icon={<IconRobot size={19}/>}>
          <p>{t("填写平台凭据，选择智能体和岗位名称，点击“添加并启用”。")}</p>
        </DetailSection>
        <DetailSection title={t("3. 配置接收并发布")} icon={<IconShield size={19}/>}>
          {feishu ? <ul className={styles.guideSteps}>
            <li><strong>{t("接收消息：")}</strong>{t("在飞书“事件与回调”选择长连接，订阅“接收消息”事件。")}</li>
            <li><strong>{t("应用权限：")}</strong>{t("开通发送消息、接收群内提及和读取企业信息；按需要开通群查询、图片与文件权限。")}</li>
            <li><strong>{t("发布应用：")}</strong>{t("将使用成员加入应用可用范围，并发布版本使配置生效。")}</li>
          </ul> : <p>{t("在企业微信完成智能机器人的可用范围设置，再将机器人加入需要处理任务的群。")}</p>}
        </DetailSection>
        <DetailSection title={t("4. 选择群聊并开始使用")} icon={<IconMessages size={19}/>}>
          <ol className={styles.guideSteps}>
            <li>{t("使用者先在“个人设置 → 企业账号绑定”关联本人账号。")}</li>
            <li>{t(feishu ? "把机器人加入群聊，直接通过 @ 菜单选择机器人并描述任务。" : "把机器人加入群聊，回到群策的“聊天会话”选择参与岗位并保存。")}</li>
            {feishu && <li>{t("需要调整参与岗位或成果展示时，可在“聊天会话”中设置。")}</li>}
          </ol>
        </DetailSection>
        <Guidance title={t("一个机器人对应一个岗位")} icon={<IconRobot size={19}/>}>
          <p>{t("添加多个机器人时分别填写各自的应用凭据；个人飞书账号在当前企业绑定一次即可使用已配置的机器人。")}</p>
        </Guidance>
        </div></AnimatedHeight>
      </div>
      <DialogActions><DialogCancel className={ui.button}>{t("关闭")}</DialogCancel></DialogActions>
    </Dialog>}
  </>;
}
