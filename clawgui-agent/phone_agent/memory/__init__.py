"""
Personalized Memory Module for GUI Agent.

This module provides long-term memory capabilities for the phone agent,
enabling it to learn user preferences, habits, and past interactions.

Inspired by TeleMem (https://github.com/TeleAI-UAGI/TeleMem)
"""

from .memory_store import MemoryStore, Memory, MemoryType
from .memory_manager import MemoryManager

__all__ = [
    "MemoryStore",
    "Memory",
    "MemoryType",
    "MemoryManager",
]




