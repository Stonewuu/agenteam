"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Select} from "@/components/ui/select";


import type {DataConfig} from "@/features/resource/types/resource";
import {
  type FieldErrors,
  NumberField,
  Section,
  StringListField,
  TextField
} from "@/features/resource/components/resource-fields";
import {CredentialPicker} from "@/features/plugin/components/credential-picker";
import ui from "@/components/ui/surface.module.css";

export function DataConfigForm({enterpriseId, value, onChange, permissions, errors, readOnly}: {
  enterpriseId: string;
  value: DataConfig;
  onChange: (value: DataConfig) => void;
  permissions: string[];
  errors: FieldErrors;
  readOnly: boolean;
}) {
  const uiText = useT();
  return <Section title={uiText("数据来源")}><label className={ui.field}><span>{uiText("来源类型")}</span><Select
    className={ui.select} value={value.sourceType} disabled={readOnly} onChange={(event) => {
    const sourceType = event.target.value as DataConfig["sourceType"];
    onChange({
      ...value,
      sourceType,
      credentialId: null,
      connection: sourceType === "mysql" ? {host: "", port: 3306, database: ""} : sourceType === "http" ? {
        endpoint: "",
        queryParameters: []
      } : {}
    });
  }}>
    <option value="file">{uiText("CSV 文件")}</option>
    <option value="mysql">{uiText("MySQL 数据库")}</option>
    <option value="http">{uiText("加密数据接口")}</option>
  </Select></label>
    {value.sourceType === "mysql" && <>
      <TextField label={uiText("数据库主机")} name="config.connection.host" value={value.connection.host ?? ""} required
                 maximum={253} errors={errors}
                 onChange={(host) => onChange({...value, connection: {...value.connection, host}})}/>
      <div className={ui.columns}><NumberField label={uiText("端口")} name="config.connection.port"
                                               value={value.connection.port ?? 3306} min={1} max={65535} errors={errors}
                                               onChange={(port) => onChange({
                                                 ...value,
                                                 connection: {...value.connection, port}
                                               })}/>
        <TextField label={uiText("数据库名称")} name="config.connection.database"
                   value={value.connection.database ?? ""} required maximum={64} errors={errors}
                   onChange={(database) => onChange({...value, connection: {...value.connection, database}})}/></div>
    </>}
    {value.sourceType === "http" && <>
      <TextField label={uiText("HTTPS 接口地址")} name="config.connection.endpoint"
                 value={value.connection.endpoint ?? ""} required maximum={2048} errors={errors}
                 onChange={(endpoint) => onChange({...value, connection: {...value.connection, endpoint}})}/>
      <StringListField label={uiText("查询参数")} name="config.connection.queryParameters"
                       values={value.connection.queryParameters ?? []} maximum={20} length={64} errors={errors}
                       onChange={(queryParameters) => onChange({
                         ...value,
                         connection: {...value.connection, queryParameters}
                       })}/>
    </>}
    {value.sourceType !== "file" && <>
      <CredentialPicker enterpriseId={enterpriseId} value={value.credentialId}
                        onChange={(credentialId) => onChange({...value, credentialId})}
                        canManage={permissions.includes("credential.manage")}
                        canUse={permissions.includes("credential.use") || permissions.includes("credential.manage")}
                        readOnly={readOnly}
                        kinds={value.sourceType === "mysql" ? ["database"] : ["bearer", "basic", "api_key"]}/>
      <NumberField label={uiText("查询等待上限（秒）")} name="config.timeoutSeconds" value={value.timeoutSeconds} min={1}
                   max={10} errors={errors} onChange={(timeoutSeconds) => onChange({...value, timeoutSeconds})}/>
    </>}
  </Section>;
}
