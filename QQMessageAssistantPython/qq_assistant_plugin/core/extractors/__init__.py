"""抽取分派器：按后缀选择抽取器，统一输出结构 JSON（喂 Dify）。

重依赖（openpyxl / python-docx / pypdf）延迟到真正处理文件时再导入：
插件包被 LangBot 安装后，若运行环境缺少某个依赖，模块级导入会让
整个插件初始化失败，表现为“插件完全不收消息”。延迟导入后插件仍能启动，
且能把“缺少依赖”作为一条可读回复发给用户。
"""
import json
from pathlib import Path

MAX_CHARS = 20_000

WRITABLE = {".xlsx", ".docx"}


def extract(path: Path) -> dict:
    suffix = path.suffix.lower()
    if suffix == ".xlsx":
        from .xlsx import extract_xlsx

        data = extract_xlsx(path)
    elif suffix == ".docx":
        from .docx import extract_docx

        data = extract_docx(path)
    elif suffix == ".pdf":
        from .pdf import extract_pdf

        data = extract_pdf(path)
    else:
        raise ValueError(f"不支持的文件类型：{suffix}")
    # 超大结构截断标记：只置标志不裁内容，由上层决定如何喂 Dify
    if len(json.dumps(data, ensure_ascii=False)) > MAX_CHARS:
        data["truncated"] = True
    return data
