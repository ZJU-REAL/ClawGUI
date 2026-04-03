"""ScreenSpot-V2 metric calculation."""

import itertools
import argparse
import os
from typing import Dict, List, Any, Optional
from base_metric import BaseMetric


class ScreenSpotV2Metric(BaseMetric):
    """
    Metric calculator for ScreenSpot-V2 benchmark.
    
    Reports metrics in the following format:
    
                Mobile          Desktop         Web             Avg
                Text  Icon/Widget  Text  Icon/Widget  Text  Icon/Widget
    """
    
    PLATFORMS = ["mobile", "desktop", "web"]
    UI_TYPES = ["text", "icon"]
    
    def collect_results_to_eval(
        self, 
        results: List[Dict],
        platform: Optional[str] = None,
        application: Optional[str] = None,
        ui_type: Optional[str] = None
    ) -> List[Dict]:
        """
        Filter results based on provided criteria.
        
        Args:
            results: List of result dictionaries
            platform: Platform filter (mobile/desktop/web)
            application: Application filter
            ui_type: UI type filter (text/icon)
            
        Returns:
            Filtered list of results
        """
        filtered_results = []
        for sample in results:
            if (platform is None or sample.get("platform") == platform) and \
               (application is None or sample.get("application") == application) and \
               (ui_type is None or sample.get("ui_type") == ui_type):
                filtered_results.append(sample)
        return filtered_results
    
    def make_combinations(
        self,
        results: List[Dict],
        platform: bool = False,
        application: bool = False,
        ui_type: bool = False
    ) -> List[Dict[str, str]]:
        """Generate combinations of attribute values for fine-grained evaluation."""
        unique_values = {
            "platform": set(),
            "application": set(),
            "ui_type": set(),
        }
        
        for sample in results:
            if platform:
                unique_values["platform"].add(sample.get("platform"))
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
    
    def calc_accuracy(self, results: List[Dict]) -> float:
        """Calculate accuracy for a list of results."""
        if len(results) == 0:
            return 0.0
        correct = sum(1 for r in results if r.get("correct", False))
        return round(correct / len(results) * 100, 2)
    
    def calc_metric_for_result_list(self, results: List[Dict]) -> Dict[str, Any]:
        """Calculate metrics for a result list."""
        num_total = len(results)
        correct_num = sum(1 for res in results if res.get("correct", False))
        
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
    
    def evaluate_by_platform_and_ui_type(self, results: List[Dict]) -> Dict[str, Dict]:
        """
        Evaluate by platform x ui_type combination.
        
        Returns a dict like:
        {
            "mobile_text": {"accuracy": ..., "num_correct": ..., "num_total": ...},
            "mobile_icon": {...},
            "desktop_text": {...},
            ...
        }
        """
        evaluation_result = {}
        for platform in self.PLATFORMS:
            for ui_type in self.UI_TYPES:
                filtered = self.collect_results_to_eval(results, platform=platform, ui_type=ui_type)
                num_total = len(filtered)
                num_correct = sum(1 for r in filtered if r.get("correct", False))
                acc = round(num_correct / num_total * 100, 2) if num_total > 0 else 0.0
                evaluation_result[f"{platform}_{ui_type}"] = {
                    "accuracy": acc,
                    "num_correct": num_correct,
                    "num_total": num_total,
                }
        return evaluation_result
    
    def evaluate_platform_avg(self, results: List[Dict]) -> Dict[str, float]:
        """Calculate per-platform average accuracy."""
        platform_avg = {}
        for platform in self.PLATFORMS:
            filtered = self.collect_results_to_eval(results, platform=platform)
            platform_avg[platform] = self.calc_accuracy(filtered)
        return platform_avg
    
    def evaluate_overall(self, results: List[Dict]) -> Dict[str, Any]:
        """Calculate overall metrics."""
        return self.calc_metric_for_result_list(results)
    
    def calculate_metrics(self) -> Dict[str, Any]:
        """
        Calculate all metrics for ScreenSpot-V2.
        
        Reports:
            Mobile (Text, Icon/Widget), Desktop (Text, Icon/Widget), Web (Text, Icon/Widget), Avg
        """
        results = self.data
        
        # Per platform x ui_type
        by_platform_ui = self.evaluate_by_platform_and_ui_type(results)
        
        # Per platform average
        platform_avg = self.evaluate_platform_avg(results)
        
        # Overall
        overall = self.evaluate_overall(results)
        
        metrics = {
            "overall": overall,
            "by_platform_ui_type": by_platform_ui,
            "platform_avg": platform_avg,
        }
        
        # Print formatted table
        self._print_table(by_platform_ui, platform_avg, overall)
        
        return metrics
    
    def _print_table(self, by_platform_ui: Dict, platform_avg: Dict, overall: Dict):
        """Print metrics in a formatted table."""
        print("\n" + "=" * 90)
        print("ScreenSpot-V2 Evaluation Results")
        print("=" * 90)
        
        # Header
        header1 = f"{'':>12} | {'Mobile':^20} | {'Desktop':^20} | {'Web':^20} | {'Avg':>6}"
        header2 = f"{'':>12} | {'Text':>9} {'Icon/Widget':>10} | {'Text':>9} {'Icon/Widget':>10} | {'Text':>9} {'Icon/Widget':>10} | {'':>6}"
        print(header1)
        print(header2)
        print("-" * 90)
        
        # Data row
        row_values = []
        for platform in self.PLATFORMS:
            text_acc = by_platform_ui.get(f"{platform}_text", {}).get("accuracy", 0.0)
            icon_acc = by_platform_ui.get(f"{platform}_icon", {}).get("accuracy", 0.0)
            row_values.append((text_acc, icon_acc))
        
        avg_acc = overall["accuracy"]
        
        row = f"{'Accuracy':>12} |"
        for text_acc, icon_acc in row_values:
            row += f" {text_acc:>8.2f}% {icon_acc:>9.2f}% |"
        row += f" {avg_acc:>5.2f}%"
        print(row)
        
        # Platform avg row
        row_avg = f"{'Platform Avg':>12} |"
        for platform in self.PLATFORMS:
            pavg = platform_avg.get(platform, 0.0)
            row_avg += f" {pavg:>19.2f}% |"
        row_avg += f" {avg_acc:>5.2f}%"
        print(row_avg)
        
        print("=" * 90)
        
        # Sample counts
        print("\nSample Counts:")
        for platform in self.PLATFORMS:
            text_info = by_platform_ui.get(f"{platform}_text", {})
            icon_info = by_platform_ui.get(f"{platform}_icon", {})
            print(f"  {platform:>8}: Text={text_info.get('num_total', 0)}, "
                  f"Icon/Widget={icon_info.get('num_total', 0)}, "
                  f"Total={text_info.get('num_total', 0) + icon_info.get('num_total', 0)}")
        print(f"  {'Overall':>8}: {overall['num_total']}")
        print()


def parse_args():
    parser = argparse.ArgumentParser(description='Calculate metrics for ScreenSpot-V2 benchmark')
    parser.add_argument('--input_file', type=str, required=True,
                        help='Path to input predictions file (jsonl format)')
    parser.add_argument('--output_file', type=str, required=True,
                        help='Path to output metrics file (json format)')
    parser.add_argument('--exp_name', type=str, default='experiment',
                        help='Experiment name for logging')
    parser.add_argument('--benchmark', type=str, default='screenspot-v2',
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
    
    metric_calculator = ScreenSpotV2Metric(
        input_file=args.input_file,
        output_file=args.output_file
    )
    
    metrics = metric_calculator.run()
    
    print(f"Metrics saved to: {args.output_file}")


if __name__ == "__main__":
    main()
