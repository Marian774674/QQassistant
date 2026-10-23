"""回写分派器：机械执行 Dify 的 fills，不认识的字段进 errors，绝不崩溃。

注意：docx 的 paragraph.text 赋值会清掉段内 run 格式（加粗/字号），
对「填空」场景可接受；要保格式后续改成替换 run 文本。
"""
from pathlib import Path

from .docx import apply_docx
from .xlsx import apply_xlsx


def apply(src: Path, out: Path, fills) -> list[str]:
    suffix = src.suffix.lower()
    if suffix == ".xlsx":
        return apply_xlsx(src, out, fills)
    if suffix == ".docx":
        return apply_docx(src, out, fills)
    raise ValueError(f"不支持回写的文件类型：{suffix}")
