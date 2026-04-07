"""UIVision metric calculation."""

import itertools
import argparse
import os
from typing import Dict, List, Any, Optional
from base_metric import BaseMetric


class UIVisionMetric(BaseMetric):
    """Metric calculator for UIVision benchmark."""
    
    def collect_results_to_eval(
        self, 
        results: List[Dict],
        platform: Optional[str] = None,
        task_type: Optional[str] = None,
        category: Optional[str] = None,
        element_type: Optional[str] = None
    ) -> List[Dict]:
        """
        Filter results based on provided criteria.
        
        Args:
            results: List of result dictionaries
            platform: Platform filter (None means no filter)
            task_type: Task type filter (basic/functional/spatial)
            category: Category filter
            element_type: Element type filter (text/icon)
            
        Returns:
            Filtered list of results
        """
        filtered_results = []
        for sample in results:
            if (platform is None or sample.get("platform") == platform) and \
               (task_type is None or sample.get("task_type") == task_type) and \
               (category is None or sample.get("category") == category) and \
               (element_type is None or sample.get("element_type") == element_type):
                filtered_results.append(sample)
        return filtered_results
    
    def make_combinations(
        self,
        results: List[Dict],
        platform: bool = False,
        task_type: bool = False,
        category: bool = False,
        element_type: bool = False
    ) -> List[Dict[str, str]]:
        """
        Generate combinations of attribute values for fine-grained evaluation.
        
        Returns:
            List of dictionaries with attribute combinations
        """
        unique_values = {
            "platform": set(),
            "task_type": set(),
            "category": set(),
            "element_type": set(),
        }
        
        for sample in results:
            if platform:
                unique_values["platform"].add(sample.get("platform"))
            if task_type:
                unique_values["task_type"].add(sample.get("task_type"))
            if category:
                unique_values["category"].add(sample.get("category"))
            if element_type:
                unique_values["element_type"].add(sample.get("element_type"))
        
        filtered_values = {key: sorted(list(value)) for key, value in unique_values.items() if value}
        if not filtered_values:
            return []
        
        attribute_combinations = list(itertools.product(*filtered_values.values()))
        combinations = []
        for combination in attribute_combinations:
            combinations.append(dict(zip(filtered_values.keys(), combination)))
        
        return combinations
    
    def calc_metric_for_result_list(self, results: List[Dict]) -> Dict[str, Any]:
        """Calculate metrics for a result list."""
        num_total = len(results)
        correct_num = sum(1 for res in results if res.get("correct", False))
        
        # Calculate text and icon specific metrics
        text_results = self.collect_results_to_eval(results, element_type="text")
        icon_results = self.collect_results_to_eval(results, element_type="icon")
        
        text_correct = sum(1 for res in text_results if res.get("correct", False))
        text_total = len(text_results)
        icon_correct = sum(1 for res in icon_results if res.get("correct", False))
        icon_total = len(icon_results)
        
        metrics = {
            "num_correct": correct_num,
            "num_total": num_total,
            "accuracy": round(correct_num / num_total * 100, 2) if num_total > 0 else 0,
            "text_acc": round(text_correct / text_total * 100, 2) if text_total > 0 else 0,
            "icon_acc": round(icon_correct / icon_total * 100, 2) if icon_total > 0 else 0,
            "text_total": text_total,
            "icon_total": icon_total,
        }
        return metrics
    
    def evaluate_by_task_type(self, results: List[Dict]) -> Dict[str, Dict]:
        """Evaluate by task_type (basic, functional, spatial)."""
        combinations = self.make_combinations(results, task_type=True)
        evaluation_result = {}
        
        for combo in combinations:
            tt = combo.get("task_type")
            filtered_results = self.collect_results_to_eval(results, task_type=tt)
            metrics = self.calc_metric_for_result_list(filtered_results)
            if metrics['num_total'] == 0:
                continue
            evaluation_result[f"task_type:{tt}"] = metrics
        
        return evaluation_result
    
    def evaluate_by_platform(self, results: List[Dict]) -> Dict[str, Dict]:
        """Evaluate by platform."""
        combinations = self.make_combinations(results, platform=True)
        evaluation_result = {}
        
        for combo in combinations:
            platform = combo.get("platform")
            filtered_results = self.collect_results_to_eval(results, platform=platform)
            metrics = self.calc_metric_for_result_list(filtered_results)
            if metrics['num_total'] == 0:
                continue
            evaluation_result[f"platform:{platform}"] = metrics
        
        return evaluation_result
    
    def evaluate_by_category(self, results: List[Dict]) -> Dict[str, Dict]:
        """Evaluate by category."""
        combinations = self.make_combinations(results, category=True)
        evaluation_result = {}
        
        for combo in combinations:
            category = combo.get("category")
            filtered_results = self.collect_results_to_eval(results, category=category)
            metrics = self.calc_metric_for_result_list(filtered_results)
            if metrics['num_total'] == 0:
                continue
            evaluation_result[f"category:{category}"] = metrics
        
        return evaluation_result
    
    def evaluate_overall(self, results: List[Dict]) -> Dict[str, Any]:
        """Calculate overall metrics."""
        return self.calc_metric_for_result_list(results)
    
    def calculate_metrics(self) -> Dict[str, Any]:
        """
        Calculate all metrics for UIVision.
        
        Returns:
            Dictionary containing various metric breakdowns
        """
        results = self.data
        
        metrics = {
            "overall": self.evaluate_overall(results),
            "by_task_type": self.evaluate_by_task_type(results),
            "by_platform": self.evaluate_by_platform(results),
            "by_category": self.evaluate_by_category(results),
        }
        
        # Print summary
        print("\n" + "="*60)
        print("UIVision Evaluation Results")
        print("="*60)
        print(f"Overall Accuracy: {metrics['overall']['accuracy']:.2f}%")
        print(f"  - Text Accuracy: {metrics['overall']['text_acc']:.2f}%")
        print(f"  - Icon Accuracy: {metrics['overall']['icon_acc']:.2f}%")
        print(f"Total Samples: {metrics['overall']['num_total']}")
        print("-"*60)
        
        # Print per task_type metrics
        print("By Task Type:")
        for key, val in sorted(metrics['by_task_type'].items()):
            print(f"  {key}: {val['accuracy']:.2f}% ({val['num_correct']}/{val['num_total']})")
        
        print("="*60 + "\n")
        
        return metrics


def parse_args():
    parser = argparse.ArgumentParser(description='Calculate metrics for UIVision benchmark')
    parser.add_argument('--input_file', type=str, required=True,
                        help='Path to input predictions file (jsonl format)')
    parser.add_argument('--output_file', type=str, required=True,
                        help='Path to output metrics file (json format)')
    parser.add_argument('--exp_name', type=str, default='experiment',
                        help='Experiment name for logging')
    parser.add_argument('--benchmark', type=str, default='uivision',
                        help='Benchmark name')
    return parser.parse_args()


def main():
    args = parse_args()
    
    if not os.path.exists(args.input_file):
        raise FileNotFoundError(f"Input file not found: {args.input_file}")
    
    os.makedirs(os.path.dirname(args.output_file), exist_ok=True)
    
    print(f"Experiment: {args.exp_name}")
    print(f"Benchmark: {args.benchmark}")
    print(f"Input: {args.input_file}")
    print(f"Output: {args.output_file}")
    print()
    
    metric_calculator = UIVisionMetric(
        input_file=args.input_file,
        output_file=args.output_file
    )
    
    metrics = metric_calculator.run()
    
    print(f"\nMetrics saved to: {args.output_file}")


if __name__ == "__main__":
    main()
