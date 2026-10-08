"""把实际依赖报告与已核对的补充声明整理成可交付文件，不自动批准发布。"""

import argparse
import base64
import hashlib
import json
from pathlib import Path


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def load(path):
    data = path.read_bytes()
    return json.loads(data.decode("utf-8-sig")), {"file": path.name, "sha256": sha256(data)}


def material(root, relative):
    candidate = (root / relative).resolve()
    if not candidate.is_relative_to(root.resolve()) or candidate == root.resolve():
        raise ValueError("补充声明文件必须位于清单所属目录内")
    return candidate.read_bytes()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-report", type=Path, required=True)
    parser.add_argument("--node-report", action="append", default=[], metavar="名称=文件")
    parser.add_argument("--office-report", type=Path)
    parser.add_argument("--java-source-report", type=Path)
    parser.add_argument("--source-notice-report", type=Path, action="append", default=[])
    parser.add_argument("--supplements", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    input_paths = [args.java_report, args.office_report, args.java_source_report, args.supplements, *args.source_notice_report]
    for argument in args.node_report:
        if "=" not in argument:
            raise ValueError("Node 依赖报告必须使用 名称=文件 的格式")
        input_paths.append(Path(argument.split("=", 1)[1]))
    for path in input_paths:
        if path is not None and not path.is_file():
            raise ValueError(f"所需的依赖或补充报告还不存在：{path}")
    args.output.mkdir(parents=True, exist_ok=False)
    (args.output / "notices").mkdir()
    records = []
    reports = []
    stored = {}
    input_bindings = {}

    def save(name, content, expected, origin=None):
        data = content.encode("utf-8") if isinstance(content, str) else content
        checksum = sha256(data)
        if checksum != expected:
            raise ValueError(f"原声明内容与报告摘要不同：{name}")
        relative = f"notices/{checksum}.txt"
        if checksum not in stored:
            (args.output / relative).write_bytes(data)
            stored[checksum] = relative
        return {"name": name, "file": relative, "sha256": checksum, **({"source": origin} if origin else {})}

    def embedded(items):
        return [save(item["name"], item["content"], item["sha256"]) for item in items]

    supplement_entries = []
    if args.supplements:
        document, evidence = load(args.supplements)
        reports.append(evidence)
        supplement_entries = document["components"]

    source_entries = []
    if args.java_source_report:
        document, evidence = load(args.java_source_report)
        reports.append(evidence)
        source_entries = document["packages"]

    java, evidence = load(args.java_report)
    reports.append(evidence)
    application_artifact = {"name": java["artifact"], "sha256": java["sha256"]}
    for item in java["packages"]:
        coordinates = item["coordinates"]
        identifier = ", ".join(":".join(c[key] for key in ("groupId", "artifactId", "version")) for c in coordinates)
        notices = embedded(item["attributions"])
        for source in source_entries:
            if not any(value["file"] == item["file"] and value["sha256"] == item["sha256"] for value in source["runtimeArtifacts"]):
                continue
            for header in source.get("sourceHeaders", []):
                notices.append(save("源码中的许可或版权声明", header["content"], header["sha256"],
                                    {"url": source["sourceUrl"], "archiveSha256": source["sourceSha256"], "files": header["sourceFiles"]}))
        for extra in supplement_entries:
            if extra.get("java") not in coordinates:
                continue
            for notice in extra["attributions"]:
                notices.append(save(notice["name"], material(args.supplements.parent, notice["file"]),
                                    notice["sha256"], {"url": notice["sourceUrl"]}))
        records.append({"family": "java", "component": identifier or item["file"], "artifactSha256": item["sha256"],
                        "declaredLicenses": item["licenses"], "notices": notices})

    for argument in args.node_report:
        name, path = argument.split("=", 1)
        document, evidence = load(Path(path))
        reports.append(evidence)
        if document.get("missing"):
            raise ValueError(f"{name} 的依赖报告仍有缺少的依赖，请从完整构建目录收集")
        input_bindings[name] = {"files": document.get("inputFiles", {}),
                                "platform": document["platform"], "architecture": document["architecture"]}
        for item in document["packages"]:
            notices = embedded(item["attributions"])
            for extra in supplement_entries:
                if extra.get("node") != {"name": item["name"], "version": item["version"]}:
                    continue
                for notice in extra["attributions"]:
                    notices.append(save(notice["name"], material(args.supplements.parent, notice["file"]),
                                        notice["sha256"], {"url": notice["sourceUrl"], "kind": notice.get("kind", "upstream")}))
            records.append({"family": name, "component": f"{item['name']}@{item['version']}",
                            "declaredLicenses": item["license"], "notices": notices})

    if args.office_report:
        document, evidence = load(args.office_report)
        reports.append(evidence)
        input_bindings["office-python"] = {"files": document.get("inputFiles", {})}
        for key, family in [("systemPackages", "office-system"), ("pythonPackages", "office-python")]:
            for item in document[key]:
                records.append({"family": family, "component": f"{item['name']}@{item['version']}",
                                "declaredLicenses": item.get("license"), "notices": embedded(item["attributions"]),
                                **({"sourcePackage": item["sourcePackage"], "sourceVersion": item["sourceVersion"]}
                                   if "sourcePackage" in item else {})})
        for item in document["systemCommonLicenses"]:
            records.append({"family": "common-license", "component": item["name"], "declaredLicenses": item["name"],
                            "notices": [save(item["name"], item["content"], item["sha256"])]})

    for path in args.source_notice_report:
        document, evidence = load(path)
        reports.append(evidence)
        for item in document["packages"]:
            notices = []
            for notice in item["attributions"]:
                content = notice["content"] if "content" in notice else base64.b64decode(notice["contentBase64"], validate=True)
                notices.append(save(notice["name"], content, notice["sha256"],
                                    {"url": item["sourceUrl"], "archiveSha256": item["archiveSha256"],
                                     "kind": notice.get("kind", "upstream")}))
            records.append({"family": "source-materials", "component": item["archive"],
                            "declaredLicenses": None, "sourceScope": document["scope"], "notices": notices})

    missing = [{"family": item["family"], "component": item["component"]} for item in records if not item["notices"]]
    metadata_only = [{"family": item["family"], "component": item["component"]} for item in records if item["notices"]
                     and all(notice.get("source", {}).get("kind") == "package-metadata" for notice in item["notices"])]
    result = {"formatVersion": 1, "releaseApproved": False,
              "scope": "实际依赖原文及补充声明的材料包；不替代对正式条款、源码提供及其他分发条件的审核。",
              "applicationArtifact": application_artifact, "inputBindings": input_bindings,
              "inputReports": reports, "components": records, "withoutAttributions": missing, "metadataOnly": metadata_only,
              "uniqueNoticeFiles": len(stored)}
    with (args.output / "index.json").open("x", encoding="utf-8", newline="\n") as target:
        json.dump(result, target, ensure_ascii=False, indent=2)
        target.write("\n")
    lines = ["# 第三方软件声明资料", "",
             "本目录保留实际依赖中的许可、版权及归属声明，并附上已经核对来源的补充文件。第三方软件继续适用各自原有条款。", "",
             "本次生成结果仍供发布审核使用。生成文件不表示全部许可义务已经履行；正式分发前还须核对对应源码、分发条款和最后的运行产物。", "",
             "个别上游只在包描述中声明许可，未提供独立许可文件，索引中的 metadataOnly 单独列出这些组件；不为它们推测版权人或伪造原文。", "",
             "source-materials 类别来自附送源码，含构建、测试及其他平台代码，不表示每项都链接进成品。该类别中只保存包描述的条目仍明确标为元数据。", "",
             "index.json 记录组件、原声明名称、内容摘要及上游补充出处；notices 目录中的文件保留原文字节。相同文字只保存一份，每个适用组件仍分别列出。", "",
             "| 范围 | 组件 | 声明文件 |", "| --- | --- | --- |"]
    for item in records:
        links = []
        seen = set()
        for notice in item["notices"]:
            if notice["sha256"] not in seen:
                seen.add(notice["sha256"])
                links.append(f"[原文 {len(links) + 1}]({notice['file']})")
        label = item["component"].replace("|", "\\|").replace("\n", " ")
        lines.append(f"| {item['family']} | {label} | {', '.join(links) or '待补充'} |")
    (args.output / "README.md").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({"dependencyRecords": sum(item["family"] != "common-license" for item in records),
                      "commonLicenseTexts": sum(item["family"] == "common-license" for item in records),
                      "uniqueNoticeFiles": len(stored), "withoutAttributions": len(missing), "metadataOnly": len(metadata_only),
                      "releaseApproved": False}, ensure_ascii=False))


if __name__ == "__main__":
    main()
