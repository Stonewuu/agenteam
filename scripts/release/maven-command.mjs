import {spawn} from "node:child_process";
import {fileURLToPath} from "node:url";

/** 两版从明确项目目录调用包装器，保留参数边界和实际退出结果。 */
export function runMaven(project, args) {
  const command = process.platform === "win32" ? "powershell.exe" : "sh";
  const parameters = process.platform === "win32"
    ? ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", fileURLToPath(new URL("./invoke-maven.ps1", import.meta.url)),
      "-ProjectDirectory", project, "-EncodedArguments", Buffer.from(JSON.stringify(args)).toString("base64")]
    : ["./mvnw", ...args];
  return new Promise((resolveResult, reject) => {
    const child = spawn(command, parameters, {cwd: project, stdio: "inherit", windowsHide: true});
    child.once("error", reject);
    child.once("exit", (code, signal) => {
      if (code === 0) {
        resolveResult();
      } else {
        reject(new Error(`Maven 构建命令失败：退出码 ${code ?? signal}`));
      }
    });
  });
}
