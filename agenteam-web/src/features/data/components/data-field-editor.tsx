"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Fieldset} from "@/components/ui/fieldset";
import {Input} from "@/components/ui/input";
import {Checkbox} from "@/components/ui/checkbox";
import {Button} from "@/components/ui/button";

import {Select} from "@/components/ui/select";


import {type DataField, type DataValueType, dataValueTypes} from "../types/data";
import ui from "@/components/ui/surface.module.css";
import styles from "./data.module.css";

export function DataFieldEditor({fields, onChange, editableNames, canAdd = false, disabled = false}: {
  fields: DataField[];
  onChange: (fields: DataField[]) => void;
  editableNames: boolean;
  canAdd?: boolean;
  disabled?: boolean;
}) {
  const uiText = useT();

  function change(index: number, value: Partial<DataField>) {
    onChange(fields.map((field, at) => at === index ? {...field, ...value, ...(value.valueType === "object" ? {sortable: false} : {})} : field));
  }

  function move(index: number, delta: number) {
    const next = [...fields];
    [next[index], next[index + delta]] = [next[index + delta], next[index]];
    onChange(next.map((field, ordinal) => ({...field, ordinal})));
  }

  return <Fieldset className={styles.fieldset} disabled={disabled}>
    <legend>{uiText("字段")}</legend>
    <div className={styles.tableScroll}>
      <table className={styles.fields}>
        <thead>
        <tr>
          <th>{uiText("字段名")}</th>
          <th>{uiText("显示名称")}</th>
          <th>{uiText("类型")}</th>
          <th>{uiText("允许读取")}</th>
          <th>{uiText("允许筛选")}</th>
          <th>{uiText("允许排序")}</th>
          <th>{uiText("隐藏内容")}</th>
          <th>{uiText("允许空值")}</th>
          <th>{uiText("调整")}</th>
        </tr>
        </thead>
        <tbody>{fields.map((field, index) => <tr key={index}>
          <td><Input className={ui.input} aria-label={uiText("字段 {0} 的名称", [index + 1])} value={field.name}
                     required maxLength={128} readOnly={!editableNames}
                     onChange={(event) => change(index, {name: event.target.value})}/></td>
          <td><Input className={ui.input} aria-label={uiText("字段 {0} 的显示名称", [index + 1])} value={field.label}
                     required maxLength={80} onChange={(event) => change(index, {label: event.target.value})}/></td>
          <td><Select className={ui.select} aria-label={uiText("{0}的类型", [field.label || uiText("字段")])}
                      value={field.valueType}
                      onChange={(event) => change(index, {valueType: event.target.value as DataValueType})}>
            {Object.entries(localizeCatalog(dataValueTypes, uiText)).map(([value, label]) => <option key={value}
                                                                                                     value={value}>{label}</option>)}
          </Select></td>
          {(["readable", "filterable", "sortable", "sensitive", "nullable"] as const).map((key) => <td key={key}>
            <Checkbox checked={field[key]} disabled={key === "sortable" && field.valueType === "object"}
                      aria-label={`${field.label || uiText("字段 {0}", [index + 1])}：${{
                        readable: uiText("允许读取"),
                        filterable: uiText("允许筛选"),
                        sortable: uiText("允许排序"),
                        sensitive: uiText("隐藏内容"),
                        nullable: uiText("允许空值")
                      }[key]}`}
                      onCheckedChange={(checked) => change(index, {[key]: checked})}/></td>)}
          <td>
            <div className={styles.rowActions}><Button type="button" className={ui.button} disabled={index === 0}
                                                       aria-label={uiText("上移字段 {0}", [index + 1])}
                                                       onClick={() => move(index, -1)}>↑</Button>
              <Button type="button" className={ui.button} disabled={index === fields.length - 1}
                      aria-label={uiText("下移字段 {0}", [index + 1])} onClick={() => move(index, 1)}>↓</Button>
              <Button type="button" className={ui.button} disabled={fields.length === 1}
                      aria-label={uiText("移除字段 {0}", [index + 1])}
                      onClick={() => onChange(fields.filter((_, at) => at !== index).map((field, ordinal) => ({
                        ...field,
                        ordinal
                      })))}>{uiText("移除")}</Button></div>
          </td>
        </tr>)}</tbody>
      </table>
    </div>
    {canAdd && <Button type="button" className={ui.button} disabled={fields.length >= 100}
                       onClick={() => onChange([...fields, emptyField(fields.length)])}>{uiText("添加字段")}</Button>}
  </Fieldset>;
}

export function emptyField(ordinal = 0): DataField {
  return {
    name: "",
    label: "",
    valueType: "string",
    readable: true,
    filterable: true,
    sortable: true,
    sensitive: false,
    nullable: true,
    ordinal
  };
}
