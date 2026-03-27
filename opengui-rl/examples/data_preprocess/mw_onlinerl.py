# Copyright 2024 Bytedance Ltd. and/or its affiliates
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""
Preprocess the Geometry3k dataset to parquet format

Three-stage curriculum learning with easy / medium (MAI-UI-8B) / hard tasks.

Usage:
  # Two-stage (easy + hard only, no medium):
  python mw_onlinerl.py --curriculum --curriculum_mode interleave --hard_task_num 50 --exclude_google \
      --batch_size 4 --total_epochs 3

  # Three-stage (easy + medium + hard):
  python mw_onlinerl.py --curriculum --curriculum_mode interleave --hard_task_num 15 --exclude_google \
      --batch_size 4 --total_epochs 3

"""

import os
import datasets
import pandas as pd

from verl.utils.hdfs_io import copy, makedirs
import argparse

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--mode', default='visual', choices=['visual', 'text'])
    parser.add_argument('--local_dir', default='~/data/mw_online_rl/')
    parser.add_argument('--hdfs_dir', default=None)
    parser.add_argument('--train_data_size', default=120, type=int)
    parser.add_argument('--val_data_size', default=1, type=int)
    parser.add_argument('--curriculum', action='store_true', help='Enable curriculum learning: put easy tasks first')
    parser.add_argument('--curriculum_mode', default='interleave', choices=['front', 'interleave'],
                        help='Curriculum strategy: front=all easy first, interleave=progressive interleaving')
    parser.add_argument('--hard_task_num', default=50, type=int, help='Number of hard tasks to sample')
    parser.add_argument('--exclude_google', action='store_true', help='Exclude tasks whose Apps column contains Chrome')
    parser.add_argument('--batch_size', default=2, type=int, help='Training batch size per step (for curriculum scheduling)')
    parser.add_argument('--total_epochs', default=3, type=int, help='Total training epochs (for curriculum scheduling)')
    parser.add_argument('--task_file', default='../env_server/mobileworld_tasks.xlsx', help='Path to MobileWorld tasks xlsx file')
    parser.add_argument('--data_source', default=None, help='Path to geometry3k dataset (default: ~/data/geometry3k)')

    args = parser.parse_args()
    print(f"processing data for mode: {args.mode}")
    args.local_dir = os.path.join(args.local_dir, args.mode)

    # Load MobileWorld tasks
    mw_tasks_path = args.task_file
    mw_tasks_df = pd.read_excel(mw_tasks_path)
    
    # Filter out tasks containing "mcp" or "interaction" in tag column
    # Try different possible column names for tag
    tag_column = None
    for col in ['tag', 'Tag', 'tags', 'Tags', 'TAG']:
        if col in mw_tasks_df.columns:
            tag_column = col
            break
    
    if tag_column is not None:
        # Filter out rows where tag contains "mcp" or "interaction" (case-insensitive)
        mask = mw_tasks_df[tag_column].astype(str).str.lower().str.contains('mcp|interaction', na=False)
        mw_tasks_df = mw_tasks_df[~mask].reset_index(drop=True)
        print(f"Filtered tasks: {len(mw_tasks_df)} tasks remaining after removing 'mcp' and 'interaction'")

    # Exclude tasks whose Apps column contains Chrome
    if args.exclude_google:
        apps_column = None
        for col in ['Apps', 'apps', 'APPS']:
            if col in mw_tasks_df.columns:
                apps_column = col
                break
        if apps_column is not None:
            chrome_mask = mw_tasks_df[apps_column].astype(str).str.contains('Chrome', case=False, na=False)
            mw_tasks_df = mw_tasks_df[~chrome_mask].reset_index(drop=True)
            print(f"Filtered tasks: {len(mw_tasks_df)} tasks remaining after excluding Chrome tasks")

    # Exclude specific tasks by Task Name
    exclude_task_names = ['MattermostEmailTask']
    task_name_col_filter = None
    for col in ['Task Name', 'task_name']:
        if col in mw_tasks_df.columns:
            task_name_col_filter = col
            break
    if task_name_col_filter is not None:
        exclude_mask = mw_tasks_df[task_name_col_filter].isin(exclude_task_names)
        mw_tasks_df = mw_tasks_df[~exclude_mask].reset_index(drop=True)
        print(f"Filtered tasks: {len(mw_tasks_df)} tasks remaining after excluding {exclude_task_names}")

    # Curriculum learning
    if args.curriculum:
        # ============================================================
        # Three-tier task classification:
        #   easy_tasks:   MAI-UI-2B can solve (13 tasks, used as curriculum anchors)
        #   medium_tasks: MAI-UI-8B can solve but NOT in easy (18 tasks)
        #   hard_tasks:   Neither 2B nor 8B can solve (sampled via --hard_task_num)
        #
        # Training flow (interleave mode):
        #   Phase M (medium phase): medium tasks + easy interleaved
        #     - M1: easy:medium ≈ 2:1 (lots of easy for warm-up reward)
        #     - M2: easy:medium ≈ 1:1
        #     - M3: easy:medium ≈ 1:2 (more medium as model improves)
        #   Phase H (hard phase): hard tasks + easy interleaved
        #     - H1: easy:hard ≈ 2:1 (re-warm up with easy)
        #     - H2: easy:hard ≈ 1:1
        #     - H3: easy:hard ≈ 1:2
        #     - H4: easy:hard ≈ 1:3
        # ============================================================

        # --- Easy tasks: MAI-UI-2B can solve ---
        curriculum_tasks = [
            'OpenFlightModeTask',
            'CloseFlightModeTask',
            'AcceptMeetingTask',
            'CancelMeetingTask',
            'CheckPuchasedItem',
            'AdjustFontIconMinimumTask',
            'CheckDepartTimeTask',
            'MastodonChangeHeaderTask',
            'CheckRegistrationTask',
            'MastodonNewPostTask',
            'SendWaiverTask',
            'CheckSetMeetTimeTask',
            'MattermostCreateChannelTask',
        ]

        # --- Medium tasks: MAI-UI-8B can solve (excluding overlap with easy) ---
        #            'SharePhotosTask',
        mai_ui_8b_tasks = [
            'AcceptMeetingTask',
            'BidFileRenameTask',
            'CancelMeetingTask',
            'CartManagementTask',
            'CheckConferenceAndSendSmsTask2',
            'CheckEventTimeTask',
            'CheckInvoiceTask3',
            'CheckSetMeetTimeTask',
            'MastodonCreateListTask',
            'MastodonCreateMemoTask',
            'MastodonExportFollowsTask',
            'MastodonFollowTask',
            'ReadQwen3PaperTask4',
            'RequestCarpoolingTask',
            'ScheduleCoffeeTimeViaSmsTask',
            'MastodonNewPostTask',
            'MastodonReplyTask',
            'ScheduleLunchViaSmsTask',
            'SearchItemAndCheckoutTask',
            'SetAlarmTask',
            'TakeSelfieTask',
        ]
        # Remove overlap: medium = MAI-UI-8B tasks that are NOT in easy
        medium_tasks = [t for t in mai_ui_8b_tasks if t not in curriculum_tasks]
        # All tasks to exclude from hard sampling
        excluded_from_hard = set(curriculum_tasks) | set(medium_tasks)

        task_name_col = 'Task Name' if 'Task Name' in mw_tasks_df.columns else None
        if task_name_col is not None:
            # Split into easy / medium / hard DataFrames
            easy_df = mw_tasks_df[mw_tasks_df[task_name_col].isin(curriculum_tasks)]
            curriculum_order = {name: i for i, name in enumerate(curriculum_tasks)}
            easy_df = easy_df.copy()
            easy_df['_curriculum_order'] = easy_df[task_name_col].map(curriculum_order)
            easy_df = easy_df.sort_values('_curriculum_order').drop(columns=['_curriculum_order'])

            medium_df = mw_tasks_df[mw_tasks_df[task_name_col].isin(medium_tasks)]
            medium_df = medium_df.copy().reset_index(drop=True)

            # Hard = everything not in easy and not in medium
            remaining_df = mw_tasks_df[~mw_tasks_df[task_name_col].isin(excluded_from_hard)]
            hard_task_num = min(args.hard_task_num, len(remaining_df))
            hard_df = remaining_df.sample(n=hard_task_num, random_state=42).reset_index(drop=True)

            print(f"\n=== Task classification ===")
            print(f"  easy tasks (curriculum): {len(easy_df)} — {list(easy_df[task_name_col])}")
            print(f"  medium tasks (MAI-UI-8B only): {len(medium_df)} — {list(medium_df[task_name_col])}")
            print(f"  hard tasks (sampled {hard_task_num} from {len(remaining_df)} remaining): {list(hard_df[task_name_col])}")
            print(f"=== End classification ===\n")

            if args.curriculum_mode == 'front':
                # Original mode: all easy first, then medium, then hard
                mw_tasks_df = pd.concat([easy_df, medium_df, hard_df], ignore_index=True)
                print(f"Curriculum [front]: {len(easy_df)} easy, {len(medium_df)} medium, {len(hard_df)} hard")

            elif args.curriculum_mode == 'interleave':
                # ============================================================
                # Three-stage interleave curriculum (先易后难, classic CL)
                #
                # Phase M: Train on medium tasks with easy interleaving
                #   - Uses the v1 proven 4-phase approach (easy:target ratios)
                #   - Model learns from tasks 8B can solve → obtainable reward
                #
                # Phase H: Train on hard tasks with easy interleaving
                #   - Same 4-phase structure
                #   - Model now has medium-level ability, ready for hard
                #
                # Easy tasks are cycled via itertools.cycle throughout both phases.
                # ============================================================
                import itertools

                n_medium = len(medium_df)
                n_hard = len(hard_df)
                n_easy_unique = len(easy_df)

                # --- Phase M: medium tasks with easy interleaving ---
                # Split medium tasks into 3 sub-phases
                m_phase_sizes = [n_medium // 3, n_medium // 3, n_medium - 2 * (n_medium // 3)]
                # easy:medium ratios — start with lots of easy (先易后难)
                m_phase_ratios = [2.0, 1.0, 0.5]
                m_phase_names = ['PhaseM1(easy:med=2:1)', 'PhaseM2(easy:med=1:1)', 'PhaseM3(easy:med=1:2)']

                # --- Phase H: hard tasks with easy interleaving ---
                # Split hard tasks into 4 sub-phases (same as v1 backup)
                h_phase_sizes = [n_hard // 4, n_hard // 4, n_hard // 4, n_hard - 3 * (n_hard // 4)]
                h_phase_ratios = [2.0, 1.0, 0.5, 0.33]
                h_phase_names = ['PhaseH1(easy:hard=2:1)', 'PhaseH2(easy:hard=1:1)', 'PhaseH3(easy:hard=1:2)', 'PhaseH4(easy:hard=1:3)']

                interleaved = []
                easy_cycle = itertools.cycle(range(n_easy_unique))

                def build_phase(target_df, target_cursor, phase_sizes, phase_ratios, phase_names, label):
                    """Build interleaved sequence for a set of phases.
                    Returns updated target_cursor."""
                    for phase_idx, (p_size, ratio) in enumerate(zip(phase_sizes, phase_ratios)):
                        phase_target = target_df.iloc[target_cursor:target_cursor + p_size]
                        target_cursor += p_size

                        if ratio >= 1.0:
                            # Insert ceil(ratio) easy tasks before each target task
                            n_easy_per_target = int(ratio)
                            for t_idx in range(len(phase_target)):
                                for _ in range(n_easy_per_target):
                                    interleaved.append(easy_df.iloc[next(easy_cycle)])
                                interleaved.append(phase_target.iloc[t_idx])
                        else:
                            # Insert 1 easy task every ceil(1/ratio) target tasks
                            target_per_easy = round(1.0 / ratio)
                            for t_idx in range(len(phase_target)):
                                if t_idx % target_per_easy == 0:
                                    interleaved.append(easy_df.iloc[next(easy_cycle)])
                                interleaved.append(phase_target.iloc[t_idx])

                        total_in_phase = len(phase_target) + (
                            int(ratio) * len(phase_target) if ratio >= 1.0
                            else len(phase_target) // round(1.0 / ratio) + (1 if len(phase_target) % round(1.0 / ratio) > 0 else 0)
                        )
                        print(f"  {phase_names[phase_idx]}: {p_size} {label} tasks, "
                              f"easy:{label} ratio ≈ {ratio}")

                    return target_cursor

                # Build Phase M (medium)
                print(f"\n=== Curriculum [interleave] three-stage schedule ===")
                print(f"  batch_size (B) = {args.batch_size}")
                print(f"  total_epochs = {args.total_epochs}")
                print(f"  easy={n_easy_unique}, medium={n_medium}, hard={n_hard}")
                print(f"\n--- Phase M: medium tasks (MAI-UI-8B can solve) ---")
                build_phase(medium_df, 0, m_phase_sizes, m_phase_ratios, m_phase_names, 'medium')
                phase_m_end = len(interleaved)

                # Build Phase H (hard)
                print(f"\n--- Phase H: hard tasks ---")
                build_phase(hard_df, 0, h_phase_sizes, h_phase_ratios, h_phase_names, 'hard')
                phase_h_end = len(interleaved)

                # If total_epochs > 1, repeat the entire sequence
                if args.total_epochs > 1:
                    single_epoch = list(interleaved)
                    for ep in range(1, args.total_epochs):
                        interleaved.extend(single_epoch)
                    print(f"\n  Repeated sequence {args.total_epochs} times for {args.total_epochs} epochs")

                mw_tasks_df = pd.DataFrame(interleaved).reset_index(drop=True)

                total_easy_count = sum(1 for _, row in mw_tasks_df.iterrows()
                                       if row[task_name_col] in curriculum_tasks)
                total_medium_count = sum(1 for _, row in mw_tasks_df.iterrows()
                                         if row[task_name_col] in medium_tasks)
                total_hard_count = len(mw_tasks_df) - total_easy_count - total_medium_count

                print(f"\n  Summary:")
                print(f"    Phase M length (1 epoch): {phase_m_end} tasks")
                print(f"    Phase H length (1 epoch): {phase_h_end - phase_m_end} tasks")
                print(f"    Total sequence: {len(mw_tasks_df)} tasks")
                print(f"    Breakdown: {total_easy_count} easy + {total_medium_count} medium + {total_hard_count} hard")
                print(f"  NOTE: set trainer.total_epochs=1 in training (all epochs pre-baked)")
                print(f"=== End curriculum schedule ===\n")
    
    # Print final task list in order
    print(f"\n=== Final task list ({len(mw_tasks_df)} tasks) ===")
    for i, row in mw_tasks_df.iterrows():
        task_name = row['Task Name'] if 'Task Name' in mw_tasks_df.columns else 'N/A'
        apps = row['Apps'] if 'Apps' in mw_tasks_df.columns else 'N/A'
        print(f"  [{i}] {task_name} | Apps: {apps}")
    print("=== End of task list ===\n")

    # Auto-adjust train_data_size to match the actual number of tasks
    actual_task_count = len(mw_tasks_df)
    if actual_task_count != args.train_data_size:
        print(f"Auto-adjusting train_data_size: {args.train_data_size} -> {actual_task_count}")
        args.train_data_size = actual_task_count

    data_source = args.data_source if args.data_source else os.path.expanduser('~/data/geometry3k')
    """
    **NOTE**: This is a frequently asked question.
    We do NOT use the data in 'hiyouga/geometry3k', instead we only use it to indicate the modality and the data size.
    See details: https://github.com/langfengQ/verl-agent?tab=readme-ov-file#2-data-preparation
    """
    # huggingface-cli download --repo-type dataset --resume-download hiyouga/geometry3k --local-dir /home/tangfei/online_rl/verl-agent/data/geometry3k
    dataset = datasets.load_dataset(data_source)

    train_dataset = dataset['train'].select(range(args.train_data_size))
    test_dataset = dataset['test'].select(range(args.val_data_size))

    instruction_following = {
        "visual": "<image>",
        "text": "",
        }

    # add a row to each data item that represents a unique id
    def make_map_fn(split):

        def process_fn(example, idx):
            problem = example.pop('problem')
            prompt = instruction_following[args.mode]
            # answer = example.pop('answer')
            images = example.pop('images')

            # Get task info from MobileWorld tasks
            if idx < len(mw_tasks_df):
                question = mw_tasks_df.iloc[idx]['Goal'] if 'Goal' in mw_tasks_df.columns else ""
                task_name = mw_tasks_df.iloc[idx]['Task Name'] if 'Task Name' in mw_tasks_df.columns else ""
                apps = mw_tasks_df.iloc[idx]['Apps'] if 'Apps' in mw_tasks_df.columns else ""
            else:
                question = ""
                task_name = ""
                apps = ""
            
            ground_truth = None
            data_source_tagged = args.mode

            if args.mode == 'visual':
                data = {
                    "data_source": args.mode,
                    "prompt": [{
                        "role": "user",
                        "content": prompt,
                    }],
                    "images": images,
                    "ability": "agent",
                    "env_kwargs": {
                        "ground_truth": ground_truth,
                        "question": question,
                        "data_source": data_source_tagged,
                        "Task Name": task_name,
                        "Apps": apps
                    },
                    "extra_info": {
                        'split': split,
                        'index': idx,
                    }
                }
            else:
                data = {
                    "data_source": args.mode,
                    "prompt": [{
                        "role": "user",
                        "content": prompt,
                    }],
                    "ability": "agent",
                    "env_kwargs": {
                        "ground_truth": ground_truth,
                        "question": question,
                        "data_source": data_source_tagged,
                        "Task Name": task_name,
                        "Apps": apps
                    },
                    "extra_info": {
                        'split': split,
                        'index': idx,
                    }
                }
            return data

        return process_fn

    train_dataset = train_dataset.map(function=make_map_fn('train'), with_indices=True, num_proc=8)
    test_dataset = test_dataset.map(function=make_map_fn('test'), with_indices=True, num_proc=8)

    local_dir = args.local_dir
    hdfs_dir = args.hdfs_dir

    train_dataset.to_parquet(os.path.join(local_dir, 'train.parquet'))
    test_dataset.to_parquet(os.path.join(local_dir, 'test.parquet'))

    if hdfs_dir is not None:
        makedirs(hdfs_dir)
        copy(src=local_dir, dst=hdfs_dir)
