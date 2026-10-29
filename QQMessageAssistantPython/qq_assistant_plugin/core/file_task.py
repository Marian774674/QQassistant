"""文件任务状态机：下载 → 抽取 → Dify 决策 → 回写 → 回传 / 反问补全。

状态：PENDING → PROCESSING → NEED_INPUT / AWAITING_CONFIRM / SENT / FAILED / CANCELLED
- 任务宿主在本模块，Spring Boot 的 file_task 只是只读镜像（每次变化 sync）
- Dify 只返回 fills/missing/confidence，本模块不认识任何业务字段
- deps 即插件实例（main.py 的 qqassistantplugin），storage API 直接可用
"""
import asyncio
import base64
import json
import logging
import re
import shutil
from pathlib import Path

import httpx
from langbot_plugin.api.entities.builtin.platform import message as pm

from core import file_store
from core.extractors import WRITABLE, extract
from core.reply import reply_text
from core.text import extract_text
from core.writers import apply as apply_fills

log = logging.getLogger(__name__)

# QQ 文件 CDN 偶发 502/503：换一次签名 URL 通常就好了。
# 不无限重试，避免用户等太久，也避免把一次 502 放大成三次重复下载。
DOWNLOAD_ATTEMPTS = 3
DOWNLOAD_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
)


def _url_host(url: str) -> str:
    """只保留 scheme+host 用于日志/报错，避免把签名参数写进 QQ 和日志。"""
    try:
        parsed = httpx.URL(url)
        port = f":{parsed.port}" if parsed.port else ""
        return f"{parsed.scheme}://{parsed.host}{port}"
    except Exception:
        return "<invalid-url>"


def _safe_error(error: Exception) -> str:
    """去掉异常文本里的 URL query，防止 signed rkey 泄漏。"""
    return re.sub(r"(https?://[^\s'\"]+)\?[^\s'\"]*", r"\1?...", str(error))


async def _napcat_call(deps, action: str, params: dict):
    """直连 NapCat HTTP API。

    LangBot 的 OneBot11 适配器白名单里没有任何文件 API（PLATFORM_API_MAP），
    get_private_file_url / upload_private_file 只能绕过宿主直接打 NapCat。
    未配置 napcat_api_url 时返回 None。
    """
    base = (getattr(deps.settings, "napcat_api_url", "") or "").rstrip("/")
    if not base:
        return None
    headers = {}
    token = getattr(deps.settings, "napcat_token", "")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    # trust_env=False：NapCat 永远是本机服务，系统代理（Clash 等）会接 localhost
    # 请求并回 502 Bad Gateway，表现为“NapCat 挂了”的假象
    async with httpx.AsyncClient(timeout=30, trust_env=False) as client:
        resp = await client.post(f"{base}/{action}", json=params, headers=headers)
        if resp.status_code >= 400:
            detail = (resp.text or "").strip()[:200] or "<空响应体>"
            if resp.status_code in (401, 403):
                why = "token 未配置" if not token else "token 与服务端不一致"
            elif resp.status_code in (502, 503, 504):
                why = "网关错误：NapCat HTTP 服务未启动/端口不对，或请求被系统代理接管"
            else:
                why = "服务端拒绝了该 action"
            raise RuntimeError(f"napcat {action} HTTP {resp.status_code}（{why}）：{detail}")
        payload = resp.json()
    file_store.debug(f"napcat {action} {params} -> {payload}")
    if str(payload.get("status", "ok")).lower() == "failed":
        raise RuntimeError(f"napcat {action} failed: {payload.get('message') or payload}")
    return payload.get("data") or {}


async def sync(deps, task: dict):
    """镜像状态到 Spring Boot；sync 失败只记日志，不影响任务。"""
    inp = task.get("input") or {}
    out = task.get("output") or {}
    body = {
        "taskRef": task["taskRef"],
        "senderId": task["senderId"],
        "chatId": task.get("chatId"),
        "conversationId": task.get("conversationId"),
        "fileName": task.get("originalName") or inp.get("name"),
        "request": task.get("request"),
        "pendingRequest": task.get("pendingRequest"),
        "status": task.get("status"),
        "missing": task.get("missing") or [],
        "confidence": task.get("confidence"),
        "needsConfirm": task.get("needs_confirm", task.get("needsConfirm")),
        "fileRef": f"local://{inp['path']}" if inp.get("path") else None,
        "outputRef": f"local://{out['path']}" if out.get("path") else None,
        "sourceMessageId": task.get("sourceMessageId"),
        "idempotencyKey": task.get("idempotencyKey"),
        "detail": task.get("detail") or {},
    }
    try:
        await deps.spring.sync_file_task(body)
    except Exception as e:
        log.warning("sync file task %s (%s) failed: %s",
                    task["taskRef"], task["status"], e)


async def _save_and_sync(deps, task: dict):
    await file_store.save_task(deps, task)
    await sync(deps, task)


def _as_list(value, *, split_text: bool = False) -> list:
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return []
        try:
            value = json.loads(text)
        except json.JSONDecodeError:
            if split_text:
                # 兼容 Dify 直接吐「姓名、学号」这种非 JSON 文本
                return [part.strip() for part in re.split(r"[,，、;\n]", text) if part.strip()]
            log.warning("workflow list output not json: %r", text[:200])
            return []
    if isinstance(value, dict):
        return [value]
    if not isinstance(value, list):
        return []
    return value


def log_out(deps, task: dict, msg_type: str, content: str):
    """把插件直接发出的内容补进统一消息日志（文本分发路径由 default.py 记）。"""
    queue = getattr(deps, "log_queue", None)
    if queue is None:
        return
    try:
        queue.enqueue({
            "senderId": task.get("senderId"),
            "chatId": task.get("chatId"),
            "conversationId": task.get("conversationId") or "",
            "direction": "OUT",
            "msgType": msg_type,
            "content": content,
        })
    except Exception as e:
        log.warning("enqueue OUT log failed: %s", e)


def _as_bool(value) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    if isinstance(value, str):
        # 关键：bool("false") == True，必须显式识别字符串
        return value.strip().lower() in {"1", "true", "yes", "y", "是", "需要", "需确认"}
    return False


async def apply_fill(task: dict, file_payload: dict, deps, ctx) -> str:
    """按 Dify 的 FILE_FILL 决策回写文件并回传/转草稿。

    插件只做机械执行：fills 怎样就写怎样，不再判断缺字段、不再自己组织反问。
    """
    payload = file_payload or {}
    fills = _as_list(payload.get("fills"))
    try:
        confidence = float(payload.get("confidence") or 0)
    except (TypeError, ValueError):
        confidence = 0.0
    needs_confirm = _as_bool(payload.get("needs_confirm"))

    if not task or not (task.get("input") or {}).get("path"):
        await reply_text(ctx, "文件任务已过期，把文件重新发一次吧。")
        return "EXPIRED"

    src = Path(task["input"]["path"])
    # source 落盘名固定是 input.xlsx，回传名要用用户原始文件名
    stem = Path(task.get("originalName") or src.name).stem or "output"
    stem = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", stem)
    out = file_store.task_dir(task["taskRef"]) / f"{stem}_filled{src.suffix}"

    if not fills:
        # 不要把原文件原样发回去冒充“填好了”；把 Dify 的真实结果告诉用户
        task["status"] = "FAILED"
        task["detail"] = {"reason": "Dify 未返回任何 fills"}
        await _save_and_sync(deps, task)
        await file_store.set_active_input(deps, task["senderId"], None)
        await reply_text(
            ctx,
            f"没找到要填的内容。{_structure_hint(task.get('structure'))}"
            "直接说一句要求就行，比如「把姓名填在第 2 行」")
        return "FAILED"

    errors = apply_fills(src, out, fills)
    task["output"] = {"path": str(out), "name": out.name, "errors": errors}
    task["confidence"] = confidence
    task["needs_confirm"] = needs_confirm
    task["missing"] = _as_list(payload.get("missing"))
    if errors:
        task["detail"] = {"fillErrors": errors}

    low_confidence = bool(errors) or needs_confirm or \
        confidence < deps.settings.file_confirm_threshold
    if low_confidence:
        await deps.spring.create_draft(
            "FILE", task["senderId"], task.get("chatId"), "文件待确认",
            {"taskRef": task["taskRef"], "fileName": out.name,
             "fileRef": f"local://{out}",
             # 明确目标：群任务回群，私聊任务回 sender；不靠 Spring 猜
             "targetType": "group" if task.get("chatId") else "private",
             "targetId": task.get("chatId") or task["senderId"]})
        task["status"] = "AWAITING_CONFIRM"
        await _save_and_sync(deps, task)
        await file_store.set_active_input(deps, task["senderId"], None)
        notice = "填好了，去草稿中心确认后发送"
        await reply_text(ctx, notice)
        log_out(deps, task, "text", notice)
        return "AWAITING_CONFIRM"

    bot_uuid = await ctx.get_bot_uuid()
    await send_file(deps, task, bot_uuid)
    log_out(deps, task, "file", out.name)
    task["status"] = "SENT"
    await _save_and_sync(deps, task)
    await file_store.set_active_input(deps, task["senderId"], None)
    return "SENT"


async def send_file(deps, task: dict, bot_uuid: str):
    """回传产物：首选 SDK send_message + File(path=本地路径)，
    失败兜底 NapCat upload_private_file/upload_group_file（Phase 3-3 共用）。"""
    out = Path(task["output"]["path"])
    target_type = "group" if task.get("chatId") else "person"
    target_id = str(task.get("chatId") or task["senderId"])
    chain = pm.MessageChain([
        pm.Plain(text=f"填好了：{out.name}"),
        pm.File(path=str(out), name=out.name),
    ])
    try:
        await deps.send_message(bot_uuid, target_type, target_id, chain)
        return
    except Exception as e:
        log.warning("send_message file failed, fallback to platform api: %s", e)
        file_store.debug(f"send_message file failed: {e!r}")
    action = "upload_group_file" if task.get("chatId") else "upload_private_file"
    params = {"file": str(out), "name": out.name}
    params["group_id" if task.get("chatId") else "user_id"] = int(target_id)
    if getattr(deps.settings, "napcat_api_url", ""):
        await _napcat_call(deps, action, params)
        return
    await deps.call_platform_api(bot_uuid, action, params)


def _structure_hint(structure) -> str:
    """把 structure 压成一句“我看到什么”，空 fills 时给用户一个抓手。

    只列前几个单元格：实机 18:18 那张表只有一个 A1=姓名，用户根本不知道
    模型到底看到了什么，只能干看着一句“没识别到内容”。
    """
    if not isinstance(structure, dict):
        return ""
    kind = structure.get("kind")
    if kind == "xlsx":
        parts = []
        for sheet in (structure.get("sheets") or [])[:3]:
            cells = sheet.get("cells") or []
            preview = "、".join(f"{c.get('ref')}={str(c.get('value'))[:20]}"
                                for c in cells[:6])
            parts.append(f"{sheet.get('name')}[{preview or '空'}]")
        return "我看到：" + "；".join(parts) + "。" if parts else ""
    if kind == "docx":
        return (f"我看到 {len(structure.get('paragraphs') or [])} 段、"
                f"{len(structure.get('tables') or [])} 个表格。")
    return ""


async def ingest(ctx, event, is_group: bool, deps) -> dict | None:
    """文件消息第一步：下载 + 抽取结构，返回任务上下文。

    语义决策一律不在插件做；拿到 task 后由 default.py 统一送 Chatflow。
    必须在 handler 内 await（LangBot 的 API 绑定在 invocation 生命周期上）。
    """
    try:
        return await _ingest(ctx, event, is_group, deps)
    except Exception as e:
        log.exception("file task bootstrap failed")
        file_store.debug(f"EXC bootstrap: {e!r}")
        try:
            await reply_text(ctx, f"处理失败：{e}")
        except Exception:
            pass
        return None


async def _ingest(ctx, event, is_group: bool, deps) -> dict | None:
    sender_id = int(event.sender_id)
    file_store.debug(f"ingest start sender={sender_id} group={is_group}")
    chat_id = int(event.launcher_id) if is_group else None

    # 全局约束：一个用户同时只允许 1 个待处理文件任务，新文件顶替旧任务
    old_ref = await file_store.load_active_input(deps, sender_id)
    file_store.debug(f"active_input={old_ref!r}")
    if old_ref:
        old = await file_store.load_task(deps, old_ref)
        if old:
            old["status"] = "CANCELLED"
            await _save_and_sync(deps, old)
        await file_store.set_active_input(deps, sender_id, None)

    task_ref = file_store.new_task_ref()
    task = {"taskRef": task_ref, "senderId": sender_id, "chatId": chat_id,
            "status": "PENDING", "input": {}, "provided": [], "fills": [],
            "request": extract_text(event.message_chain)}
    await _save_and_sync(deps, task)
    file_store.debug(f"task {task_ref} saved PENDING")

    try:
        path = await download_and_prepare(ctx, event, task, deps)
        if path is None:
            return None  # download_and_prepare 内已处理不支持格式的分支

        # 结构落进 task.json：后续文本补充时不必重读文件
        task["status"] = "PROCESSING"
        task["structure"] = extract(path)
        await _save_and_sync(deps, task)
        return task

    except Exception as e:
        log.exception("file task %s failed", task_ref)
        file_store.debug(f"EXC in task {task_ref}: {e!r}")
        task["status"] = "FAILED"
        task["detail"] = {"reason": str(e)}
        await _save_and_sync(deps, task)
        try:
            await reply_text(ctx, f"处理失败：{e}")
        except Exception:
            pass
        return None


async def download_and_prepare(ctx, event, task: dict, deps):
    """下载 + 可写性检查。返回输入文件路径；不可写格式已回复并返回 None。"""
    path = await download(ctx, event, task, deps)
    task["input"] = {"path": str(path), "name": path.name}
    if path.suffix.lower() not in WRITABLE:
        task["status"] = "FAILED"
        task["detail"] = {"reason": "暂不支持回写"}
        await _save_and_sync(deps, task)
        await reply_text(ctx, "这个格式我只能读不能改，换成 xlsx/docx 吧")
        return None
    return path


async def download(ctx, event, task: dict, deps) -> Path:
    """把 File 组件的附件下载到 data/files/<taskRef>/input<ext>。

    File 字段（SDK 0.7.13 + NapCat 实测）：id/name/size/url/path/base64，
    **NapCat 回传时 url/path/base64 全为空**，只剩 id（形如 `866b..._b84d...`）。
    因此需要按平台换 URL：群→get_group_file_url，私聊→get_private_file_url。
    """
    task_ref = task["taskRef"]
    file_comp = next(c for c in event.message_chain if isinstance(c, pm.File))
    # 计划 Task 4 Step 0 探针：真机环境下确认字段到底给了什么
    log.warning("file component probe: %s", file_comp.model_dump())
    file_store.debug(f"probe {file_comp.model_dump()}")
    # QQ 文件名不可信（可能含路径），同时处理 / 和 \，防止写到任务目录外
    name = (file_comp.name or "upload.bin").replace("\\", "/").rsplit("/", 1)[-1] or "upload.bin"
    task["originalName"] = name
    ext = Path(name).suffix.lower() or ""
    target = file_store.task_dir(task_ref) / f"input{ext}"

    # 部分适配器直接给本地 path，不必再绕 NapCat/HTTP
    local_path = getattr(file_comp, "path", "") or ""
    if local_path and Path(local_path).is_file():
        shutil.copyfile(local_path, target)
        file_store.debug(f"copied local {local_path} -> {target}")
        return target

    raw_b64 = getattr(file_comp, "base64", "") or ""
    if raw_b64:
        try:
            payload = raw_b64.split(",", 1)[1] if raw_b64.startswith("data:") else raw_b64
            target.write_bytes(base64.b64decode(payload))
            file_store.debug(f"decoded base64 -> {target} ({target.stat().st_size} bytes)")
            return target
        except Exception as e:
            file_store.debug(f"base64 decode failed: {e!r}")

    url = file_comp.url or ""
    await _download_with_retries(ctx, deps, file_comp, task, target, url)
    return target


async def _download_with_retries(ctx, deps, file_comp, task: dict,
                                 target: Path, initial_url: str) -> None:
    """下载文件；失败后向 NapCat 重新取一次 URL，最多共 3 次。

    实机 15:36：NapCat 已给出新签名 URL，但该 QQ CDN 边缘节点返回 502。
    旧代码只 GET 一次，502 直接抛给用户；现在换新 URL 再试，并禁用系统代理。
    """
    last_error: Exception | None = None
    for attempt in range(1, DOWNLOAD_ATTEMPTS + 1):
        if attempt == 1 and initial_url:
            url = initial_url
        else:
            try:
                url = await _fetch_file_url(ctx, deps, file_comp, task)
            except Exception as e:
                last_error = e
                file_store.debug(f"fetch url attempt {attempt} failed: {e!r}")
                if attempt < DOWNLOAD_ATTEMPTS:
                    await asyncio.sleep(0.5 * attempt)
                continue

        if not url:
            last_error = RuntimeError("文件组件无可用下载地址")
            file_store.debug(f"download attempt {attempt}: empty url")
            if attempt < DOWNLOAD_ATTEMPTS:
                await asyncio.sleep(0.5 * attempt)
            continue

        try:
            file_store.debug(f"download attempt={attempt} host={_url_host(url)}")
            await _download_url(url, target, attempt)
            return
        except Exception as e:
            last_error = e
            file_store.debug(f"download attempt={attempt} failed: {e!r}")
            if attempt < DOWNLOAD_ATTEMPTS:
                await asyncio.sleep(0.5 * attempt)

    detail = _safe_error(last_error) if last_error else "没有可用下载地址"
    raise RuntimeError(f"文件下载失败（已尝试{DOWNLOAD_ATTEMPTS}次）：{detail}")


async def _download_url(url: str, target: Path, attempt: int = 1) -> None:
    """流式下载到 .part，成功后再原子替换，避免失败留下半个文件。"""
    tmp = target.with_name(target.name + ".part")
    try:
        # trust_env=False：QQ 文件 CDN 不应被系统代理接管（Clash 等会回 502）
        async with httpx.AsyncClient(
                timeout=60,
                follow_redirects=True,
                trust_env=False,
                headers={"User-Agent": DOWNLOAD_UA},
        ) as client:
            async with client.stream("GET", url) as resp:
                resp.raise_for_status()
                with tmp.open("wb") as fh:
                    async for chunk in resp.aiter_bytes():
                        fh.write(chunk)
        tmp.replace(target)
    except httpx.HTTPStatusError as e:
        tmp.unlink(missing_ok=True)
        raise RuntimeError(
            f"下载文件失败：HTTP {e.response.status_code}（{_url_host(str(e.request.url))}）"
        ) from e
    except Exception:
        tmp.unlink(missing_ok=True)
        raise
    file_store.debug(f"downloaded attempt={attempt} {target} "
                     f"({target.stat().st_size} bytes)")


async def _fetch_file_url(ctx, deps, file_comp, task: dict) -> str:
    """换下载地址：首选直连 NapCat HTTP API，退化到宿主平台 API。"""
    file_id = str(file_comp.id or "")
    chat_id = task.get("chatId")
    sender_id = task["senderId"]
    reasons: list[str] = []

    if getattr(deps.settings, "napcat_api_url", ""):
        action = "get_group_file_url" if chat_id else "get_private_file_url"
        params = {"file_id": file_id}
        params["group_id" if chat_id else "user_id"] = chat_id or sender_id
        try:
            data = await _napcat_call(deps, action, params) or {}
            url = data.get("url") or ""
            if url:
                return url
            reasons.append(f"NapCat {action} 未返回 url：{data!r}")
            file_store.debug(f"napcat {action} 返回无 url: {data!r}")
        except Exception as e:
            reasons.append(str(e))
            file_store.debug(f"napcat {action} failed: {e!r}")
    else:
        reasons.append("napcat_api_url 未配置，无法直连 NapCat 取址")
        log.warning("napcat_api_url 未配置，跳过 NapCat 取址；宿主适配器不支持文件 API")

    bot_uuid = await ctx.get_bot_uuid()

    candidates = []
    if chat_id:
        candidates.append(("get_group_file_url",
                           {"group_id": chat_id, "file_id": file_id}))
    else:
        candidates.append(("get_private_file_url",
                           {"user_id": sender_id, "file_id": file_id}))
    # 跨平台兜底：部分实现只提供 get_file / get_file_url
    candidates.append(("get_file", {"file_id": file_id}))
    candidates.append(("get_file_url", {"file_id": file_id}))

    for action, params in candidates:
        try:
            info = await deps.call_platform_api(bot_uuid, action, params)
        except Exception as e:
            reasons.append(f"宿主 {action} 不可用")
            file_store.debug(f"platform api {action} failed: {e!r}")
            continue
        file_store.debug(f"{action} -> {info!r}")
        if not isinstance(info, dict):
            continue
        payload = info.get("data") if isinstance(info.get("data"), dict) else info
        url = payload.get("url") or payload.get("file") or ""
        if url:
            return url
    if reasons:
        # 把真实原因带回 QQ（去重后限长），否则用户只看到“无可用下载地址”无从下手
        raise RuntimeError("；".join(dict.fromkeys(reasons))[:300])
    return ""


