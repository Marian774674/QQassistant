"""主动发送桥 HTTP 入口。

契约见 design/api_contract.md §13：Spring Boot 调这里，这里再调 LangBot / NapCat。
发送绝不自动重试——宁可失败，也不能重复发消息。
"""

import logging
import os
import uuid
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from .config import Settings, settings as DEFAULT_SETTINGS
from .idempotency import IdempotencyStore
from .upstream import LangBotUnsupported, LangBotUpstream, human_pause

log = logging.getLogger(__name__)


class Target(BaseModel):
    type: str
    id: int


class SendMessageBody(BaseModel):
    target: Target
    content: str
    delayMs: int | None = None
    bizRef: str | None = None


class SendFileBody(BaseModel):
    target: Target
    fileRef: str
    fileName: str | None = None
    bizRef: str | None = None


def error(status: int, message: str) -> JSONResponse:
    return JSONResponse(status_code=status,
                        content={"code": status, "message": message, "data": None})


def ok(data: dict) -> dict:
    return {"code": 0, "message": "ok", "data": data}


def create_app(s: Settings = DEFAULT_SETTINGS) -> FastAPI:
    upstream = LangBotUpstream(s)
    spring = httpx.AsyncClient(base_url=s.spring_base, timeout=s.timeout, trust_env=False)
    idem = IdempotencyStore(s.state_file, s.idempotency_ttl)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        log.info("send bridge ready on %s:%s", s.host, s.port)
        yield
        await upstream.close()
        await spring.aclose()

    app = FastAPI(title="QQ Assistant Send Bridge", lifespan=lifespan)

    @app.exception_handler(RequestValidationError)
    async def invalid_body(request: Request, exc: RequestValidationError):
        return error(400, f"请求体不合法：{exc.errors()[:1]}")

    @app.get("/health")
    async def health():
        return ok({"status": "up"})

    async def log_out(target: Target, msg_type: str, content: str,
                      message_id: str | None = None) -> None:
        """写 OUT 日志；失败只记 warning，不影响已经成功的发送。"""
        item = {
            "senderId": target.id,
            "chatId": target.id if target.type == "group" else None,
            "messageId": message_id,
            "dedupeKey": f"out:{message_id}" if message_id else f"out:{uuid.uuid4().hex}",
            "direction": "OUT",
            "msgType": msg_type,
            "content": content,
        }
        try:
            await spring.post("/api/message-logs", json=[item])
        except Exception as e:
            log.warning("log OUT failed: %s", e)

    @app.post("/api/langbot/send_message")
    async def send_message(body: SendMessageBody):
        if body.target.type not in ("private", "group"):
            return error(400, "target.type 只能是 private 或 group")
        if not body.content.strip():
            return error(400, "content 不能为空")

        cached = idem.get(body.bizRef)
        if cached is not None:
            return ok({"messageId": cached})
        if not idem.reserve(body.bizRef):
            # 同一个 bizRef 正在发送中：宁可让调用方失败重试，也不能再发一条
            return error(409, "同一 bizRef 正在发送中，已拒绝重复发送")

        try:
            await human_pause(body.content, body.delayMs, s, body.target.type)
            message_id = await upstream.send_text(
                body.target.type, body.target.id, body.content)
        except Exception as e:
            idem.release(body.bizRef)
            log.exception("send_message failed")
            return error(500, f"发送失败：{e}")

        idem.put(body.bizRef, message_id)
        await log_out(body.target, "text", body.content, message_id)
        return ok({"messageId": message_id})

    @app.post("/api/langbot/send_file")
    async def send_file(body: SendFileBody):
        if body.target.type not in ("private", "group"):
            return error(400, "target.type 只能是 private 或 group")
        ref = (body.fileRef or "").strip()
        if not ref:
            return error(400, "fileRef 不能为空")

        # 幂等判断必须最先做：产物文件可能已被清理，重试仍应返回上次结果
        cached = idem.get(body.bizRef)
        if cached is not None:
            return ok({"messageId": cached})
        if not idem.reserve(body.bizRef):
            return error(409, "同一 bizRef 正在发送中，已拒绝重复发送")

        path = ref.removeprefix("local://")
        if not os.path.isfile(path):
            idem.release(body.bizRef)
            return error(400, f"fileRef 指向的文件不存在：{path}")

        name = body.fileName or os.path.basename(path)
        try:
            if s.file_via_langbot:
                try:
                    message_id = await upstream.send_file_via_langbot(
                        body.target.type, body.target.id, path, name)
                except LangBotUnsupported as e:
                    # 只有确定性拒绝（4xx）才回落；超时/5xx 可能已经送达
                    log.warning("LangBot 不接受 File 组件，回落 NapCat: %s", e)
                    message_id = await upstream.send_file_via_napcat(
                        body.target.type, body.target.id, path, name)
            else:
                message_id = await upstream.send_file_via_napcat(
                    body.target.type, body.target.id, path, name)
        except Exception as e:
            idem.release(body.bizRef)
            log.exception("send_file failed")
            return error(500, f"发送失败：{e}")

        idem.put(body.bizRef, message_id)
        await log_out(body.target, "file", name, message_id)
        return ok({"messageId": message_id})

    return app


app = create_app()
