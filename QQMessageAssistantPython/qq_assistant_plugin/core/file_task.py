"""文件任务状态机：下载 → 抽取 → Dify 决策 → 回写 → 回传 / 反问补全。

状态：PENDING → PROCESSING → NEED_INPUT / AWAITING_CONFIRM / SENT / FAILED / CANCELLED
- 任务宿主在本模块，Spring Boot 的 file_task 只是只读镜像（每次变化 sync）
- Dify 只返回 fills/missing/confidence，本模块不认识任何业务字段
- deps 即插件实例（main.py 的 qqassistantplugin），storage API 直接可用
"""
import json
import logging
from pathlib import Path

import httpx
from langbot_plugin.api.entities.builtin.platform import message as pm

from core import file_store
from core.extractors import WRITABLE, extract
from core.reply import reply_text
from core.text import extract_text
from core.writers import apply as apply_fills

log = logging.getLogger(__name__)


async def sync(deps, task: dict):
    """镜像状态到 Spring Boot；sync 失败只记日志，不影响任务。"""
    body = {
        "taskRef": task["taskRef"],
        "senderId": task["senderId"],
        "chatId": task.get("chatId"),
        "fileName": (task.get("input") or {}).get("name"),
        "status": task["status"],
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


async def call_file_workflow(deps, structure: dict, facts: dict,
                             request: str, user: str) -> dict:
    """调 Dify 文件工作流，输出统一解析成 fills/missing/confidence/needs_confirm。

    outputs 里的 fills/missing 可能是 JSON 字符串（Dify 变量输出习惯），双格式兼容。
    """
    result = await deps.dify.run_workflow(
        deps.settings.dify_file_workflow_key,
        {
            "structure": json.dumps(structure, ensure_ascii=False),
            "facts": json.dumps(facts, ensure_ascii=False),
            "request": request,
        },
        user=user,
    )
    outputs = (result.get("data") or {}).get("outputs") or {}

    def parse(v, default):
        if isinstance(v, str):
            try:
                return json.loads(v)
            except json.JSONDecodeError:
                log.warning("workflow output not json: %r", v[:200])
                return default
        return v if v is not None else default

    return {
        "fills": parse(outputs.get("fills"), []),
        "missing": parse(outputs.get("missing"), []),
        "confidence": float(outputs.get("confidence") or 0),
        "needs_confirm": bool(outputs.get("needs_confirm") or False),
    }


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
    action = "upload_group_file" if task.get("chatId") else "upload_private_file"
    params = {"file": str(out), "name": out.name}
    params["group_id" if task.get("chatId") else "user_id"] = int(target_id)
    await deps.call_platform_api(bot_uuid, action, params)


async def finish(task: dict, deps, ctx, bot_uuid: str):
    """按 fills 回写 → 高置信直接回传 / 低置信进草稿中心。"""
    src = Path(task["input"]["path"])
    out = file_store.task_dir(task["taskRef"]) / f"{src.stem}_filled{src.suffix}"
    errors = apply_fills(src, out, task.get("fills") or [])
    task["output"] = {"path": str(out), "name": out.name, "errors": errors}

    low_confidence = task.get("needs_confirm") or \
        task.get("confidence", 0) < deps.settings.file_confirm_threshold
    if low_confidence:
        await deps.spring.create_draft(
            "FILE", task["senderId"], task.get("chatId"), "文件待确认",
            {"taskRef": task["taskRef"], "fileName": out.name,
             "fileRef": f"local://{out}"})
        task["status"] = "AWAITING_CONFIRM"
        await _save_and_sync(deps, task)
        await reply_text(ctx, "填好了，去草稿中心确认后发送")
    else:
        await send_file(deps, task, bot_uuid)
        task["status"] = "SENT"
        await _save_and_sync(deps, task)


async def handle(ctx, event, is_group: bool, deps):
    """收到文件的主入口（由 default.py create_task 调起，不阻塞事件链）。"""
    sender_id = int(event.sender_id)
    chat_id = int(event.launcher_id) if is_group else None
    bot_uuid = await ctx.get_bot_uuid()

    # 全局约束：一个用户同时只允许 1 个 NEED_INPUT 任务，新文件顶替旧任务
    old_ref = await file_store.load_active_input(deps, sender_id)
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
    await reply_text(ctx, "收到，正在处理…")

    try:
        path = await download_and_prepare(ctx, event, task, deps)
        if path is None:
            return  # download_and_prepare 内已处理不支持格式的分支

        task["status"] = "PROCESSING"
        await _save_and_sync(deps, task)

        structure = extract(path)
        task["structure"] = structure          # NEED_INPUT 续跑时复用，不重读文件
        facts = ((await deps.spring.profile(sender_id)) or {}).get("facts", {})
        decision = await call_file_workflow(deps, structure, facts,
                                            task["request"], str(sender_id))
        task.update(fills=decision["fills"], missing=decision["missing"],
                    confidence=decision["confidence"],
                    needs_confirm=decision["needs_confirm"])

        if task["missing"]:
            task["status"] = "NEED_INPUT"
            await _save_and_sync(deps, task)
            await file_store.set_active_input(deps, sender_id, task_ref)
            await reply_text(ctx, "还缺：" + "、".join(task["missing"]) + "，直接回复我即可")
            return

        await finish(task, deps, ctx, bot_uuid)

    except Exception as e:
        log.exception("file task %s failed", task_ref)
        task["status"] = "FAILED"
        task["detail"] = {"reason": str(e)}
        await _save_and_sync(deps, task)
        try:
            await reply_text(ctx, f"处理失败：{e}")
        except Exception:
            pass


async def download_and_prepare(ctx, event, task: dict, deps):
    """下载 + 可写性检查。返回输入文件路径；不可写格式已回复并返回 None。"""
    path = await download(ctx, event, task["taskRef"], deps)
    task["input"] = {"path": str(path), "name": path.name}
    if path.suffix.lower() not in WRITABLE:
        task["status"] = "FAILED"
        task["detail"] = {"reason": "暂不支持回写"}
        await _save_and_sync(deps, task)
        await reply_text(ctx, "这个格式我只能读不能改，换成 xlsx/docx 吧")
        return None
    return path


async def download(ctx, event, task_ref: str, deps) -> Path:
    """把 File 组件的附件下载到 data/files/<taskRef>/input<ext>。

    File 字段（SDK 0.7.13 实测）：id/name/size/url/path/base64。
    url 直下；缺 url 时用平台 API get_file_url 换取。
    """
    file_comp = next(c for c in event.message_chain if isinstance(c, pm.File))
    name = file_comp.name or "upload.bin"
    ext = Path(name).suffix.lower() or ""
    target = file_store.task_dir(task_ref) / f"input{ext}"

    url = file_comp.url
    if not url and file_comp.id:
        bot_uuid = await ctx.get_bot_uuid()
        info = await deps.call_platform_api(
            bot_uuid, "get_file_url", {"file_id": str(file_comp.id)})
        url = (info or {}).get("url") or (info or {}).get("file")
    if not url:
        raise RuntimeError(f"文件组件无可用下载地址：{file_comp.model_dump()}")

    async with httpx.AsyncClient(timeout=60, follow_redirects=True) as client:
        resp = await client.get(url)
        resp.raise_for_status()
        target.write_bytes(resp.content)
    return target


async def provide_input(task_ref: str, text: str, ctx, deps) -> bool:
    """NEED_INPUT 续跑：整段用户补充交给 Dify 对齐 missing，插件不做语义解析。

    返回 True 表示消息已被文件任务消费；False 表示任务不存在，走正常流程。
    """
    sender_id = int(ctx.event.sender_id)
    task = await file_store.load_task(deps, task_ref)
    if not task:
        await file_store.set_active_input(deps, sender_id, None)
        return False

    await file_store.set_active_input(deps, sender_id, None)
    task["provided"] = (task.get("provided") or []) + [text]
    task["status"] = "PROCESSING"
    await _save_and_sync(deps, task)

    try:
        request = task.get("request", "") + "\n用户补充：" + "；".join(task["provided"])
        facts = ((await deps.spring.profile(sender_id)) or {}).get("facts", {})
        decision = await call_file_workflow(deps, task.get("structure") or {},
                                            facts, request, str(sender_id))
        task.update(fills=decision["fills"], missing=decision["missing"],
                    confidence=decision["confidence"],
                    needs_confirm=decision["needs_confirm"])

        if task["missing"]:
            # 还缺别的：再次进入 NEED_INPUT
            task["status"] = "NEED_INPUT"
            await _save_and_sync(deps, task)
            await file_store.set_active_input(deps, sender_id, task_ref)
            await reply_text(ctx, "还缺：" + "、".join(task["missing"]) + "，直接回复我即可")
            return True

        bot_uuid = await ctx.get_bot_uuid()
        await finish(task, deps, ctx, bot_uuid)
        return True
    except Exception as e:
        log.exception("provide_input for %s failed", task_ref)
        task["status"] = "FAILED"
        task["detail"] = {"reason": str(e)}
        await _save_and_sync(deps, task)
        try:
            await reply_text(ctx, f"处理失败：{e}")
        except Exception:
            pass
        return True
