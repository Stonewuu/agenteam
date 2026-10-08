"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Fieldset} from "@/components/ui/fieldset";
import {Checkbox} from "@/components/ui/checkbox";
import {Textarea} from "@/components/ui/textarea";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";

import {Select} from "@/components/ui/select";


import {useState} from "react";
import {Dialog} from "@/components/ui/dialog";
import {apiRequest} from "@/lib/http/api-client";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import type {DataCollection, DataField, DataFilter, DataQuery, DataQueryResult} from "../types/data";
import {DataTable} from "./data-table";
import ui from "@/components/ui/surface.module.css";
import styles from "./data.module.css";

type FilterDraft = { field: string; operator: DataFilter["operator"]; text: string };
const operators: Record<DataFilter["operator"], string> = {
  eq: "等于",
  ne: "不等于",
  gt: "大于",
  gte: "大于或等于",
  lt: "小于",
  lte: "小于或等于",
  in: "在这些值中",
  contains: "包含文字",
  is_null: "是否为空"
};

export function DataQueryDialog({enterpriseId, resourceId, collection, onClose, onReload}: {
  enterpriseId: string;
  resourceId: string;
  collection: DataCollection;
  onClose: () => void;
  onReload: () => void
}) {
  const uiText = useT();
  return <Dialog title={uiText("查询“{0}”", [collection.name])} onClose={onClose} wide><DataQueryForm
    key={`${collection.id}:${collection.activeGeneration}:${collection.revision}`} enterpriseId={enterpriseId}
    resourceId={resourceId} collection={collection} onReload={onReload}/></Dialog>;
}

export function DataQueryForm({enterpriseId, resourceId, collection, onReload}: {
  enterpriseId: string;
  resourceId: string;
  collection: DataCollection;
  onReload: () => void
}) {
  const uiText = useT();
  const available = collection.fields.filter((field) => field.readable);
  const filterable = available.filter((field) => field.filterable);
  const sortable = available.filter((field) => field.sortable);
  const [fields, setFields] = useState(available.map((field) => field.name));
  const [filters, setFilters] = useState<FilterDraft[]>([]);
  const [sort, setSort] = useState<DataQuery["sort"]>([]);
  const [limit, setLimit] = useState(50);
  const [result, setResult] = useState<{ request: DataQuery; value: DataQueryResult; previous: number[] } | null>(null);
  const action = useFormAction();
  const path = organizationPath(enterpriseId, `/data/${encodeURIComponent(resourceId)}/query`);

  function run(query: DataQuery, previous: number[] = []) {
    setResult(null);
    void action.execute(async () => {
      const value = await apiRequest<DataQueryResult>(path, {method: "POST", body: query, timeoutMs: 15000});
      setResult({request: query, value, previous});
    }, "");
  }

  function editFilter(index: number, value: Partial<FilterDraft>) {
    setResult(null);
    setFilters(filters.map((filter, at) => at === index ? {...filter, ...value} : filter));
  }

  return <form className={ui.form} onSubmit={(event) => {
    event.preventDefault();
    setResult(null);
    void action.execute(async () => {
      const query: DataQuery = {
        collectionId: collection.id,
        generation: collection.activeGeneration,
        fields,
        filters: filters.map((filter) => parse(filter, filterable)),
        sort,
        limit,
        offset: 0
      };
      const value = await apiRequest<DataQueryResult>(path, {method: "POST", body: query, timeoutMs: 15000});
      setResult({request: query, value, previous: []});
    }, "");
  }}><Fieldset className={styles.fieldset} disabled={action.busy}>
    <legend>{uiText("返回字段")}</legend>
    <div className={styles.choices}>
      {available.map((field) => <label key={field.name}><Checkbox checked={fields.includes(field.name)}
                                                                  onCheckedChange={(checked) => {
                                                                    setResult(null);
                                                                    setFields(checked ? [...fields, field.name] : fields.filter((name) => name !== field.name));
                                                                  }}/>{field.label}{field.sensitive ? uiText("（内容隐藏）") : ""}
      </label>)}
    </div>
  </Fieldset>
    <Fieldset className={styles.fieldset} disabled={action.busy}>
      <legend>{uiText("筛选条件")}</legend>
      {filters.map((filter, index) => <div className={styles.condition} key={index}>
        <Select className={ui.select} aria-label={uiText("条件 {0} 的字段", [index + 1])} value={filter.field}
                onChange={(event) => editFilter(index, {field: event.target.value, operator: "eq", text: ""})}>
          {filterable.map((field) => <option key={field.name} value={field.name}>{field.label}</option>)}
        </Select><Select className={ui.select} aria-label={uiText("条件 {0} 的比较方式", [index + 1])}
                         value={filter.operator} onChange={(event) => editFilter(index, {
        operator: event.target.value as DataFilter["operator"],
        text: ""
      })}>
        {Object.entries(localizeCatalog(operators, uiText)).filter(([operator]) => supports(filterable.find((field) => field.name === filter.field), operator)).map(([value, label]) =>
          <option key={value} value={value}>{label}</option>)}
      </Select>
        {filter.operator === "is_null" || (filterable.find((field) => field.name === filter.field)?.valueType === "boolean" && filter.operator !== "in") ?
          <Select className={ui.select} aria-label={uiText("条件 {0} 的值", [index + 1])} value={filter.text || "true"}
                  onChange={(event) => editFilter(index, {text: event.target.value})}>
            <option value="true">{filter.operator === "is_null" ? uiText("为空") : "true"}</option>
            <option value="false">{filter.operator === "is_null" ? uiText("不为空") : "false"}</option>
          </Select> :
          <Textarea className={ui.textarea} rows={1} maxLength={10000} aria-label={uiText("条件 {0} 的值", [index + 1])}
                    placeholder={filter.operator === "in" ? uiText("每行一个值") : filterable.find((field) => field.name === filter.field)?.valueType === "object" ? uiText("填写 JSON 对象") : uiText("填写比较值")}
                    value={filter.text} onChange={(event) => editFilter(index, {text: event.target.value})}/>}
        <Button type="button" className={ui.button} aria-label={uiText("移除条件 {0}", [index + 1])} onClick={() => {
          setResult(null);
          setFilters(filters.filter((_, at) => at !== index));
        }}>{uiText("移除")}</Button>
      </div>)}
      <Button type="button" className={ui.button} disabled={!filterable.length || filters.length >= 20} onClick={() => {
        setResult(null);
        setFilters([...filters, {field: filterable[0].name, operator: "eq", text: ""}]);
      }}>{uiText("添加条件")}</Button>
    </Fieldset>
    <Fieldset className={styles.fieldset} disabled={action.busy}>
      <legend>{uiText("排序")}</legend>
      {sort.map((item, index) => <div className={styles.condition} key={index}>
        <Select className={ui.select} aria-label={uiText("排序 {0} 的字段", [index + 1])} value={item.field}
                onChange={(event) => {
                  setResult(null);
                  setSort(sort.map((value, at) => at === index ? {...value, field: event.target.value} : value));
                }}>
          {sortable.map((field) => <option key={field.name} value={field.name}
                                           disabled={sort.some((item, at) => at !== index && item.field === field.name)}>{field.label}</option>)}
        </Select><Select className={ui.select} aria-label={uiText("排序 {0} 的方向", [index + 1])}
                         value={item.direction} onChange={(event) => {
        setResult(null);
        setSort(sort.map((value, at) => at === index ? {
          ...value,
          direction: event.target.value as "asc" | "desc"
        } : value));
      }}>
        <option value="asc">{uiText("升序")}</option>
        <option value="desc">{uiText("降序")}</option>
      </Select>
        <Button type="button" className={ui.button} onClick={() => {
          setResult(null);
          setSort(sort.filter((_, at) => at !== index));
        }}>{uiText("移除")}</Button>
      </div>)}<Button type="button" className={ui.button}
                      disabled={sort.length >= 3 || !sortable.some((field) => !sort.some((item) => item.field === field.name))}
                      onClick={() => {
                        const field = sortable.find((field) => !sort.some((item) => item.field === field.name));
                        if (field) {
                          setResult(null);
                          setSort([...sort, {field: field.name, direction: "asc"}]);
                        }
                      }}>{uiText("添加排序")}</Button></Fieldset>
    <label className={ui.field}><span>{uiText("每次返回行数")}</span><Input className={ui.input} type="number" required
                                                                            min={1} max={200} step={1} value={limit}
                                                                            disabled={action.busy}
                                                                            onChange={(event) => {
                                                                              setResult(null);
                                                                              setLimit(event.target.valueAsNumber);
                                                                            }}/></label>
    <MutationFeedback action={action} onReload={onReload}/>
    <div className={ui.actions}><Button className={ui.primary}
                                        disabled={action.busy || !fields.length}>{action.busy ? uiText("正在查询…") : uiText("查询")}</Button>
    </div>
    {result && <><p
      className={ui.description}>{uiText("返回 ")}{result.value.rows.length}{uiText(" 行，用时 ")}{result.value.durationMs}{uiText(" 毫秒。")}{result.value.truncated ? uiText("本次结果达到大小上限。") : ""}</p>
      {result.value.rows.length ? <DataTable fields={result.value.fields} rows={result.value.rows}/> :
        <p className={ui.empty}>{uiText("没有符合条件的数据。")}</p>}
      {(result.previous.length > 0 || result.value.hasMore) &&
        <nav className={ui.pagination} aria-label={uiText("查询结果翻页")}><Button type="button" className={ui.button}
                                                                                   disabled={action.busy || !result.previous.length}
                                                                                   onClick={() => run({
                                                                                     ...result.request,
                                                                                     offset: result.previous.at(-1)!
                                                                                   }, result.previous.slice(0, -1))}>{uiText("上一页")}</Button>
          <Button type="button" className={ui.button}
                  disabled={action.busy || !result.value.hasMore || !result.value.rows.length || result.request.offset + result.value.rows.length > 100000}
                  onClick={() => run({
                    ...result.request,
                    offset: result.request.offset + result.value.rows.length
                  }, [...result.previous, result.request.offset])}>{uiText("下一页")}</Button></nav>}
    </>}
  </form>;
}

function supports(field: DataField | undefined, operator: string) {
  if (operator === "contains") {
    return field?.valueType === "string";
  }
  return !["gt", "gte", "lt", "lte"].includes(operator) || !["object", "boolean"].includes(field?.valueType ?? "");
}

function parse(filter: FilterDraft, fields: DataField[]): DataFilter {
  const field = fields.find((field) => field.name === filter.field);
  if (!field) {
    throw new Error("所选字段已不可筛选，请重新选择。");
  }
  if (filter.operator === "is_null") {
    return {field: filter.field, operator: filter.operator, value: filter.text !== "false"};
  }

  function value(text: string): unknown {
    if (field!.valueType === "object") {
      try {
        const value = JSON.parse(text);
        if (value === null || Array.isArray(value) || typeof value !== "object") {
          throw new Error();
        }
        return value;
      } catch {
        throw new Error(`“${field!.label}”需要填写 JSON 对象。`);
      }
    }
    if (field!.valueType === "boolean") {
      const input = text.trim() || "true";
      if (!["true", "false"].includes(input)) {
        throw new Error(`“${field!.label}”只接受 true 或 false。`);
      }
      return input === "true";
    }
    return text;
  }

  const selected = filter.operator === "in" ? filter.text.split(/\r?\n/).map(value) : value(filter.text);
  if (Array.isArray(selected) && selected.length > 100) {
    throw new Error("同一个条件最多比较一百个值。");
  }
  return {field: filter.field, operator: filter.operator, value: selected};
}
