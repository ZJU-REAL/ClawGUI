"""Task intent normalization and lightweight slot extraction."""

from __future__ import annotations

import re
from dataclasses import dataclass


COMMON_APPS = [
    "Feishu",
    "飞书",
    "Lark",
    "WeChat",
    "微信",
    "Settings",
    "设置",
    "Notes",
    "备忘录",
    "笔记",
    "Taobao",
    "淘宝",
    "Chrome",
    "浏览器",
    "Calendar",
    "日历",
    "Messages",
    "短信",
]

SYSTEM_APPS = {
    "System Home",
    "Home",
    "Launcher",
    "桌面",
    "系统桌面",
    "Unknown",
}

APP_ALIASES = {
    "飞书": "Feishu",
    "Feishu": "Feishu",
    "Lark": "Feishu",
    "微信": "WeChat",
    "WeChat": "WeChat",
    "设置": "Settings",
    "Settings": "Settings",
    "备忘录": "Notes",
    "笔记": "Notes",
    "Notes": "Notes",
    "Chrome": "Chrome",
    "浏览器": "Browser",
    "日历": "Calendar",
    "Calendar": "Calendar",
    "短信": "Messages",
    "Messages": "Messages",
}

OPERATION_DISPLAY_NAMES = {
    "send_message": "Send message",
    "create_event": "Create event",
    "toggle_setting": "Toggle setting",
    "search_open": "Search and open",
    "create_note": "Create note",
}

SETTING_ALIASES = {
    "bluetooth": "Bluetooth",
    "蓝牙": "Bluetooth",
    "wifi": "Wi-Fi",
    "wi-fi": "Wi-Fi",
    "无线网络": "Wi-Fi",
    "wlan": "Wi-Fi",
    "location": "Location",
    "定位": "Location",
    "airplane mode": "Airplane mode",
    "飞行模式": "Airplane mode",
    "nfc": "NFC",
    "dark mode": "Dark mode",
    "深色模式": "Dark mode",
    "do not disturb": "Do Not Disturb",
    "勿扰": "Do Not Disturb",
}

STOPWORDS = {
    "the",
    "a",
    "an",
    "to",
    "of",
    "in",
    "on",
    "for",
    "and",
    "or",
    "is",
    "be",
    "with",
    "by",
    "at",
    "from",
    "as",
    "this",
    "that",
    "task",
    "user",
    "这个",
    "该",
}


@dataclass(frozen=True)
class TaskSignature:
    """Stable task slots used for retrieval and duplicate prevention."""

    apps: tuple[str, ...] = ()
    operation: str = ""
    target: str = ""
    arguments: tuple[str, ...] = ()

    @property
    def labels(self) -> set[str]:
        labels: set[str] = set()
        labels.update(f"app:{app.lower()}" for app in self.apps if app)
        if self.operation:
            labels.add(f"op:{self.operation}")
        if self.target:
            labels.add(f"target:{normalize_slot(self.target)}")
        labels.update(f"arg:{arg}" for arg in self.arguments if arg)
        return labels


@dataclass(frozen=True)
class TaskFrame:
    """Abstract task identity plus concrete runtime slots.

    The frame separates the reusable skill identity (apps + operation +
    argument names) from task-specific values such as recipient, message,
    event time, or setting name.
    """

    apps: tuple[str, ...] = ()
    operation: str = ""
    stable_target: str = ""
    runtime_slots: tuple[tuple[str, str], ...] = ()

    @property
    def arguments(self) -> tuple[str, ...]:
        return tuple(key for key, value in self.runtime_slots if key and value)

    @property
    def runtime_parameters(self) -> dict[str, str]:
        return {key: value for key, value in self.runtime_slots if key and value}

    @property
    def labels(self) -> set[str]:
        labels: set[str] = set()
        labels.update(f"app:{app.lower()}" for app in self.apps if app)
        if self.operation:
            labels.add(f"op:{self.operation}")
        if self.stable_target:
            labels.add(f"target:{normalize_slot(self.stable_target)}")
        labels.update(f"arg:{arg}" for arg in self.arguments if arg)
        return labels

    @property
    def operation_display_name(self) -> str:
        return OPERATION_DISPLAY_NAMES.get(self.operation, "")

    def stable_intent(self) -> str:
        app = self.apps[0] if self.apps else ""
        if self.operation == "send_message":
            suffix = f" in {app}" if app else ""
            return f"Send a message to a chat/contact{suffix}"
        if self.operation == "create_event":
            suffix = f" in {app}" if app else ""
            return f"Create a calendar event{suffix}"
        if self.operation == "toggle_setting":
            suffix = f" in {app}" if app else ""
            return f"Toggle a setting{suffix}"
        if self.operation == "search_open":
            suffix = f" in {app}" if app else ""
            return f"Search and open a result{suffix}"
        if self.operation == "create_note":
            suffix = f" in {app}" if app else ""
            return f"Create a note{suffix}"
        return ""


def canonical_app(value: str) -> str:
    return APP_ALIASES.get(value, APP_ALIASES.get(value.strip(), value.strip()))


def normalize_text(text: str | None) -> str:
    text = (text or "").lower()
    text = text.replace("“", '"').replace("”", '"').replace("‘", "'").replace("’", "'")
    for alias, canonical in APP_ALIASES.items():
        text = re.sub(re.escape(alias.lower()), canonical.lower(), text)
    text = re.sub(r"\b(the|this|that)\b|这个|该", " ", text)
    text = re.sub(r"群聊|群|聊天|chat", " chat ", text)
    text = re.sub(r"发送|send", " send ", text)
    text = re.sub(r"创建|新建|create|add", " create ", text)
    text = re.sub(r"打开|open", " open ", text)
    text = re.sub(r"搜索|查找|search|find", " search ", text)
    text = re.sub(r"开启|打开|turn on|enable", " enable ", text)
    text = re.sub(r"关闭|turn off|disable", " disable ", text)
    return re.sub(r"\s+", " ", text).strip()


def normalize_slot(text: str | None) -> str:
    value = normalize_text(text)
    value = re.sub(r"[^a-z0-9\u4e00-\u9fff]+", "_", value)
    value = re.sub(r"_+", "_", value).strip("_")
    return value


def tokenize(text: str | None) -> list[str]:
    normalized = normalize_text(text)
    tokens = re.findall(r"[a-zA-Z0-9_']+", normalized)
    tokens.extend(re.findall(r"[\u4e00-\u9fff]{2,}", normalized))
    return [t for t in tokens if len(t.strip()) >= 2 and t not in STOPWORDS]


def is_informative_token(token: str) -> bool:
    """Return True for concept tokens and False for likely task parameters."""

    if not (3 <= len(token) <= 16):
        return False
    if any(ch.isdigit() for ch in token):
        return False
    if token in STOPWORDS:
        return False
    return token.isalpha()


def infer_apps(task: str, current_app: str = "") -> list[str]:
    haystack = normalize_text(task)
    apps: list[str] = []
    for alias, canonical in APP_ALIASES.items():
        if alias.lower() in haystack and canonical not in apps:
            apps.append(canonical)
    for app in COMMON_APPS:
        canonical = canonical_app(app)
        if app.lower() in haystack and canonical not in apps:
            apps.append(canonical)
    if current_app and current_app not in SYSTEM_APPS:
        canonical = canonical_app(current_app)
        if canonical and canonical not in apps:
            apps.append(canonical)
    return apps[:4]


def parse_message_task(task: str) -> tuple[str, str] | None:
    target_match = re.search(r"在(.+?)(?:群|群聊|聊天|chat)", task, re.I)
    message_match = re.search(r"(?:发送|send)\s*[“\"']?(.+?)[”\"']?$", task, re.I)
    if target_match and message_match:
        target = clean_chat_target(target_match.group(1))
        message = _clean_slot_value(message_match.group(1))
        if target and message:
            return target, message

    direct_patterns = [
        r"(?:给|向)\s*(.+?)\s*(?:发送|发|send)\s*(?:消息|信息|message)?\s*[“\"']?(.+?)[”\"']?$",
        r"(?:发送|发|send)\s*[“\"']?(.+?)[”\"']?\s*(?:给|向|to)\s*(.+?)$",
        r"\bsend\s+[“\"']?(.+?)[”\"']?\s+to\s+(.+?)$",
    ]
    for index, pattern in enumerate(direct_patterns):
        match = re.search(pattern, task, re.I)
        if not match:
            continue
        if index == 0:
            target, message = match.group(1), match.group(2)
        else:
            message, target = match.group(1), match.group(2)
        target = clean_chat_target(target)
        message = _clean_slot_value(message)
        if target and message:
            return target, message
    return None


def parse_event_task(task: str) -> dict[str, str] | None:
    normalized = normalize_text(task)
    if "calendar" not in normalized and "event" not in normalized and "meeting" not in normalized:
        return None
    if "create" not in normalized and not re.search(r"创建|新建|安排|预约", task, re.I):
        return None
    slots: dict[str, str] = {}
    title_patterns = [
        r"(?:标题|名称|主题)(?:为|是|:|：)\s*[“\"']?(.+?)[”\"']?(?:，|,|。|$)",
        r"(?:meeting|event)\s+(?:called|named)\s+[“\"']?(.+?)[”\"']?(?:,|\.|$)",
    ]
    for pattern in title_patterns:
        match = re.search(pattern, task, re.I)
        if match:
            slots["event_title"] = _clean_slot_value(match.group(1))
            break
    time_match = re.search(
        r"((?:今天|明天|后天|下周|周[一二三四五六日天]|星期[一二三四五六日天]|\d{1,2}[月/-]\d{1,2}[日号]?|"
        r"\d{1,2}(?::\d{2})?\s*(?:am|pm)?|上午|下午|晚上|早上)[^，,。]*)",
        task,
        re.I,
    )
    if time_match:
        slots["time"] = _clean_slot_value(time_match.group(1))
    location_match = re.search(r"(?:地点|位置|location|at)(?:为|是|:|：)?\s*[“\"']?(.+?)[”\"']?(?:，|,|。|$)", task, re.I)
    if location_match:
        slots["location"] = _clean_slot_value(location_match.group(1))
    return slots or {"event_title": "<current task event>"}


def parse_setting_task(task: str) -> dict[str, str] | None:
    normalized = normalize_text(task)
    if "settings" not in normalized and "setting" not in normalized:
        return None
    action = ""
    if "enable" in normalized or re.search(r"开启|打开|启用", task):
        action = "on"
    elif "disable" in normalized or re.search(r"关闭|停用", task):
        action = "off"
    elif re.search(r"切换|toggle|switch", task, re.I):
        action = "toggle"
    setting = ""
    for alias, canonical in SETTING_ALIASES.items():
        if alias.lower() in normalized or alias in task:
            setting = canonical
            break
    if not action and not setting:
        return None
    slots: dict[str, str] = {}
    if setting:
        slots["setting_name"] = setting
    if action:
        slots["state"] = action
    return slots or None


def parse_search_open_task(task: str) -> dict[str, str] | None:
    normalized = normalize_text(task)
    if "search" not in normalized and not re.search(r"搜索|查找", task):
        return None
    if "open" not in normalized and not re.search(r"打开|进入|查看|点击", task):
        return None
    query_match = re.search(r"(?:搜索|查找|search(?: for)?|find)\s*[“\"']?(.+?)[”\"']?(?:，|,|。|后|并|然后|and|then|$)", task, re.I)
    target_match = re.search(r"(?:打开|进入|查看|点击|open)\s*[“\"']?(.+?)[”\"']?(?:，|,|。|$)", task, re.I)
    slots: dict[str, str] = {}
    if query_match:
        slots["query"] = _clean_slot_value(query_match.group(1))
    if target_match:
        target = _clean_slot_value(target_match.group(1))
        if target and normalize_slot(target) != normalize_slot(slots.get("query", "")):
            slots["result"] = target
    return slots or None


def parse_note_task(task: str) -> dict[str, str] | None:
    normalized = normalize_text(task)
    if "notes" not in normalized and not re.search(r"备忘录|笔记", task):
        return None
    if "create" not in normalized and not re.search(r"创建|新建|记录|写", task):
        return None
    content_match = re.search(r"(?:内容|记录|写|\bcontent\b)(?:为|是|:|：)?\s*[“\"']?(.+?)[”\"']?$", task, re.I)
    title_match = re.search(r"(?:标题|名称|title)(?:为|是|:|：)\s*[“\"']?(.+?)[”\"']?(?:，|,|。|$)", task, re.I)
    slots: dict[str, str] = {}
    if title_match:
        slots["title"] = _clean_slot_value(title_match.group(1))
    if content_match:
        slots["content"] = _clean_slot_value(content_match.group(1))
    return slots or {"content": "<current task note>"}


def _clean_slot_value(raw: str) -> str:
    return raw.strip(" ：:，,。 .'\"“”‘’")


def clean_chat_target(raw: str) -> str:
    target = raw.strip(" ：:，,。 .")
    target = re.sub(r"(这个|该|the)\s*$", "", target, flags=re.I)
    return target.strip(" ：:，,。 .")


def task_frame(
    text: str,
    *,
    apps: list[str] | tuple[str, ...] | None = None,
    current_app: str = "",
    arguments: list[str] | tuple[str, ...] | None = None,
) -> TaskFrame:
    inferred_apps = list(apps or infer_apps(text, current_app))
    normalized_args = _arguments_to_slots(arguments or [])

    message_task = parse_message_task(text)
    if message_task:
        target, message = message_task
        return TaskFrame(
            apps=tuple(inferred_apps[:4]),
            operation="send_message",
            runtime_slots=(("recipient", target), ("message", message)),
        )

    event_slots = parse_event_task(text)
    if event_slots:
        return _frame_from_slots(inferred_apps, "create_event", event_slots, normalized_args)

    setting_slots = parse_setting_task(text)
    if setting_slots:
        return _frame_from_slots(inferred_apps, "toggle_setting", setting_slots, normalized_args)

    search_slots = parse_search_open_task(text)
    if search_slots:
        return _frame_from_slots(inferred_apps, "search_open", search_slots, normalized_args)

    note_slots = parse_note_task(text)
    if note_slots:
        return _frame_from_slots(inferred_apps, "create_note", note_slots, normalized_args)

    if normalized_args:
        operation = _operation_from_args(normalized_args)
        return TaskFrame(
            apps=tuple(inferred_apps[:4]),
            operation=operation,
            runtime_slots=tuple(normalized_args.items()),
        )

    return TaskFrame(apps=tuple(inferred_apps[:4]))


def _frame_from_slots(
    apps: list[str],
    operation: str,
    slots: dict[str, str],
    extra_slots: dict[str, str],
) -> TaskFrame:
    merged = {**extra_slots, **{k: v for k, v in slots.items() if v}}
    return TaskFrame(
        apps=tuple(apps[:4]),
        operation=operation,
        runtime_slots=tuple(merged.items()),
    )


def _arguments_to_slots(arguments: list[str] | tuple[str, ...]) -> dict[str, str]:
    slots: dict[str, str] = {}
    for arg in arguments:
        text = str(arg)
        if ":" in text:
            key, value = text.split(":", 1)
            slots[key.strip()] = value.strip()
        else:
            slots[text.strip()] = "<variable>"
    return {k: v for k, v in slots.items() if k}


def _operation_from_args(slots: dict[str, str]) -> str:
    keys = set(slots)
    if {"recipient", "message"} & keys:
        return "send_message"
    if {"event_title", "time", "location"} & keys:
        return "create_event"
    if {"setting_name", "state"} & keys:
        return "toggle_setting"
    if {"query", "result"} & keys:
        return "search_open"
    if {"title", "content"} & keys:
        return "create_note"
    return ""


def task_signature(
    text: str,
    *,
    apps: list[str] | tuple[str, ...] | None = None,
    arguments: list[str] | tuple[str, ...] | None = None,
) -> TaskSignature:
    frame = task_frame(text, apps=apps, arguments=arguments)

    return TaskSignature(
        apps=frame.apps,
        operation=frame.operation,
        target=frame.stable_target,
        arguments=frame.arguments,
    )


def task_arguments(text: str) -> list[str]:
    frame = task_frame(text)
    return [f"{arg}:<variable>" for arg in frame.arguments]


def task_runtime_parameters(text: str) -> dict[str, str]:
    return task_frame(text).runtime_parameters


def stable_task_intent(text: str, apps: list[str] | None = None) -> str:
    """Return an abstract intent that does not bake in runtime parameters."""

    frame = task_frame(text, apps=apps)
    return frame.stable_intent() or text


def task_keywords(text: str, apps: list[str] | None = None, limit: int = 12) -> list[str]:
    keywords: list[str] = []
    frame = task_frame(text, apps=apps)
    if frame.operation:
        for app in apps or []:
            token = normalize_slot(app)
            if token and token not in keywords:
                keywords.append(token)
        for token in (frame.operation, *frame.arguments):
            if token not in keywords:
                keywords.append(token)
        return keywords[:limit]

    for token in tokenize(text):
        if token not in keywords and not token.startswith("variable"):
            keywords.append(token)
        if len(keywords) >= limit:
            break
    for app in apps or []:
        token = normalize_slot(app)
        if token and token not in keywords:
            keywords.append(token)
    return keywords[:limit]
