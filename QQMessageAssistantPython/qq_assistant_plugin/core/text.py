import random
import time
from langbot_plugin.api.entities.builtin.platform import message as pm


def extract_text(chain) -> str:
    parts = []
    for comp in chain:
        if isinstance(comp, pm.Plain):
            parts.append(comp.text or "")
    return "".join(parts).strip()


def has_file(chain) -> bool:
    return any(isinstance(comp, pm.File) for comp in chain)


def is_at_bot(chain, bot_qq: int) -> bool:
    return any(isinstance(comp, pm.At) and str(comp.target) == str(bot_qq) for comp in chain)


def human_delay_seconds(text: str, settings) -> float:
    ms = (settings.delay_base_ms
          + len(text) * settings.delay_per_char_ms
          + random.randint(0, settings.delay_jitter_ms))
    return min(ms, settings.delay_max_ms) / 1000.0