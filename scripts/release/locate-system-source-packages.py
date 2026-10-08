"""从发行版官方源码索引定位成品系统组件的同版本源码文件，不下载软件或改变镜像。"""

import argparse
import gzip
import hashlib
import json
import lzma
import re
import traceback
import urllib.request
from pathlib import Path


def paragraphs(lines):
    fields = {}
    key = None
    for line in lines:
        line = line.rstrip("\n")
        if not line:
            if fields:
                yield fields
            fields, key = {}, None
        elif line[0].isspace() and key:
            fields[key] += "\n" + line[1:]
        elif ":" in line:
            key, value = line.split(":", 1)
            fields[key] = value.lstrip()
    if fields:
        yield fields


def locate(report_path, urls, output):
    raw_report = report_path.read_bytes()
    runtime = json.loads(raw_report)
    requested = {(item["name"], item["version"]): item for item in runtime["sourcePackages"]}
    for url in urls:
        if not re.fullmatch(r"https://[A-Za-z0-9.-]+/[^?#]*dists/[^?#]+/source/Sources\.(xz|gz)", url):
            raise ValueError("请使用发行版 HTTPS 源码索引地址，不带凭据和查询参数")
    if output.exists():
        raise ValueError("源码定位结果必须写入新目录")
    output.mkdir(parents=True)
    result = {"formatVersion": 1, "runtimeReportSha256": hashlib.sha256(raw_report).hexdigest(),
              "imageId": runtime["imageId"], "scope": "官方索引的精确源码包匹配；归档尚未下载，索引签名另行核对",
              "indexes": [], "packages": [], "missing": []}
    found = {}
    for number, url in enumerate(urls):
        filename = output / (f"index-{number}-" + url.rsplit("/", 1)[1])
        record = {"url": url, "file": filename.name}
        try:
            with urllib.request.urlopen(url, timeout=45) as response, filename.open("xb") as destination:
                digest = hashlib.sha256()
                size = 0
                while chunk := response.read(1024 * 1024):
                    size += len(chunk)
                    if size > 96 * 1024 * 1024:
                        raise ValueError("源码索引超过 96 MiB 限制")
                    destination.write(chunk)
                    digest.update(chunk)
            record.update(sha256=digest.hexdigest(), bytes=size)
            opener = lzma.open if url.endswith(".xz") else gzip.open
            with opener(filename, "rt", encoding="utf-8") as lines:
                for fields in paragraphs(lines):
                    key = (fields.get("Package"), fields.get("Version"))
                    if key not in requested:
                        continue
                    directory = fields["Directory"]
                    if not re.fullmatch(r"pool/[A-Za-z0-9/._+-]+", directory) or ".." in directory.split("/"):
                        raise ValueError("源码索引中存在非法目录")
                    origin = url.split("/dists/", 1)[0]
                    files = []
                    for item in fields["Checksums-Sha256"].strip().splitlines():
                        checksum, length, name = item.split()
                        if not re.fullmatch(r"[0-9a-f]{64}", checksum) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+~%-]*", name):
                            raise ValueError("源码索引中的文件名或摘要格式错误")
                        files.append({"file": name, "bytes": int(length), "sha256": checksum,
                                      "url": f"{origin}/{directory}/{name}"})
                    if not files:
                        raise ValueError("已匹配的源码包没有校验文件清单")
                    candidate = dict(requested[key], index=url, files=files)
                    if key in found:
                        previous = {(f["file"], f["sha256"]) for f in found[key]["files"]}
                        current = {(f["file"], f["sha256"]) for f in files}
                        if previous != current:
                            raise ValueError("同名同版本源码包在两个索引中内容不同")
                    else:
                        found[key] = candidate
            print(f"{url}：累计匹配 {len(found)}/{len(requested)} 个源码版本", flush=True)
        except (OSError, ValueError, KeyError) as failure:
            record["error"] = str(failure)[:500]
            record["traceback"] = traceback.format_exc()
            print(f"{url}：未完成，{record['error']}", flush=True)
        result["indexes"].append(record)
        result["packages"] = [found[key] for key in sorted(found)]
        result["missing"] = [value for key, value in sorted(requested.items()) if key not in found]
        (output / "source-locations.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-report", type=Path, required=True)
    parser.add_argument("--index", action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = locate(args.runtime_report.resolve(), args.index, args.output.resolve())
    print(json.dumps({"matched": len(result["packages"]), "missing": len(result["missing"])}, ensure_ascii=False))
    raise SystemExit(1 if result["missing"] else 0)
