import type {FileReference} from "@/features/agent/types/execution";

/** 只根据服务器返回的状态解释文件是否可用。 */
export function fileStatusText(file: FileReference): string {
  if (file.status === "scanning" && file.errorCode === "FILE_SCAN_UNAVAILABLE") {
    return "文件检查暂时无法完成，正在等待重试。";
  }
  switch (file.status) {
    case "ready":
      return file.errorCode === "FILE_NO_TEXT" ? "已上传，未提取到文字" : "可使用";
    case "scanning":
      return "正在处理文件…";
    case "rejected":
      return fileFailureText(file);
    case "deleted":
      return "此文件已不可用，请重新选择。";
    default:
      return "正在准备文件…";
  }
}

/** 提供真实的拒绝原因，不把正文读取失败误报为扫描失败。 */
export function fileFailureText(file: Pick<FileReference, "status" | "errorCode">): string {
  if (file.status === "deleted") {
    return "此文件已不可用，请重新选择。";
  }
  switch (file.errorCode) {
    case "FILE_NO_TEXT":
      return "文件没有可读取的文字，请上传带文本层的资料。";
    case "FILE_ENCRYPTED":
      return "文件已加密，请先导出不需要密码的副本。";
    case "FILE_ACTIVE_CONTENT":
      return "文件包含宏或嵌入对象，请导出普通文字文档。";
    case "FILE_TYPE_INVALID":
      return "文件格式与内容不符，或文件已损坏，请重新导出后上传。";
    case "FILE_EXPANDED_TOO_LARGE":
      return "文件展开后的内容超过处理限制，请拆分后上传。";
    case "FILE_PROCESSING_TIMEOUT":
      return "文件处理超时，请简化内容或拆分文件后重试。";
    case "FILE_CONTENT_MISMATCH":
      return "上传内容与选择的文件不一致，请重新上传。";
    case "FILE_SCAN_UNAVAILABLE":
      return "文件扫描暂时不可用，请稍后重新上传。";
    default:
      return "文件未通过检查，请重新选择文件。";
  }
}
