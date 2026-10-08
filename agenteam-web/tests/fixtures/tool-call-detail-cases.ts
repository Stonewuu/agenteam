export const toolCallDetailCases = [
  {
    id: "create", label: "文件 · 创建文件", status: "completed" as const,
    input: { path: "work/md2docx.py", content: "from pathlib import Path\n\n\ndef export_document(source, destination):\n    text = Path(source).read_text(encoding=\"utf-8\")\n    Path(destination).write_text(text, encoding=\"utf-8\")\n    print(f\"已保存：{destination}\")\n" },
    result: { path: "work/md2docx.py", sizeBytes: 236, revision: "2" },
  },
  {
    id: "edit", label: "文件 · 编辑文件", status: "completed" as const,
    input: { path: "work/md2docx.py", old_text: "print(\"开始导出\")", new_text: "print(\"正在导出文档…\")\nprint(\"导出完成\")", replace_all: false },
    result: { path: "work/md2docx.py", replacements: 1, revision: "3" },
  },
  {
    id: "command", label: "文件 · 执行命令", status: "completed" as const,
    input: { command: 'python work/md2docx.py "outputs/技术方案.md" "outputs/技术方案.docx"\nls -la outputs/', working_directory: "/workspace/projects/example", timeout_seconds: 60 },
    result: { exitCode: 0, stdout: "已保存：outputs/技术方案.docx\n技术方案.md\n技术方案.docx\n", stderr: "", timedOut: false, stdoutPath: "tool-results/output.txt", stdoutBytes: 86 },
  },
  {
    id: "failed", label: "文件 · 执行失败", status: "failed" as const,
    input: { command: "python work/missing.py" },
    result: { exitCode: 2, stdout: "", stderr: "找不到要执行的文件：work/missing.py", isError: true },
  },
  {
    id: "search", label: "知识库 · 搜索资料", status: "completed" as const,
    input: { query: "项目交付时间", limit: 8 },
    result: { items: [{ name: "项目计划", content: "文档计划在周五交付。", page: 3 }], nextCursor: null, hasMore: false, total: 1 },
  },
  {
    id: "empty", label: "数据 · 查询数据", status: "completed" as const,
    input: { fields: ["amount"], filters: [{ field: "year", operator: "eq", value: 2026 }], collectionId: "collection", generation: 2, limit: 20 },
    result: { rows: [], fields: [{ name: "amount", label: "金额" }], total: 0 },
  },
];
