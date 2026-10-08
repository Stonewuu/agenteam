export type DataValueType = "string" | "integer" | "decimal" | "boolean" | "date" | "datetime" | "object";
export type DataField = {
  name: string; label: string; valueType: DataValueType; readable: boolean; filterable: boolean;
  sortable: boolean; sensitive: boolean; nullable: boolean; ordinal: number
};
export type DataCollection = {
  id: string;
  revision: string;
  name: string;
  sourceName: string;
  activeGeneration: number;
  rowCount: number | null;
  status: "active" | "processing" | "disabled";
  fields: DataField[];
  createdAt: string;
  updatedAt: string
};
export type DataImportPreview = {
  previewToken: string;
  fields: DataField[];
  rows: Record<string, unknown>[];
  rowCount: number;
  expiresAt: string
};
export type DataFilter = {
  field: string;
  operator: "eq" | "ne" | "gt" | "gte" | "lt" | "lte" | "in" | "contains" | "is_null";
  value?: unknown
};
export type DataQuery = {
  collectionId: string; generation: number; fields: string[]; filters: DataFilter[];
  sort: { field: string; direction: "asc" | "desc" }[]; limit: number; offset: number
};
export type DataQueryResult = {
  collectionId: string; generation: number; fields: DataField[]; rows: Record<string, unknown>[];
  hasMore: boolean; truncated: boolean; durationMs: number
};
export const dataValueTypes: Record<DataValueType, string> = {
  string: "文字",
  integer: "整数",
  decimal: "小数",
  boolean: "布尔值",
  date: "日期",
  datetime: "日期与时间",
  object: "对象（JSON）"
};
