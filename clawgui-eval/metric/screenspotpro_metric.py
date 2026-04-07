"""ScreenSpot-Pro metric calculation."""

import itertools
import argparse
import os
from typing import Dict, List, Any, Optional
from base_metric import BaseMetric


class ScreenSpotProMetric(BaseMetric):
    """Metric calculator for ScreenSpot-Pro benchmark."""
    
    def collect_results_to_eval(
        self, 
        results: List[Dict],
        platform: Optional[str] = None,
        group: Optional[str] = None,
        application: Optional[str] = None,
        ui_type: Optional[str] = None
    ) -> List[Dict]:
        """
        Filter results based on provided criteria.
        
        Args:
            results: List of result dictionaries
            platform: Platform filter (None means no filter)
            group: Group filter
            application: Application filter
            ui_type: UI type filter (text/icon)
            
        Returns:
            Filtered list of results
        """
        filtered_results = []
        for sample in results:
            if (platform is None or sample.get("platform") == platform) and \
               (group is None or sample.get("group") == group) and \
               (application is None or sample.get("application") == application) and \
               (ui_type is None or sample.get("ui_type") == ui_type):
                filtered_results.append(sample)
        return filtered_results
    
    def make_combinations(
        self,
        results: List[Dict],
        platform: bool = False,
        group: bool = False,
        application: bool = False,
        ui_type: bool = False
    ) -> List[Dict[str, str]]:
        """
        Generate combinations of attribute values for fine-grained evaluation.
        
        Returns:
            List of dictionaries with attribute combinations
        """
        unique_values = {
            "platform": set(),
            "group": set(),
            "application": set(),
            "ui_type": set(),
        }
        
        for sample in results:
            if platform:
                unique_values["platform"].add(sample.get("platform"))
            if group:
                unique_values["group"].add(sample.get("group"))
            if application:
                unique_values["application"].add(sample.get("application"))
            if ui_type:
                unique_values["ui_type"].add(sample.get("ui_type"))
        
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
        text_results = self.collect_results_to_eval(results, ui_type="text")
        icon_results = self.collect_results_to_eval(results, ui_type="icon")
        
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
    
    def evaluate_by_group(self, results: List[Dict]) -> Dict[str, Dict]:
        """Evaluate by group (Dev, Design, Daily)."""
        combinations = self.make_combinations(results, group=True)
        evaluation_result = {}
        
        for combo in combinations:
            group = combo.get("group")
            filtered_results = self.collect_results_to_eval(results, group=group)
            metrics = self.calc_metric_for_result_list(filtered_results)
            if metrics['num_total'] == 0:
                continue
            evaluation_result[f"group:{group}"] = metrics
        
        return evaluation_result
    
    def evaluate_by_application(self, results: List[Dict]) -> Dict[str, Dict]:
        """Evaluate by application."""
        combinations = self.make_combinations(results, application=True)
        evaluation_result = {}
        
        for combo in combinations:
            application = combo.get("application")
            filtered_results = self.collect_results_to_eval(results, application=application)
            metrics = self.calc_metric_for_result_list(filtered_results)
            if metrics['num_total'] == 0:
                continue
            evaluation_result[f"app:{application}"] = metrics
        
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
    
    def evaluate_overall(self, results: List[Dict]) -> Dict[str, Any]:
        """Calculate overall metrics."""
        return self.calc_metric_for_result_list(results)
    
    def calculate_metrics(self) -> Dict[str, Any]:
        """
        Calculate all metrics for ScreenSpot-Pro.
        
        Returns:
            Dictionary containing various metric breakdowns
        """
        results = self.data
        
        metrics = {
            "overall": self.evaluate_overall(results),
            "by_group": self.evaluate_by_group(results),
            "by_application": self.evaluate_by_application(results),
            "by_platform": self.evaluate_by_platform(results),
        }
        
        # Print summary
        print("\n" + "="*50)
        print("ScreenSpot-Pro Evaluation Results")
        print("="*50)
        print(f"Overall Accuracy: {metrics['overall']['accuracy']:.2f}%")
        print(f"  - Text Accuracy: {metrics['overall']['text_acc']:.2f}%")
        print(f"  - Icon Accuracy: {metrics['overall']['icon_acc']:.2f}%")
        print(f"Total Samples: {metrics['overall']['num_total']}")
        print("="*50 + "\n")
        
        return metrics


def parse_args():
    parser = argparse.ArgumentParser(description='Calculate metrics for ScreenSpot-Pro benchmark')
    parser.add_argument('--input_file', type=str, required=True,
                        help='Path to input predictions file (jsonl format)')
    parser.add_argument('--output_file', type=str, required=True,
                        help='Path to output metrics file (json format)')
    parser.add_argument('--exp_name', type=str, default='experiment',
                        help='Experiment name for logging')
    parser.add_argument('--benchmark', type=str, default='screenspot-pro',
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
    metric_calculator = ScreenSpotProMetric(
        input_file=args.input_file,
        output_file=args.output_file
    )
    
    metrics = metric_calculator.run()
    
    print(f"\nMetrics saved to: {args.output_file}")


if __name__ == "__main__":
    main()
