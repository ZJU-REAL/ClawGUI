#!/usr/bin/env python3
"""
ClawGUI-Agent Web UI - 基于 Gradio 的可视化控制界面

Features:
    - 📱 设备管理：查看、连接、断开设备
    - 🔍 系统检查：ADB/HDC/iOS 工具、设备、键盘、API 状态
    - 💬 对话控制：自然语言任务输入、流式输出、实时截图
    - ⚙️ 配置管理：API 地址、Key、最大步数设置
"""

import base64
import io
import json
import os
import shutil
import subprocess
import threading
import time
import traceback
from dataclasses import dataclass
from io import BytesIO
from typing import Generator, Any

import gradio as gr
from PIL import Image
from openai import OpenAI

# 导入项目模块
from phone_agent import PhoneAgent
from phone_agent.agent import AgentConfig
from phone_agent.agent_ios import IOSAgentConfig, IOSPhoneAgent
from phone_agent.adb.connection import ADBConnection, ConnectionType, DeviceInfo
from phone_agent.device_factory import DeviceType, DeviceFactory, get_device_factory, set_device_type
from phone_agent.model import ModelConfig
from phone_agent.model.client import ModelClient, MessageBuilder
from phone_agent.model.adapters import ModelType, get_adapter, detect_model_type, get_adapter_for_model
from phone_agent.actions.handler_uitars import UITarsActionHandler, UITarsAction
from phone_agent.actions.handler_qwenvl import QwenVLActionHandler, QwenVLAction
from phone_agent.actions.handler_guiowl import GUIOwlActionHandler, GUIOwlAction

# 导入记忆模块
try:
    from phone_agent.memory import MemoryManager, MemoryType
    HAS_MEMORY = True
except ImportError:
    HAS_MEMORY = False
    MemoryManager = None
    MemoryType = None


# ==================== 全局状态 ====================
@dataclass
class AppState:
    """应用程序全局状态"""
    agent: PhoneAgent | IOSPhoneAgent | None = None
    device_type: DeviceType = DeviceType.ADB
    is_running: bool = False
    should_stop: bool = False
    current_task: str = ""
    # Take_over 人工介入状态
    waiting_for_takeover: bool = False
    takeover_message: str = ""
    takeover_continue_event: threading.Event | None = None
    # 记忆管理器
    memory_manager: "MemoryManager | None" = None
    

app_state = AppState()

# 初始化全局记忆管理器
def get_memory_manager(user_id: str = "default") -> "MemoryManager | None":
    """获取或创建记忆管理器"""
    global app_state
    if not HAS_MEMORY:
        return None
    
    if app_state.memory_manager is None or app_state.memory_manager.user_id != user_id:
        try:
            app_state.memory_manager = MemoryManager(
                storage_dir="memory_db",
                user_id=user_id,
                enable_auto_extract=True,
            )
        except Exception as e:
            print(f"记忆管理器初始化失败: {e}")
            return None
    
    return app_state.memory_manager


# ==================== 设备管理功能 ====================
def get_device_list(device_type: str) -> str:
    """获取已连接设备列表"""
    try:
        if device_type == "ios":
            from phone_agent.xctest import list_devices as list_ios_devices
            devices = list_ios_devices()
            if not devices:
                return "📵 未检测到 iOS 设备\n\n请确保:\n1. 设备已通过 USB 连接\n2. 已解锁并信任此电脑\n3. WebDriverAgent 正在运行"
            
            result = "📱 **已连接的 iOS 设备:**\n\n"
            for device in devices:
                conn_type = device.connection_type.value
                model_info = f"{device.model}" if device.model else "Unknown"
                ios_info = f"iOS {device.ios_version}" if device.ios_version else ""
                name_info = device.device_name or "Unnamed"
                
                result += f"✅ **{name_info}**\n"
                result += f"   - UUID: `{device.device_id}`\n"
                result += f"   - 型号: {model_info}\n"
                result += f"   - 系统: {ios_info}\n"
                result += f"   - 连接: {conn_type}\n\n"
            return result
        else:
            # ADB 或 HDC
            set_device_type(DeviceType.ADB if device_type == "adb" else DeviceType.HDC)
            device_factory = get_device_factory()
            devices = device_factory.list_devices()
            
            if not devices:
                tool_name = "ADB" if device_type == "adb" else "HDC"
                return f"📵 未检测到 {tool_name} 设备\n\n请确保:\n1. 设备已通过 USB 连接\n2. 已启用开发者调试模式\n3. 已授权调试连接"
            
            result = f"📱 **已连接的{'Android' if device_type == 'adb' else 'HarmonyOS'}设备:**\n\n"
            for device in devices:
                status_icon = "✅" if device.status == "device" else "⚠️"
                conn_type = device.connection_type.value
                model_info = f" ({device.model})" if device.model else ""
                
                result += f"{status_icon} **{device.device_id}**{model_info}\n"
                result += f"   - 状态: {device.status}\n"
                result += f"   - 连接: {conn_type}\n\n"
            return result
            
    except Exception as e:
        return f"❌ 获取设备列表失败: {str(e)}"


def connect_device(address: str, device_type: str) -> str:
    """连接远程设备"""
    if not address.strip():
        return "⚠️ 请输入设备地址 (例如: 192.168.1.100:5555)"
    
    try:
        if device_type == "ios":
            return "ℹ️ iOS 设备请使用 WebDriverAgent URL 进行连接，在配置中设置 WDA URL"
        
        set_device_type(DeviceType.ADB if device_type == "adb" else DeviceType.HDC)
        device_factory = get_device_factory()
        ConnectionClass = device_factory.get_connection_class()
        conn = ConnectionClass()
        
        success, message = conn.connect(address)
        
        if success:
            return f"✅ 连接成功: {message}"
        else:
            return f"❌ 连接失败: {message}"
            
    except Exception as e:
        return f"❌ 连接错误: {str(e)}"


def disconnect_device(address: str, device_type: str) -> str:
    """断开设备连接"""
    try:
        if device_type == "ios":
            return "ℹ️ iOS 设备断开连接请在 Xcode 中停止 WebDriverAgent"
        
        set_device_type(DeviceType.ADB if device_type == "adb" else DeviceType.HDC)
        device_factory = get_device_factory()
        ConnectionClass = device_factory.get_connection_class()
        conn = ConnectionClass()
        
        if address.strip():
            success, message = conn.disconnect(address)
        else:
            success, message = conn.disconnect()  # 断开所有
            
        if success:
            return f"✅ {message}"
        else:
            return f"❌ 断开失败: {message}"
            
    except Exception as e:
        return f"❌ 断开错误: {str(e)}"


def enable_wifi_debug(port: int, device_type: str) -> str:
    """启用 WiFi 调试"""
    try:
        if device_type == "ios":
            return "ℹ️ iOS 设备请通过网络直接连接 WebDriverAgent"
        
        set_device_type(DeviceType.ADB if device_type == "adb" else DeviceType.HDC)
        device_factory = get_device_factory()
        ConnectionClass = device_factory.get_connection_class()
        conn = ConnectionClass()
        
        success, message = conn.enable_tcpip(port)
        
        if success:
            ip = conn.get_device_ip()
            if ip:
                return f"✅ WiFi 调试已启用\n\n📡 连接信息:\n- IP: {ip}\n- 端口: {port}\n\n可使用以下命令连接:\n```\npython main.py --connect {ip}:{port}\n```"
            else:
                return f"✅ {message}\n\n⚠️ 无法获取设备 IP，请在设备 WiFi 设置中查看"
        else:
            return f"❌ 启用失败: {message}"
            
    except Exception as e:
        return f"❌ 错误: {str(e)}"


# ==================== 系统检查功能 ====================
def check_tool_installation(device_type: str) -> str:
    """检查工具安装状态"""
    results = []
    
    if device_type == "ios":
        tool_name = "libimobiledevice"
        tool_cmd = "idevice_id"
        install_hint = "macOS: brew install libimobiledevice\nLinux: sudo apt-get install libimobiledevice-utils"
    elif device_type == "hdc":
        tool_name = "HDC"
        tool_cmd = "hdc"
        install_hint = "请从 HarmonyOS SDK 或 OpenHarmony 官网下载安装"
    else:
        tool_name = "ADB"
        tool_cmd = "adb"
        install_hint = "macOS: brew install android-platform-tools\nLinux: sudo apt install android-tools-adb\nWindows: 下载 Android Platform Tools"
    
    # 检查工具是否安装
    if shutil.which(tool_cmd) is None:
        results.append(f"❌ **{tool_name}**: 未安装或未在 PATH 中\n\n安装方法:\n```\n{install_hint}\n```")
    else:
        try:
            if device_type == "adb":
                version_cmd = [tool_cmd, "version"]
            elif device_type == "hdc":
                version_cmd = [tool_cmd, "-v"]
            else:
                version_cmd = [tool_cmd, "-ln"]
            
            result = subprocess.run(version_cmd, capture_output=True, text=True, timeout=10)
            if result.returncode == 0:
                version_line = result.stdout.strip().split("\n")[0]
                results.append(f"✅ **{tool_name}**: 已安装\n   版本: {version_line if version_line else '已安装'}")
            else:
                results.append(f"⚠️ **{tool_name}**: 安装但无法运行")
        except Exception as e:
            results.append(f"⚠️ **{tool_name}**: 检查出错 - {str(e)}")
    
    return "\n\n".join(results)


def check_device_connection(device_type: str) -> str:
    """检查设备连接状态"""
    try:
        if device_type == "ios":
            from phone_agent.xctest import list_devices as list_ios_devices
            devices = list_ios_devices()
            if devices:
                return f"✅ **设备连接**: 已连接 {len(devices)} 台 iOS 设备"
            else:
                return "❌ **设备连接**: 未检测到 iOS 设备"
        else:
            set_device_type(DeviceType.ADB if device_type == "adb" else DeviceType.HDC)
            device_factory = get_device_factory()
            devices = device_factory.list_devices()
            
            if devices:
                connected = [d for d in devices if d.status == "device"]
                return f"✅ **设备连接**: 已连接 {len(connected)}/{len(devices)} 台设备"
            else:
                return "❌ **设备连接**: 未检测到设备"
                
    except Exception as e:
        return f"❌ **设备连接**: 检查失败 - {str(e)}"


def check_keyboard_installation(device_type: str) -> str:
    """检查 ADB Keyboard 安装状态"""
    if device_type != "adb":
        if device_type == "ios":
            return "ℹ️ **输入法**: iOS 使用 WebDriverAgent 原生输入"
        else:
            return "ℹ️ **输入法**: HarmonyOS 使用原生输入方式"
    
    try:
        result = subprocess.run(
            ["adb", "shell", "ime", "list", "-s"],
            capture_output=True, text=True, timeout=10
        )
        ime_list = result.stdout.strip()
        
        if "com.android.adbkeyboard/.AdbIME" in ime_list:
            return "✅ **ADB Keyboard**: 已安装"
        else:
            return "❌ **ADB Keyboard**: 未安装\n\n安装步骤:\n1. 下载: https://github.com/senzhk/ADBKeyBoard\n2. 安装: `adb install ADBKeyboard.apk`\n3. 在设置中启用"
            
    except Exception as e:
        return f"⚠️ **ADB Keyboard**: 检查失败 - {str(e)}"


def check_wda_status(wda_url: str) -> str:
    """检查 WebDriverAgent 状态"""
    try:
        from phone_agent.xctest import XCTestConnection
        conn = XCTestConnection(wda_url=wda_url)
        
        if conn.is_wda_ready():
            status = conn.get_wda_status()
            if status:
                session_id = status.get("sessionId", "N/A")
                return f"✅ **WebDriverAgent**: 运行中\n   Session: {session_id[:16]}..."
            return "✅ **WebDriverAgent**: 运行中"
        else:
            return f"❌ **WebDriverAgent**: 未运行或无法访问\n   URL: {wda_url}\n\n请确保:\n1. 在 Xcode 中运行 WebDriverAgentRunner\n2. USB 设备需设置端口转发: `iproxy 8100 8100`"
            
    except Exception as e:
        return f"❌ **WebDriverAgent**: 检查失败 - {str(e)}"


def check_model_api(base_url: str, api_key: str, model_name: str) -> str:
    """检查模型 API 连接"""
    try:
        client = OpenAI(base_url=base_url, api_key=api_key or "EMPTY", timeout=30.0)
        
        response = client.chat.completions.create(
            model=model_name,
            messages=[{"role": "user", "content": "Hi"}],
            max_tokens=5,
            temperature=0.0,
            stream=False,
        )
        
        if response.choices and len(response.choices) > 0:
            return f"✅ **模型 API**: 连接正常\n   Base URL: {base_url}\n   Model: {model_name}"
        else:
            return f"⚠️ **模型 API**: 连接成功但响应异常"
            
    except Exception as e:
        error_msg = str(e)
        if "Connection refused" in error_msg or "Connection error" in error_msg:
            return f"❌ **模型 API**: 无法连接\n   URL: {base_url}\n\n请检查模型服务是否已启动"
        elif "timeout" in error_msg.lower():
            return f"❌ **模型 API**: 连接超时\n   URL: {base_url}"
        else:
            return f"❌ **模型 API**: {error_msg}"


def run_full_check(device_type: str, base_url: str, api_key: str, model_name: str, wda_url: str) -> str:
    """运行完整系统检查"""
    results = ["# 🔍 系统检查报告\n"]
    
    # 1. 工具安装检查
    results.append("## 1. 工具安装\n")
    results.append(check_tool_installation(device_type))
    
    # 2. 设备连接检查
    results.append("\n\n## 2. 设备连接\n")
    results.append(check_device_connection(device_type))
    
    # 3. 输入法检查
    results.append("\n\n## 3. 输入方式\n")
    results.append(check_keyboard_installation(device_type))
    
    # 4. iOS WDA 检查
    if device_type == "ios":
        results.append("\n\n## 4. WebDriverAgent\n")
        results.append(check_wda_status(wda_url))
    
    # 5. 模型 API 检查
    results.append("\n\n## 5. 模型 API\n")
    results.append(check_model_api(base_url, api_key, model_name))
    
    return "\n".join(results)


# ==================== 截图功能 ====================
def get_device_screenshot(device_type: str, device_id: str | None, wda_url: str) -> Image.Image | None:
    """获取设备截图"""
    try:
        if device_type == "ios":
            from phone_agent.xctest import get_screenshot
            screenshot = get_screenshot(wda_url=wda_url)
        else:
            set_device_type(DeviceType.ADB if device_type == "adb" else DeviceType.HDC)
            device_factory = get_device_factory()
            screenshot = device_factory.get_screenshot(device_id if device_id else None)
        
        if screenshot and screenshot.base64_data:
            img_data = base64.b64decode(screenshot.base64_data)
            img = Image.open(BytesIO(img_data))
            return img
    except Exception as e:
        print(f"截图错误: {e}")
    return None


def refresh_screenshot(device_type: str, device_id: str, wda_url: str) -> Image.Image | None:
    """刷新截图"""
    device_id_clean = device_id.strip() if device_id else None
    return get_device_screenshot(device_type, device_id_clean, wda_url)


# ==================== 对话控制功能 ====================
class StreamingAgent:
    """支持流式输出的 Agent 包装器"""
    
    def __init__(
        self,
        model_config: ModelConfig,
        agent_config: AgentConfig | IOSAgentConfig,
        device_type: DeviceType,
        model_type: str = "auto",  # 新增：模型类型 (auto/autoglm/uitars)
        user_id: str = "default",  # 用户 ID 用于记忆
    ):
        self.model_config = model_config
        self.agent_config = agent_config
        self.device_type = device_type
        self._context: list[dict[str, Any]] = []
        self._step_count = 0
        self._should_stop = False
        
        # 初始化模型客户端
        self.client = OpenAI(base_url=model_config.base_url, api_key=model_config.api_key)
        
        # 初始化记忆管理器
        self.memory_manager = None
        if HAS_MEMORY:
            try:
                self.memory_manager = get_memory_manager(user_id)
            except Exception as e:
                print(f"记忆系统初始化失败: {e}")
        
        # 确定模型类型并获取适配器
        if model_type == "auto":
            self._model_type = detect_model_type(model_config.model_name)
        elif model_type == "uitars":
            self._model_type = ModelType.UITARS
        elif model_type == "qwenvl":
            self._model_type = ModelType.QWENVL
        elif model_type == "maiui":
            self._model_type = ModelType.MAIUI
        elif model_type == "guiowl":
            self._model_type = ModelType.GUIOWL
        else:
            self._model_type = ModelType.AUTOGLM
        
        self._adapter = get_adapter(self._model_type)
        
        self._is_uitars = self._model_type == ModelType.UITARS
        self._is_qwenvl = self._model_type == ModelType.QWENVL
        self._is_maiui = self._model_type == ModelType.MAIUI
        self._is_guiowl = self._model_type == ModelType.GUIOWL
        
        # 保存原始任务用于 UI-TARS
        self._original_task = ""
        self._task_success = False
    
    def stop(self):
        """停止执行"""
        self._should_stop = True
    
    def reset(self):
        """重置状态"""
        self._context = []
        self._step_count = 0
        self._should_stop = False
        self._task_success = False
    
    def _prepare_message_for_print(self, message: dict) -> dict:
        """准备消息用于打印，移除base64图片数据以便显示"""
        import copy
        msg_copy = copy.deepcopy(message)
        
        if "content" in msg_copy:
            if isinstance(msg_copy["content"], list):
                for item in msg_copy["content"]:
                    if isinstance(item, dict) and item.get("type") == "image_url":
                        if "image_url" in item and "url" in item["image_url"]:
                            url = item["image_url"]["url"]
                            if url.startswith("data:image"):
                                # 截断base64数据，只显示前缀
                                item["image_url"]["url"] = url[:50] + "...[truncated]"
        
        return msg_copy
    
    def run_streaming(self, task: str) -> Generator[tuple[str, str, Image.Image | None], None, None]:
        """
        流式执行任务
        
        Yields:
            (thinking_log, action_log, screenshot) 元组
        """
        self._context = []
        self._step_count = 0
        self._should_stop = False
        self._task_success = False
        self._original_task = task  # 保存原始任务
        
        # 清除 adapter 操作历史（QwenVL / GUI-Owl 使用）
        if hasattr(self._adapter, 'clear_history'):
            self._adapter.clear_history()
        
        # 🧠 记忆系统：任务开始
        if self.memory_manager:
            self.memory_manager.start_task(task)
        
        thinking_log = ""
        action_log = ""
        
        # 显示使用的模型类型
        if self._is_uitars:
            model_type_name = "UI-TARS"
        elif self._is_qwenvl:
            model_type_name = "Qwen-VL"
        elif self._is_maiui:
            model_type_name = "MAI-UI"
        elif self._is_guiowl:
            model_type_name = "GUI-Owl"
        else:
            model_type_name = "AutoGLM"
        action_log += f"🤖 使用模型适配器: **{model_type_name}**\n"
        
        # 显示记忆系统状态
        if self.memory_manager:
            action_log += f"🧠 记忆系统: **已启用** (用户: {self.memory_manager.user_id})\n"
            # 显示检索到的相关记忆
            try:
                context = self.memory_manager.get_relevant_context(task)
                if context:
                    action_log += f"\n📋 **检索到的用户记忆:**\n```\n{context}\n```\n"
                else:
                    action_log += f"📋 暂无相关记忆\n"
            except Exception as e:
                action_log += f"⚠️ 记忆检索失败: {e}\n"
        
        # 定义 takeover 回调函数（用于人工介入场景）
        def takeover_callback(message: str) -> None:
            """WebUI 的 takeover 回调：设置状态并等待用户继续"""
            global app_state
            app_state.waiting_for_takeover = True
            app_state.takeover_message = message
            app_state.takeover_continue_event = threading.Event()
            # 等待用户点击"继续执行"按钮
            app_state.takeover_continue_event.wait()
            # 重置状态
            app_state.waiting_for_takeover = False
            app_state.takeover_message = ""
            app_state.takeover_continue_event = None
        
        # 初始化 action handler
        if self._is_uitars:
            # UI-TARS 使用专用的 action handler
            action_handler = UITarsActionHandler(
                device_id=self.agent_config.device_id,
                takeover_callback=takeover_callback,
            )
        elif self._is_qwenvl:
            # Qwen-VL 使用专用的 action handler
            action_handler = QwenVLActionHandler(
                device_id=self.agent_config.device_id,
                takeover_callback=takeover_callback,
            )
        elif self._is_maiui:
            # MAI-UI 使用专用的 action handler
            from phone_agent.actions.handler_maiui import MAIUIActionHandler
            action_handler = MAIUIActionHandler(
                device_id=self.agent_config.device_id,
                takeover_callback=takeover_callback,
            )
        elif self._is_guiowl:
            # GUI-Owl 使用专用的 action handler
            action_handler = GUIOwlActionHandler(
                device_id=self.agent_config.device_id,
                takeover_callback=takeover_callback,
            )
        elif self.device_type == DeviceType.IOS:
            from phone_agent.actions.handler_ios import IOSActionHandler
            action_handler = IOSActionHandler(
                wda_url=self.agent_config.wda_url,
                device_id=self.agent_config.device_id,
                takeover_callback=takeover_callback,
            )
        else:
            from phone_agent.actions import ActionHandler
            action_handler = ActionHandler(
                device_id=self.agent_config.device_id,
                takeover_callback=takeover_callback,
            )
        
        # 获取设备工厂和截图函数
        if self.device_type == DeviceType.IOS:
            from phone_agent.xctest import get_screenshot as ios_get_screenshot
            get_screenshot_func = lambda: ios_get_screenshot(wda_url=self.agent_config.wda_url)
            get_current_app_func = lambda: action_handler.connection.get_current_app() or "Unknown"
        else:
            set_device_type(self.device_type)
            device_factory = get_device_factory()
            get_screenshot_func = lambda: device_factory.get_screenshot(self.agent_config.device_id)
            get_current_app_func = lambda: device_factory.get_current_app(self.agent_config.device_id)
        
        # 执行第一步
        result = yield from self._execute_step_streaming(
            task, True, thinking_log, action_log,
            get_screenshot_func, get_current_app_func, action_handler
        )
        
        if result["finished"]:
            return
        
        thinking_log = result["thinking_log"]
        action_log = result["action_log"]
        
        # 继续执行直到完成或达到最大步数
        while self._step_count < self.agent_config.max_steps and not self._should_stop:
            result = yield from self._execute_step_streaming(
                None, False, thinking_log, action_log,
                get_screenshot_func, get_current_app_func, action_handler
            )
            
            if result["finished"]:
                return
                
            thinking_log = result["thinking_log"]
            action_log = result["action_log"]
        
        if self._should_stop:
            action_log += "\n\n⚠️ 任务已被用户终止"
            # 🧠 记忆系统：任务被终止
            if self.memory_manager:
                self.memory_manager.end_task(success=False, result="用户终止")
            yield thinking_log, action_log, None
    
    def _execute_step_streaming(
        self,
        user_prompt: str | None,
        is_first: bool,
        thinking_log: str,
        action_log: str,
        get_screenshot_func,
        get_current_app_func,
        action_handler,
    ) -> Generator[tuple[str, str, Image.Image | None], None, dict]:
        """执行单个步骤并流式输出"""
        from phone_agent.actions.handler import parse_action, finish
        from phone_agent.config import get_system_prompt
        
        # 导入记忆相关模块
        if HAS_MEMORY:
            from phone_agent.memory.memory_manager import build_personalized_prompt
        
        self._step_count += 1
        
        # 添加步骤标题
        step_header = f"\n\n{'='*50}\n## 步骤 {self._step_count}\n{'='*50}\n"
        thinking_log += step_header
        action_log += step_header
        thinking_log += "\n### 💭 思考过程\n"
        
        # 获取截图
        try:
            screenshot = get_screenshot_func()
            current_app = get_current_app_func()
        except Exception as e:
            action_log += f"\n❌ 获取截图失败: {str(e)}"
            yield thinking_log, action_log, None
            return {"finished": True, "thinking_log": thinking_log, "action_log": action_log}
        
        # 转换截图为 PIL Image
        screenshot_img = None
        if screenshot and screenshot.base64_data:
            try:
                img_data = base64.b64decode(screenshot.base64_data)
                screenshot_img = Image.open(BytesIO(img_data))
            except:
                pass
        
        yield thinking_log, action_log, screenshot_img
        
        # 根据模型类型构建消息
        if self._is_uitars or self._is_qwenvl or self._is_maiui or self._is_guiowl:
            # 记录构建前是否为空（首轮）
            is_first_build = len(self._context) == 0
            
            # UI-TARS、Qwen-VL、MAI-UI、GUI-Owl 使用专用的消息格式
            self._context = self._adapter.build_messages(
                task=self._original_task,
                image_base64=screenshot.base64_data,
                current_app=current_app,
                context=self._context,
                lang=self.agent_config.lang,
                screen_width=screenshot.width,
                screen_height=screenshot.height,
            )
            
            # 🧠 首轮注入个性化记忆上下文（所有非 AutoGLM 模型都需要）
            if is_first_build and self.memory_manager and HAS_MEMORY:
                memory_context = self.memory_manager.get_relevant_context(self._original_task)
                if memory_context:
                    self._inject_memory_into_context(memory_context)
                    action_log += f"\n📋 **检索到的用户记忆:**\n```\n{memory_context}\n```\n"
            
            # 限制上下文中的图片数量
            if self._is_qwenvl or self._is_guiowl:
                # QwenVL / GUI-Owl: 只保留 1 张图片（当前）
                pass
            elif self._is_maiui:
                # MAI-UI: 保留最近 3 张图片
                if hasattr(self._adapter, 'limit_context'):
                    self._context = self._adapter.limit_context(self._context, max_images=3)
            elif hasattr(self._adapter, 'limit_context'):
                # UI-TARS: 保留最近 5 张图片
                self._context = self._adapter.limit_context(self._context, max_images=5)
            
            # # 打印当前构建的 messages
            # print("\n" + "="*80)
            # print("📨 当前 Messages:")
            # print("="*80)
            # import json
            # for msg in self._context:
            #     msg_to_print = self._prepare_message_for_print(msg)
            #     print(json.dumps(msg_to_print, ensure_ascii=False, indent=2))
            # print("="*80 + "\n")
        else:
            # AutoGLM 使用相同的消息格式
            if is_first:
                # 获取基础 system prompt
                base_prompt = get_system_prompt(self.agent_config.lang)
                
                # 🧠 注入个性化记忆上下文
                if self.memory_manager and HAS_MEMORY:
                    system_prompt = build_personalized_prompt(
                        base_prompt, self.memory_manager, user_prompt
                    )
                    # 显示个性化信息
                    context = self.memory_manager.get_relevant_context(user_prompt)
                    if context:
                        action_log_extra = f"\n\n📋 **检索到的用户记忆:**\n```\n{context}\n```\n"
                else:
                    system_prompt = base_prompt
                    action_log_extra = ""
                
                self._context.append(
                    MessageBuilder.create_system_message(system_prompt)
                )
                screen_info = MessageBuilder.build_screen_info(current_app)
                text_content = f"{user_prompt}\n\n{screen_info}"
                self._context.append(
                    MessageBuilder.create_user_message(
                        text=text_content, image_base64=screenshot.base64_data
                    )
                )
            else:
                screen_info = MessageBuilder.build_screen_info(current_app)
                text_content = f"** Screen Info **\n\n{screen_info}"
                self._context.append(
                    MessageBuilder.create_user_message(
                        text=text_content, image_base64=screenshot.base64_data
                    )
                )
            
            # # 打印当前构建的 messages
            # print("\n" + "="*80)
            # print("📨 当前 Messages:")
            # print("="*80)
            # import json
            # for msg in self._context:
            #     msg_to_print = self._prepare_message_for_print(msg)
            #     print(json.dumps(msg_to_print, ensure_ascii=False, indent=2))
            # print("="*80 + "\n")
        
        # 流式请求模型
        yield thinking_log, action_log, screenshot_img
        
        # UI-TARS 使用不同的推理参数
        if self._is_uitars:
            temperature = 0.0  # UI-TARS 建议使用 0
            top_p = 0.7
            frequency_penalty = 0.0
        else:
            temperature = self.model_config.temperature
            top_p = 0.85
            frequency_penalty = 0.2
        
        try:
            stream = self.client.chat.completions.create(
                messages=self._context,
                model=self.model_config.model_name,
                max_tokens=self.model_config.max_tokens,
                temperature=temperature,
                top_p=top_p,
                frequency_penalty=frequency_penalty,
                stream=True,
            )
            
            raw_content = ""
            in_action_phase = False
            # 根据模型类型使用不同的 action 标记
            if self._is_uitars:
                action_markers = ["Action:", "click(", "long_press(", "type(", "scroll(", 
                                  "open_app(", "drag(", "press_home(", "press_back(", 
                                  "finished(", "wait("]
            elif self._is_qwenvl:
                action_markers = ["<tool_call>", '"action":', "Action:", "tap(", "long_press(", "double_tap(", "swipe(",
                                  "type(", "type_name(", "open_app(", "back(", "home(",
                                  "wait(", "finish(", "terminate("]
            elif self._is_maiui:
                # MAI-UI 使用 <tool_call> 格式的 action
                action_markers = ["<tool_call>", '"action":', "terminate", "answer"]
            elif self._is_guiowl:
                # GUI-Owl 1.5 使用 <tool_call> 格式（官方格式）
                action_markers = ["<tool_call>", '"action":', "Action:", "terminate", "answer"]
            else:
                action_markers = ["finish(message=", "do(action="]
            
            pending_content = ""  # 待输出的内容缓冲
            last_yield_time = time.time()
            yield_interval = 0.3  # 300ms 更新一次界面
            
            for chunk in stream:
                if self._should_stop:
                    break
                    
                if len(chunk.choices) == 0:
                    continue
                if chunk.choices[0].delta.content is not None:
                    content = chunk.choices[0].delta.content
                    raw_content += content
                    
                    if not in_action_phase:
                        # 检查是否进入动作阶段
                        for marker in action_markers:
                            if marker in raw_content:
                                in_action_phase = True
                                break
                        
                        if in_action_phase:
                            # 刚进入 action 阶段，先把缓冲中的 thinking 内容 flush 出去
                            if pending_content:
                                thinking_log += pending_content
                                pending_content = ""
                                yield thinking_log, action_log, None
                        else:
                            pending_content += content
                            # 批量更新：每隔一段时间或内容较多时才更新
                            current_time = time.time()
                            if current_time - last_yield_time >= yield_interval or len(pending_content) > 100:
                                thinking_log += pending_content
                                pending_content = ""
                                last_yield_time = current_time
                                yield thinking_log, action_log, None
            
            # 输出剩余的内容
            if pending_content:
                thinking_log += pending_content
            
        except Exception as e:
            action_log += f"\n❌ 模型请求错误: {str(e)}"
            yield thinking_log, action_log, screenshot_img
            return {"finished": True, "thinking_log": thinking_log, "action_log": action_log}
        
        # 根据模型类型解析响应
        if self._is_uitars:
            # UI-TARS 响应解析
            thinking, action_str = self._adapter.parse_response(raw_content)
            
            # 使用 UI-TARS action handler 解析
            uitars_action = action_handler.parse_response(raw_content)
            
            # 添加屏幕分辨率信息到日志（帮助调试坐标问题）
            action_log += f"\n### 🎯 执行动作\n📐 屏幕分辨率: {screenshot.width}x{screenshot.height}px\n```\nAction: {action_str}\n```\n"
            yield thinking_log, action_log, screenshot_img
            
            # 执行动作
            try:
                result = action_handler.execute(uitars_action, screenshot.width, screenshot.height)
                
                if result.success:
                    # 显示坐标转换信息（帮助调试定位问题）
                    if result.message:
                        action_log += f"\n✅ {result.message}"
                    else:
                        action_log += f"\n✅ 动作执行成功"
                else:
                    action_log += f"\n⚠️ 动作执行: {result.message}"
                    
            except Exception as e:
                action_log += f"\n❌ 动作执行失败: {str(e)}"
                yield thinking_log, action_log, screenshot_img
                return {"finished": True, "thinking_log": thinking_log, "action_log": action_log}
            
            # 移除上下文中的图片（UI-TARS 保留最近 5 张由 limit_context 处理）
            # 这里不需要 remove_images_from_message，因为 limit_context 已经限制了数量
            
            # 添加助手响应到上下文（保留模型的全部输出）
            self._context.append({
                "role": "assistant",
                "content": raw_content
            })
            
            # 检查是否完成
            finished = uitars_action.action_type == "finished" or result.should_finish
        elif self._is_qwenvl:
            # Qwen-VL 响应解析
            thinking, action_str = self._adapter.parse_response(raw_content)
            
            # 使用 Qwen-VL action handler 解析
            qwenvl_action = action_handler.parse_response(raw_content)
            
            # 添加屏幕分辨率信息到日志（帮助调试坐标问题）
            action_log += f"\n### 🎯 执行动作\n📐 屏幕分辨率: {screenshot.width}x{screenshot.height}px\n```\nAction: {action_str}\n```\n"
            yield thinking_log, action_log, screenshot_img
            
            # 执行动作
            try:
                result = action_handler.execute(qwenvl_action, screenshot.width, screenshot.height)
                
                if result.success:
                    action_log += f"\n✅ 动作执行成功"
                else:
                    action_log += f"\n⚠️ 动作执行: {result.message}"
                    
            except Exception as e:
                action_log += f"\n❌ 动作执行失败: {str(e)}"
                yield thinking_log, action_log, screenshot_img
                return {"finished": True, "thinking_log": thinking_log, "action_log": action_log}
            
            # QwenVL: 不添加 assistant 消息到历史
            # 只提取 Action 描述文本，通过 adapter.add_history() 添加到历史
            # 这样下一轮的 user message 会包含这个描述
            if hasattr(qwenvl_action, 'action_desc') and qwenvl_action.action_desc:
                action_description = qwenvl_action.action_desc.strip()
            else:
                # Fallback: 从 raw_content 中提取 Action: 后面的描述文本
                import re
                action_match = re.search(r'Action:\s*"([^"]+)"', raw_content)
                if action_match:
                    action_description = action_match.group(1).strip()
                else:
                    lines = raw_content.split('\n')
                    action_description = ""
                    for line in lines:
                        if line.strip().startswith('Action:'):
                            action_description = line.strip()[7:].strip()
                            action_description = action_description.strip('"').strip("'")
                            break
            
            # 添加到 adapter 的历史记录
            if action_description and hasattr(self._adapter, 'add_history'):
                self._adapter.add_history(action_description)
            
            # 检查是否完成（tool_call 格式用 terminate，旧格式用 finish）
            finished = qwenvl_action.action_type in ("finish", "terminate") or result.should_finish
        elif self._is_maiui:
            # MAI-UI 响应解析
            from phone_agent.actions.handler_maiui import MAIUIActionHandler, convert_maiui_to_autoglm
            
            thinking, action_str = self._adapter.parse_response(raw_content)
            
            # 使用 MAI-UI action handler 解析
            maiui_action = action_handler.parse_response(raw_content)
            
            # 转换为 AutoGLM 格式用于日志显示
            action_for_log = convert_maiui_to_autoglm(maiui_action, screenshot.width, screenshot.height)
            
            # 添加屏幕分辨率信息到日志
            action_log += f"\n### 🎯 执行动作\n📐 屏幕分辨率: {screenshot.width}x{screenshot.height}px\n```json\n{json.dumps(action_for_log, ensure_ascii=False, indent=2)}\n```\n"
            yield thinking_log, action_log, screenshot_img
            
            # 执行动作
            try:
                result = action_handler.execute(maiui_action, screenshot.width, screenshot.height)
                
                if result.success:
                    if result.message:
                        action_log += f"\n✅ {result.message}"
                    else:
                        action_log += f"\n✅ 动作执行成功"
                else:
                    action_log += f"\n⚠️ 动作执行: {result.message}"
                    
            except Exception as e:
                action_log += f"\n❌ 动作执行失败: {str(e)}"
                yield thinking_log, action_log, screenshot_img
                return {"finished": True, "thinking_log": thinking_log, "action_log": action_log}
            
            # 移除上下文中的图片（MAI-UI 保留最近 3 张由 limit_context 处理）
            # 这里不需要 remove_images_from_message，因为 limit_context 已经限制了数量
            
            # 添加助手响应到上下文（MAI-UI 使用纯字符串格式的 assistant 消息）
            self._context.append({
                "role": "assistant",
                "content": raw_content
            })
            
            # 检查是否完成
            finished = maiui_action.action_type in ["terminate", "answer"] or result.should_finish
        elif self._is_guiowl:
            # GUI-Owl 1.5 响应解析（官方 tool_call 格式）
            from phone_agent.actions.handler_guiowl import convert_guiowl_to_autoglm
            
            thinking, action_str = self._adapter.parse_response(raw_content)
            
            # 使用 GUI-Owl action handler 解析
            guiowl_action = action_handler.parse_response(raw_content)
            
            # 转换为 AutoGLM 格式用于日志显示
            action_for_log = convert_guiowl_to_autoglm(guiowl_action, screenshot.width, screenshot.height)
            
            # 添加屏幕分辨率信息到日志
            action_log += f"\n### 🎯 执行动作\n📐 屏幕分辨率: {screenshot.width}x{screenshot.height}px\n```json\n{json.dumps(action_for_log, ensure_ascii=False, indent=2)}\n```\n"
            yield thinking_log, action_log, screenshot_img
            
            # 执行动作
            try:
                result = action_handler.execute(guiowl_action, screenshot.width, screenshot.height)
                
                if result.success:
                    if result.message:
                        action_log += f"\n✅ {result.message}"
                    else:
                        action_log += f"\n✅ 动作执行成功"
                else:
                    action_log += f"\n⚠️ 动作执行: {result.message}"
                    
            except Exception as e:
                action_log += f"\n❌ 动作执行失败: {str(e)}"
                yield thinking_log, action_log, screenshot_img
                return {"finished": True, "thinking_log": thinking_log, "action_log": action_log}
            
            # GUI-Owl（官方格式）: 不添加 assistant 消息到历史
            # 只提取 Action 描述文本，通过 adapter.add_history() 添加到历史
            # 这样下一轮的 user message 会包含 Previous actions 历史
            action_description = ""
            if hasattr(guiowl_action, 'action_desc') and guiowl_action.action_desc:
                action_description = guiowl_action.action_desc.strip()
            elif hasattr(guiowl_action, 'description') and guiowl_action.description:
                action_description = guiowl_action.description.strip()
            else:
                # Fallback: 从 raw_content 中提取 Action: 后面的描述文本
                import re
                action_match_re = re.search(r'Action:\s*"?([^"\n]+)"?', raw_content)
                if action_match_re:
                    action_description = action_match_re.group(1).strip()
            
            # 添加到 adapter 的历史记录
            if action_description and hasattr(self._adapter, 'add_history'):
                self._adapter.add_history(action_description)
            
            # 同步 handler 的 action_history 到 adapter
            if hasattr(action_handler, 'action_history') and hasattr(self._adapter, '_action_history'):
                self._adapter._action_history = list(action_handler.action_history)
            
            # 检查是否完成
            finished = guiowl_action.action_type in ["terminate", "answer"] or result.should_finish
        else:
            # AutoGLM 响应解析
            thinking, action_str = self._parse_response(raw_content)
            
            # 解析动作
            try:
                action = parse_action(action_str)
            except ValueError:
                action = finish(message=action_str)
            
            action_log += f"\n### 🎯 执行动作\n```json\n{json.dumps(action, ensure_ascii=False, indent=2)}\n```\n"
            yield thinking_log, action_log, screenshot_img
            
            # 移除上下文中的图片
            self._context[-1] = MessageBuilder.remove_images_from_message(self._context[-1])
            
            # 检查是否是 Take_over 动作（需要人工介入）
            is_takeover = action.get("action") == "Take_over"
            if is_takeover:
                takeover_msg = action.get("message", "需要用户人工操作")
                action_log += f"\n\n⏸️ **需要人工介入**: {takeover_msg}\n"
                action_log += f"👉 请在手机上完成操作（如登录、验证码等），然后点击 **继续执行** 按钮\n"
                yield thinking_log, action_log, screenshot_img
            
            # 执行动作
            try:
                result = action_handler.execute(action, screenshot.width, screenshot.height)
                
                if result.success:
                    if is_takeover:
                        action_log += f"\n✅ 人工操作已完成，继续执行任务"
                    else:
                        action_log += f"\n✅ 动作执行成功"
                else:
                    action_log += f"\n⚠️ 动作执行: {result.message}"
                    
            except Exception as e:
                action_log += f"\n❌ 动作执行失败: {str(e)}"
                yield thinking_log, action_log, screenshot_img
                return {"finished": True, "thinking_log": thinking_log, "action_log": action_log}
            
            # 添加助手响应到上下文
            self._context.append(
                MessageBuilder.create_assistant_message(
                    f"<think>{thinking}</think><answer>{action_str}</answer>"
                )
            )
            
            # 检查是否完成
            finished = action.get("_metadata") == "finish" or result.should_finish
        
        # 🧠 记忆系统：记录每一步执行
        if self.memory_manager:
            try:
                self.memory_manager.add_step(
                    thinking=thinking,
                    action={"raw": raw_content[-300:]},
                    screenshot_app=current_app,
                )
            except Exception:
                pass  # 记忆追踪失败不影响主流程
        
        if finished:
            action_log += f"\n\n🎉 **任务完成**: {result.message or '已完成'}"
            self._task_success = True
            # 🧠 记忆系统：任务成功完成
            if self.memory_manager:
                self.memory_manager.end_task(
                    success=True,
                    result=result.message or "已完成"
                )
                action_log += f"\n🧠 记忆已更新"
        
        yield thinking_log, action_log, screenshot_img
        
        return {"finished": finished, "thinking_log": thinking_log, "action_log": action_log}
    
    def _inject_memory_into_context(self, memory_context: str):
        """
        将记忆上下文注入到对话的系统/首条消息中。
        
        遍历已构建的消息，找到 system 或第一条含文本的 user 消息，
        将记忆上下文追加到其文本内容末尾。
        支持 content 为 str 或 list[dict] 两种格式。
        """
        for i, msg in enumerate(self._context):
            role = msg.get("role", "")
            content = msg.get("content", "")
            
            if role == "system":
                self._append_to_message(i, content, memory_context)
                return
            
            # 如果没有 system 消息（如 UI-TARS），注入到第一条 user 消息
            if role == "user":
                if isinstance(content, str) and len(content) > 50:
                    self._context[i]["content"] = content + f"\n\n{memory_context}"
                    return
                elif isinstance(content, list):
                    self._append_to_message(i, content, memory_context)
                    return
    
    def _append_to_message(self, msg_idx: int, content, text_to_append: str):
        """将文本追加到消息内容的文本部分（支持 str 和 list 格式）。"""
        if isinstance(content, str):
            self._context[msg_idx]["content"] = content + f"\n\n{text_to_append}"
        elif isinstance(content, list):
            for j, item in enumerate(content):
                if isinstance(item, dict) and item.get("type") == "text":
                    self._context[msg_idx]["content"][j]["text"] = item["text"] + f"\n\n{text_to_append}"
                    return
            # 如果没找到 text 类型的 item，追加一个新的
            self._context[msg_idx]["content"].append({
                "type": "text",
                "text": text_to_append,
            })
    
    def _parse_response(self, content: str) -> tuple[str, str]:
        """解析模型响应"""
        # <answer> 标签优先（因为 <answer> 可能包裹 do()/finish()）
        if "<answer>" in content:
            parts = content.split("<answer>", 1)
            thinking = parts[0].replace("<think>", "").replace("</think>", "").strip()
            action = parts[1].replace("</answer>", "").strip()
            return thinking, action
        
        if "finish(message=" in content:
            parts = content.split("finish(message=", 1)
            thinking = parts[0].strip()
            action = "finish(message=" + parts[1]
            return thinking, action
        
        if "do(action=" in content:
            parts = content.split("do(action=", 1)
            thinking = parts[0].strip()
            action = "do(action=" + parts[1]
            return thinking, action
        
        return "", content


# 全局流式 Agent
streaming_agent: StreamingAgent | None = None


def execute_task(
    task: str,
    device_type: str,
    device_id: str,
    base_url: str,
    api_key: str,
    model_name: str,
    max_steps: int,
    wda_url: str,
    model_type: str = "auto",  # 模型类型参数
    user_id: str = "default",  # 用户 ID（用于记忆系统）
    lang: str = "cn",  # Prompt 语言 (cn/en)
) -> Generator[tuple[str, str, Image.Image | None, gr.update], None, None]:
    """执行任务并流式输出结果"""
    global streaming_agent, app_state
    
    if not task.strip():
        yield "请输入任务描述", "", None, gr.update(interactive=True)
        return
    
    # 检查是否已有任务在运行
    if app_state.is_running:
        yield "⚠️ 已有任务在运行中，请先停止当前任务", "", None, gr.update(interactive=True)
        return
    
    app_state.is_running = True
    app_state.should_stop = False
    app_state.current_task = task
    
    # 创建配置
    model_config = ModelConfig(
        base_url=base_url,
        api_key=api_key or "EMPTY",
        model_name=model_name,
        lang=lang,
    )
    
    dt = DeviceType.ADB if device_type == "adb" else (DeviceType.HDC if device_type == "hdc" else DeviceType.IOS)
    
    if dt == DeviceType.IOS:
        agent_config = IOSAgentConfig(
            max_steps=max_steps,
            wda_url=wda_url,
            device_id=device_id.strip() if device_id.strip() else None,
            verbose=True,
            lang=lang,
        )
    else:
        agent_config = AgentConfig(
            max_steps=max_steps,
            device_id=device_id.strip() if device_id.strip() else None,
            verbose=True,
            lang=lang,
        )
    
    # 创建流式 Agent，传入模型类型和用户 ID（用于记忆系统）
    streaming_agent = StreamingAgent(
        model_config, agent_config, dt,
        model_type=model_type,
        user_id=user_id.strip() or "default"
    )
    
    try:
        # 禁用开始按钮
        yield "", "", None, gr.update(interactive=False)
        
        # 执行任务
        for thinking, action, screenshot in streaming_agent.run_streaming(task):
            if app_state.should_stop:
                break
            yield thinking, action, screenshot, gr.update(interactive=False)
        
    except Exception as e:
        yield f"❌ 执行错误:\n{traceback.format_exc()}", "", None, gr.update(interactive=True)
    finally:
        app_state.is_running = False
        app_state.should_stop = False
        streaming_agent = None
        yield gr.update(), gr.update(), gr.update(), gr.update(interactive=True)


def stop_task():
    """停止当前任务"""
    global streaming_agent, app_state
    
    app_state.should_stop = True
    # 如果正在等待人工介入，也要触发继续事件以便停止
    if app_state.takeover_continue_event:
        app_state.takeover_continue_event.set()
    if streaming_agent:
        streaming_agent.stop()
    
    return "⚠️ 正在停止任务..."


def continue_after_takeover():
    """人工操作完成后继续执行"""
    global app_state
    
    if app_state.waiting_for_takeover and app_state.takeover_continue_event:
        app_state.takeover_continue_event.set()
        return "✅ 继续执行中..."
    else:
        return "⚠️ 当前没有需要人工介入的任务"


def new_conversation():
    """新建对话"""
    global streaming_agent, app_state
    
    app_state.is_running = False
    app_state.should_stop = True
    app_state.current_task = ""
    
    if streaming_agent:
        streaming_agent.reset()
    
    return "", "", "", None


# ==================== 记忆管理功能 ====================
def get_memory_stats(user_id: str) -> str:
    """获取记忆统计信息"""
    if not HAS_MEMORY:
        return "❌ 记忆模块未安装，请检查 phone_agent/memory 目录"
    
    mm = get_memory_manager(user_id.strip() or "default")
    if not mm:
        return "❌ 无法初始化记忆管理器"
    
    stats = mm.get_stats()
    summary = mm.get_user_summary()
    
    result = f"""# 🧠 记忆系统统计

## 基本信息
- **用户 ID**: {stats.get('user_id', 'default')}
- **记忆总数**: {stats.get('total_memories', 0)}
- **存储目录**: {stats.get('storage_dir', 'N/A')}
- **FAISS 支持**: {'✅ 已启用' if stats.get('has_faiss') else '⚠️ 未安装（使用简单相似度）'}

## 记忆类型分布
"""
    
    type_counts = stats.get('by_type', {})
    if type_counts:
        for mem_type, count in type_counts.items():
            type_name = {
                'user_preference': '用户偏好',
                'contact': '联系人',
                'task_pattern': '任务模式',
                'app_usage': '应用使用',
                'task_history': '任务历史',
                'user_correction': '用户纠正',
                'general': '通用',
            }.get(mem_type, mem_type)
            result += f"- {type_name}: {count}\n"
    else:
        result += "- 暂无记忆\n"
    
    result += "\n## 用户画像\n"
    
    if summary.get('contacts'):
        result += f"### 常用联系人\n"
        for contact in summary['contacts'][:5]:
            result += f"- {contact}\n"
    
    if summary.get('frequent_apps'):
        result += f"\n### 常用应用\n"
        for app in summary['frequent_apps'][:5]:
            result += f"- {app}\n"
    
    if summary.get('preferences'):
        result += f"\n### 用户偏好\n"
        for pref in summary['preferences'][:5]:
            result += f"- {pref}\n"
    
    if summary.get('recent_tasks'):
        result += f"\n### 最近任务\n"
        for task in summary['recent_tasks'][:3]:
            result += f"- {task[:50]}{'...' if len(task) > 50 else ''}\n"
    
    return result


def add_user_preference(user_id: str, preference: str, category: str, importance: float) -> str:
    """添加用户偏好"""
    if not HAS_MEMORY:
        return "❌ 记忆模块未安装"
    
    if not preference.strip():
        return "⚠️ 请输入偏好内容"
    
    mm = get_memory_manager(user_id.strip() or "default")
    if not mm:
        return "❌ 无法初始化记忆管理器"
    
    mm.add_user_preference(
        preference=preference.strip(),
        category=category,
        importance=importance,
    )
    
    return f"✅ 已添加偏好: {preference}"


def search_memories(user_id: str, query: str, top_k: int = 5) -> str:
    """搜索相关记忆"""
    if not HAS_MEMORY:
        return "❌ 记忆模块未安装"
    
    if not query.strip():
        return "⚠️ 请输入搜索内容"
    
    mm = get_memory_manager(user_id.strip() or "default")
    if not mm:
        return "❌ 无法初始化记忆管理器"
    
    memories = mm.store.search(query=query.strip(), top_k=top_k)
    
    if not memories:
        return f"未找到与「{query}」相关的记忆"
    
    result = f"# 🔍 搜索结果: {query}\n\n找到 {len(memories)} 条相关记忆:\n\n"
    
    for i, mem in enumerate(memories, 1):
        type_name = {
            'user_preference': '用户偏好',
            'contact': '联系人',
            'task_pattern': '任务模式',
            'app_usage': '应用使用',
            'task_history': '任务历史',
            'user_correction': '用户纠正',
            'general': '通用',
        }.get(mem.memory_type.value, mem.memory_type.value)
        
        result += f"### {i}. [{type_name}]\n"
        result += f"- **内容**: {mem.content}\n"
        result += f"- **重要性**: {mem.importance:.2f}\n"
        result += f"- **访问次数**: {mem.access_count}\n"
        result += f"- **最后访问**: {mem.last_accessed[:10]}\n\n"
    
    return result


def clear_all_memories(user_id: str) -> str:
    """清除所有记忆"""
    if not HAS_MEMORY:
        return "❌ 记忆模块未安装"
    
    mm = get_memory_manager(user_id.strip() or "default")
    if not mm:
        return "❌ 无法初始化记忆管理器"
    
    mm.clear_all()
    return "🗑️ 所有记忆已清除"


def export_memories_json(user_id: str) -> tuple[str, str]:
    """导出记忆为 JSON"""
    if not HAS_MEMORY:
        return "❌ 记忆模块未安装", ""
    
    mm = get_memory_manager(user_id.strip() or "default")
    if not mm:
        return "❌ 无法初始化记忆管理器", ""
    
    memories = mm.export_memories()
    json_str = json.dumps(memories, ensure_ascii=False, indent=2)
    
    return f"✅ 已导出 {len(memories)} 条记忆", json_str


def import_memories_json(user_id: str, json_str: str) -> str:
    """从 JSON 导入记忆"""
    if not HAS_MEMORY:
        return "❌ 记忆模块未安装"
    
    if not json_str.strip():
        return "⚠️ 请输入 JSON 数据"
    
    mm = get_memory_manager(user_id.strip() or "default")
    if not mm:
        return "❌ 无法初始化记忆管理器"
    
    try:
        memories = json.loads(json_str)
        mm.import_memories(memories)
        return f"✅ 已导入 {len(memories)} 条记忆"
    except json.JSONDecodeError as e:
        return f"❌ JSON 解析错误: {e}"
    except Exception as e:
        return f"❌ 导入失败: {e}"


# ==================== 构建 Gradio 界面 ====================
def create_ui():
    """创建 Gradio 界面"""
    
    # 自定义 CSS
    custom_css = """
    .gradio-container {
        font-family: 'Noto Sans SC', 'Microsoft YaHei', sans-serif !important;
    }
    
    .header-title {
        text-align: center;
        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
        -webkit-background-clip: text;
        -webkit-text-fill-color: transparent;
        font-size: 2.5em;
        font-weight: bold;
        margin-bottom: 0.5em;
    }
    
    .header-title .logo-emoji {
        background: none;
        -webkit-text-fill-color: initial;
        color: #6b5bd5;
        margin-right: 0.2em;
    }
    
    .header-subtitle {
        text-align: center;
        color: #666;
        margin-bottom: 1em;
    }
    
    .status-box {
        padding: 1em;
        border-radius: 8px;
        background: #f8f9fa;
    }
    
    .thinking-box {
        background: linear-gradient(135deg, #f5f7fa 0%, #e4e8eb 100%);
        border-left: 4px solid #667eea;
        padding: 1em;
        border-radius: 4px;
    }
    
    .action-box {
        background: linear-gradient(135deg, #fff9e6 0%, #fff3cd 100%);
        border-left: 4px solid #ffc107;
        padding: 1em;
        border-radius: 4px;
    }
    
    .screenshot-container {
        border: 2px solid #dee2e6;
        border-radius: 8px;
        overflow: hidden;
        display: inline-flex;
        max-width: 100%;
        margin: 0 auto;
    }
    .screenshot-container img {
        max-width: 100%;
        height: auto;
        display: block;
    }
    
    .btn-primary {
        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%) !important;
        border: none !important;
    }
    
    .btn-danger {
        background: linear-gradient(135deg, #ff6b6b 0%, #ee5a5a 100%) !important;
        border: none !important;
    }
    
    .tab-nav button {
        font-weight: 500 !important;
    }
    """
    
    with gr.Blocks(
        title="OpenGUI Web UI",
        theme=gr.themes.Soft(
            primary_hue="indigo",
            secondary_hue="purple",
        ),
        css=custom_css,
    ) as demo:
        
        # 头部
        gr.HTML("""
        <div style="text-align: center; padding: 20px 0;">
            <h1 class="header-title"><span class="logo-emoji">🤖</span> ClawGUI-Agent</h1>
            <p class="header-subtitle">AI 驱动的手机自动化控制平台</p>
        </div>
        """)
        
        with gr.Tabs():
            # ==================== 配置管理 Tab ====================
            with gr.Tab("⚙️ 配置管理"):
                gr.Markdown("### 基础配置")
                
                device_type = gr.Radio(
                    choices=[("Android (ADB)", "adb"), ("HarmonyOS (HDC)", "hdc"), ("iOS", "ios")],
                    value="adb",
                    label="设备类型",
                    info="选择您的设备类型"
                )
                
                gr.Markdown("### 模型 API 配置")
                
                with gr.Row():
                    with gr.Column(scale=2):
                        base_url = gr.Textbox(
                            label="Base URL",
                            value=os.getenv("PHONE_AGENT_BASE_URL", "http://localhost:8000/v1"),
                            placeholder="http://localhost:8000/v1",
                            info="模型服务的 API 地址"
                        )
                    
                    with gr.Column(scale=2):
                        model_name = gr.Textbox(
                            label="模型名称",
                            value=os.getenv("PHONE_AGENT_MODEL", "autoglm-phone-9b"),
                            placeholder="autoglm-phone-9b 或 doubao-1-5-ui-tars-250428",
                            info="要使用的模型名称"
                        )
                
                with gr.Row():
                    model_type = gr.Radio(
                        choices=[
                            ("自动检测", "auto"),
                            ("AutoGLM", "autoglm"),
                            ("UI-TARS (Doubao)", "uitars"),
                            ("Qwen-VL (Qwen2.5/3-VL)", "qwenvl"),
                            ("MAI-UI (通义)", "maiui"),
                            ("GUI-Owl (mPLUG)", "guiowl"),
                        ],
                        value="auto",
                        label="模型类型",
                        info="选择模型的 action space 类型，自动检测会根据模型名称判断"
                    )
                
                with gr.Row():
                    with gr.Column(scale=2):
                        api_key = gr.Textbox(
                            label="API Key",
                            value=os.getenv("PHONE_AGENT_API_KEY", ""),
                            placeholder="sk-...",
                            type="password",
                            info="API 密钥（如果需要）"
                        )
                    
                    with gr.Column(scale=1):
                        max_steps = gr.Slider(
                            minimum=1,
                            maximum=200,
                            value=int(os.getenv("PHONE_AGENT_MAX_STEPS", "100")),
                            step=1,
                            label="最大步数",
                            info="单个任务最大执行步数"
                        )
                
                with gr.Row():
                    prompt_lang = gr.Radio(
                        choices=[("中文", "cn"), ("English", "en")],
                        value=os.getenv("PHONE_AGENT_LANG", "cn"),
                        label="Prompt 语言",
                        info="System Prompt 使用的语言，影响模型的思考和输出语言"
                    )
                
                gr.Markdown("### iOS 专属配置")
                
                with gr.Row():
                    wda_url = gr.Textbox(
                        label="WebDriverAgent URL",
                        value=os.getenv("PHONE_AGENT_WDA_URL", "http://localhost:8100"),
                        placeholder="http://localhost:8100",
                        info="iOS 设备的 WDA 服务地址"
                    )
                
                gr.Markdown("### 设备 ID（可选）")
                
                with gr.Row():
                    device_id = gr.Textbox(
                        label="设备 ID",
                        value=os.getenv("PHONE_AGENT_DEVICE_ID", ""),
                        placeholder="留空自动选择第一个设备",
                        info="指定设备 ID（多设备时使用）"
                    )
                
                gr.Markdown("### 🧠 记忆系统配置")
                
                with gr.Row():
                    memory_user_id_config = gr.Textbox(
                        label="用户 ID",
                        value=os.getenv("PHONE_AGENT_USER_ID", "default"),
                        placeholder="default",
                        info="不同用户 ID 对应独立的记忆库，用于多用户场景"
                    )
            
            # ==================== 设备管理 Tab ====================
            with gr.Tab("📱 设备管理"):
                with gr.Row():
                    with gr.Column(scale=1):
                        gr.Markdown("### 已连接设备")
                        device_list_output = gr.Markdown("点击刷新查看设备列表")
                        refresh_devices_btn = gr.Button("🔄 刷新设备列表", variant="primary")
                    
                    with gr.Column(scale=1):
                        gr.Markdown("### 远程连接")
                        connect_address = gr.Textbox(
                            label="设备地址",
                            placeholder="192.168.1.100:5555",
                            info="输入远程设备的 IP:端口"
                        )
                        
                        with gr.Row():
                            connect_btn = gr.Button("🔌 连接", variant="primary")
                            disconnect_btn = gr.Button("⛔ 断开", variant="stop")
                        
                        connect_output = gr.Markdown("")
                
                gr.Markdown("### WiFi 调试")
                
                with gr.Row():
                    wifi_port = gr.Number(
                        label="调试端口",
                        value=5555,
                        precision=0,
                        info="TCP/IP 调试端口"
                    )
                    enable_wifi_btn = gr.Button("📡 启用 WiFi 调试", variant="secondary")
                
                wifi_output = gr.Markdown("")
                
                # 事件绑定
                refresh_devices_btn.click(
                    fn=get_device_list,
                    inputs=[device_type],
                    outputs=[device_list_output]
                )
                
                connect_btn.click(
                    fn=connect_device,
                    inputs=[connect_address, device_type],
                    outputs=[connect_output]
                )
                
                disconnect_btn.click(
                    fn=disconnect_device,
                    inputs=[connect_address, device_type],
                    outputs=[connect_output]
                )
                
                enable_wifi_btn.click(
                    fn=enable_wifi_debug,
                    inputs=[wifi_port, device_type],
                    outputs=[wifi_output]
                )
            
            # ==================== 系统检查 Tab ====================
            with gr.Tab("🔍 系统检查"):
                gr.Markdown("### 系统环境检查")
                gr.Markdown("检查所有必要组件是否正确安装和配置")
                
                run_check_btn = gr.Button("🚀 运行完整检查", variant="primary", size="lg")
                
                check_output = gr.Markdown("点击按钮开始检查...")
                
                gr.Markdown("### 单项检查")
                
                with gr.Row():
                    check_tool_btn = gr.Button("🔧 检查工具安装")
                    check_device_btn = gr.Button("📱 检查设备连接")
                    check_keyboard_btn = gr.Button("⌨️ 检查输入法")
                    check_api_btn = gr.Button("🌐 检查 API 连接")
                
                single_check_output = gr.Markdown("")
                
                # 事件绑定
                run_check_btn.click(
                    fn=run_full_check,
                    inputs=[device_type, base_url, api_key, model_name, wda_url],
                    outputs=[check_output]
                )
                
                check_tool_btn.click(
                    fn=check_tool_installation,
                    inputs=[device_type],
                    outputs=[single_check_output]
                )
                
                check_device_btn.click(
                    fn=check_device_connection,
                    inputs=[device_type],
                    outputs=[single_check_output]
                )
                
                check_keyboard_btn.click(
                    fn=check_keyboard_installation,
                    inputs=[device_type],
                    outputs=[single_check_output]
                )
                
                check_api_btn.click(
                    fn=check_model_api,
                    inputs=[base_url, api_key, model_name],
                    outputs=[single_check_output]
                )
            
            # ==================== 记忆管理 Tab ====================
            with gr.Tab("🧠 记忆管理"):
                gr.Markdown("""
                ### 个性化记忆系统
                
                记忆系统帮助 Agent 了解您的偏好和习惯，提供更智能的服务。
                
                **功能特点**：
                - 🎯 自动学习常用联系人和应用
                - 📝 记录任务历史和偏好
                - 🔍 语义搜索相关记忆
                - 🔄 自动去重避免冗余
                """)
                
                with gr.Row():
                    memory_user_id = gr.Textbox(
                        label="用户 ID",
                        value="default",
                        placeholder="输入用户标识",
                        info="不同用户 ID 对应不同的记忆库"
                    )
                    refresh_stats_btn = gr.Button("🔄 刷新统计", variant="primary")
                
                memory_stats_output = gr.Markdown("点击「刷新统计」查看记忆系统状态")
                
                gr.Markdown("---")
                gr.Markdown("### 添加用户偏好")
                
                with gr.Row():
                    with gr.Column(scale=3):
                        preference_input = gr.Textbox(
                            label="偏好内容",
                            placeholder="例如：喜欢使用深色模式、常用外卖平台是美团...",
                            lines=2
                        )
                    with gr.Column(scale=1):
                        preference_category = gr.Dropdown(
                            label="类别",
                            choices=["general", "app", "contact", "habit", "ui"],
                            value="general"
                        )
                        preference_importance = gr.Slider(
                            label="重要性",
                            minimum=0.1,
                            maximum=1.0,
                            value=0.6,
                            step=0.1
                        )
                
                add_preference_btn = gr.Button("➕ 添加偏好", variant="secondary")
                add_preference_output = gr.Markdown("")
                
                gr.Markdown("---")
                gr.Markdown("### 搜索记忆")
                
                with gr.Row():
                    search_query = gr.Textbox(
                        label="搜索内容",
                        placeholder="输入关键词搜索相关记忆...",
                        scale=3
                    )
                    search_top_k = gr.Slider(
                        label="结果数量",
                        minimum=1,
                        maximum=20,
                        value=5,
                        step=1,
                        scale=1
                    )
                
                search_btn = gr.Button("🔍 搜索", variant="secondary")
                search_output = gr.Markdown("")
                
                gr.Markdown("---")
                gr.Markdown("### 数据管理")
                
                with gr.Row():
                    export_btn = gr.Button("📤 导出记忆", variant="secondary")
                    import_btn = gr.Button("📥 导入记忆", variant="secondary")
                    clear_btn = gr.Button("🗑️ 清除所有", variant="stop")
                
                export_output = gr.Markdown("")
                export_json = gr.Textbox(
                    label="JSON 数据",
                    placeholder="导出的 JSON 数据将显示在这里，也可粘贴 JSON 进行导入",
                    lines=10,
                    visible=True
                )
                
                # 事件绑定
                refresh_stats_btn.click(
                    fn=get_memory_stats,
                    inputs=[memory_user_id],
                    outputs=[memory_stats_output]
                )
                
                add_preference_btn.click(
                    fn=add_user_preference,
                    inputs=[memory_user_id, preference_input, preference_category, preference_importance],
                    outputs=[add_preference_output]
                )
                
                search_btn.click(
                    fn=search_memories,
                    inputs=[memory_user_id, search_query, search_top_k],
                    outputs=[search_output]
                )
                
                export_btn.click(
                    fn=export_memories_json,
                    inputs=[memory_user_id],
                    outputs=[export_output, export_json]
                )
                
                import_btn.click(
                    fn=import_memories_json,
                    inputs=[memory_user_id, export_json],
                    outputs=[export_output]
                )
                
                clear_btn.click(
                    fn=clear_all_memories,
                    inputs=[memory_user_id],
                    outputs=[export_output]
                )
            
            # ==================== 对话控制 Tab ====================
            with gr.Tab("💬 对话控制"):
                with gr.Row():
                    # 左侧：输入和日志
                    with gr.Column(scale=3):
                        gr.Markdown("### 任务输入")
                        task_input = gr.Textbox(
                            label="任务描述",
                            placeholder="例如：打开微信，发送消息给张三说'你好'",
                            lines=3,
                            info="用自然语言描述您想让 AI 执行的任务"
                        )
                        
                        with gr.Row():
                            start_btn = gr.Button("▶️ 开始执行", variant="primary", scale=2)
                            stop_btn = gr.Button("⏹️ 停止", variant="stop", scale=1)
                            continue_btn = gr.Button("⏩ 继续执行", variant="secondary", scale=1)
                            new_btn = gr.Button("🔄 新对话", variant="secondary", scale=1)
                        
                        gr.Markdown("### 💭 AI 思考过程")
                        thinking_output = gr.Markdown(
                            "",
                            elem_classes=["thinking-box"]
                        )
                        
                        gr.Markdown("### 🎯 动作执行日志")
                        action_output = gr.Markdown(
                            "",
                            elem_classes=["action-box"]
                        )
                    
                    # 右侧：截图预览
                    with gr.Column(scale=2):
                        gr.Markdown("### 📱 设备截图")
                        screenshot_display = gr.Image(
                            label="实时截图",
                            type="pil",
                            elem_classes=["screenshot-container"],
                        )
                        
                        with gr.Row():
                            refresh_screenshot_btn = gr.Button("🔄 刷新截图", size="sm")
                            auto_refresh = gr.Checkbox(
                                label="自动刷新 (2秒)",
                                value=False,
                                info="自动定时刷新截图"
                            )
                
                # 事件绑定
                start_btn.click(
                    fn=execute_task,
                    inputs=[
                        task_input, device_type, device_id,
                        base_url, api_key, model_name,
                        max_steps, wda_url, model_type,
                        memory_user_id_config,  # 用户 ID（记忆系统）
                        prompt_lang  # Prompt 语言 (cn/en)
                    ],
                    outputs=[thinking_output, action_output, screenshot_display, start_btn]
                )
                
                stop_btn.click(
                    fn=stop_task,
                    outputs=[action_output]
                )
                
                continue_btn.click(
                    fn=continue_after_takeover,
                    outputs=[action_output]
                )
                
                new_btn.click(
                    fn=new_conversation,
                    outputs=[task_input, thinking_output, action_output, screenshot_display]
                )
                
                refresh_screenshot_btn.click(
                    fn=refresh_screenshot,
                    inputs=[device_type, device_id, wda_url],
                    outputs=[screenshot_display]
                )

                # 自动刷新定时器 (2秒)
                auto_refresh_timer = gr.Timer(value=2, active=False)
                
                def toggle_auto_refresh(enabled):
                    return gr.Timer(active=enabled)
                
                auto_refresh.change(
                    fn=toggle_auto_refresh,
                    inputs=[auto_refresh],
                    outputs=[auto_refresh_timer]
                )
                
                auto_refresh_timer.tick(
                    fn=refresh_screenshot,
                    inputs=[device_type, device_id, wda_url],
                    outputs=[screenshot_display]
                )
            
            # ==================== 帮助文档 Tab ====================
            with gr.Tab("📖 帮助"):
                gr.Markdown("""
                # ClawGUI-Agent Web UI 使用指南
                
                ## 🚀 快速开始
                
                1. **配置模型 API**：在「配置管理」页面设置模型服务地址和密钥
                2. **连接设备**：确保手机已通过 USB 连接，并启用开发者调试
                3. **运行检查**：在「系统检查」页面验证所有组件状态
                4. **开始使用**：在「对话控制」页面输入任务，点击开始执行
                
                ## 🤖 支持的模型
                
                ### AutoGLM (默认)
                - 模型名称：`autoglm-phone-9b`
                - Action Space：`do(action="Tap", element=[x, y])` 格式
                - 适用于本地部署的 AutoGLM 模型
                
                ### UI-TARS (Doubao)
                - 模型名称：`doubao-1-5-ui-tars-250428`
                - Action Space：`click(point='<point>x y</point>')` 格式
                - 适用于火山引擎的 Doubao-1.5-UI-TARS 模型
                - API 地址：`https://ark.cn-beijing.volces.com/api/v3`
                - 需要在火山引擎控制台获取 API Key
                
                ### Qwen-VL (Qwen2.5-VL / Qwen3-VL)
                - 模型名称：`Qwen2.5-VL-72B-Instruct`、`Qwen3-VL-32B` 等
                - Action Space：`tap(x, y)`、`swipe(x1, y1, x2, y2)` 格式
                - 适用于阿里云/vLLM/Ollama 部署的 Qwen-VL 系列模型
                - 支持阿里云 DashScope API 或本地 vLLM 部署
                - 阿里云 API：`https://dashscope.aliyuncs.com/compatible-mode/v1`
                
                ### GLM-4V (GLM-4.6V / GLM-4.1V)
                - 模型名称：`GLM-4.6V-flash`、`GLM-4.1V-9B-thinking` 等
                - Action Space：`do(action="Tap", element=[x, y])` 格式（与 AutoGLM 相同）
                - 适用于智谱 GLM-4V 系列视觉语言模型
                - 支持 vLLM/transformers 本地部署
                - **自动使用 AutoGLM 适配器**，无需单独选择模型类型
                
                ### MAI-UI (通义 MAI-Mobile)
                - 模型名称：`MAI-UI`、`MAI-Mobile` 等
                - Action Space：`{"action": "click", "coordinate": [x, y]}` JSON 格式
                - 基于阿里云通义 [MAI-UI](https://github.com/Tongyi-MAI/MAI-UI) 项目
                - 坐标系统：0-999 归一化坐标
                - 输出格式：`<thinking>...</thinking><tool_call>...</tool_call>`
                - 支持 click、long_press、type、swipe、open、drag、system_button、wait、terminate、answer 动作
                - 智谱 API：`https://open.bigmodel.cn/api/paas/v4`
                
                ### GUI-Owl (mPLUG)
                - 模型名称：`GUI-Owl-7B`、`GUI-Owl-32B`、`GUI-Owl-1.5-8B-Instruct` 等
                - Action Space：`{"action": "click", "coordinate": [x, y]}` JSON 格式
                - 基于阿里巴巴通义 [mPLUG/GUI-Owl](https://github.com/X-PLUG/MobileAgent) 项目
                - 坐标系统：默认使用绝对像素坐标（区别于其他模型的归一化坐标）
                - 输出格式：`### Thought ### ... ### Action ### {JSON} ### Description ### ...`
                - 支持 click、long_press、swipe、type、system_button、open、wait、answer、terminate 动作
                - swipe 使用起点/终点坐标对（coordinate + coordinate2）
                - 建议使用 vLLM 部署，参考：`vllm serve GUI-Owl-1.5-8B-Instruct --max-model-len 32768`
                
                > **提示**：选择「自动检测」会根据模型名称自动选择正确的 action space
                
                ## 📱 设备连接指南
                
                ### Android 设备
                1. 在手机设置中启用「开发者选项」
                2. 打开「USB 调试」
                3. 用 USB 线连接电脑，在手机上授权调试
                4. 安装 ADB Keyboard 输入法（用于中文输入）
                
                ### iOS 设备
                1. 使用 Xcode 运行 WebDriverAgent
                2. 设置端口转发：`iproxy 8100 8100`
                3. 在配置中设置 WDA URL
                
                ### HarmonyOS 设备
                1. 启用开发者模式和 USB 调试
                2. 安装 HDC 工具
                3. 连接设备并授权
                
                ## 💡 使用技巧
                
                - **任务描述**：尽量具体清晰，例如「打开微信，搜索联系人张三，发送消息：明天见」
                - **中断任务**：如果 AI 执行出错，可以点击「停止」按钮中断
                - **查看进度**：思考过程和动作日志会实时显示 AI 的决策过程
                - **截图刷新**：可以手动刷新或开启自动刷新查看设备屏幕
                - **模型选择**：如果使用火山引擎的 UI-TARS 模型，请选择对应的模型类型
                
                ## ⚠️ 注意事项
                
                - 确保手机屏幕保持常亮，不要锁屏
                - 敏感操作（如支付）可能会被阻止截图
                - 建议在任务执行时不要手动操作手机
                - UI-TARS 和 Qwen-VL 模型建议使用 temperature=0 以获得稳定输出
                - Qwen-VL 模型支持更长的上下文，适合复杂多步骤任务
                
                ## 🖥️ 本地部署小模型须知
                
                如果你使用本地部署的小模型（如 ui-tars-1.5-7b、qwen3-vl-8b-instruct），可能会遇到定位不准确的问题。这是因为：
                
                1. **模型能力限制**：7B/8B 级别的小模型在 GUI grounding 任务上的能力不如大模型（72B+）
                2. **分辨率信息**：本地部署需要正确传递屏幕分辨率信息，我们已自动处理
                
                ### 改善建议
                
                - **使用更大的模型**：如 qwen2.5-vl-32b 或 qwen2.5-vl-72b
                - **降低推理参数**：设置 temperature=0，top_p=0.7
                - **vLLM 部署优化**：确保使用最新版本的 vLLM，正确配置多模态处理
                - **云端 API 备选**：如果本地模型效果不佳，可使用阿里云/火山引擎的 API
                
                > **提示**：系统会自动将屏幕分辨率信息传递给模型，帮助改善坐标准确性
                
                ## 🧠 个性化记忆系统
                
                ClawGUI-Agent 内置了个性化记忆系统，参考了 [TeleMem](https://github.com/TeleAI-UAGI/TeleMem) 的设计理念：
                
                ### 功能特点
                
                - **自动学习**：Agent 会自动学习您的常用联系人、应用和操作习惯
                - **语义去重**：相似的记忆会自动合并，避免冗余存储
                - **上下文增强**：执行任务时会自动检索相关记忆，提供个性化服务
                - **持久化存储**：记忆保存在本地，重启后仍然有效
                
                ### 使用方法
                
                1. 在「记忆管理」页面可以查看和管理所有记忆
                2. 手动添加用户偏好，帮助 Agent 更好地理解您
                3. 搜索功能可以查找相关记忆
                4. 支持导入/导出记忆数据
                
                ### 记忆类型
                
                - **用户偏好**：您的个人喜好和习惯
                - **联系人**：常用的联系人信息
                - **应用使用**：常用应用和使用频率
                - **任务历史**：成功完成的任务记录
                - **用户纠正**：您对 Agent 的纠正反馈
                
                > 💡 **提示**：记忆系统会随着使用自动变得更智能，无需手动配置
                
                ## 🔗 更多资源
                
                - [项目 GitHub](https://github.com/THUDM/Open-AutoGLM)
                - [TeleMem 记忆系统](https://github.com/TeleAI-UAGI/TeleMem)
                - [ADB Keyboard 下载](https://github.com/senzhk/ADBKeyBoard)
                - [WebDriverAgent 文档](https://github.com/appium/WebDriverAgent)
                - [Doubao-1.5-UI-TARS 文档](https://www.volcengine.com/docs/82379/1536429)
                """)
        
        # 页脚
        gr.HTML("""
        <div style="text-align: center; padding: 20px; color: #666; border-top: 1px solid #eee; margin-top: 20px;">
            <p>ClawGUI-Agent Web UI | Powered by Gradio</p>
        </div>
        """)
    
    return demo


# ==================== 主函数 ====================
def main():
    """主入口"""
    import argparse
    
    parser = argparse.ArgumentParser(description="ClawGUI-Agent Web UI")
    parser.add_argument("--host", type=str, default="127.0.0.1", help="服务器地址")
    parser.add_argument("--port", type=int, default=7860, help="服务器端口")
    parser.add_argument("--share", action="store_true", help="创建公共链接")
    parser.add_argument("--auth", type=str, help="认证信息，格式: username:password")
    
    args = parser.parse_args()
    
    # 创建界面
    demo = create_ui()
    
    # 解析认证信息
    auth = None
    if args.auth:
        parts = args.auth.split(":")
        if len(parts) == 2:
            auth = (parts[0], parts[1])
    
    print(f"""
╔══════════════════════════════════════════════════════╗
║         🤖 ClawGUI-Agent Web UI                  ║
║                                                      ║
║   启动中...                                          ║
║   地址: http://{args.host}:{args.port}                      ║
║                                                      ║
╚══════════════════════════════════════════════════════╝
    """)
    
    # 启动服务
    demo.launch(
        server_name=args.host,
        server_port=args.port,
        share=args.share,
        auth=auth,
        show_error=True,
    )


if __name__ == "__main__":
    main()
