"""下载已核对清单中的上游源码和补丁，保留原文件及内容摘要，不解压或执行。"""

import argparse
import hashlib
import json
import re
import tarfile
import traceback
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path


def download_one(item, output, max_file_mib):
    result = dict(item, downloaded=False)
    partial = output / (item["file"] + ".partial")
    try:
        request = urllib.request.Request(item["url"], headers={"User-Agent": "AgenTeam-source-materials/0.2"})
        digest = hashlib.sha256()
        size = 0
        with urllib.request.urlopen(request, timeout=45) as response, partial.open("xb") as target:
            while chunk := response.read(1024 * 1024):
                size += len(chunk)
                if size > max_file_mib * 1024 * 1024:
                    raise ValueError(f"单份源码超出 {max_file_mib} MiB 下载限制")
                digest.update(chunk)
                target.write(chunk)
        if size == 0:
            raise ValueError("上游返回空文件")
        if item.get("bytes") is not None and size != item["bytes"]:
            raise ValueError("实际文件大小与上游清单不同")
        actual = digest.hexdigest()
        if item.get("sha256") and actual != item["sha256"]:
            raise ValueError("实际文件与清单中的上游摘要不一致")
        if item["type"] == "tar":
            with tarfile.open(partial) as archive:
                if archive.next() is None:
                    raise ValueError("源码归档中没有文件")
        elif item["type"] != "binary":
            text = partial.read_text(encoding="utf-8")
            if item["type"] == "patch" and "diff --git " not in text and "--- " not in text:
                raise ValueError("下载结果不包含补丁内容")
        partial.rename(output / item["file"])
        result.update(downloaded=True, bytes=size, sha256=actual,
                      upstreamChecksumVerified=bool(item.get("sha256")))
    except (OSError, ValueError, tarfile.TarError, urllib.error.URLError) as failure:
        result["error"] = str(failure)[:500]
        result["traceback"] = traceback.format_exc()
        result["requestMethod"] = "GET"
        result["requestPath"] = urllib.parse.urlsplit(item["url"]).path
        if isinstance(failure, urllib.error.HTTPError):
            result["httpStatus"] = failure.code
    return result


def collect(manifest_path, output, workers=1, max_file_mib=256):
    if not 1 <= workers <= 4 or not 1 <= max_file_mib <= 1024:
        raise ValueError("并行下载数必须为 1～4，单文件上限必须为 1～1024 MiB")
    raw = manifest_path.read_bytes()
    manifest = json.loads(raw)
    names = set()
    items = manifest["files"]
    if not isinstance(items, list) or not items:
        raise ValueError("源码清单必须包含待下载文件")
    for item in items:
        name = item["file"]
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+~-]*", name) or name in names:
            raise ValueError("源码文件名必须唯一，并且不能包含目录")
        names.add(name)
        url = urllib.parse.urlsplit(item["url"])
        if url.scheme != "https" or not url.netloc or url.username or url.password or url.query or url.fragment:
            raise ValueError("源码地址必须是没有凭据、查询参数和片段的 HTTPS 地址")
        if item.get("sha256") and not re.fullmatch(r"[0-9a-f]{64}", item["sha256"]):
            raise ValueError("上游文件摘要格式错误")
        if item["type"] not in ("tar", "text", "patch", "binary"):
            raise ValueError("源码清单的文件类型不受支持")
        if item["type"] == "binary" and not item.get("sha256"):
            raise ValueError("不能解析格式的原文件必须提供上游内容摘要")
        if item.get("bytes") is not None and not 0 < item["bytes"] <= max_file_mib * 1024 * 1024:
            raise ValueError("上游记录的文件大小超出本次允许的下载范围")
    if output.exists():
        raise ValueError("输出必须使用新目录，不覆盖已有源码资料")
    output.mkdir(parents=True)
    (output / "requested-sources.json").write_bytes(raw)
    report = {"formatVersion": 1, "scope": "原始源码下载记录；不替代对应版本、完整性及分发条件审核",
              "requestSha256": hashlib.sha256(raw).hexdigest(), "workers": workers,
              "maxFileMiB": max_file_mib, "files": [], "complete": False}
    result_path = output / "download-results.json"
    with ThreadPoolExecutor(max_workers=workers) as executor:
        downloads = [executor.submit(download_one, item, output, max_file_mib) for item in items]
        for completed in as_completed(downloads):
            result = completed.result()
            report["files"].append(result)
            detail = f"已保存 {result['bytes']} 字节" if result["downloaded"] else f"未完成，{result['error']}"
            print(f"{result['file']}：{detail}", flush=True)
            result_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report["complete"] = all(item["downloaded"] for item in report["files"])
    result_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report["complete"]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--workers", type=int, default=1)
    parser.add_argument("--max-file-mib", type=int, default=256)
    args = parser.parse_args()
    raise SystemExit(0 if collect(args.manifest.resolve(), args.output.resolve(), args.workers, args.max_file_mib) else 1)
