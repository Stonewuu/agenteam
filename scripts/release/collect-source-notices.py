"""从已下载并校验的源码归档收集原声明；不把全部源码依赖称作实际运行依赖。"""

import argparse
import base64
import hashlib
import json
import re
import tarfile
from pathlib import Path


NOTICE = re.compile(r"^(?:licen[cs]e|copying|notice|copyright|authors)(?:[._-].*)?$", re.I)
EXCLUDED = {".c", ".h", ".cc", ".cpp", ".java", ".class", ".js", ".ts", ".py", ".rs", ".go"}


def hash_file(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def original_notice(name, raw, kind="upstream"):
    item = {"name": name, "sha256": hashlib.sha256(raw).hexdigest(), "kind": kind}
    try:
        item["content"] = raw.decode("utf-8")
    except UnicodeDecodeError:
        item["contentBase64"] = base64.b64encode(raw).decode("ascii")
    return item


def collect(paths, output):
    reports = []
    for path in paths:
        raw = path.read_bytes()
        report = json.loads(raw)
        if not report.get("complete") or not report.get("files"):
            raise ValueError("只能收集已经全部下载完成的源码清单")
        reports.append((path, report, hashlib.sha256(raw).hexdigest()))
    if output.exists():
        raise ValueError("源码声明报告必须使用新文件")
    entries = []
    for path, report, checksum in reports:
        for source in report["files"]:
            if source["type"] != "tar":
                continue
            archive_path = path.parent / source["file"]
            if archive_path.is_symlink() or archive_path.resolve().parent != path.parent.resolve():
                raise ValueError("源码归档必须是下载结果目录内的普通文件")
            if not source.get("downloaded") or hash_file(archive_path) != source["sha256"]:
                raise ValueError("源码文件与下载记录不同")
            notices = []
            with tarfile.open(archive_path) as archive:
                for member in archive:
                    if not member.isfile() or member.size > 4 * 1024 * 1024:
                        continue
                    name = Path(member.name).name
                    if not NOTICE.fullmatch(name) or Path(name).suffix.lower() in EXCLUDED:
                        continue
                    raw = archive.extractfile(member).read()
                    notices.append(original_notice(member.name, raw))
                standalone = bool(notices)
                if not standalone and source["file"].endswith(".crate"):
                    metadata_name = source["file"].removesuffix(".crate") + "/Cargo.toml"
                    member = archive.getmember(metadata_name)
                    if not member.isfile() or member.size > 1024 * 1024:
                        raise ValueError("Rust 源码包描述不是可读取的普通文件")
                    notices.append(original_notice(metadata_name, archive.extractfile(member).read(), "package-metadata"))
            entries.append({"component": source.get("component", source["file"]),
                            "version": source.get("version"), "sourceUrl": source["url"],
                            "archive": source["file"], "archiveSha256": source["sha256"],
                            "standaloneNoticeFound": standalone, "attributions": notices})
    result = {"formatVersion": 1,
              "scope": "附送源码归档中的原声明，包含构建、测试和其他平台源码，不表示每个条目都已链接进成品",
              "inputReports": [{"file": p.name, "sha256": s} for p, _, s in reports],
              "packages": entries,
              "withoutStandaloneNotice": [p["archive"] for p in entries if not p["standaloneNoticeFound"]]}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"archives": len(entries), "notices": sum(len(p["attributions"]) for p in entries),
                      "withoutStandaloneNotice": result["withoutStandaloneNotice"]}, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--download-report", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    collect([p.resolve() for p in args.download_report], args.output.resolve())
