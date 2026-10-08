"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Fieldset} from "@/components/ui/fieldset";
import {Button} from "@/components/ui/button";

import {useId, useState} from "react";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import {randomResourceAppearance, resourceColors, resourceIcons} from "@/components/ui/resource-appearance";
import {IconChevronDown} from "@/components/ui/icons";
import {RadioGroup, RadioGroupItem} from "@/components/ui/radio-group";
import {Popover, PopoverContent, PopoverTitle, PopoverTrigger} from "@/components/ui/shadcn/popover";
import type {Appearance} from "../types/resource";
import styles from "./appearance-fields.module.css";
import ui from "@/components/ui/surface.module.css";

export function AppearanceFields({value, onChange, disabled = false}: {
  value: Appearance;
  onChange: (value: Appearance) => void;
  disabled?: boolean
}) {
  const uiText = useT();
  const group = useId();
  const [open, setOpen] = useState(false);
  return <Popover open={open} onOpenChange={setOpen}>
    <PopoverTrigger type="button" className={`${ui.button} ${styles.trigger}`} disabled={disabled}
                    aria-label={uiText("选择图标与配色")}>
      <ResourceAvatar icon={value.icon} color={value.color}
                      size="small"/><span>{uiText("更换图标")}</span><IconChevronDown size={16}/>
    </PopoverTrigger>
    <PopoverContent className={`agenteam-popup ${styles.popup}`} align="start" sideOffset={8}>
      <PopoverTitle className={styles.title}>{uiText("图标与配色")}</PopoverTitle>
      <Fieldset className={styles.field} disabled={disabled}>
        <legend>{uiText("图标")}</legend>
        <RadioGroup className={styles.icons} name={`${group}-resource-icon`} value={value.icon}
                    onValueChange={(icon) => onChange({...value, icon: String(icon)})} aria-label={uiText("图标")}>
          {localizeCatalog(resourceIcons, uiText).map(([icon, name]) => <RadioGroupItem key={icon}
                                                                                        className={styles.choice}
                                                                                        title={name} value={icon}
                                                                                        aria-label={name}>
            <ResourceAvatar icon={icon} color={value.color} size="small"/>
          </RadioGroupItem>)}
        </RadioGroup></Fieldset>
      <Fieldset className={styles.field} disabled={disabled}>
        <legend>{uiText("配色")}</legend>
        <RadioGroup className={styles.colors} name={`${group}-resource-color`} value={value.color}
                    onValueChange={(color) => onChange({...value, color: color as Appearance["color"]})}
                    aria-label={uiText("配色")}>
          {localizeCatalog(resourceColors, uiText).map(([color, name]) => <RadioGroupItem key={color}
                                                                                          className={`${styles.choice} ${styles.color}`}
                                                                                          title={name} value={color}
                                                                                          aria-label={name}>
            <ResourceAvatar icon={value.icon} color={color} size="small"/>
          </RadioGroupItem>)}
        </RadioGroup></Fieldset>
      <div className={styles.footer}>
        <Button type="button" className={ui.button} disabled={disabled}
                onClick={() => onChange(randomResourceAppearance())}>{uiText("随机搭配")}</Button>
        <Button type="button" className={ui.button} onClick={() => setOpen(false)}>{uiText("完成")}</Button>
      </div>
    </PopoverContent>
  </Popover>;
}
