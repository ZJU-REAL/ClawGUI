"""
Preprocess data for RealDevice online RL training.

Unlike MobileWorld (which loads tasks from an Excel file), RealDevice tasks
are defined inline as free-form natural-language instructions for a physical phone.

No ground_truth, Task Name, or Apps needed — just the question (task goal).

Usage:
  python realdevice_onlinerl.py \
      --task_file /path/to/tasks.txt \
      --total_epochs 3

  # Or use inline default tasks (for quick testing):
  python realdevice_onlinerl.py --total_epochs 3
"""

import os
import argparse
import datasets
import pandas as pd
from verl.utils.hdfs_io import copy, makedirs


def load_tasks_from_file(task_file: str):
    """
    Load tasks from a text file. Each non-empty, non-comment line is one task.
    Format: task_name,question  (comma-separated, first field is task_name, rest is question)
    Lines starting with '#' are comments and will be skipped.
    
    Returns:
        List of (task_name, question) tuples.
    """
    tasks = []
    with open(task_file, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith('#'):
                # Split on first comma: task_name,question
                if ',' in line:
                    task_name, question = line.split(',', 1)
                    tasks.append((task_name.strip(), question.strip()))
                else:
                    # Fallback: no comma means entire line is the question, task_name = 'unknown_task'
                    tasks.append(('unknown_task', line))
    return tasks


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Preprocess data for RealDevice online RL')
    parser.add_argument('--mode', default='visual', choices=['visual', 'text'])
    parser.add_argument('--local_dir', default='~/data/realdevice_online_rl/')
    parser.add_argument('--hdfs_dir', default=None)
    parser.add_argument('--task_file', default=None, type=str,
                        help='Path to a .txt file with one task per line. If not provided, uses inline defaults.')
    parser.add_argument('--val_data_size', default=1, type=int)
    parser.add_argument('--total_epochs', default=3, type=int,
                        help='Total training epochs — tasks will be repeated this many times')
    parser.add_argument('--data_source', default=None,
                        help='Path to geometry3k dataset (default: ~/data/geometry3k)')

    args = parser.parse_args()
    print(f"[RealDevice Data] Processing data for mode: {args.mode}")
    args.local_dir = os.path.expanduser(os.path.join(args.local_dir, args.mode))

    # ============================================================
    # Load tasks
    # ============================================================
    if args.task_file and os.path.exists(args.task_file):
        tasks = load_tasks_from_file(args.task_file)
        print(f"[RealDevice Data] Loaded {len(tasks)} tasks from {args.task_file}")
    else:
        # Inline default tasks for quick testing (task_name, question)
        tasks = [
            ("小红书", "请帮我打开小红书App,搜索GUI-G2，查看第一篇关于GUI-G2的帖子。并点个赞。"),
            ("小红书", "请帮我搜索浙江大学，并查看第一个帖子，无需滚动查看。"),
        ]
        print(f"[RealDevice Data] Using {len(tasks)} inline default tasks")

    # Repeat for total_epochs
    if args.total_epochs > 1:
        single_epoch_tasks = list(tasks)
        for ep in range(1, args.total_epochs):
            tasks.extend(single_epoch_tasks)
        print(f"[RealDevice Data] Repeated tasks x{args.total_epochs} epochs → {len(tasks)} total tasks")

    train_data_size = len(tasks)
    print(f"\n=== RealDevice task list ({train_data_size} tasks) ===")
    for i, (task_name, question) in enumerate(tasks):
        print(f"  [{i}] [{task_name}] {question}")
    print("=== End of task list ===\n")

    # ============================================================
    # Build parquet from a dummy HuggingFace dataset
    # (Same approach as mw_onlinerl.py — we only use the dataset to
    #  get the correct schema; actual task data comes from env_kwargs)
    # ============================================================
    data_source = args.data_source if args.data_source else os.path.expanduser('~/data/geometry3k')
    dataset = datasets.load_dataset(data_source)

    train_dataset = dataset['train'].select(range(train_data_size))
    test_dataset = dataset['test'].select(range(args.val_data_size))

    instruction_following = {
        "visual": "<image>",
        "text": "",
    }

    def make_map_fn(split):
        def process_fn(example, idx):
            problem = example.pop('problem')
            prompt = instruction_following[args.mode]
            images = example.pop('images')

            # Get task (task_name, question) tuple
            if idx < len(tasks):
                task_name, question = tasks[idx]
            else:
                task_name, question = "unknown_task", ""

            data_source_tagged = args.mode

            data = {
                "data_source": args.mode,
                "prompt": [{
                    "role": "user",
                    "content": prompt,
                }],
                "images": images,
                "ability": "agent",
                "env_kwargs": {
                    "question": question,
                    "task_name": task_name,
                    "data_source": data_source_tagged,
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

    os.makedirs(local_dir, exist_ok=True)
    train_dataset.to_parquet(os.path.join(local_dir, 'train.parquet'))
    test_dataset.to_parquet(os.path.join(local_dir, 'test.parquet'))

    print(f"\n[RealDevice Data] Saved {train_data_size} train + {args.val_data_size} val samples to {local_dir}")

    if hdfs_dir is not None:
        makedirs(hdfs_dir)
        copy(src=local_dir, dst=hdfs_dir)
