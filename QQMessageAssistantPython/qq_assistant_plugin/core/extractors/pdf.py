from pypdf import PdfReader


def extract_pdf(path) -> dict:
    reader = PdfReader(str(path))
    pages = [{"index": i, "text": (page.extract_text() or "")[:4000]}
             for i, page in enumerate(reader.pages)]
    return {"kind": "pdf", "pages": pages, "writable": False}
