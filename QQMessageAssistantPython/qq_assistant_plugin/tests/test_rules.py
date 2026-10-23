from core.rules import matches

RULES = [
    {"name": "学号", "keywords": "学号,姓名", "regex": r"(学号|姓名)\s*[:：]", "enabled": True},
    {"name": "坏正则", "keywords": "坏", "regex": "([", "enabled": True},
]

def test_keyword_and_regex():
    assert matches("请填学号：2023001", RULES[0]) is True
    assert matches("今天天气不错", RULES[0]) is False

def test_bad_regex_does_not_raise():
    assert matches("坏消息", RULES[1]) is False