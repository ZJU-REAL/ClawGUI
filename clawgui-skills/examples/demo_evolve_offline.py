"""Offline demo: failed run creates a revision and failure example."""

from clawgui_skills.config import SkillRuntimeConfig
from clawgui_skills.runtime import SkillRuntime


def main() -> None:
    runtime = SkillRuntime(SkillRuntimeConfig(mode="evolve", store_dir="demo_skill_store"))
    prepared = runtime.prepare(task="Open Calendar and create a meeting", current_app="Calendar")
    print(prepared.summary)

    finished = runtime.finish(
        task="Open Calendar and create a meeting",
        success=False,
        result="offline failure: max steps reached",
        trace_path=None,
    )
    print(finished.summary)
    print(finished.feedback)
    print(finished.edits)


if __name__ == "__main__":
    main()
