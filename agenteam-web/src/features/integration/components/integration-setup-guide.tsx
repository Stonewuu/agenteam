"use client";

import {useState} from "react";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {Button} from "@/components/ui/button";
import {Dialog, DialogActions, DialogCancel} from "@/components/ui/dialog";
import {DetailSection} from "@/components/ui/detail-section";
import {IconBell, IconBook2, IconBuilding, IconExternalLink, IconKey, IconPlug, IconShield} from "@/components/ui/icons";
import {Tabs} from "@/components/ui/tabs";
import {useT} from "@/lib/i18n/locale-provider";
import type {Integration} from "../types/integration";
import {IntegrationAddressField} from "./integration-address-field";
import ui from "@/components/ui/surface.module.css";
import styles from "./integration-setup-guide.module.css";

type Provider = "wecom" | "feishu";
type Topic = "parameters" | "addresses" | "activation";
type Addresses = Pick<Integration, "callbackUrl" | "loginUrl">;

/** 只在用户主动点击后挂载指南，不改变正在编辑的字段或接入状态。 */
export function IntegrationSetupGuide({providerCode, connection, defaultTopic = "parameters", disabled = false}: {
  providerCode?: string; connection?: Addresses | null; defaultTopic?: Topic; disabled?: boolean;
}) {
  const t = useT();
  const [open, setOpen] = useState(false);
  if (providerCode !== undefined && providerCode !== "wecom" && providerCode !== "feishu") {
    return null;
  }
  return <>
    <Button type="button" className={`${ui.button} ${styles.trigger}`} disabled={disabled}
      aria-haspopup="dialog" onClick={() => setOpen(true)}><IconBook2 size={16}/>{t("配置指南")}</Button>
    {open && <SetupGuideDialog providerCode={providerCode} connection={connection} defaultTopic={defaultTopic}
      onClose={() => setOpen(false)}/>}
  </>;
}

function SetupGuideDialog({providerCode, connection, defaultTopic, onClose}: {
  providerCode?: Provider; connection?: Addresses | null; defaultTopic: Topic; onClose: () => void;
}) {
  const t = useT();
  const [provider, setProvider] = useState<Provider>(providerCode ?? "feishu");
  const [topic, setTopic] = useState<Topic>(defaultTopic);
  const feishu = provider === "feishu";
  return <Dialog title={t(feishu ? "飞书配置指南" : "企业微信配置指南")} icon={<IconBook2 size={21}/>}
    onClose={onClose} className={styles.dialog}>
    <div className={styles.guide}>
      {!providerCode && <Tabs value={provider} onChange={setProvider} label={t("接入平台")}
        items={[{value: "feishu", label: t("飞书")}, {value: "wecom", label: t("企业微信")}]}/>}
      <Tabs value={topic} onChange={setTopic} label={t("配置指南内容")} variant="filter" items={[
        {value: "parameters", label: t("参数填写"), icon: IconKey},
        {value: "addresses", label: t("地址与通知"), icon: IconPlug},
        {value: "activation", label: t("启用步骤"), icon: IconShield},
      ]}/>
      <AnimatedHeight preserveControlShadows><div className={styles.content}>
        {topic === "parameters" && <>
          <DetailSection title={t("创建应用")} icon={<IconBuilding size={19}/> }>
            <p>{t(feishu ? "在飞书开发者后台创建企业自建应用，添加机器人能力，用于向成员发送个人通知。"
              : "在企业微信管理后台的应用管理中创建自建应用，将需要绑定的成员加入应用可见范围。")}</p>
            <a className={styles.link} href={feishu ? "https://open.feishu.cn/app" : "https://work.weixin.qq.com/wework_admin/frame"}
              target="_blank" rel="noreferrer">{t(feishu ? "打开飞书开发者后台" : "打开企业微信管理后台")}<IconExternalLink size={15}/></a>
          </DetailSection>
          <DetailSection title={t("填写接入参数")} icon={<IconKey size={19}/> }>
            <dl className={styles.parameters}>
              <GuideParameter name={t("接入名称")} description={t("填写便于成员识别的名称，可与平台中的应用名称不同。")}/>
              {!feishu && <GuideParameter name={t("企业编号（CorpID）")} description={t("从企业微信管理后台“我的企业 → 企业信息”复制企业 ID。")}/>}
              <GuideParameter name={t(feishu ? "应用编号（App ID）" : "应用编号（AgentId）")}
                description={t(feishu ? "从飞书应用的“凭证与基础信息”复制 App ID（应用编号）。"
                  : "从企业微信自建应用详情复制 AgentId（应用编号），填写数字。")}/>
              <GuideParameter name={t("应用密钥（Secret）")} description={t(feishu
                ? "从同一飞书应用的“凭证与基础信息”复制 App Secret（应用密钥）。"
                : "从同一企业微信自建应用详情复制 Secret（应用密钥），与上面的应用编号对应。")}/>
              {!feishu && <GuideParameter name={t("每日通知发送上限")} description={t("按企业微信允许的每日消息量设置。此值限制本平台的发送量，不会提高企业微信的额度。")}/>}
            </dl>
          </DetailSection>
          {feishu && <DetailSection title={t("申请应用权限")} icon={<IconShield size={19}/> }>
            <dl className={styles.parameters}>
              <GuideParameter name="im:message:send_as_bot" description={t("以应用的身份发消息，用于个人通知。")}/>
              <GuideParameter name="tenant:tenant:readonly" description={t("获取企业信息，用于校验应用所属企业。")}/>
            </dl><p className={styles.note}>{t("将需要绑定的成员加入应用可用范围，并发布版本，使能力与权限生效。")}</p>
          </DetailSection>}
        </>}
        {topic === "addresses" && <>
          <DetailSection title={t("授权回调地址")} icon={<IconShield size={19}/> }>
            <p>{t(feishu ? "填入飞书应用“安全设置 → 重定向 URL”，完整复制协议、域名和路径。账号绑定和授权登录共用此地址。"
              : "按自建应用的网页授权与 Web 登录设置配置可信域名和授权回调域。域名取自下方回调地址；若要求完整回调地址，则复制整个地址。")}</p>
            {connection?.callbackUrl ? <IntegrationAddressField label={t("授权回调地址")} value={connection.callbackUrl}/>
              : <p className={styles.note}>{t("先保存接入，再打开“查看配置”复制生成的授权回调地址。")}</p>}
          </DetailSection>
          <DetailSection title={t("企业登录链接")} icon={<IconPlug size={19}/> }>
            <p>{t(feishu ? "如需从飞书工作台打开登录入口，添加网页应用能力，将 PC 端和移动端主页填写为本接入的企业登录链接。"
              : "如需从企业微信工作台打开登录入口，将应用主页填写为本接入的企业登录链接。")}</p>
            {connection?.loginUrl ? <IntegrationAddressField label={t("企业登录链接")} value={connection.loginUrl}/>
              : <p className={styles.note}>{t("企业登录链接在保存接入后生成，可从“查看配置”复制。")}</p>}
            <p className={styles.note}>{t("此链接供成员打开登录页面，不能填作授权回调地址。成员需先绑定账号并允许通过此接入登录。")}</p>
          </DetailSection>
          <DetailSection title={t("消息相关地址")} icon={<IconBell size={19}/> }>
            <p>{t("发送个人通知无需填写消息发送地址。开启应用的发送能力后，本平台会按已绑定的账号发送。")}</p>
            <p className={styles.note}>{t(feishu
              ? "本接入不需要配置“事件与回调”的请求地址或消息卡片回调地址，保持未配置即可。授权回调地址只用于账号授权。"
              : "本接入不需要配置“接收消息”的服务器地址、Token（验证令牌）或 EncodingAESKey（消息加密密钥）。授权回调地址只用于账号授权。")}</p>
            {!feishu && <p className={styles.note}>{t("在企业微信应用的可信 IP 中配置部署服务器的实际公网出口地址，网页域名不能代替出口地址。")}</p>}
          </DetailSection>
        </>}
        {topic === "activation" && <>
          <DetailSection title={t("成员可用功能")} icon={<IconPlug size={19}/> }>
            <dl className={styles.parameters}>
              <GuideParameter name={t("允许成员绑定账号")} description={t("开启后，成员可在个人设置中授权并绑定此渠道，已有其他渠道的绑定会保留。")}/>
              <GuideParameter name={t("允许已绑定成员登录")} description={t("开启后，普通成员还需在个人绑定中允许登录。超级管理员继续使用本地账号登录。")}/>
              <GuideParameter name={t("允许发送通知")} description={t("开启后可向已绑定且允许接收的成员发通知。自动工作提醒还需成员按类别主动开启。")}/>
            </dl>
          </DetailSection>
          <DetailSection title={t("完成配置并验证")} icon={<IconShield size={19}/> }>
            <ol className={styles.steps}>
              <li>{t("保存接入，复制生成的地址并完成平台后台设置。")}</li>
              <li>{t(feishu ? "发布飞书应用版本，按企业要求完成审核，确认使用成员在应用可用范围内。"
                : "确认使用成员在企业微信应用可见范围内，完成可信域名和可信 IP 设置。")}</li>
              <li>{t("回到接入列表，在更多操作中点击“校验应用”，通过后点击“启用”。")}</li>
              <li>{t("成员在“个人设置 → 企业账号绑定”完成授权，按需要打开接收通知、登录和自动通知类别。")}</li>
              <li>{t("在接入卡片点击“测试通知”，选择已绑定成员，确认该成员实际收到消息。")}</li>
            </ol><p className={styles.note}>{t("更换应用密钥后，需要重新校验并启用接入。")}</p>
          </DetailSection>
        </>}
      </div></AnimatedHeight>
    </div>
    <DialogActions><DialogCancel className={ui.button}>{t("关闭")}</DialogCancel></DialogActions>
  </Dialog>;
}

function GuideParameter({name, description}: {name: string; description: string}) {
  return <div><dt>{name}</dt><dd>{description}</dd></div>;
}
