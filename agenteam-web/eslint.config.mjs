import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

export const platformControlsRule = {
  meta: {
    type: "problem",
    schema: [],
    messages: {
      native: "请使用平台公共控件，业务页面不能直接使用原生 {{name}}。",
      picker: "日期时间选择必须使用 @/components/ui/input，不能直接打开原生面板。",
      file: "文件输入必须隐藏，并通过公共 FilePicker 或已有平台上传按钮操作。",
      unsupported: "请使用平台的勾选、单选、滑块或颜色组件，不要直接设置原生 {{type}} 输入。",
    },
  },
  create(context) {
    const publicInputs = new Set();
    const inputNames = new Set();
    const attribute = (node, name) => node.attributes.find((item) => item.type === "JSXAttribute" && item.name.name === name);
    const literal = (attribute) => attribute?.value?.type === "JSXExpressionContainer" ? attribute.value.expression.value : attribute?.value?.value;
    return {
      ImportDeclaration(node) {
        if (typeof node.source.value === "string" && /\/(?:shadcn\/)?input$/.test(node.source.value)) {
          for (const item of node.specifiers) {
            inputNames.add(item.local.name);
            if (node.source.value === "@/components/ui/input") {
              publicInputs.add(item.local.name);
            }
          }
        }
      },
      JSXOpeningElement(node) {
        const name = node.name.name;
        if (["input", "select", "textarea", "button", "datalist"].includes(name)) {
          context.report({node, messageId: "native", data: {name}});
          return;
        }
        if (!inputNames.has(name)) {
          return;
        }
        const type = literal(attribute(node, "type"));
        if (["date", "time", "datetime-local"].includes(type) && !publicInputs.has(name)) {
          context.report({node, messageId: "picker"});
        }
        if (["checkbox", "radio", "range", "color", "month", "week"].includes(type)) {
          context.report({node, messageId: "unsupported", data: {type}});
        }
        const hidden = attribute(node, "hidden");
        if (type === "file" && (!hidden || hidden.value !== null && literal(hidden) !== true)) {
          context.report({node, messageId: "file"});
        }
      },
    };
  },
};

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  {
    rules: {
      curly: ["error", "all"],
      "brace-style": ["error", "1tbs", { allowSingleLine: false }],
    },
  },
  {
    files: ["src/**/*.{ts,tsx}"],
    ignores: ["src/components/ui/**"],
    plugins: {agenteam: {rules: {"platform-controls": platformControlsRule}}},
    rules: {
      "agenteam/platform-controls": "error",
      "no-restricted-globals": ["error", ...["alert", "confirm", "prompt"].map((name) => ({name, message: "请使用平台提示或确认弹窗。"}))],
      "no-restricted-properties": ["error", ...["window", "globalThis"].flatMap((object) => ["alert", "confirm", "prompt"].map((property) => ({object, property, message: "请使用平台提示或确认弹窗。"})))],
    },
  },
  // 仅检查维护源码，排除构建与测试生成文件。
  globalIgnores([
    ".test-build/**",
    ".next/**",
    ".next-*/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
  ]),
]);

export default eslintConfig;
