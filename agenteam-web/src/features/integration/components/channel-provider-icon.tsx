import {BrandIcon} from "@/components/ui/brand-icon";
import {IconPlug} from "@/components/ui/icons";

/** 同一平台在标签、登录入口、账号与机器人连接中使用相同品牌标志。 */
export function ChannelProviderIcon({providerCode, size = 22}: {providerCode?: string; size?: number}) {
  if (providerCode === "feishu") {
    return <BrandIcon brand="feishu" size={size}/>;
  }
  if (providerCode === "wecom" || providerCode === "wecom_aibot") {
    return <BrandIcon brand="wecom" size={size}/>;
  }
  return <IconPlug size={size} variant="Bulk"/>;
}
