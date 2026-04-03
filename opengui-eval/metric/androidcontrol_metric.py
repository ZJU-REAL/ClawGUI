"""AndroidControl metric calculation."""

import argparse
import os
from typing import Dict, List, Any
from collections import defaultdict
from base_metric import BaseMetric


class AndroidControlMetric(BaseMetric):
    """Metric calculator for AndroidControl benchmark."""
    
    def calculate_metrics(self) -> Dict[str, Any]:
        """
        Calculate all metrics for AndroidControl.
        
        Returns:
            Dictionary containing various metric breakdowns
        """
        results = self.data
        
        # 整体统计
        total = len(results)
        correct = sum(1 for r in results if r.get("correct", False))
        type_match = sum(1 for r in results if r.get("type_match", False))
        match_success = sum(1 for r in results if r.get("match_success", False))
        
        # 按action类型统计
        action_stats = defaultdict(lambda: {"total": 0, "correct": 0, "type_match": 0, "match_success": 0})
        
        # Grounding统计 (click + long_press)
        grounding_total = 0
        grounding_correct = 0
        
        for r in results:
            gt_action = r.get("gt_action")
            if not gt_action:
                continue
                
            action_stats[gt_action]["total"] += 1
            if r.get("correct", False):
                action_stats[gt_action]["correct"] += 1
            if r.get("type_match", False):
                action_stats[gt_action]["type_match"] += 1
            if r.get("match_success", False):
                action_stats[gt_action]["match_success"] += 1
            
            # Grounding统计
            if gt_action in ["click", "long_press"]:
                grounding_total += 1
                if r.get("correct", False):
                    grounding_correct += 1
        
        # 构建每个action的详细metric
        action_metrics = {}
        for action, stats in sorted(action_stats.items()):
            action_metrics[action] = {
                "total": stats["total"],
                "correct": stats["correct"],
                "type_match": stats["type_match"],
                "match_success": stats["match_success"],
                "accuracy": round(stats["correct"] / stats["total"] * 100, 2) if stats["total"] > 0 else 0,
                "type_acc": round(stats["type_match"] / stats["total"] * 100, 2) if stats["total"] > 0 else 0,
                "match_acc": round(stats["match_success"] / stats["total"] * 100, 2) if stats["total"] > 0 else 0,
            }
        
        # 构建整体metric
        metrics = {
            "overall": {
                "total": total,
                "correct": correct,
                "type_match": type_match,
                "match_success": match_success,
                "accuracy": round(correct / total * 100, 2) if total > 0 else 0,
                "type_acc": round(type_match / total * 100, 2) if total > 0 else 0,
                "match_acc": round(match_success / total * 100, 2) if total > 0 else 0,
            },
            "grounding": {
                "total": grounding_total,
                "correct": grounding_correct,
                "accuracy": round(grounding_correct / grounding_total * 100, 2) if grounding_total > 0 else 0,
            },
            "by_action": action_metrics,
        }
        
        # 打印结果
        self.print_metrics(metrics)
        
        return metrics
    
    def print_metrics(self, metrics: Dict[str, Any]):
        """Print metrics in a readable format."""
        print("\n" + "="*60)
        print("AndroidControl Evaluation Results")
        print("="*60)
        
        # 整体结果
        overall = metrics["overall"]
        print(f"\nOverall Metrics:")
        print(f"  Total Samples: {overall['total']}")
        print(f"  Accuracy:      {overall['accuracy']:.2f}% ({overall['correct']}/{overall['total']})")
        print(f"  Type Acc:      {overall['type_acc']:.2f}% ({overall['type_match']}/{overall['total']})")
        print(f"  Match Acc:     {overall['match_acc']:.2f}% ({overall['match_success']}/{overall['total']})")
        
        # Grounding结果
        grounding = metrics["grounding"]
        print(f"\nGrounding Metrics (click + long_press):")
        print(f"  Total:    {grounding['total']}")
        print(f"  Correct:  {grounding['correct']}")
        print(f"  Accuracy: {grounding['accuracy']:.2f}%")
        
        # 各action结果
        print(f"\nBy Action:")
        print("-" * 60)
        print(f"{'Action':<15} {'Total':>8} {'Correct':>8} {'Acc(%)':>8} {'Type(%)':>8} {'Match(%)':>8}")
        print("-" * 60)
        for action, stats in metrics["by_action"].items():
            print(f"{action:<15} {stats['total']:>8} {stats['correct']:>8} "
                  f"{stats['accuracy']:>8.2f} {stats['type_acc']:>8.2f} {stats['match_acc']:>8.2f}")
        print("="*60 + "\n")


def parse_args():
    parser = argparse.ArgumentParser(description='Calculate metrics for AndroidControl benchmark')
    parser.add_argument('--input_file', type=str, required=True,
                        help='Path to input predictions file (jsonl format)')
    parser.add_argument('--output_file', type=str, required=True,
                        help='Path to output metrics file (json format)')
    parser.add_argument('--exp_name', type=str, default='experiment',
                        help='Experiment name for logging')
    parser.add_argument('--benchmark', type=str, default='androidcontrol',
                        help='Benchmark name')
    return parser.parse_args()


def main():
    args = parse_args()
    
    # Check input file exists
    if not os.path.exists(args.input_file):
        raise FileNotFoundError(f"Input file not found: {args.input_file}")
    
    # Create output directory if needed
    os.makedirs(os.path.dirname(args.output_file), exist_ok=True)
    
    print(f"Experiment: {args.exp_name}")
    print(f"Benchmark: {args.benchmark}")
    print(f"Input: {args.input_file}")
    print(f"Output: {args.output_file}")
    print()
    
    # Calculate metrics
    metric_calculator = AndroidControlMetric(
        input_file=args.input_file,
        output_file=args.output_file
    )
    
    metrics = metric_calculator.run()
    
    print(f"\nMetrics saved to: {args.output_file}")


if __name__ == "__main__":
    main()
