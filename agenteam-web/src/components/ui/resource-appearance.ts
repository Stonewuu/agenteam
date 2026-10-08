export const resourceIcons = [
  ["Sparkles", "星光"], ["Feather", "书写"], ["Telescope", "探索"], ["ShoppingBag", "购物袋"],
  ["NotebookPen", "笔记"], ["Lightbulb", "灯泡"], ["WandSparkles", "魔杖"], ["BookOpen", "书本"],
  ["Box", "盒子"], ["FileText", "文档"], ["GitBranch", "分支"], ["Library", "书库"],
  ["Folders", "文件夹"], ["Database", "数据库"], ["ScanText", "文字识别"],
] as const;

export const resourceColors = [
  ["purple", "柔紫"], ["pink", "玫瑰"], ["blue", "湖蓝"], ["amber", "暖金"], ["mint", "薄荷"],
  ["teal", "青绿"], ["coral", "珊瑚"], ["indigo", "靛蓝"], ["plum", "梅紫"], ["slate", "雾灰"],
] as const;
export type ResourceColor = typeof resourceColors[number][0];

/** 只在新建表单初始化时调用，后续输入和重新渲染沿用用户已选外观。 */
export function randomResourceAppearance() {
  const values = crypto.getRandomValues(new Uint32Array(2));
  return {
    icon: resourceIcons[values[0] % resourceIcons.length][0],
    color: resourceColors[values[1] % resourceColors.length][0]
  };
}
