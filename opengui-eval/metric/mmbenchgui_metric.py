"""MMBench-GUI metric calculation."""

import itertools
import argparse
import os
import numpy as np
from typing import Dict, List, Any, Optional
from base_metric import BaseMetric


class MMBenchGUIMetric(BaseMetric):
    """
    Metric calculator for MMBench-GUI (L2 Grounding) benchmark.

    Reports metrics in the following format:

              Windows  MacOS  Linux  iOS  Android  Web  Avg.
    Bas.        xx.x   xx.x   xx.x  xx.x   xx.x  xx.x  xx.x
    Adv.        xx.x   xx.x   xx.x  xx.x   xx.x  xx.x  xx.x
    All         xx.x   xx.x   xx.x  xx.x   xx.x  xx.x  xx.x
    """

    # Canonical order for platforms
    PLATFORMS = ["windows", "macos", "linux", "ios", "android", "web"]
    # Display names
    PLATFORM_DISPLAY = {
        "windows": "Windows",
        "macos": "MacOS",
        "linux": "Linux",
        "ios": "iOS",
        "android": "Android",
        "web": "Web",
    }
    GROUNDING_TYPES = ["basic", "advanced"]
    GROUNDING_DISPLAY = {"basic": "Bas.", "advanced": "Adv."}

    def collect_results_to_eval(
        self,
        results: List[Dict],
        platform: Optional[str] = None,
        grounding_type: Optional[str] = None,
        ui_type: Optional[str] = None,
        application: Optional[str] = None,
    ) -> List[Dict]:
        """Filter results based on provided criteria."""
        filtered_results = []
        for sample in results:
            if (platform is None or sample.get("platform") == platform) and \
               (grounding_type is None or sample.get("grounding_type") == grounding_type) and \
               (ui_type is None or sample.get("ui_type") == ui_type) and \
               (application is None or sample.get("application") == application):
                filtered_results.append(sample)
        return filtered_results

    def make_combinations(
        self,
        results: List[Dict],
        platform: bool = False,
        grounding_type: bool = False,
        ui_type: bool = False,
        application: bool = False,
    ) -> List[Dict[str, str]]:
        """Generate combinations of attribute values for fine-grained evaluation."""
        unique_values = {
            "platform": set(),
            "grounding_type": set(),
            "ui_type": set(),
            "application": set(),
        }

        for sample in results:
            if platform:
                unique_values["platform"].add(sample.get("platform"))
            if grounding_type:
                unique_values["grounding_type"].add(sample.get("grounding_type"))
            if ui_type:
                unique_values["ui_type"].add(sample.get("ui_type"))
            if application:
                unique_values["application"].add(sample.get("application"))

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

    def evaluate_by_platform_and_grounding_type(self, results: List[Dict]) -> Dict[str, Dict]:
        """
        Evaluate by platform x grounding_type combination.

        Returns a dict like:
        {
            "windows_basic": {"accuracy": ..., "num_correct": ..., "num_total": ...},
            "windows_advanced": {...},
            ...
        }
        """
        evaluation_result = {}
        for platform in self.PLATFORMS:
            for gtype in self.GROUNDING_TYPES:
                filtered = self.collect_results_to_eval(results, platform=platform, grounding_type=gtype)
                num_total = len(filtered)
                num_correct = sum(1 for r in filtered if r.get("correct", False))
                acc = round(num_correct / num_total * 100, 2) if num_total > 0 else 0.0
                evaluation_result[f"{platform}_{gtype}"] = {
                    "accuracy": acc,
                    "num_correct": num_correct,
                    "num_total": num_total,
                }
        return evaluation_result

    def evaluate_by_platform(self, results: List[Dict]) -> Dict[str, Dict]:
        """Evaluate by platform (all grounding types combined)."""
        evaluation_result = {}
        for platform in self.PLATFORMS:
            filtered = self.collect_results_to_eval(results, platform=platform)
            metrics = self.calc_metric_for_result_list(filtered)
            if metrics["num_total"] > 0:
                evaluation_result[platform] = metrics
        return evaluation_result

    def evaluate_by_grounding_type(self, results: List[Dict]) -> Dict[str, Dict]:
        """Evaluate by grounding_type (all platforms combined)."""
        evaluation_result = {}
        for gtype in self.GROUNDING_TYPES:
            filtered = self.collect_results_to_eval(results, grounding_type=gtype)
            metrics = self.calc_metric_for_result_list(filtered)
            if metrics["num_total"] > 0:
                evaluation_result[gtype] = metrics
        return evaluation_result

    def evaluate_overall(self, results: List[Dict]) -> Dict[str, Any]:
        """Calculate overall metrics."""
        return self.calc_metric_for_result_list(results)

    def calculate_metrics(self) -> Dict[str, Any]:
        """
        Calculate all metrics for MMBench-GUI.

        Reports:
            Platform (Windows, MacOS, Linux, iOS, Android, Web) x Grounding Type (Basic, Advanced), Avg.
        """
        results = self.data

        # Per platform x grounding_type
        by_platform_gtype = self.evaluate_by_platform_and_grounding_type(results)

        # Per platform (all grounding types)
        by_platform = self.evaluate_by_platform(results)

        # Per grounding_type (all platforms)
        by_gtype = self.evaluate_by_grounding_type(results)

        # Overall
        overall = self.evaluate_overall(results)

        metrics = {
            "overall": overall,
            "by_platform_grounding_type": by_platform_gtype,
            "by_platform": {k: v for k, v in by_platform.items()},
            "by_grounding_type": by_gtype,
        }

        # Print formatted table
        self._print_table(by_platform_gtype, by_platform, by_gtype, overall)

        return metrics

    def _print_table(self, by_platform_gtype: Dict, by_platform: Dict, by_gtype: Dict, overall: Dict):
        """Print metrics in a formatted table matching the paper format."""
        print("\n" + "=" * 100)
        print("MMBench-GUI (L2 Grounding) Evaluation Results")
        print("=" * 100)

        # Column widths
        label_w = 8
        col_w = 10

        # Header row
        header = f"{'':>{label_w}}"
        for platform in self.PLATFORMS:
            display = self.PLATFORM_DISPLAY[platform]
            header += f" {display:>{col_w}}"
        header += f" {'Avg.':>{col_w}}"
        print(header)
        print("-" * 100)

        # Rows for each grounding type
        for gtype in self.GROUNDING_TYPES:
            display_name = self.GROUNDING_DISPLAY[gtype]
            row = f"{display_name:>{label_w}}"

            accs = []
            for platform in self.PLATFORMS:
                key = f"{platform}_{gtype}"
                info = by_platform_gtype.get(key, {})
                acc = info.get("accuracy", 0.0)
                num_total = info.get("num_total", 0)
                if num_total > 0:
                    row += f" {acc:>{col_w}.2f}"
                    accs.append(acc)
                else:
                    row += f" {'N/A':>{col_w}}"

            # Grounding type average (weighted by sample count)
            gtype_info = by_gtype.get(gtype, {})
            gtype_acc = gtype_info.get("accuracy", 0.0)
            row += f" {gtype_acc:>{col_w}.2f}"
            print(row)

        # All row (per-platform total)
        row = f"{'All':>{label_w}}"
        for platform in self.PLATFORMS:
            info = by_platform.get(platform, {})
            acc = info.get("accuracy", 0.0)
            num_total = info.get("num_total", 0)
            if num_total > 0:
                row += f" {acc:>{col_w}.2f}"
            else:
                row += f" {'N/A':>{col_w}}"
        row += f" {overall['accuracy']:>{col_w}.2f}"
        print(row)

        print("=" * 100)

        # Sample counts
        print("\nSample Counts:")
        for gtype in self.GROUNDING_TYPES:
            display_name = self.GROUNDING_DISPLAY[gtype]
            counts = f"  {display_name:>6}:"
            for platform in self.PLATFORMS:
                key = f"{platform}_{gtype}"
                info = by_platform_gtype.get(key, {})
                counts += f"  {self.PLATFORM_DISPLAY[platform]}={info.get('num_total', 0)}"
            gtype_info = by_gtype.get(gtype, {})
            counts += f"  Total={gtype_info.get('num_total', 0)}"
            print(counts)
        print(f"  {'All':>6}: Total={overall['num_total']}")
        print()


def parse_args():
    parser = argparse.ArgumentParser(description='Calculate metrics for MMBench-GUI benchmark')
    parser.add_argument('--input_file', type=str, required=True,
                        help='Path to input predictions file (jsonl format)')
    parser.add_argument('--output_file', type=str, required=True,
                        help='Path to output metrics file (json format)')
    parser.add_argument('--exp_name', type=str, default='experiment',
                        help='Experiment name for logging')
    parser.add_argument('--benchmark', type=str, default='mmbench-gui',
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

    metric_calculator = MMBenchGUIMetric(
        input_file=args.input_file,
        output_file=args.output_file
    )

    metrics = metric_calculator.run()

    print(f"Metrics saved to: {args.output_file}")


if __name__ == "__main__":
    main()
