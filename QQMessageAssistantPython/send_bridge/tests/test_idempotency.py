import json
import time

from send_bridge.idempotency import IdempotencyStore


def test_put_then_get_returns_message_id(tmp_path):
    store = IdempotencyStore(str(tmp_path / "sent.json"))
    store.put("draft:1", "m1")
    assert store.get("draft:1") == "m1"


def test_missing_key_returns_none(tmp_path):
    store = IdempotencyStore(str(tmp_path / "sent.json"))
    assert store.get("draft:9") is None
    assert store.get(None) is None


def test_expired_entry_is_ignored(tmp_path):
    store = IdempotencyStore(str(tmp_path / "sent.json"), ttl=0)
    store.put("draft:1", "m1")
    time.sleep(0.01)
    assert store.get("draft:1") is None


def test_state_survives_restart(tmp_path):
    path = str(tmp_path / "sent.json")
    IdempotencyStore(path).put("draft:2", "m2")
    assert IdempotencyStore(path).get("draft:2") == "m2"


def test_stale_entries_are_pruned_on_load(tmp_path):
    path = tmp_path / "sent.json"
    path.write_text(json.dumps({
        "old": {"messageId": "x", "ts": time.time() - 1000},
        "fresh": {"messageId": "y", "ts": time.time()},
    }), encoding="utf-8")

    store = IdempotencyStore(str(path), ttl=60)
    assert store.get("old") is None
    assert store.get("fresh") == "y"
    # 落盘后旧条目不再存在
    assert "old" not in json.loads(path.read_text(encoding="utf-8"))


def test_put_without_key_is_noop(tmp_path):
    path = tmp_path / "sent.json"
    store = IdempotencyStore(str(path))
    store.put(None, "m1")
    assert not path.exists()


# --- C2: 并发同一 bizRef 只能发一次 ---

def test_reserve_blocks_second_in_flight_request(tmp_path):
    store = IdempotencyStore(str(tmp_path / "sent.json"))
    assert store.reserve("draft:1") is True
    # 第一个请求还在发，第二个必须被拒绝，而不是再发一次
    assert store.reserve("draft:1") is False


def test_release_after_failure_allows_retry(tmp_path):
    store = IdempotencyStore(str(tmp_path / "sent.json"))
    store.reserve("draft:1")
    store.release("draft:1")
    assert store.reserve("draft:1") is True


def test_reserve_rejects_already_sent_key(tmp_path):
    store = IdempotencyStore(str(tmp_path / "sent.json"))
    store.put("draft:1", "m1")
    assert store.reserve("draft:1") is False


def test_default_state_file_is_absolute():
    """幂等状态不能跟着启动目录漂移，否则换个目录启动就丢幂等。"""
    import os
    from send_bridge.config import Settings

    assert os.path.isabs(Settings().state_file)
