"use client";

import {Button} from "@/components/ui/button";
import {Field} from "@/components/ui/field";
import {IconCopy} from "@/components/ui/icons";
import {Input} from "@/components/ui/input";
import {toast} from "@/components/ui/toast";
import {useT} from "@/lib/i18n/locale-provider";
import styles from "./integration.module.css";

/** 地址来自已保存接入的接口响应，详情和指南共用相同的复制入口。 */
export function IntegrationAddressField({label, value}: {label: string; value: string}) {
  const t = useT();
  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      toast.success(t("已复制"));
    } catch (error) {
      console.error("复制接入地址失败", error);
      toast.error(t("复制失败，请选中地址后手动复制。"));
    }
  }
  return <Field label={label}><div className={styles.copyField}>
    <Input aria-label={label} readOnly value={value} onFocus={(event) => event.target.select()}/>
    <Button type="button" className="icon-button" aria-label={t("复制{0}", [label])} onClick={() => void copy()}>
      <IconCopy size={17}/>
    </Button>
  </div></Field>;
}
