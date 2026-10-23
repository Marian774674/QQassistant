import logging
import re
from datetime import date

log = logging.getLogger(__name__)


def matches(text: str, rule: dict) -> bool:
    if not text:
        return False
    keywords = [k.strip() for k in (rule.get("keywords") or "").split(",") if k.strip()]
    regex = (rule.get("regex") or "").strip()

    if keywords and not any(k in text for k in keywords):
        return False
    if regex:
        try:
            if not re.search(regex, text):
                return False
        except re.error as e:
            log.warning("invalid regex in rule %s: %s", rule.get("name"), e)
            return False
    return bool(keywords or regex)


def task_key(chat_id: int, rule_name, window: str = "day") -> str:
    today = date.today()
    suffix = f"{today.isocalendar().year}W{today.isocalendar().week}" if window == "week" else today.isoformat()
    return f"{chat_id}-{rule_name}-{suffix}"


class RuleCache:
    def __init__(self, spring):
        self._spring = spring
        self._rules = []

    async def refresh(self):
        try:
            self._rules = [r for r in (await self._spring.list_rules()) or [] if r.get("enabled", True)]
        except Exception as e:
            log.warning("refresh intercept rules failed, keep %d cached: %s", len(self._rules), e)

    def match(self, text: str):
        for rule in self._rules:
            if matches(text, rule):
                return rule
        return None
