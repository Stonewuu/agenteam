"""按实际依赖清单取得同版本源码包，仅收集原声明，不执行源码或认定分发条件已经满足。"""

import argparse
import concurrent.futures
import hashlib
import importlib.util
import json
import re
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

MAX_DOWNLOAD = 32 * 1024 * 1024
ORIGIN = "https://repo.maven.apache.org/maven2/"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def source_headers(archive):
    headers = {}
    for entry in archive.infolist():
        if entry.is_dir() or entry.file_size > 2 * 1024 * 1024 or not re.search(r"\.(java|kt|scala|js|ts|c|h|cc|cpp)$", entry.filename):
            continue
        data = archive.read(entry)[:65536]
        match = re.match(rb"(?:\xef\xbb\xbf)?\s*(/\*.*?\*/|(?://[^\r\n]*(?:\r?\n|$))+)", data, re.S)
        if not match:
            continue
        header = match.group(1)
        if not re.search(rb"copyright|SPDX-License-Identifier|licensed under|permission is hereby|license\s*:", header, re.I):
            continue
        key = digest(header)
        item = headers.setdefault(key, {"sha256": key, "content": header.decode("utf-8", errors="replace"), "sourceFiles": []})
        item["sourceFiles"].append(entry.filename)
    return list(headers.values())


def component_path(coordinates):
    parts = [coordinates[key] for key in ("groupId", "artifactId", "version")]
    if any(not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]*", part) or ".." in part for part in parts):
        raise ValueError("组件编号含有不允许的路径字符")
    group, artifact, version = parts
    return f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}-sources.jar"


def download(url, destination):
    request = urllib.request.Request(url, headers={"User-Agent": "Agenteam-third-party-review/1.0"})
    with urllib.request.urlopen(request, timeout=30) as response:
        if not response.geturl().startswith(ORIGIN):
            raise ValueError("源码下载被重定向到允许来源之外")
        data = response.read(MAX_DOWNLOAD + 1)
        if len(data) > MAX_DOWNLOAD:
            raise ValueError("源码包超过本工具允许的体积")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("xb") as output:
        output.write(data)
    return data


def collect_one(item, directory, collector):
    coordinates = item["coordinates"]
    relative = component_path(coordinates)
    url = ORIGIN + relative
    result = {"coordinates": coordinates, "runtimeArtifacts": item["runtimeArtifacts"], "sourceUrl": url}
    try:
        destination = directory / relative
        data = download(url, destination)
        with zipfile.ZipFile(destination) as archive:
            if len(archive.infolist()) > 30000 or sum(entry.file_size for entry in archive.infolist()) > 256 * 1024 * 1024:
                raise ValueError("源码包展开后的体积或条目数超过限制")
            attributions = collector.attribution_files(archive)
            headers = source_headers(archive)
        result.update({"downloaded": True, "sourceFile": relative, "sourceSha256": digest(data),
                       "sourceBytes": len(data), "attributions": attributions, "sourceHeaders": headers})
    except urllib.error.HTTPError as error:
        result.update({"downloaded": False, "httpStatus": error.code, "reason": "同版本源码包未能从 Maven Central 取得"})
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        result.update({"downloaded": False, "reason": str(error)})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--output-directory", type=Path, required=True)
    args = parser.parse_args()
    source_bytes = args.report.read_bytes()
    report = json.loads(source_bytes)
    args.output_directory.mkdir(parents=True, exist_ok=False)
    spec = importlib.util.spec_from_file_location("java_notices", Path(__file__).with_name("collect-java-licenses.py"))
    collector = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(collector)
    selected = {}
    missing_coordinates = []
    for package in report["packages"]:
        if package["attributions"]:
            continue
        if not package["coordinates"]:
            missing_coordinates.append(package["file"])
            continue
        # 一个依赖包可能合并其他组件；每个实际登记的组件都保留来源，不能丢掉被合并组件的许可。
        for coordinates in package["coordinates"]:
            key = component_path(coordinates)
            item = selected.setdefault(key, {"coordinates": coordinates, "runtimeArtifacts": []})
            item["runtimeArtifacts"].append({"file": package["file"], "sha256": package["sha256"]})
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        tasks = {executor.submit(collect_one, item, args.output_directory, collector): item for item in selected.values()}
        for future in concurrent.futures.as_completed(tasks):
            result = future.result()
            results.append(result)
            identifier = ":".join(result["coordinates"][key] for key in ("groupId", "artifactId", "version"))
            print(f"{identifier}：{'已取得' if result['downloaded'] else '未取得'}，声明 {len(result.get('attributions', []))} 份", flush=True)
    results.sort(key=lambda item: component_path(item["coordinates"]))
    output = {"formatVersion": 1, "scope": "补充实际依赖缺少的声明；取得源码包不等于完成全部许可审核",
              "inputReportSha256": digest(source_bytes), "applicationSha256": report["sha256"],
              "packages": results, "missingCoordinates": missing_coordinates}
    with (args.output_directory / "source-attributions.json").open("x", encoding="utf-8", newline="\n") as target:
        json.dump(output, target, ensure_ascii=False, indent=2)
        target.write("\n")
    print(json.dumps({"selected": len(results), "downloaded": sum(item["downloaded"] for item in results),
                      "withAttributions": sum(bool(item.get("attributions")) for item in results),
                      "missingCoordinates": len(missing_coordinates)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
