"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Fieldset} from "@/components/ui/fieldset";
import {Button} from "@/components/ui/button";

import {IconPlus, IconTrash} from "@/components/ui/icons";
import type {AgentConfig} from "../types/resource";
import {type FieldErrors, TextField} from "./resource-fields";
import ui from "@/components/ui/surface.module.css";
import styles from "./business-terms-field.module.css";

export function BusinessTermsField({values, onChange, errors}: {
  values: AgentConfig["businessTerms"]; onChange: (values: AgentConfig["businessTerms"]) => void; errors: FieldErrors;
}) {
  const uiText = useT();
  return <Fieldset className={styles.terms}>
    <legend>{uiText("业务术语")}</legend>
    <p className={styles.description}>{uiText("补充常用简称或专有名称的含义。")}</p>
    {values.map((term, index) => <div className={styles.row} key={index}>
      <TextField label={uiText("术语")} name={`config.businessTerms.${index}.term`} required value={term.term}
                 onChange={(text) => onChange(values.map((item, at) => at === index ? {...item, term: text} : item))}
                 maximum={50} errors={errors}/>
      <TextField label={uiText("含义")} name={`config.businessTerms.${index}.meaning`} required value={term.meaning}
                 onChange={(meaning) => onChange(values.map((item, at) => at === index ? {...item, meaning} : item))}
                 maximum={500} errors={errors}/>
      <Button className={styles.remove} type="button" aria-label={uiText("移除术语{0}", [term.term || index + 1])}
              onClick={() => onChange(values.filter((_, at) => at !== index))}><IconTrash size={17}/></Button>
    </div>)}
    <Button className={`${ui.button} ${styles.add}`} type="button" disabled={values.length >= 100}
            onClick={() => onChange([...values, {term: "", meaning: ""}])}><IconPlus size={16}/>{uiText("添加术语")}
    </Button>
  </Fieldset>;
}
