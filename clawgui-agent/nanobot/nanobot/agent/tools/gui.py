"""GUI automation tool using PhoneAgent (ClawGUI-Agent) for mobile device control."""

import asyncio
import base64
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

from loguru import logger

from nanobot.agent.tools.base import Tool


class GUITool(Tool):
    """
    Tool to execute GUI tasks on a connected mobile device.

    Integrates PhoneAgent (ClawGUI-Agent) to let the agent control a phone
    via ADB/HDC by understanding screen content with a VLM.

    Supports two modes:
    - Internal mode (default): use the current nanobot model for GUI reasoning.
    - External mode: use a separately configured GUI VLM (e.g. AutoGLM-Phone).
    """

    def __init__(
        self,
        device_type: str = "adb",
        device_id: str | None = None,
        max_steps: int = 50,
        # Config-level default: always use external model regardless of LLM's per-call choice.
        use_external_model: bool = False,
        # Current nanobot model config (used when use_external_model=false)
        current_base_url: str | None = None,
        current_api_key: str | None = None,
        current_model_name: str | None = None,
        # External GUI model config (used when use_external_model=true)
        gui_base_url: str = "",
        gui_api_key: str = "",
        gui_model_name: str = "autoglm-phone",
        # Prompt template controls
        prompt_template_lang: str = "cn",
        prompt_template_style: str = "autoglm",
        # Trace
        trace_enabled: bool = False,
        trace_dir: str = "gui_trace",
    ):
        self.device_type = device_type
        self.device_id = device_id
        self.max_steps = max_steps
        self.use_external_model = use_external_model

        self.current_base_url = current_base_url
        self.current_api_key = current_api_key
        self.current_model_name = current_model_name

        self.gui_base_url = gui_base_url
        self.gui_api_key = gui_api_key
        self.gui_model_name = gui_model_name
        self.prompt_template_lang = prompt_template_lang
        self.prompt_template_style = prompt_template_style
        self.trace_enabled = trace_enabled
        self.trace_dir = trace_dir

    @property
    def name(self) -> str:
        return "gui_execute"

    @property
    def description(self) -> str:
        return (
            "Execute a GUI task on a connected mobile device (Android/HarmonyOS/iOS) "
            "via ADB. Describe the task in natural language and the tool will use a "
            "vision-language model to understand the phone screen, then perform actions "
            "(tap, swipe, type text, launch app, back, home, etc.) in a loop until the "
            "task is completed.\n\n"
            "Examples:\n"
            '  - "Open WeChat and send a message to Zhang San saying I will be late"\n'
            '  - "Search for Bluetooth headphones on Taobao and add to cart"\n'
            '  - "Open Settings and turn on Wi-Fi"'
        )

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "task": {
                    "type": "string",
                    "description": "Natural language description of the GUI task to perform on the phone.",
                },
                "max_steps": {
                    "type": "integer",
                    "description": "Maximum number of GUI action steps for this task (default: 50).",
                    "minimum": 1,
                    "maximum": 200,
                },
            },
            "required": ["task"],
        }

    async def execute(
        self,
        task: str,
        max_steps: int | None = None,
        **kwargs: Any,
    ) -> str | list[dict[str, Any]]:
        """Execute a GUI task on the connected device.

        Returns either a plain string (on error) or a list of content blocks
        containing the result text and a final screenshot image so the LLM can
        see the screen state and optionally forward it via the message tool.
        """

        # --- 1. Pre-flight checks (reuses phone_agent logic) ---
        device_check_error = await self._check_device_connection()
        if device_check_error:
            return device_check_error
        # --- 2. Resolve model config ---
        if self.use_external_model:
            if not self.gui_api_key:
                return (
                    "Error: External GUI model requested but gui_api_key is not set. "
                    "Please configure tools.gui.guiApiKey in ~/.nanobot/config.json."
                )
            base_url = self.gui_base_url
            api_key = self.gui_api_key
            model_name = self.gui_model_name
        else:
            # Use current nanobot model
            if not self.current_base_url or not self.current_model_name:
                return (
                    "Error: Current model config not available for GUI mode. "
                    "Please ensure the nanobot model provider is properly configured."
                )
            base_url = self.current_base_url
            api_key = self.current_api_key or "EMPTY"
            model_name = self.current_model_name

        steps = max_steps or self.max_steps
        logger.info(
            "GUI task starting: model={}, base_url={}, max_steps={}, task={}",
            model_name, base_url, steps, task[:80],
        )

        # --- 3. Run PhoneAgent in a thread (it's synchronous) ---
        try:
            result = await asyncio.to_thread(
                self._run_phone_agent,
                task=task,
                base_url=base_url,
                api_key=api_key,
                model_name=model_name,
                max_steps=steps,
            )
            logger.info("GUI task completed: {}", result[:120] if result else "")
        except Exception as e:
            logger.error("GUI task failed: {}", e)
            return f"Error executing GUI task: {e}"

        # --- 4. Capture final screenshot and return image + text ---
        screenshot_path = await self._capture_final_screenshot()
        if screenshot_path:
            try:
                from nanobot.utils.helpers import build_image_content_blocks
                raw = Path(screenshot_path).read_bytes()
                blocks = build_image_content_blocks(
                    raw, "image/png", screenshot_path,
                    f"[GUI Result] {result}\n\n📱 Final screenshot saved at: {screenshot_path}",
                )
                return blocks
            except Exception as e:
                logger.warning("Failed to build image blocks for final screenshot: {}", e)
                return f"{result}\n\n[Screenshot saved: {screenshot_path}]"
        return result

    async def _capture_final_screenshot(self) -> str | None:
        """Capture the current phone screen and save to a temporary PNG file.

        Returns the local file path on success, or None on failure.
        """
        try:
            tmp = tempfile.mktemp(suffix=".png", prefix="gui_result_")
            device_arg = ["-s", self.device_id] if self.device_id else []

            # Capture screenshot on device
            proc = await asyncio.create_subprocess_exec(
                "adb", *device_arg, "shell", "screencap", "-p", "/sdcard/gui_final.png",
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            await asyncio.wait_for(proc.communicate(), timeout=10)

            # Pull to local
            proc = await asyncio.create_subprocess_exec(
                "adb", *device_arg, "pull", "/sdcard/gui_final.png", tmp,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            await asyncio.wait_for(proc.communicate(), timeout=10)

            if Path(tmp).exists() and Path(tmp).stat().st_size > 0:
                logger.info("Final screenshot saved: {}", tmp)
                return tmp
            else:
                logger.warning("Final screenshot file missing or empty")
                return None
        except Exception as e:
            logger.warning("Failed to capture final screenshot: {}", e)
            return None

    def _run_phone_agent(
        self,
        task: str,
        base_url: str,
        api_key: str,
        model_name: str,
        max_steps: int,
    ) -> str:
        """Run PhoneAgent synchronously (called via asyncio.to_thread)."""

        from phone_agent import PhoneAgent
        from phone_agent.agent import AgentConfig
        from phone_agent.model import ModelConfig
        from phone_agent.device_factory import DeviceType, set_device_type

        # Set device type (global in phone_agent)
        dt_map = {"adb": DeviceType.ADB, "hdc": DeviceType.HDC}
        set_device_type(dt_map.get(self.device_type, DeviceType.ADB))

        model_config = ModelConfig(
            base_url=base_url,
            api_key=api_key,
            model_name=model_name,
            lang=self.prompt_template_lang,
        )

        agent_config = AgentConfig(
            max_steps=max_steps,
            device_id=self.device_id,
            lang=self.prompt_template_lang,
            verbose=True,
            enable_memory=True,
            model_type=self.prompt_template_style,
            trace_enabled=self.trace_enabled,
            trace_dir=self.trace_dir,
        )

        # Auto-confirm for nanobot context (no interactive stdin)
        def auto_confirm(message: str) -> bool:
            logger.info("GUI auto-confirm: {}", message)
            return True

        def auto_takeover(message: str) -> None:
            logger.info("GUI takeover requested (skipped in nanobot): {}", message)

        agent = PhoneAgent(
            model_config=model_config,
            agent_config=agent_config,
            confirmation_callback=auto_confirm,
            takeover_callback=auto_takeover,
        )

        return agent.run(task)

    async def _check_device_connection(self) -> str | None:
        """
        Check device connectivity following the same logic as
        ClawGUI-Agent main.py check_system_requirements().

        Returns an error message string, or None if all checks pass.
        """
        tool_cmd = "adb" if self.device_type == "adb" else "hdc"

        # Check 1: tool installed
        if not shutil.which(tool_cmd):
            return (
                f"Error: {tool_cmd.upper()} is not installed or not in PATH.\n"
                f"Please install it first. For ADB:\n"
                f"  macOS: brew install android-platform-tools\n"
                f"  Linux: sudo apt install android-tools-adb"
            )

        # Check 2: device connected
        try:
            if tool_cmd == "adb":
                proc = await asyncio.create_subprocess_exec(
                    "adb", "devices",
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE,
                )
            else:
                proc = await asyncio.create_subprocess_exec(
                    "hdc", "list", "targets",
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE,
                )
            stdout, _ = await asyncio.wait_for(proc.communicate(), timeout=10)
            output = stdout.decode("utf-8", errors="replace")

            if tool_cmd == "adb":
                lines = output.strip().split("\n")
                devices = [l for l in lines[1:] if l.strip() and "\tdevice" in l]
            else:
                devices = [l for l in output.strip().split("\n") if l.strip()]

            if not devices:
                return (
                    f"Error: No {tool_cmd.upper()} devices connected.\n"
                    f"Please connect your phone via USB and enable USB debugging."
                )

            # If a specific device_id was requested, verify it exists
            if self.device_id:
                device_ids = (
                    [d.split("\t")[0] for d in devices]
                    if tool_cmd == "adb"
                    else [d.strip() for d in devices]
                )
                if self.device_id not in device_ids:
                    return (
                        f"Error: Specified device '{self.device_id}' not found.\n"
                        f"Connected devices: {', '.join(device_ids)}"
                    )

        except asyncio.TimeoutError:
            return f"Error: {tool_cmd.upper()} command timed out."
        except Exception as e:
            return f"Error checking device: {e}"

        # Check 3: ADB Keyboard (ADB only, following main.py logic)
        if tool_cmd == "adb":
            try:
                device_arg = ["-s", self.device_id] if self.device_id else []
                proc = await asyncio.create_subprocess_exec(
                    "adb", *device_arg, "shell", "ime", "list", "-s",
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE,
                )
                stdout, _ = await asyncio.wait_for(proc.communicate(), timeout=10)
                ime_list = stdout.decode("utf-8", errors="replace")

                if "com.android.adbkeyboard/.AdbIME" not in ime_list:
                    return (
                        "Error: ADB Keyboard is not installed on the device.\n"
                        "Please install it:\n"
                        "  1. Download APK from https://github.com/senzhk/ADBKeyBoard\n"
                        "  2. adb install ADBKeyboard.apk\n"
                        "  3. Enable it in Settings > Languages & Input > Virtual Keyboard"
                    )
            except asyncio.TimeoutError:
                return "Error: ADB keyboard check timed out."
            except Exception as e:
                return f"Error checking ADB keyboard: {e}"

        return None  # All checks passed
