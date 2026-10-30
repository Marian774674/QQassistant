from core.text import extract_text, human_delay_seconds, is_at_bot
from langbot_plugin.api.entities.builtin.platform.message import MessageChain, Plain, At
from core.config import Settings

settings = Settings(
    spring_base_url="http://localhost:8080",
    spring_timeout=5.0,
    dify_base_url="http://127.0.0.1/v1",
    dify_api_key="",
    dify_timeout=60.0,
    dify_file_workflow_key="",
    file_confirm_threshold=0.85,
    bot_uuid="",
    bot_qq=1753800374,
    group_prefix="!",
    fact_auto_threshold=0.85,
    delay_base_ms=1500,
    delay_per_char_ms=150,
    delay_jitter_ms=1000,
    delay_max_ms=15000,
    intercept_refresh_s=60,
    intercept_scope="group",
)

def test_extract_text_joins_plain_only():
    chain = MessageChain([Plain(text="在"), Plain(text="吗")])
    assert extract_text(chain) == "在吗"

def test_at_bot_detection():
    chain = MessageChain([At(target=12345), Plain(text=" 你好")])
    assert is_at_bot(chain, 12345) is True
    assert is_at_bot(chain, 99999) is False

def test_delay_formula_bounds():
    s = human_delay_seconds("x" * 200, settings)
    assert s == 15.0          # 上限


def test_peer_names_reads_sender_and_group():
    from types import SimpleNamespace
    from core.text import peer_names

    event = SimpleNamespace(sender=SimpleNamespace(name="小明"),
                            group=SimpleNamespace(name="三班群"))
    assert peer_names(event) == ("小明", "三班群")


def test_peer_names_falls_back_to_empty_strings():
    from types import SimpleNamespace
    from core.text import peer_names

    assert peer_names(SimpleNamespace()) == ("", "")


def test_extract_text_keeps_at_mentions():
    """有人真的 @ 你时，正文里得留下 @昵称，否则整理时认不出「在叫你」。"""
    chain = MessageChain([At(target=12345, display="小明"), Plain(text=" 明天来吗")])
    assert extract_text(chain) == "@小明 明天来吗"


def test_extract_text_keeps_at_all():
    from langbot_plugin.api.entities.builtin.platform.message import AtAll
    chain = MessageChain([AtAll(), Plain(text=" 开会了")])
    assert extract_text(chain) == "@全体成员 开会了"
