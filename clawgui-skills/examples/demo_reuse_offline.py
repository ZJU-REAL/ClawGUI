"""Offline demo: create/retrieve a skill without a phone."""

from clawgui_skills.config import SkillRuntimeConfig
from clawgui_skills.runtime import SkillRuntime


def main() -> None:
    runtime = SkillRuntime(SkillRuntimeConfig(mode="evolve", store_dir="demo_skill_store"))
    first = runtime.prepare(task="Open Settings and turn on Bluetooth", current_app="Settings")
    print(first.summary)

    runtime.finish(task="Open Settings and turn on Bluetooth", success=True, result="offline success")

    reuse = runtime.prepare(task="Open Settings and enable Bluetooth", current_app="Settings")
    print(reuse.summary)
    print(reuse.context[:600])


if __name__ == "__main__":
    main()
