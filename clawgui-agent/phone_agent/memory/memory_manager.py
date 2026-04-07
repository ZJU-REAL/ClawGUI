"""
Memory Manager - High-level interface for agent memory operations.

Handles automatic extraction of memories from conversations,
context enrichment, and integration with the agent loop.
"""

import json
import re
from datetime import datetime
from typing import Any

from .memory_store import MemoryStore, Memory, MemoryType


# Patterns for extracting user preferences
PREFERENCE_PATTERNS = {
    "contact": [
        # 改进：更精确的联系人提取，限制长度，排除动词
        r"(?:给|发送?给?|联系|打电话给?|发消息给?)\s*[「『""]?([\u4e00-\u9fa5a-zA-Z]{2,8})[」』""]?(?:发|说|打|$)",
        r"(?:联系人|好友|朋友)\s*[「『""]?([\u4e00-\u9fa5a-zA-Z]{2,8})[」』""]?",
        r"(?:to|contact|call|message)\s+([a-zA-Z\u4e00-\u9fa5]{2,15})(?:\s|$)",
    ],
    "app": [
        r"(打开|启动|使用|进入)[\s]*([\u4e00-\u9fa5a-zA-Z]+)",
        r"(open|launch|use)[\s]+([a-zA-Z\u4e00-\u9fa5]+)",
    ],
    "time_preference": [
        r"(每天|每周|每月|通常|一般)[\s]*([\u4e00-\u9fa5a-zA-Z]+)",
        r"(usually|always|often)[\s]+([a-zA-Z\u4e00-\u9fa5]+)",
    ],
}

# Common apps to recognize
KNOWN_APPS = {
    "微信", "wechat", "支付宝", "alipay", "淘宝", "taobao", "抖音", "tiktok",
    "美团", "meituan", "饿了么", "eleme", "京东", "jd", "拼多多", "pinduoduo",
    "高德地图", "amap", "百度地图", "baidu maps", "微博", "weibo", "qq",
    "钉钉", "dingtalk", "飞书", "feishu", "网易云音乐", "netease music",
    "spotify", "bilibili", "b站", "小红书", "xiaohongshu", "safari", "chrome",
    "设置", "settings", "相机", "camera", "相册", "photos", "备忘录", "notes",
}


class MemoryManager:
    """
    High-level memory management for the phone agent.
    
    Responsibilities:
    - Extract memories from user inputs and agent outputs
    - Provide relevant context for new tasks
    - Track user preferences and habits
    - Learn from successful task completions
    - Automatically learn from agent's thinking process
    """
    
    def __init__(
        self,
        storage_dir: str = "memory_db",
        user_id: str = "default",
        enable_auto_extract: bool = True,
        enable_thinking_analysis: bool = True,
    ):
        """
        Initialize memory manager.
        
        Args:
            storage_dir: Base directory for memory storage
            user_id: User identifier for personalization
            enable_auto_extract: Auto-extract memories from conversations
            enable_thinking_analysis: Auto-learn from agent's thinking process
        """
        self.user_id = user_id
        self.enable_auto_extract = enable_auto_extract
        self.enable_thinking_analysis = enable_thinking_analysis
        
        # Create user-specific storage
        user_storage = f"{storage_dir}/{user_id}"
        self.store = MemoryStore(storage_dir=user_storage)
        
        # Session history for context
        self.session_history: list[dict] = []
        
        # Current task context
        self.current_task: str = ""
        self.task_start_time: str = ""
        
        # Track extracted info in current session to avoid duplicates
        self._session_contacts: set[str] = set()
        self._session_apps: set[str] = set()
    
    def start_task(self, task: str):
        """Called when a new task begins."""
        self.current_task = task
        self.task_start_time = datetime.now().isoformat()
        self.session_history.clear()
        
        # Reset session tracking
        self._session_contacts.clear()
        self._session_apps.clear()
        
        if self.enable_auto_extract:
            self._extract_from_task(task)
    
    def end_task(self, success: bool, result: str = ""):
        """Called when a task completes."""
        if self.current_task:
            # Record task history with success/failure info
            importance = 0.6 if success else 0.4
            
            self.store.add(
                content=f"任务: {self.current_task} | 结果: {result} | {'成功' if success else '失败'}",
                memory_type=MemoryType.TASK_HISTORY,
                metadata={
                    "task": self.current_task,
                    "result": result,
                    "success": success,
                    "duration": self._calculate_duration(),
                    "steps": len(self.session_history),
                    "apps_used": list(self._session_apps),
                    "contacts_mentioned": list(self._session_contacts),
                },
                importance=importance,
            )
            
            # If task was successful, learn patterns from the session
            if success and len(self.session_history) > 0:
                self._learn_successful_pattern()
        
        self.current_task = ""
        self.task_start_time = ""
    
    def _learn_successful_pattern(self):
        """Learn patterns from successfully completed tasks."""
        if len(self.session_history) < 2:
            return
        
        # Extract the sequence of apps used
        apps_sequence = []
        for step in self.session_history:
            app = step.get("app", "")
            if app and app not in ("Unknown", "unknown"):
                if not apps_sequence or apps_sequence[-1] != app:
                    apps_sequence.append(app)
        
        # If there's a consistent app flow, record it
        if len(apps_sequence) >= 2:
            flow_description = " → ".join(apps_sequence[:5])
            self.store.add(
                content=f"任务执行流程: {self.current_task[:30]} 使用了 {flow_description}",
                memory_type=MemoryType.TASK_PATTERN,
                metadata={
                    "task_type": self._classify_task(self.current_task),
                    "apps_flow": apps_sequence,
                    "task_summary": self.current_task[:100],
                },
                importance=0.4,
            )
        
        # 🔥 重要：记录联系人-应用绑定关系
        self._learn_contact_app_binding(apps_sequence)
    
    def _learn_contact_app_binding(self, apps_used: list[str]):
        """
        学习联系人与应用的绑定关系，基于使用频率。
        
        当用户通过某个应用联系某人时，记录这个关联。
        如果多次使用同一应用联系同一人，增加使用次数。
        """
        if not apps_used:
            return
        
        # 从当前任务中提取联系人
        import re
        contact_patterns = [
            r'给[「『""]?([\u4e00-\u9fa5a-zA-Z]{2,10})[」』""]?(?:发|说|打)',
            r'联系[「『""]?([\u4e00-\u9fa5a-zA-Z]{2,10})[」』""]?',
            r'(?:to|contact|message)\s+([a-zA-Z\u4e00-\u9fa5]{2,15})',
        ]
        
        contacts_found = set()
        for pattern in contact_patterns:
            matches = re.findall(pattern, self.current_task, re.IGNORECASE)
            for match in matches:
                if len(match) >= 2:
                    contacts_found.add(match)
        
        if not contacts_found:
            return
        
        # 找到主要使用的通讯应用
        comm_apps = ["qq", "微信", "wechat", "钉钉", "dingtalk", "飞书", "feishu", "短信", "sms"]
        main_app = None
        for app in apps_used:
            if any(ca in app.lower() for ca in comm_apps):
                main_app = app
                break
        
        if not main_app:
            return
        
        # 为每个联系人创建/更新应用绑定
        for contact in contacts_found:
            self._update_contact_app_binding(contact, main_app)
    
    def _update_contact_app_binding(self, contact: str, app: str):
        """
        更新联系人-应用绑定，支持使用频率统计。
        
        如果已存在绑定，增加使用次数；否则创建新绑定。
        """
        # 生成唯一的绑定 ID
        binding_key = f"{contact.lower()}_{app.lower()}"
        
        # 查找现有绑定
        existing_binding = None
        for memory in self.store.memories.values():
            if memory.memory_type == MemoryType.CONTACT_APP_BINDNG:
                if memory.metadata.get("binding_key") == binding_key:
                    existing_binding = memory
                    break
        
        if existing_binding:
            # 更新现有绑定
            existing_binding.access_count += 1
            existing_binding.last_accessed = datetime.now().isoformat()
            # 重要性随使用次数增加
            existing_binding.importance = min(1.0, 0.5 + existing_binding.access_count * 0.05)
            existing_binding.metadata["use_count"] = existing_binding.access_count
            self.store._save_memories()
        else:
            # 创建新绑定
            self.store.add(
                content=f"联系人应用绑定: {contact} → {app}",
                memory_type=MemoryType.CONTACT_APP_BINDNG,
                metadata={
                    "contact": contact,
                    "app": app,
                    "binding_key": binding_key,
                    "use_count": 1,
                },
                importance=0.5,
            )
    
    def _classify_task(self, task: str) -> str:
        """Classify task into categories."""
        task_lower = task.lower()
        
        if any(k in task_lower for k in ["消息", "发送", "聊天", "微信", "qq"]):
            return "communication"
        if any(k in task_lower for k in ["外卖", "点餐", "美团", "饿了么"]):
            return "food_delivery"
        if any(k in task_lower for k in ["购买", "下单", "淘宝", "京东", "购物"]):
            return "shopping"
        if any(k in task_lower for k in ["导航", "地图", "打车", "路线"]):
            return "navigation"
        if any(k in task_lower for k in ["视频", "抖音", "b站", "bilibili"]):
            return "entertainment"
        if any(k in task_lower for k in ["设置", "配置", "开关"]):
            return "settings"
        
        return "general"
    
    def add_step(self, thinking: str, action: dict, screenshot_app: str = ""):
        """
        Record a step in the current task and auto-learn from it.
        
        This method automatically extracts:
        - App usage patterns from the current app
        - Contact information from actions
        - User preferences from the thinking process
        """
        step = {
            "timestamp": datetime.now().isoformat(),
            "thinking": thinking,
            "action": action,
            "app": screenshot_app,
        }
        self.session_history.append(step)
        
        # Extract app usage patterns
        if screenshot_app:
            self._track_app_usage(screenshot_app)
        
        # Auto-learn from action
        if self.enable_auto_extract:
            self._learn_from_action(action)
        
        # Auto-learn from thinking (extract mentioned entities)
        if self.enable_thinking_analysis and thinking:
            self._learn_from_thinking(thinking)
    
    def _calculate_duration(self) -> float:
        """Calculate task duration in seconds."""
        if not self.task_start_time:
            return 0.0
        try:
            start = datetime.fromisoformat(self.task_start_time)
            return (datetime.now() - start).total_seconds()
        except Exception:
            return 0.0
    
    def _extract_from_task(self, task: str):
        """Extract memories from user task description."""
        # Extract contact mentions
        for pattern in PREFERENCE_PATTERNS["contact"]:
            matches = re.findall(pattern, task, re.IGNORECASE)
            for match in matches:
                if isinstance(match, tuple) and len(match) > 1:
                    contact_name = match[1].strip()
                    if len(contact_name) >= 2:
                        self._add_contact_memory(contact_name, task)
        
        # Extract app mentions
        for pattern in PREFERENCE_PATTERNS["app"]:
            matches = re.findall(pattern, task, re.IGNORECASE)
            for match in matches:
                if isinstance(match, tuple) and len(match) > 1:
                    app_name = match[1].strip()
                    if app_name.lower() in {a.lower() for a in KNOWN_APPS}:
                        self._add_app_preference(app_name, task)
    
    def _add_contact_memory(self, name: str, context: str):
        """Add or update contact memory."""
        self.store.add(
            content=f"联系人: {name}",
            memory_type=MemoryType.CONTACT,
            metadata={
                "name": name,
                "last_context": context,
                "interaction_count": 1,
            },
            importance=0.5,
        )
    
    def _add_app_preference(self, app_name: str, context: str):
        """Add or update app preference."""
        self.store.add(
            content=f"用户常用应用: {app_name}",
            memory_type=MemoryType.APP_USAGE,
            metadata={
                "app_name": app_name,
                "context": context,
            },
            importance=0.4,
        )
    
    def _track_app_usage(self, app_name: str):
        """Track app usage for preference learning."""
        if not app_name or app_name in ("Unknown", "unknown"):
            return
        
        # Normalize app name
        app_lower = app_name.lower().strip()
        
        # Avoid tracking in same session too frequently
        if app_lower in self._session_apps:
            return
        self._session_apps.add(app_lower)
        
        # Check if this is a known app worth tracking
        is_known = any(
            known.lower() in app_lower or app_lower in known.lower()
            for known in KNOWN_APPS
        )
        
        if is_known:
            self.store.add(
                content=f"用户使用了应用: {app_name}",
                memory_type=MemoryType.APP_USAGE,
                metadata={
                    "app_name": app_name,
                    "timestamp": datetime.now().isoformat(),
                },
                importance=0.3,
            )
    
    def _learn_from_action(self, action: dict):
        """Auto-learn from executed actions."""
        action_type = action.get("action", "")
        
        # Learn from Type_Name action (contact names)
        if action_type == "Type_Name":
            name = action.get("text", "")
            if name and len(name) >= 2:
                self._auto_add_contact(name, f"在任务中输入了联系人名: {name}")
        
        # Learn from Launch action (app preferences)
        if action_type == "Launch":
            app = action.get("app", "")
            if app:
                self._auto_add_app(app, f"用户主动启动了应用: {app}")
        
        # Learn from Type action (potential search patterns)
        if action_type == "Type":
            text = action.get("text", "")
            if text and len(text) > 5:
                self._learn_search_pattern(text)
    
    # 不应被识别为联系人的常见词汇
    _CONTACT_BLACKLIST = {
        "联系人", "聊天窗口", "窗口", "消息", "发送消息", "发送", "对话",
        "一个联系人", "某个联系人", "该联系人", "这个联系人",
        "输入框", "搜索框", "搜索栏", "按钮", "图标", "界面", "页面",
        "应用", "程序", "列表", "设置", "信息", "内容", "文本",
        "位置", "区域", "屏幕", "底部", "顶部", "左侧", "右侧",
        "相应", "相关", "当前", "目标", "指定", "对应",
        "来打开", "来打开聊天窗口", "的聊天窗口", "信息是可见的",
        "打开聊天", "发消息", "说", "打电话", "视频通话",
        "这个", "那个", "一个", "某个", "其他", "所有",
        "成功", "失败", "完成", "结束", "开始", "继续",
    }
    
    def _learn_from_thinking(self, thinking: str):
        """Auto-learn from agent's thinking process."""
        contact_patterns = [
            r'[「『""]([\u4e00-\u9fa5a-zA-Z]{2,8})[」』""](?:的聊天|的对话|的消息)',
            r'给[「『""]([\u4e00-\u9fa5a-zA-Z]{2,8})[」『""](?:发|说|打)',
            r'找到了?[「『""]([\u4e00-\u9fa5a-zA-Z]{2,8})[」『""](?:的聊天|这个联系)',
        ]
        
        for pattern in contact_patterns:
            matches = re.findall(pattern, thinking)
            for name in matches:
                name = name.strip()
                if (
                    name
                    and len(name) >= 2
                    and name not in self._session_contacts
                    and name not in self._CONTACT_BLACKLIST
                    and not any(bw in name for bw in ("窗口", "输入", "按钮", "页面", "列表", "可见"))
                ):
                    self._auto_add_contact(name, f"从任务执行中识别: {thinking[:50]}")
        
        # Extract preference hints from thinking
        preference_hints = [
            (r"用户(喜欢|偏好|习惯|经常|常用)([\u4e00-\u9fa5a-zA-Z0-9]+)", "habit"),
            (r"(深色|浅色|暗色|亮色)模式", "ui"),
            (r"(每天|每周|通常|一般)([\u4e00-\u9fa5]+)", "time"),
        ]
        
        for pattern, category in preference_hints:
            match = re.search(pattern, thinking)
            if match:
                preference_text = match.group(0)
                self.store.add(
                    content=f"从执行过程推断: {preference_text}",
                    memory_type=MemoryType.USER_PREFERENCE,
                    metadata={
                        "category": category,
                        "source": "auto_thinking",
                        "context": thinking[:100],
                    },
                    importance=0.4,
                )
    
    def _auto_add_contact(self, name: str, context: str):
        """Auto-add contact with deduplication."""
        if name in self._session_contacts:
            return
        self._session_contacts.add(name)
        
        self.store.add(
            content=f"联系人: {name}",
            memory_type=MemoryType.CONTACT,
            metadata={
                "name": name,
                "source": "auto_extract",
                "context": context,
            },
            importance=0.5,
        )
    
    def _auto_add_app(self, app_name: str, context: str):
        """Auto-add frequently used app."""
        app_lower = app_name.lower()
        if app_lower in self._session_apps:
            return
        self._session_apps.add(app_lower)
        
        self.store.add(
            content=f"用户常用应用: {app_name}",
            memory_type=MemoryType.APP_USAGE,
            metadata={
                "app_name": app_name,
                "source": "auto_extract",
                "context": context,
            },
            importance=0.5,
        )
    
    def _learn_search_pattern(self, search_text: str):
        """Learn from user search patterns."""
        # Common search pattern categories
        food_keywords = ["外卖", "餐厅", "美食", "咖啡", "奶茶", "火锅", "烧烤"]
        shopping_keywords = ["购买", "下单", "商品", "店铺", "价格"]
        travel_keywords = ["酒店", "机票", "火车", "打车", "导航", "路线"]
        
        for keyword in food_keywords:
            if keyword in search_text:
                self.store.add(
                    content=f"用户搜索过: {search_text}",
                    memory_type=MemoryType.TASK_PATTERN,
                    metadata={
                        "category": "food",
                        "search_text": search_text,
                    },
                    importance=0.3,
                )
                return
        
        for keyword in shopping_keywords:
            if keyword in search_text:
                self.store.add(
                    content=f"用户搜索过: {search_text}",
                    memory_type=MemoryType.TASK_PATTERN,
                    metadata={
                        "category": "shopping",
                        "search_text": search_text,
                    },
                    importance=0.3,
                )
                return
        
        for keyword in travel_keywords:
            if keyword in search_text:
                self.store.add(
                    content=f"用户搜索过: {search_text}",
                    memory_type=MemoryType.TASK_PATTERN,
                    metadata={
                        "category": "travel",
                        "search_text": search_text,
                    },
                    importance=0.3,
                )
    
    def add_user_preference(
        self,
        preference: str,
        category: str = "general",
        importance: float = 0.6,
    ):
        """
        Manually add a user preference.
        
        Args:
            preference: The preference description
            category: Category of preference
            importance: Importance score (0-1)
        """
        self.store.add(
            content=f"用户偏好 ({category}): {preference}",
            memory_type=MemoryType.USER_PREFERENCE,
            metadata={
                "category": category,
                "raw_preference": preference,
            },
            importance=importance,
        )
    
    def add_user_correction(self, original_action: str, correction: str):
        """
        Record a user correction for learning.
        
        Args:
            original_action: What the agent did wrong
            correction: What the user wanted instead
        """
        self.store.add(
            content=f"用户纠正: 原操作 '{original_action}' 应改为 '{correction}'",
            memory_type=MemoryType.USER_CORRECTION,
            metadata={
                "original": original_action,
                "correction": correction,
                "task": self.current_task,
            },
            importance=0.8,  # Corrections are highly important
        )
    
    def get_relevant_context(self, task: str, max_memories: int = 8) -> str:
        """
        Get relevant memories for a task as context.
        
        Prioritizes contact-app bindings based on usage frequency.
        
        Args:
            task: The current task description
            max_memories: Maximum number of memories to include
        
        Returns:
            Formatted context string for the agent prompt
        """
        import re
        
        # 1. 首先提取任务中的联系人
        contact_patterns = [
            r'给[「『""]?([\u4e00-\u9fa5a-zA-Z]{2,10})[」『""]?(?:发|说|打)',
            r'联系[「『""]?([\u4e00-\u9fa5a-zA-Z]{2,10})[」『""]?',
        ]
        task_contacts = set()
        for pattern in contact_patterns:
            matches = re.findall(pattern, task, re.IGNORECASE)
            task_contacts.update(matches)
        
        # 2. 查找联系人-应用绑定（基于频率）
        contact_app_stats = {}  # {contact: {app: count}}
        
        for memory in self.store.memories.values():
            if memory.memory_type == MemoryType.CONTACT_APP_BINDNG:
                contact = memory.metadata.get("contact", "")
                app = memory.metadata.get("app", "")
                use_count = memory.metadata.get("use_count", 1)
                
                if contact and app:
                    if contact not in contact_app_stats:
                        contact_app_stats[contact] = {}
                    contact_app_stats[contact][app] = use_count
        
        # 3. Search for relevant memories
        memories = self.store.search(
            query=task,
            top_k=max_memories,
            min_importance=0.2,
        )
        
        # Format memories as context
        context_parts = ["【用户个性化信息 - 请严格按照以下信息选择应用】"]
        
        # 4. 🔥 最重要：基于频率的联系人-应用推荐
        frequency_recommendations = []
        for contact in task_contacts:
            # 查找这个联系人的应用使用统计
            if contact in contact_app_stats:
                apps_stats = contact_app_stats[contact]
                # 按使用次数排序
                sorted_apps = sorted(apps_stats.items(), key=lambda x: x[1], reverse=True)
                if sorted_apps:
                    best_app, best_count = sorted_apps[0]
                    total_count = sum(apps_stats.values())
                    
                    # 生成推荐说明
                    if len(sorted_apps) > 1:
                        second_app, second_count = sorted_apps[1]
                        frequency_recommendations.append(
                            f"⚡ 联系「{contact}」：推荐使用 **{best_app}** (使用{best_count}次) "
                            f"而非 {second_app} (使用{second_count}次)"
                        )
                    else:
                        frequency_recommendations.append(
                            f"⚡ 联系「{contact}」：推荐使用 **{best_app}** (已使用{best_count}次)"
                        )
            else:
                # 从任务历史中查找
                for memory in self.store.memories.values():
                    if memory.memory_type == MemoryType.TASK_HISTORY:
                        past_task = memory.metadata.get("task", "")
                        if contact in past_task:
                            apps_used = memory.metadata.get("apps_used", [])
                            for app in apps_used:
                                if app.lower() not in ("system home", "unknown"):
                                    frequency_recommendations.append(
                                        f"⚡ 联系「{contact}」：历史记录显示使用 **{app}**"
                                    )
                                    break
                            break
        
        # 添加频率推荐（最高优先级）
        if frequency_recommendations:
            context_parts.append("\n**🎯 基于使用频率的应用推荐（必须遵循）:**")
            for rec in frequency_recommendations[:5]:
                context_parts.append(f"  {rec}")
        
        # 5. 任务历史中的应用关联（次要参考）
        task_app_hints = []
        for memory in memories:
            if memory.memory_type == MemoryType.TASK_HISTORY:
                past_task = memory.metadata.get("task", "")
                apps_used = memory.metadata.get("apps_used", [])
                success = memory.metadata.get("success", False)
                
                if success and past_task and apps_used:
                    for app in apps_used:
                        if app.lower() not in ("system home", "unknown"):
                            task_app_hints.append(f"历史: 「{past_task[:35]}」→ {app}")
                            break
            
            elif memory.memory_type == MemoryType.TASK_PATTERN:
                apps_flow = memory.metadata.get("apps_flow", [])
                task_summary = memory.metadata.get("task_summary", "")
                if apps_flow and task_summary:
                    main_app = [a for a in apps_flow if a.lower() not in ("system home", "unknown")]
                    if main_app:
                        task_app_hints.append(f"模式: 「{task_summary[:30]}」→ {main_app[0]}")
        
        if task_app_hints:
            context_parts.append("\n**📋 相关任务历史:**")
            for hint in task_app_hints[:3]:
                context_parts.append(f"  {hint}")
        
        # 6. 其他记忆（低优先级）
        other_context = []
        for memory in memories:
            if memory.memory_type == MemoryType.USER_CORRECTION:
                other_context.append(f"⚠️ 注意: {memory.content}")
            elif memory.memory_type == MemoryType.USER_PREFERENCE:
                pref = memory.metadata.get("raw_preference", memory.content)
                other_context.append(f"偏好: {pref}")
        
        if other_context:
            context_parts.append("\n**其他信息:**")
            context_parts.extend(other_context[:3])
        
        if len(context_parts) == 1:
            return ""
        
        return "\n".join(context_parts)
    
    def get_user_summary(self) -> dict:
        """
        Get a summary of user information.
        
        Returns:
            Dictionary with user preferences, contacts, and habits
        """
        summary = {
            "contacts": [],
            "frequent_apps": [],
            "preferences": [],
            "recent_tasks": [],
        }
        
        # Get frequent contacts
        contacts = self.store.get_by_type(MemoryType.CONTACT, limit=10)
        for mem in contacts:
            name = mem.metadata.get("name", "")
            if name:
                summary["contacts"].append(name)
        
        # Get frequent apps
        apps = self.store.get_by_type(MemoryType.APP_USAGE, limit=10)
        app_counts: dict[str, int] = {}
        for mem in apps:
            app = mem.metadata.get("app_name", "")
            if app:
                app_counts[app] = app_counts.get(app, 0) + mem.access_count
        
        # Sort by usage frequency
        sorted_apps = sorted(app_counts.items(), key=lambda x: x[1], reverse=True)
        summary["frequent_apps"] = [app for app, _ in sorted_apps[:5]]
        
        # Get preferences
        prefs = self.store.get_by_type(MemoryType.USER_PREFERENCE, limit=10)
        for mem in prefs:
            pref = mem.metadata.get("raw_preference", "")
            if pref:
                summary["preferences"].append(pref)
        
        # Get recent tasks
        tasks = self.store.get_by_type(MemoryType.TASK_HISTORY, limit=5)
        for mem in tasks:
            task = mem.metadata.get("task", "")
            if task:
                summary["recent_tasks"].append(task)
        
        return summary
    
    def get_stats(self) -> dict:
        """Get memory statistics."""
        store_stats = self.store.get_stats()
        store_stats["user_id"] = self.user_id
        store_stats["session_steps"] = len(self.session_history)
        return store_stats
    
    def clear_all(self):
        """Clear all memories for this user."""
        self.store.clear()
        self.session_history.clear()
    
    def export_memories(self) -> list[dict]:
        """Export all memories for backup."""
        return self.store.export_memories()
    
    def import_memories(self, memories: list[dict]):
        """Import memories from backup."""
        self.store.import_memories(memories)


def build_personalized_prompt(
    base_prompt: str,
    memory_manager: MemoryManager,
    task: str,
) -> str:
    """
    Build a personalized system prompt with memory context.
    
    Args:
        base_prompt: The original system prompt
        memory_manager: Memory manager instance
        task: Current task description
    
    Returns:
        Enhanced prompt with personalization context
    """
    # Get relevant context
    context = memory_manager.get_relevant_context(task)
    
    if not context:
        return base_prompt
    
    # Insert personalization context before the rules
    # Find a good insertion point
    if "必须遵循的规则" in base_prompt:
        parts = base_prompt.split("必须遵循的规则")
        enhanced = parts[0] + f"\n\n{context}\n\n必须遵循的规则" + parts[1]
    else:
        enhanced = f"{base_prompt}\n\n{context}"
    
    return enhanced

