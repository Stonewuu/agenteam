"""办公文件命令入口；结果写入工作目录，失败保留明确的错误编号。"""

import argparse
import json
import subprocess
import sys
import tempfile
import traceback
from pathlib import Path

from document_content import DocumentFailure, extract, validate_office


def render(source, output):
    kind = source.suffix.lower().lstrip(".")
    validate_office(source, kind)
    output.mkdir(parents=True, exist_ok=True)
    result = output / (source.stem + ".pdf")
    if result.exists():
        raise DocumentFailure("OFFICE_TARGET_EXISTS", "目标预览已经存在，请选择新的输出目录")
    with tempfile.TemporaryDirectory(prefix="agenteam-office-") as profile:
        command = ["soffice", "-env:UserInstallation=" + Path(profile).as_uri(), "--headless", "--nologo", "--norestore",
                   "--convert-to", "pdf", "--outdir", str(output), str(source)]
        subprocess.run(command, check=True, capture_output=True, timeout=150)
    if not result.is_file() or result.stat().st_size == 0:
        raise DocumentFailure("OFFICE_RENDER_FAILED", "文档未能生成预览")
    return {"path": str(result), "sizeBytes": result.stat().st_size}


def check(source):
    kind = source.suffix.lower().lstrip(".")
    validate_office(source, kind)
    result = {"format": kind, "valid": True}
    if kind == "docx":
        from docx import Document
        document = Document(source)
        result.update({"paragraphCount": len(document.paragraphs), "tableCount": len(document.tables)})
    elif kind == "pptx":
        from pptx import Presentation
        presentation = Presentation(source)
        result.update({"slideCount": len(presentation.slides), "shapeCount": sum(len(slide.shapes) for slide in presentation.slides)})
    elif kind == "xlsx":
        from openpyxl import load_workbook
        workbook = load_workbook(source, read_only=True, data_only=False, keep_links=False)
        cached = load_workbook(source, read_only=True, data_only=True, keep_links=False)
        try:
            result["sheets"] = workbook.sheetnames
            formulas = 0
            errors = []
            examined = 0
            for sheet in workbook:
                cached_sheet = cached[sheet.title]
                sheet.reset_dimensions()
                cached_sheet.reset_dimensions()
                for row, cached_row in zip(sheet.iter_rows(), cached_sheet.iter_rows()):
                    for cell, saved in zip(row, cached_row):
                        examined += 1
                        if examined > 1000000:
                            raise DocumentFailure("FILE_EXPANDED_TOO_LARGE", "表格单元格数量超过限制")
                        if cell.data_type == "f":
                            formulas += 1
                        if saved.data_type == "e" and len(errors) < 100:
                            errors.append({"sheet": sheet.title, "cell": saved.coordinate, "error": saved.value})
            result["formulaCount"] = formulas
            result["formulaErrors"] = errors
            result["formulasCalculated"] = False
        finally:
            workbook.close()
            cached.close()
    return result


def main():
    parser = argparse.ArgumentParser(description="读取、检查、渲染和重算办公文档")
    actions = parser.add_subparsers(dest="action", required=True)
    reader = actions.add_parser("extract", help="提取带位置的文本块")
    reader.add_argument("source", type=Path)
    reader.add_argument("--format", required=True, choices=["docx", "xlsx", "pptx", "pdf", "txt", "md"])
    reader.add_argument("--output-dir", required=True, type=Path)
    converter = actions.add_parser("render", help="生成 PDF 预览")
    converter.add_argument("source", type=Path)
    converter.add_argument("--output-dir", required=True, type=Path)
    checker = actions.add_parser("check", help="检查文档结构")
    checker.add_argument("source", type=Path)
    recalc = actions.add_parser("recalculate", help="重新计算并另存 Excel 文件")
    recalc.add_argument("source", type=Path)
    recalc.add_argument("--output", required=True, type=Path)
    actions.add_parser("capabilities", help="读取已安装程序的真实版本")
    args = parser.parse_args()
    try:
        if args.action == "extract":
            args.output_dir.mkdir(parents=True, exist_ok=True)
            result = extract(args.source, args.format, args.output_dir / "chunks.jsonl")
        elif args.action == "render":
            result = render(args.source.resolve(), args.output_dir.resolve())
        elif args.action == "check":
            result = check(args.source)
        elif args.action == "recalculate":
            validate_office(args.source, "xlsx")
            subprocess.run(["/usr/bin/python3", "/opt/agenteam/office/recalculate.py", str(args.source.resolve()),
                            str(args.output.resolve())], check=True, timeout=150)
            result = check(args.output)
            if result["formulaErrors"]:
                raise DocumentFailure("OFFICE_FORMULA_ERROR", "重新计算后仍有公式错误，请检查工作表")
            result.update({"path": str(args.output), "formulasCalculated": True})
        else:
            import importlib.metadata
            result = {"python": sys.version.split()[0], "node": subprocess.check_output(["node", "--version"], text=True).strip(),
                      "libreoffice": subprocess.check_output(["soffice", "--version"], text=True).strip(),
                      "packages": {name: importlib.metadata.version(name) for name in ["python-docx", "openpyxl", "python-pptx", "pypdf"]}}
        if args.action == "extract":
            (args.output_dir / "result.json").write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
        print(json.dumps(result, ensure_ascii=False))
    except Exception as failure:
        traceback.print_exc(file=sys.stderr)
        code = failure.code if isinstance(failure, DocumentFailure) else "FILE_TYPE_INVALID"
        result = {"success": False, "errorCode": code, "chunkCount": 0, "pageCount": None, "csv": None}
        if args.action == "extract":
            args.output_dir.mkdir(parents=True, exist_ok=True)
            (args.output_dir / "result.json").write_text(json.dumps(result), encoding="utf-8")
        print(json.dumps(result), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
