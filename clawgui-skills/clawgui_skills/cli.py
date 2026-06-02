"""Command line helpers for inspecting ClawGUI skill stores."""

from __future__ import annotations

import argparse
import json

from clawgui_skills.config import SkillRuntimeConfig
from clawgui_skills.runtime import SkillRuntime


def main() -> None:
    parser = argparse.ArgumentParser(prog="clawgui-skills")
    parser.add_argument("--store", default="skill_store", help="Skill store directory")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("list", help="List skills")
    detail = sub.add_parser("show", help="Show one skill")
    detail.add_argument("skill_id")

    args = parser.parse_args()
    runtime = SkillRuntime(SkillRuntimeConfig(mode="reuse", store_dir=args.store))

    if args.command == "list":
        print(json.dumps(runtime.list_skill_summaries(), ensure_ascii=False, indent=2))
    elif args.command == "show":
        print(runtime.render_skill_detail(args.skill_id))


if __name__ == "__main__":
    main()
