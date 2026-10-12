"use client";

import {Input} from "@/components/ui/input";
import {Select} from "@/components/ui/select";
import {Checkbox} from "@/components/ui/checkbox";
import {Fieldset} from "@/components/ui/fieldset";
import type {JavaPlugin} from "../types/java-plugin";
import ui from "@/components/ui/surface.module.css";
import styles from "./java-plugin.module.css";
import {useT} from "@/lib/i18n/locale-provider";

export function JavaPluginFields({plugin, value, onChange, disabled}: {
  plugin: JavaPlugin;
  value: Record<string, string | number | boolean>;
  onChange: (value: Record<string, string | number | boolean>) => void;
  disabled: boolean;
}) {
  const t = useT();
  const update = (name: string, next: string | number | boolean | undefined) => {
    const updated = {...value};
    if (next === undefined) {
      delete updated[name];
    } else {
      updated[name] = next;
    }
    onChange(updated);
  };
  return <Fieldset disabled={disabled} className={`${ui.form} ${styles.fields}`}>
    {Object.entries(plugin.configurationSchema.properties).map(([name, field]) => {
      const required = plugin.configurationSchema.required?.includes(name) ?? false;
      const current = value[name];
      return <label className={ui.field} key={name}><span>{field.title}{required && t("（必填）")}</span>
        {field.enum ? <Select className={ui.select} value={current === undefined ? "" : String(current)} required={required}
          onChange={(event) => update(name, field.enum?.find((item) => String(item) === event.target.value))}>
          <option value="">{t("请选择")}</option>{field.enum.map((item) => <option key={String(item)} value={String(item)}>{String(item)}</option>)}
        </Select> : field.type === "boolean" ? <Checkbox checked={current === true} onCheckedChange={(checked) => update(name, Boolean(checked))}/>
          : <Input className={ui.input} type={field.type === "string" ? "text" : "number"}
            value={typeof current === "string" || typeof current === "number" ? current : ""}
            required={required} minLength={field.minLength} maxLength={field.maxLength ?? 4096}
            min={field.minimum} max={field.maximum} step={field.type === "integer" ? 1 : "any"}
            onChange={(event) => update(name, event.target.value === "" ? undefined
              : field.type === "string" ? event.target.value : event.target.valueAsNumber)}/>
        }
        {field.description && <small className={ui.description}>{field.description}</small>}
      </label>;
    })}
  </Fieldset>;
}
