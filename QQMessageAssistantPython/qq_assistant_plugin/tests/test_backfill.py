"""掉线补拉：边沿立即补 + 低频周期兜底，只补游标之后的，写成功才推进游标。

这几个约束是这个功能唯一的价值所在：
- 不能只靠「离线 → 在线」边沿：登录过期 / WS 断开时边沿可能根本不出现；
- 补早了会跟实时消息抢 QQ 接口，所以周期兜底要够低频、失败还要压住重试风暴；
- 补重了会把已经整理过的消息再喂一遍（靠 dedupeKey + 唯一索引吞掉）；
- 写失败还推进游标，那批消息就永远补不回来了。
"""
from types import SimpleNamespace

import pytest

from core import file_store
from core.backfill import MAX_MESSAGES, MAX_PAGES, BackfillService


def msg(message_id, seq, text="你好", sender=1001, nickname="小明",
        time=1767000000, segments=None):
    return {
        "message_id": message_id,
        "message_seq": seq,
        "time": time,
        "sender": {"user_id": sender, "nickname": nickname},
        "message": segments if segments is not None else [
            {"type": "text", "data": {"text": text}}],
    }


class FakeNapcat:
    configured = True

    def __init__(self, online=True, logged_in=True):
        self.online_value = online
        self.online_error = None
        # 服务在线和 QQ 账号登录态是两回事：登录过期时前者仍可能是 True
        self.account_value = logged_in
        self.account_error = None
        self.pages = {}          # (kind, chat_id, message_seq) -> messages
        self.history_calls = []

    async def online(self):
        if self.online_error is not None:
            raise self.online_error
        return self.online_value

    async def account_online(self):
        if self.account_error is not None:
            raise self.account_error
        return self.account_value

    async def group_history(self, group_id, count, message_seq=0):
        self.history_calls.append(("GROUP", group_id, message_seq))
        return self.pages.get(("GROUP", group_id, message_seq), [])

    async def friend_history(self, user_id, count, message_seq=0):
        self.history_calls.append(("PRIVATE", user_id, message_seq))
        return self.pages.get(("PRIVATE", user_id, message_seq), [])


class FakeSpring:
    def __init__(self, targets=None, fail_chats=()):
        self.targets = targets or []
        self.fail_chats = set(fail_chats)
        self.logged = []
        self.cursors = []

    async def napcat_targets(self, limit=50):
        return self.targets

    async def log_messages(self, items):
        if items and items[0].get("chatId") in self.fail_chats:
            raise RuntimeError("spring down")
        self.logged.extend(items)
        return len(items)

    async def advance_napcat_cursor(self, chat_type, chat_id, seq):
        self.cursors.append((chat_type, chat_id, seq))


async def _no_sleep(_seconds):
    return None


@pytest.fixture(autouse=True)
def _quiet(tmp_path, monkeypatch):
    monkeypatch.setattr(file_store, "BASE", tmp_path / "files")


class Clock:
    """可控时钟：周期兜底要测「没到间隔不补、到了间隔补」，不能靠真等。"""

    def __init__(self, now=0.0):
        self.now = now

    def __call__(self):
        return self.now


def build(spring, napcat, enabled=True, sweep=300, clock=None):
    settings = SimpleNamespace(
        napcat_backfill_enabled=enabled,
        napcat_backfill_interval_s=30,
        napcat_backfill_start_delay_s=0,
        napcat_backfill_sweep_s=sweep,
        bot_qq=12345,
    )
    return BackfillService(spring, napcat, settings, sleep=_no_sleep,
                           clock=clock or Clock())


def target(chat_id, cursor=0, kind="GROUP", name="三班群"):
    return {"chatType": kind, "chatId": chat_id, "chatName": name,
            "lastMessageSeq": cursor}


@pytest.mark.asyncio
async def test_first_online_tick_backfills_even_without_a_previous_offline():
    """插件重启时 NapCat 可能一直在跑，那段时间的消息一条都没进来。"""
    napcat = FakeNapcat(online=True)
    napcat.pages[("GROUP", 100, 0)] = [msg("m1", 11), msg("m2", 12)]
    spring = FakeSpring(targets=[target(100)])
    service = build(spring, napcat)

    assert await service.tick() is True
    assert len(spring.logged) == 2


@pytest.mark.asyncio
async def test_staying_online_does_not_pull_again():
    napcat = FakeNapcat(online=True)
    napcat.pages[("GROUP", 100, 0)] = [msg("m1", 11)]
    spring = FakeSpring(targets=[target(100)])
    service = build(spring, napcat)

    await service.tick()
    spring.logged.clear()
    assert await service.tick() is False
    assert spring.logged == []


@pytest.mark.asyncio
async def test_probe_failure_counts_as_offline_so_recovery_still_backfills():
    """NapCat 重启 / 退出登录期间 HTTP 服务是关的，探活会抛异常。

    如果让异常冒出去，`_was_online` 会一直停在 True，
    NapCat 回来后 `online and not True` 恒为 False —— 永远不补拉。
    """
    napcat = FakeNapcat(online=True)
    napcat.pages[("GROUP", 100, 0)] = [msg("m1", 11)]
    spring = FakeSpring(targets=[target(100)])
    service = build(spring, napcat)

    await service.tick()                      # 首次在线 → 补一次
    spring.logged.clear()

    napcat.online_error = RuntimeError("Connection refused")
    assert await service.tick() is False      # 探不到 = 离线，不能抛
    assert service._was_online is False

    napcat.online_error = None
    assert await service.tick() is True       # 恢复在线 → 再补一次
    assert len(spring.logged) == 1


@pytest.mark.asyncio
async def test_reconnect_triggers_a_pull():
    napcat = FakeNapcat(online=True)
    napcat.pages[("GROUP", 100, 0)] = [msg("m1", 11)]
    spring = FakeSpring(targets=[target(100)])
    service = build(spring, napcat)

    await service.tick()                    # 首次在线 → 补
    spring.logged.clear()
    napcat.online_value = False
    assert await service.tick() is False    # 掉线，什么都不做
    napcat.online_value = True
    assert await service.tick() is True     # 重新上线 → 再补
    assert len(spring.logged) == 1


@pytest.mark.asyncio
async def test_login_expiry_backfills_on_relogin_though_service_never_went_offline():
    """登录过期时 NapCat 进程和 HTTP 服务都在，get_status 可能照常报在线。

    旧版只看 online 边沿，这里 `_was_online` 会一直停在 True，补拉永不执行——
    这正是「NapCat 重新登录后，中间的消息还是没被消费」的根因。
    """
    napcat = FakeNapcat(online=True, logged_in=True)
    napcat.pages[("GROUP", 100, 0)] = [msg("m1", 11)]
    spring = FakeSpring(targets=[target(100)])
    service = build(spring, napcat)

    assert await service.tick() is True
    spring.logged.clear()

    napcat.account_value = False            # QQ 登录过期，服务仍报在线
    assert await service.tick() is False
    assert spring.logged == []

    napcat.account_value = True             # 重新登录
    assert await service.tick() is True
    assert [i["dedupeKey"] for i in spring.logged] == ["msg:m1"]


@pytest.mark.asyncio
async def test_account_probe_failure_counts_as_not_ready_and_recovery_pulls():
    """get_login_info 在掉登录时会抛或返回 failed，不能当成「服务在线」糊过去。"""
    napcat = FakeNapcat()
    napcat.pages[("GROUP", 100, 0)] = [msg("m1", 11)]
    spring = FakeSpring(targets=[target(100)])
    service = build(spring, napcat)

    await service.tick()
    spring.logged.clear()

    napcat.account_error = RuntimeError("login expired")
    assert await service.tick() is False
    assert service._was_online is False

    napcat.account_error = None
    assert await service.tick() is True
    assert len(spring.logged) == 1


@pytest.mark.asyncio
async def test_periodic_sweep_backfills_when_no_offline_edge_ever_appears():
    """LangBot 与 NapCat 的 WS 断开、NapCat 一直就绪：没有任何边沿也必须补上。"""
    napcat = FakeNapcat()
    napcat.pages[("GROUP", 100, 0)] = [msg("m1", 11)]
    spring = FakeSpring(targets=[target(100)])
    clock = Clock()
    service = build(spring, napcat, sweep=300, clock=clock)

    assert await service.tick() is True       # 首次就绪
    spring.logged.clear()

    clock.now = 299
    assert await service.tick() is False      # 没到间隔，不刷 QQ 接口
    assert spring.logged == []

    clock.now = 300
    assert await service.tick() is True       # 周期兜底触发
    assert [i["dedupeKey"] for i in spring.logged] == ["msg:m1"]


@pytest.mark.asyncio
async def test_failed_pull_does_not_retry_every_probe():
    """Spring 挂掉时不能每 10s 探活就全量扫一次历史，要等下一个周期。"""
    napcat = FakeNapcat()
    clock = Clock()
    service = build(FakeSpring(targets=[]), napcat, sweep=300, clock=clock)

    async def boom():
        raise RuntimeError("spring down")

    service.run_once = boom
    with pytest.raises(RuntimeError):
        await service.tick()
    assert service._last_pull_at == 0.0

    clock.now = 10
    assert await service.tick() is False      # 失败后立刻的下一轮不再重试


@pytest.mark.asyncio
async def test_only_messages_after_the_cursor_are_written_and_cursor_advances():
    napcat = FakeNapcat()
    napcat.pages[("GROUP", 100, 0)] = [msg("m12", 12), msg("m11", 11), msg("m10", 10)]
    spring = FakeSpring(targets=[target(100, cursor=10)])

    saved = await build(spring, napcat)._backfill_target(target(100, cursor=10))

    # 10 已经补过了，不能再写一遍
    assert saved == 2
    assert [i["dedupeKey"] for i in spring.logged] == ["msg:m11", "msg:m12"]
    assert spring.cursors == [("GROUP", 100, 12)]


@pytest.mark.asyncio
async def test_pages_backwards_until_it_reaches_the_cursor():
    napcat = FakeNapcat()
    # 每页倒序（最新在前），page_seq=0 取最新一页
    napcat.pages[("GROUP", 100, 0)] = [msg("m9", 9), msg("m8", 8), msg("m7", 7)]
    napcat.pages[("GROUP", 100, 7)] = [msg("m6", 6), msg("m5", 5), msg("m4", 4)]
    spring = FakeSpring(targets=[target(100, cursor=4)])

    saved = await build(spring, napcat)._backfill_target(target(100, cursor=4))

    assert [i["dedupeKey"] for i in spring.logged] == [
        "msg:m5", "msg:m6", "msg:m7", "msg:m8", "msg:m9"]
    assert napcat.history_calls == [("GROUP", 100, 0), ("GROUP", 100, 7)]
    assert spring.cursors == [("GROUP", 100, 9)]


@pytest.mark.asyncio
async def test_failed_write_does_not_advance_the_cursor():
    """写一半就推进游标，没写进去的那批消息永远不会再补。"""
    napcat = FakeNapcat()
    napcat.pages[("GROUP", 100, 0)] = [msg("m11", 11), msg("m12", 12)]
    spring = FakeSpring(targets=[target(100)], fail_chats={100})

    summary = await build(spring, napcat).run_once()

    assert summary["saved"] == 0
    assert spring.cursors == []


@pytest.mark.asyncio
async def test_one_failing_chat_does_not_stop_the_others():
    napcat = FakeNapcat()
    napcat.pages[("GROUP", 100, 0)] = [msg("a1", 11)]
    napcat.pages[("GROUP", 200, 0)] = [msg("b1", 21)]
    spring = FakeSpring(targets=[target(100), target(200, name="四班群")],
                        fail_chats={100})

    summary = await build(spring, napcat).run_once()

    assert summary["targets"] == 2
    assert [i["dedupeKey"] for i in spring.logged] == ["msg:b1"]
    assert spring.cursors == [("GROUP", 200, 21)]


@pytest.mark.asyncio
async def test_private_items_have_no_chat_id_so_they_stay_private():
    napcat = FakeNapcat()
    napcat.pages[("PRIVATE", 7001, 0)] = [msg("p1", 31, sender=7001)]
    spring = FakeSpring(targets=[target(7001, kind="PRIVATE", name=None)])

    await build(spring, napcat).run_once()

    item = spring.logged[0]
    # chat_id 一旦填上，Spring 会把它当群消息，私聊会话在网页上就消失了
    assert item["chatId"] is None
    assert "chatName" not in item
    assert item["senderId"] == 7001


@pytest.mark.asyncio
async def test_item_keeps_the_original_time_as_epoch():
    """补回来的消息要落回它原本的时间点，否则整理窗口把它当成「刚刚发生」。"""
    napcat = FakeNapcat()
    napcat.pages[("GROUP", 100, 0)] = [msg("m1", 11, time=1767000123)]
    spring = FakeSpring(targets=[target(100)])

    await build(spring, napcat).run_once()

    assert spring.logged[0]["createdAtEpochSeconds"] == 1767000123


@pytest.mark.asyncio
async def test_media_segments_keep_a_readable_placeholder():
    napcat = FakeNapcat()
    napcat.pages[("GROUP", 100, 0)] = [
        msg("f1", 11, segments=[{"type": "file", "data": {"name": "名单.xlsx"}}]),
        msg("i1", 12, segments=[{"type": "image", "data": {"file": "a.jpg"}}]),
        msg("t1", 13, segments=[{"type": "at", "data": {"qq": "all"}},
                                {"type": "text", "data": {"text": " 开会"}}]),
    ]
    spring = FakeSpring(targets=[target(100)])

    await build(spring, napcat).run_once()

    by_key = {i["dedupeKey"]: i for i in spring.logged}
    assert by_key["msg:f1"]["msgType"] == "file"
    assert by_key["msg:f1"]["content"] == "[文件] 名单.xlsx"
    # 非 text 的 msgType 会被噪音过滤放过，不会被当成「正文没字」丢掉
    assert by_key["msg:i1"]["msgType"] == "image"
    assert by_key["msg:i1"]["content"] == "[图片]"
    assert by_key["msg:t1"]["content"] == "@全体成员 开会"


@pytest.mark.asyncio
async def test_caps_stop_at_five_pages_and_two_hundred_messages():
    napcat = FakeNapcat()
    for page in range(MAX_PAGES):
        top = 200 - page * 40
        page_seq = 0 if page == 0 else top + 1
        napcat.pages[("GROUP", 100, page_seq)] = [
            msg(f"m{s}", s) for s in range(top, top - 40, -1)]
    spring = FakeSpring(targets=[target(100, cursor=0)])

    saved = await build(spring, napcat)._backfill_target(target(100, cursor=0))

    # 5 页 × 40 条正好撞上 200 条上限：不能因为「还有历史」就一直翻下去，
    # 短时间密集拉历史很容易触发 QQ 风控
    assert saved == MAX_MESSAGES
    assert len(napcat.history_calls) == MAX_PAGES


@pytest.mark.asyncio
async def test_bot_own_messages_are_not_logged_as_inbound():
    """NapCat 的历史里有机器人自己说的话。

    当成 IN 写进去，检索会把机器人说过的话当成对方说的证据，
    整理也会把机器人的回复算进「今天群里聊了什么」。
    """
    napcat = FakeNapcat()
    napcat.pages[("GROUP", 100, 0)] = [
        msg("h1", 11, sender=12345, nickname="机器人", text="明天下午三点"),
        msg("h2", 12, sender=1001, nickname="小明", text="好的"),
    ]
    spring = FakeSpring(targets=[target(100)])

    await build(spring, napcat).run_once()

    assert [i["dedupeKey"] for i in spring.logged] == ["msg:h2"]


@pytest.mark.asyncio
async def test_empty_history_writes_nothing():
    napcat = FakeNapcat()
    spring = FakeSpring(targets=[target(100)])

    assert await build(spring, napcat).run_once() == {"targets": 1, "saved": 0}
    assert spring.logged == []
    assert spring.cursors == []


@pytest.mark.asyncio
async def test_disabled_service_never_starts():
    service = build(FakeSpring(), FakeNapcat(), enabled=False)

    assert service.enabled is False
    service.start()
    assert service._task is None


@pytest.mark.asyncio
async def test_unconfigured_napcat_is_not_started():
    napcat = FakeNapcat()
    napcat.configured = False
    service = build(FakeSpring(), napcat)

    assert service.enabled is False
