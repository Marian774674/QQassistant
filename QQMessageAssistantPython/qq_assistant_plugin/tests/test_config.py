"""配置读取：initialize() 期间 get_config() 被空 scoped 快照遮住的历史坑。

实机 0.2.14：DB 里 dify_file_workflow_key 明明有值，插件运行时报
「未配置 Dify 文件工作流 API Key」——因为 SDK 的 BasePlugin.get_config()
优先返回 invocation 作用域里的 plugin_config（dedicated worker 注册时是 {}），
把运行器刚赋值给 _legacy_config 的真配置遮住了。
"""
from core.config import load_settings, read_plugin_config


class FakePlugin:
    def __init__(self, legacy=None, scoped=None, scoped_raises=False):
        self._legacy_config = legacy or {}
        self._scoped = scoped or {}
        self.scoped_raises = scoped_raises

    def get_config(self):
        if self.scoped_raises:
            # 后台任务里调用会抛这个；initialize 期间遇到也不能炸
            raise RuntimeError("Plugin invocation has ended")
        return self._scoped


def test_host_config_wins_over_empty_scoped_snapshot():
    p = FakePlugin(legacy={"dify_api_key": "k1", "dify_file_workflow_key": "w1"})
    cfg, source = read_plugin_config(p)
    assert source == "host"
    assert cfg["dify_file_workflow_key"] == "w1"


def test_load_settings_uses_host_config():
    p = FakePlugin(legacy={"dify_api_key": "k1", "dify_file_workflow_key": "w1",
                           "bot_qq": "3621586096"})
    s = load_settings(p)
    assert s.dify_api_key == "k1"
    assert s.dify_file_workflow_key == "w1"
    assert s.bot_qq == 3621586096


def test_scoped_config_used_when_host_empty():
    p = FakePlugin(scoped={"dify_file_workflow_key": "w2"})
    cfg, source = read_plugin_config(p)
    assert source == "scoped"
    assert cfg["dify_file_workflow_key"] == "w2"


def test_empty_config_does_not_raise():
    p = FakePlugin(scoped_raises=True)
    cfg, source = read_plugin_config(p)
    assert cfg == {} and source == "empty"
    # 全空时退化成默认值，但绝不能让初始化抛异常
    s = load_settings(p)
    assert s.dify_file_workflow_key == ""
    assert s.napcat_api_url == "http://127.0.0.1:3000"
