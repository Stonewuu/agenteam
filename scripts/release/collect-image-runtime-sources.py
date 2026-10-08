"""从明确的本地镜像读取系统组件和运行环境，保留对应源码定位所需的实际版本。"""

import argparse
import base64
import hashlib
import io
import json
import re
import subprocess
import tarfile
from pathlib import Path


def docker(*arguments, timeout=120):
    return subprocess.run(["docker", *arguments], check=True, capture_output=True, timeout=timeout)


def sha256(raw):
    return hashlib.sha256(raw).hexdigest()


def run_readonly(image_id, script):
    container = docker("create", "--pull=never", "--network", "none", "--read-only",
                       "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                       "--entrypoint", "/bin/sh", image_id, "-c", script).stdout.decode().strip()
    if not re.fullmatch(r"[0-9a-f]{64}", container):
        raise ValueError("Docker 没有返回完整的临时容器编号")
    try:
        result = docker("start", "--attach", container)
        state = json.loads(docker("inspect", "--format", "{{json .State}}", container).stdout)
        if state["ExitCode"] != 0:
            raise RuntimeError(f"镜像采集程序退出码：{state['ExitCode']}")
        return result.stdout
    finally:
        docker("rm", "--force", "--volumes", container)


def original(name, raw):
    item = {"name": name, "sha256": sha256(raw)}
    try:
        item["content"] = raw.decode("utf-8")
        item["encoding"] = "utf-8"
    except UnicodeDecodeError:
        item["contentBase64"] = base64.b64encode(raw).decode("ascii")
        item["encoding"] = "base64"
    return item


def parse_packages(status, files):
    result = []
    for paragraph in re.split(r"\n\s*\n", status.strip()):
        fields = {}
        for line in paragraph.splitlines():
            if line and not line[0].isspace() and ": " in line:
                key, value = line.split(": ", 1)
                fields[key] = value
        if fields.get("Status") != "install ok installed":
            continue
        name, version = fields["Package"], fields["Version"]
        source = re.fullmatch(r"([^\s]+)(?: \(([^)]+)\))?", fields.get("Source", name))
        if source is None:
            raise ValueError(f"不能解析已安装组件的来源：{name}")
        path = f"usr/share/doc/{name}/copyright"
        result.append({"name": name, "version": version, "architecture": fields.get("Architecture"),
                       "sourcePackage": source[1], "sourceVersion": source[2] or version,
                       "homepage": fields.get("Homepage"),
                       "attributions": [original("/" + path, files[path])] if path in files else []})
    return sorted(result, key=lambda item: item["name"])


def collect(image, output):
    if output.exists():
        raise ValueError("采集输出必须使用新目录，不覆盖已有证据")
    raw_inspect = json.loads(docker("image", "inspect", image).stdout)[0]
    image_id = raw_inspect["Id"]
    if raw_inspect["Os"] != "linux":
        raise ValueError("此工具只支持当前 Linux 成品镜像")
    output.mkdir(parents=True)
    script = r'''set -eu
set --
for path in /etc/os-release /var/lib/dpkg/status /usr/share/doc/*/copyright \
  /usr/share/common-licenses/* /opt/java/openjdk/release /opt/java/openjdk/legal \
  /usr/local/LICENSE /usr/local/lib/python*/LICENSE.txt /usr/share/licenses/node/LICENSE; do
  if [ -f "$path" ] || [ -d "$path" ]; then
    set -- "$@" "$path"
  fi
done
exec tar --dereference --hard-dereference -cf - -- "$@"
'''
    archive = run_readonly(image_id, script)
    (output / "runtime-original-files.tar").write_bytes(archive)
    files = {}
    with tarfile.open(fileobj=io.BytesIO(archive)) as document:
        for member in document:
            if not member.isfile():
                continue
            if member.size > 16 * 1024 * 1024:
                raise ValueError("运行环境声明文件超出采集大小限制")
            name = member.name.lstrip("/")
            if ".." in name.split("/"):
                raise ValueError("声明归档含有越界路径")
            files[name] = document.extractfile(member).read()
    status = files["var/lib/dpkg/status"].decode("utf-8")
    packages = parse_packages(status, files)
    # 仅执行版本和摘要命令；不启动应用，不访问挂载目录或网络。
    versions = run_readonly(image_id, r'''set -eu
for program in java node python docker; do
  if command -v "$program" >/dev/null 2>&1; then
    printf '%s\n' "program=$program"
    "$program" --version 2>&1
    sha256sum "$(command -v "$program")"
  fi
done
''').decode("utf-8")
    sources = {}
    for item in packages:
        key = (item["sourcePackage"], item["sourceVersion"])
        sources.setdefault(key, []).append(item["name"])
    materials = [original("/" + name, raw) for name, raw in sorted(files.items())
                 if name != "var/lib/dpkg/status"]
    report = {"formatVersion": 1,
              "scope": "固定镜像的系统包、运行环境版本及原文；源码取得及许可审查另行完成",
              "imageId": image_id, "platform": f"{raw_inspect['Os']}/{raw_inspect['Architecture']}",
              "runtimeUser": raw_inspect["Config"].get("User", ""),
              "archiveSha256": sha256(archive), "packageStatusSha256": sha256(files["var/lib/dpkg/status"]),
              "osRelease": files["etc/os-release"].decode("utf-8"), "runtimeVersions": versions,
              "systemPackages": packages,
              "sourcePackages": [{"name": key[0], "version": key[1], "binaryPackages": value}
                                 for key, value in sorted(sources.items())],
              "originalFiles": materials,
              "systemWithoutAttributions": [item["name"] for item in packages if not item["attributions"]]}
    report_path = output / "runtime-sources.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"imageId": image_id, "report": str(report_path),
                      "reportSha256": sha256(report_path.read_bytes()),
                      "systemPackages": len(packages), "sourcePackages": len(sources),
                      "originalFiles": len(materials),
                      "systemWithoutAttributions": report["systemWithoutAttributions"]}, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    collect(arguments.image, arguments.output.resolve())
