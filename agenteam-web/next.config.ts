import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // 容器镜像仅携带生产运行所需文件，静态资源由镜像一并复制。
  output: process.env.AGENTEAM_STANDALONE === "true" ? "standalone" : undefined,
  // 独立验收实例使用各自的编译输出目录。
  distDir: process.env.AGENTEAM_NEXT_DIST_DIR || ".next",
  // 允许通过本机回环地址加载开发脚本、字体及热更新连接。
  allowedDevOrigins: ["127.0.0.1"],
};

export default nextConfig;
