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
