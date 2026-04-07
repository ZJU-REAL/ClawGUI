"""Judge module for GUI grounding evaluation."""
from .base_judge import BaseJudge
from .grounding_judge import ScreenSpotJudge
from .osworld_g_judge import OSWorldGJudge
from .androidcontrol_judge import AndroidControlJudge

__all__ = ['BaseJudge', 'ScreenSpotJudge', 'OSWorldGJudge', "AndroidControlJudge"]
