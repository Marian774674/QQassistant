"""数据目录探测测试。

回归背景（0.2.13 实机崩溃）：LangBot 的 Windows 插件 worker 只继承白名单 env
（COMSPEC/PATH/PATHEXT/SYSTEMROOT/WINDIR），没有 USERPROFILE/LOCALAPPDATA/TEMP，
`Path.home()` 抛 RuntimeError("Could not determine home directory.")，
插件在 initialize 阶段就失败，安装卡在「启动并刷新组件」。
"""
from core import file_store

CLEARED = (
    "USERPROFILE",
    "HOMEDRIVE",
    "HOMEPATH",
    "HOME",
    "LOCALAPPDATA",
    "XDG_DATA_HOME",
    "QQ_ASSISTANT_DATA_DIR",
    "TEMP",
    "TMP",
)


def test_home_missing_falls_back_to_langbot_data_root(tmp_path, monkeypatch):
    storage = (tmp_path / "data" / "plugin-runtime" / "installations"
               / "abc" / "rpc-transfer")
    storage.mkdir(parents=True)
    for name in CLEARED:
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("LANGBOT_PLUGIN_FILE_STORAGE_DIR", str(storage))

    base = file_store.default_base()

    expected = (tmp_path / "data" / "qq_assistant" / "data" / "files").resolve()
    assert base == expected
    assert (base / ".writable").exists() is False  # 探测文件已清理


def test_env_override_wins(tmp_path, monkeypatch):
    monkeypatch.setenv("QQ_ASSISTANT_DATA_DIR", str(tmp_path / "custom"))

    assert file_store.default_base() == (tmp_path / "custom" / "files").resolve()


def test_configure_uses_explicit_data_dir(tmp_path, monkeypatch):
    monkeypatch.setattr(file_store, "BASE", tmp_path / "files")

    configured = file_store.configure(str(tmp_path / "explicit"))

    assert configured == (tmp_path / "explicit" / "files").resolve()
    assert file_store.BASE == configured


def test_safe_path_never_raises_on_unexpandable_tilde(monkeypatch):
    for name in CLEARED:
        monkeypatch.delenv(name, raising=False)
    # 直接构造 Path("~") 会炸；_safe_path 必须兜住
    assert file_store._safe_path("~") == file_store.Path("~")
