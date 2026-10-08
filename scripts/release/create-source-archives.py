"""按逐项核对的文件清单生成第三方源码附件，只复制列明且摘要相同的原文件。"""

import argparse
import hashlib
import io
import json
import re
import tarfile
from pathlib import Path, PurePosixPath


def checksum(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def relative_path(value):
    path = PurePosixPath(value)
    if path.is_absolute() or not path.parts or any(part in (".", "..") for part in value.split("/")):
        raise ValueError("附件中的文件必须使用明确的内部相对路径")
    if "\\" in value or ":" in value or any(ord(char) < 32 for char in value):
        raise ValueError("附件文件名含有不允许的字符")
    return path.as_posix()


def add_bytes(archive, name, raw):
    entry = tarfile.TarInfo(name)
    entry.size = len(raw)
    entry.mode = 0o644
    archive.addfile(entry, io.BytesIO(raw))


def create(manifest_path, root, output):
    raw = manifest_path.read_bytes()
    request = json.loads(raw)
    version = request["version"]
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[a-z0-9.-]+)?", version):
        raise ValueError("请明确指定源码附件对应的应用版本")
    groups = request["groups"]
    if not groups:
        raise ValueError("附件清单不能为空")
    names = set()
    for group in groups:
        name = group["name"]
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]*", name) or name in names or not group["files"]:
            raise ValueError("附件分组名称必须唯一，每组必须有明确文件")
        names.add(name)
        paths = set()
        for item in group["files"]:
            relative = relative_path(item["file"])
            if relative in paths:
                raise ValueError("同一附件中不能有重名文件")
            paths.add(relative)
            source = root / relative_path(item["sourceFile"])
            if source.is_symlink() or not source.resolve().is_relative_to(root) or not source.is_file():
                raise ValueError("源码原文件必须位于指定材料目录内")
            if source.stat().st_size != item["bytes"] or checksum(source) != item["sha256"]:
                raise ValueError(f"源码原文件与清单不同：{relative}")
    if output.exists():
        raise ValueError("附件输出必须使用新目录，不覆盖已经生成的文件")
    output.mkdir(parents=True)
    result = {"formatVersion": 1, "version": version, "releaseApproved": False, "complete": False,
              "scope": "对应源码附件的准备结果，正式来源、许可及发布检查另行完成",
              "requestSha256": hashlib.sha256(raw).hexdigest(),
              "runtimeImages": request.get("runtimeImages", []), "archives": []}
    for group in groups:
        name = f"agenteam-third-party-sources-{group['name']}-{version}.tar"
        target = output / name
        public_files = [{key: item[key] for key in ("file", "url", "sha256", "bytes")} for item in group["files"]]
        index = {"formatVersion": 1, "applicationVersion": version, "group": group["name"],
                 "description": group["description"], "sourceScope": request["scope"],
                 "components": group.get("components", []), "files": public_files}
        index_bytes = (json.dumps(index, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        readme = ("# 第三方源码资料\n\n" + group["description"] + "\n\n"
                  "sources 目录保留原始源码、补丁或源码描述文件；SOURCE-INDEX.json 列出原地址、内容摘要及适用组件。\n\n"
                  "源码继续适用各自附带的原许可。部分归档包含构建、测试或其他平台文件，不能据此认定这些内容全部链接进成品。\n").encode("utf-8")
        with tarfile.open(target, "x", format=tarfile.PAX_FORMAT) as archive:
            add_bytes(archive, "README.md", readme)
            add_bytes(archive, "SOURCE-INDEX.json", index_bytes)
            for item in group["files"]:
                source = root / item["sourceFile"]
                entry = tarfile.TarInfo("sources/" + item["file"])
                entry.size = item["bytes"]
                entry.mode = 0o644
                with source.open("rb") as stream:
                    archive.addfile(entry, stream)
        expected = {"sources/" + item["file"]: item["sha256"] for item in group["files"]}
        expected.update({"README.md": hashlib.sha256(readme).hexdigest(), "SOURCE-INDEX.json": hashlib.sha256(index_bytes).hexdigest()})
        actual = {}
        with tarfile.open(target) as archive:
            for entry in archive:
                if not entry.isfile() or entry.name in actual:
                    raise ValueError("生成的附件有重复或非普通文件")
                actual[entry.name] = hashlib.file_digest(archive.extractfile(entry), "sha256").hexdigest()
        if actual != expected:
            raise ValueError("生成的源码附件与清单不一致")
        result["archives"].append({"file": name, "sha256": checksum(target), "bytes": target.stat().st_size,
                                   "sourceFilesVerified": len(group["files"]), "indexSha256": hashlib.sha256(index_bytes).hexdigest()})
        (output / "source-archives.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"{name}：{len(group['files'])} 个原文件逐字节核对通过", flush=True)
    result["complete"] = True
    (output / "source-archives.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    create(args.manifest.resolve(), args.source_root.resolve(), args.output.resolve())
