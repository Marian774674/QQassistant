"""抽取分派器：按后缀选择抽取器，统一输出结构 JSON（喂 Dify）。"""
import json
from pathlib import Path

from .docx import extract_docx
from .pdf import extract_pdf
from .xlsx import extract_xlsx

MAX_CHARS = 20_000

WRITABLE = {".xlsx", ".docx"}


def extract(path: Path) -> dict:
    suffix = path.suffix.lower()
    if suffix == ".xlsx":
        data = extract_xlsx(path)
    elif suffix == ".docx":
        data = extract_docx(path)
    elif suffix == ".pdf":
        data = extract_pdf(path)
    else:
        raise ValueError(f"不支持的文件类型：{suffix}")
    # 超大结构截断标记：只置标志不裁内容，由上层决定如何喂 Dify
    if len(json.dumps(data, ensure_ascii=False)) > MAX_CHARS:
        data["truncated"] = True
    return data
