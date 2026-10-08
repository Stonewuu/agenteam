"""从实际可执行包及本地依赖描述收集第三方许可，不下载依赖或推断分发许可已经满足。"""

import argparse
import hashlib
import io
import json
import re
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def xml_text(element, path):
    if not element.tag.startswith("{"):
        path = path.replace("m:", "")
    return element.findtext(path, default="", namespaces=NS).strip()


def coordinates_from_properties(archive):
    result = []
    for entry in archive.namelist():
        if entry.startswith("META-INF/maven/") and entry.endswith("/pom.properties"):
            values = {}
            for line in archive.read(entry).decode("utf-8", errors="replace").splitlines():
                if line and not line.startswith("#") and "=" in line:
                    key, value = line.split("=", 1)
                    values[key.strip()] = value.strip()
            if all(values.get(key) for key in ("groupId", "artifactId", "version")):
                result.append({key: values[key] for key in ("groupId", "artifactId", "version")})
    return result


def pom_licenses(path, repository, visited=None):
    visited = set() if visited is None else visited
    if path in visited or not path.is_file():
        return [], []
    visited.add(path)
    try:
        root = ET.fromstring(path.read_bytes())
    except ET.ParseError:
        return [], ["依赖描述不是有效的 XML 文件"]
    namespaced = root.tag.startswith("{")
    license_path = "m:licenses/m:license" if namespaced else "licenses/license"
    licenses = [{"name": xml_text(entry, "m:name"), "url": xml_text(entry, "m:url"),
                 "comments": xml_text(entry, "m:comments")} for entry in root.findall(license_path, NS)]
    if licenses:
        return licenses, [str(path.relative_to(repository)).replace("\\", "/")]
    parent = root.find("m:parent" if namespaced else "parent", NS)
    if parent is not None:
        group = xml_text(parent, "m:groupId")
        artifact = xml_text(parent, "m:artifactId")
        version = xml_text(parent, "m:version")
        if all((group, artifact, version)) and "${" not in version:
            target = repository / group.replace(".", "/") / artifact / version / f"{artifact}-{version}.pom"
            return pom_licenses(target, repository, visited)
    return [], []


def attribution_files(archive):
    records = []
    for entry in archive.infolist():
        leaf = entry.filename.rsplit("/", 1)[-1]
        # License、Notice 也是业务类名，编译后的类文件不能被当作许可原文解码。
        if leaf.lower().endswith(".class"):
            continue
        legal = re.match(r"^(licen[cs]e|copying|notice|copyright|authors)(?:[._-].*)?$", leaf, re.I)
        bundled = re.match(r"^third[-_ ]?party.*(?:\.(txt|md))?$", leaf, re.I)
        nested = re.search(r"/(licen[cs]es|notices)/", entry.filename, re.I)
        if entry.is_dir() or not (legal or bundled or nested) or entry.file_size > 8 * 1024 * 1024:
            continue
        raw = archive.read(entry)
        records.append({"name": entry.filename, "sha256": hashlib.sha256(raw).hexdigest(),
                        "content": raw.decode("utf-8", errors="replace")})
    return records


def collect(jar, repository):
    # 部分库不在 JAR 内携带 Maven 描述，先按实际本地文件名建立候选，再核对内容摘要。
    local_jars = {}
    for file in repository.rglob("*.jar"):
        local_jars.setdefault(file.name, []).append(file)
    packages = []
    with zipfile.ZipFile(jar) as application:
        for name in sorted(application.namelist()):
            if not name.startswith("BOOT-INF/lib/") or not name.endswith(".jar"):
                continue
            raw = application.read(name)
            checksum = hashlib.sha256(raw).hexdigest()
            candidates = local_jars.get(name.rsplit("/", 1)[-1], [])
            matching = [path for path in candidates if hashlib.sha256(path.read_bytes()).hexdigest() == checksum]
            pom = matching[0].with_suffix(".pom") if matching else None
            if pom is not None and not pom.exists():
                # 带 classifier（同一组件的特殊构建名称）的文件使用组件本身的描述。
                alternatives = list(pom.parent.glob("*.pom"))
                pom = alternatives[0] if len(alternatives) == 1 else None
            with zipfile.ZipFile(io.BytesIO(raw)) as library:
                coordinates = coordinates_from_properties(library)
                notices = attribution_files(library)
            licenses, sources = pom_licenses(pom, repository) if pom else ([], [])
            if not coordinates and pom and pom.exists():
                root = ET.fromstring(pom.read_bytes())
                coordinates = [{"groupId": xml_text(root, "m:groupId") or xml_text(root, "m:parent/m:groupId"),
                                "artifactId": xml_text(root, "m:artifactId"),
                                "version": xml_text(root, "m:version") or xml_text(root, "m:parent/m:version")}]
            packages.append({"file": name, "sha256": checksum, "coordinates": coordinates,
                             "licenses": licenses, "licenseMetadataSources": sources, "attributions": notices})
    return {"formatVersion": 1, "artifact": jar.name, "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
            "scope": "实际可执行包中的依赖及原声明；不包含 Java 运行环境、操作系统或未展开的原生组件，仍需核对分发义务",
            "packages": packages, "missingLicenseMetadata": [entry["file"] for entry in packages if not entry["licenses"]],
            "withoutEmbeddedAttributions": [entry["file"] for entry in packages if not entry["attributions"]]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--maven-repository", type=Path, default=Path.home() / ".m2/repository")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.jar.is_file() or not args.maven_repository.is_dir():
        raise ValueError("可执行包或实际本地依赖目录不存在")
    result = collect(args.jar.resolve(), args.maven_repository.resolve())
    with args.output.open("x", encoding="utf-8", newline="\n") as target:
        json.dump(result, target, ensure_ascii=False, indent=2)
        target.write("\n")
    print(json.dumps({"packages": len(result["packages"]), "missingLicenseMetadata": len(result["missingLicenseMetadata"]),
                      "withoutEmbeddedAttributions": len(result["withoutEmbeddedAttributions"])}, ensure_ascii=False))


if __name__ == "__main__":
    main()
