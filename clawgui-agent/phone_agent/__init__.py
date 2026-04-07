"""
Phone Agent - An AI-powered phone automation framework.

This package provides tools for automating Android and iOS phone interactions
using AI models for visual understanding and decision making.

Now with personalized memory support for learning user preferences
and providing more intelligent assistance.
"""

from phone_agent.agent import PhoneAgent
from phone_agent.agent_ios import IOSPhoneAgent

# Memory module (optional)
try:
    from phone_agent.memory import MemoryManager, MemoryStore, MemoryType
    HAS_MEMORY = True
except ImportError:
    HAS_MEMORY = False
    MemoryManager = None
    MemoryStore = None
    MemoryType = None

__version__ = "0.1.0"
__all__ = ["PhoneAgent", "IOSPhoneAgent", "MemoryManager", "MemoryStore", "MemoryType"]
