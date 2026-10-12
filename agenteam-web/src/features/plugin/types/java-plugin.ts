export type JavaPluginField = {
  type: "string" | "integer" | "number" | "boolean";
  title: string;
  description?: string;
  default?: string | number | boolean;
  enum?: (string | number | boolean)[];
  minLength?: number;
  maxLength?: number;
  minimum?: number;
  maximum?: number;
};

export type JavaPlugin = {
  id: string;
  pluginId: string;
  version: string;
  name: string;
  description: string;
  author: string;
  status: "prepared" | "active" | "upgraded" | "uninstalled";
  currentInstallationId: string | null;
  configurationSchema: {properties: Record<string, JavaPluginField>; required?: string[]};
  tools: {name: string; description: string; operationClass: string; annotations: {title?: string}}[];
};

export type JavaPluginCatalog = {canManage: boolean; items: JavaPlugin[]};
