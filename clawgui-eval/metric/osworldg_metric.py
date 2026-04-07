"""OSWorld-G metric calculation."""

import argparse
import os
from typing import Dict, List, Any, Optional, Set
from base_metric import BaseMetric


# Category mapping: ui_type element -> high-level category
CATEGORY_MAP = {
    # Text Matching
    "Label": "text_matching",
    # Element Recognition
    "Icon": "element_recognition",
    "Image": "element_recognition",
    "Button": "element_recognition",
    # Layout Understanding
    "Tab": "layout_understanding",
    "Banner/Notification": "layout_understanding",
    "Accordion/Collapsible Panel": "layout_understanding",
    "Pagination Control": "layout_understanding",
    "Toolbar": "layout_understanding",
    "Menu Bar": "layout_understanding",
    "Dropdown Menu": "layout_understanding",
    "List": "layout_understanding",
    "Grid": "layout_understanding",
    "Tree View": "layout_understanding",
    "Dialog/Modal": "layout_understanding",
    "Panel/Container": "layout_understanding",
    "Sidebar": "layout_understanding",
    "Drawer": "layout_understanding",
    "Window Hierarchy": "layout_understanding",
    # Fine-grained Manipulation
    "Slider": "fine_grained_manipulation",
    "Stepper": "fine_grained_manipulation",
    "Divider": "fine_grained_manipulation",
    "Toggle/Switch": "fine_grained_manipulation",
    "Checkbox": "fine_grained_manipulation",
    "Radio Button": "fine_grained_manipulation",
    "Color Picker": "fine_grained_manipulation",
    "Date Picker": "fine_grained_manipulation",
    "Table": "fine_grained_manipulation",
    "Text Field/Input Box": "fine_grained_manipulation",
    "Search Bar": "fine_grained_manipulation",
    "Text Field": "fine_grained_manipulation",
    "Input Box": "fine_grained_manipulation",
    "Window Border": "fine_grained_manipulation",
}

# Note: "Accordion/Collapsible Panel" appears in both layout_understanding and
# fine_grained_manipulation in the original spec. We assign it to layout_understanding
# as primary. If a sample's ui_type list contains elements from fine_grained_manipulation,
# it will also be counted there.

# Category display names and order
CATEGORIES = [
    "text_matching",
    "element_recognition",
    "layout_understanding",
    "fine_grained_manipulation",
]
CATEGORY_DISPLAY = {
    "text_matching": "Text Matching",
    "element_recognition": "Element Recognition",
    "layout_understanding": "Layout Understanding",
    "fine_grained_manipulation": "Fine-grained Manipulation",
}
CATEGORY_SHORT = {
    "text_matching": "TextMatch",
    "element_recognition": "ElemRecog",
    "layout_understanding": "LayoutUnd",
    "fine_grained_manipulation": "FineManip",
}


def get_sample_categories(ui_type_list: List[str]) -> Set[str]:
    """
    Given a sample's ui_type list, return the set of high-level categories it belongs to.
    A sample belongs to a category if ANY of its ui_type elements maps to that category.
    """
    categories = set()
    for ui_type in ui_type_list:
        cat = CATEGORY_MAP.get(ui_type)
        if cat:
            categories.add(cat)
    return categories


class OSWorldGMetric(BaseMetric):
    """
    Metric calculator for OSWorld-G benchmark.

    Reports metrics in the following format:

    Text Matching | Element Recognition | Layout Understanding | Fine-grained Manipulation | Refusal | Overall

    By default, refusal samples (box_type == "refusal") are EXCLUDED from the
    Overall accuracy. Use --include_refusal to include them.
    """

    def __init__(self, input_file: str, output_file: str, include_refusal: bool = False):
        super().__init__(input_file, output_file)
        self.include_refusal = include_refusal

    def calc_accuracy(self, results: List[Dict]) -> Dict[str, Any]:
        """Calculate accuracy for a list of results."""
        num_total = len(results)
        num_correct = sum(1 for r in results if r.get("correct", False))
        return {
            "accuracy": round(num_correct / num_total * 100, 2) if num_total > 0 else 0.0,
            "num_correct": num_correct,
            "num_total": num_total,
        }

    def evaluate_by_category(self, results: List[Dict]) -> Dict[str, Dict[str, Any]]:
        """
        Evaluate accuracy per high-level category.

        A sample is counted in a category if ANY of its ui_type elements belongs
        to that category. One sample may be counted in multiple categories.
        """
        category_results = {cat: [] for cat in CATEGORIES}

        for sample in results:
            # Skip refusal samples for category breakdown
            if sample.get("box_type") == "refusal":
                continue
            ui_types = sample.get("ui_type", [])
            if isinstance(ui_types, str):
                ui_types = [ui_types]
            cats = get_sample_categories(ui_types)
            for cat in cats:
                category_results[cat].append(sample)

        evaluation = {}
        for cat in CATEGORIES:
            evaluation[cat] = self.calc_accuracy(category_results[cat])
        return evaluation

    def evaluate_refusal(self, results: List[Dict]) -> Dict[str, Any]:
        """Evaluate refusal samples separately."""
        refusal_samples = [r for r in results if r.get("box_type") == "refusal"]
        return self.calc_accuracy(refusal_samples)

    def evaluate_overall(self, results: List[Dict]) -> Dict[str, Any]:
        """
        Calculate overall accuracy.
        By default excludes refusal samples (uses counted_in_accuracy field).
        If include_refusal is True, includes all samples.
        """
        if self.include_refusal:
            filtered = results
        else:
            filtered = [r for r in results if r.get("counted_in_accuracy", True)]
        return self.calc_accuracy(filtered)

    def calculate_metrics(self) -> Dict[str, Any]:
        """Calculate all metrics for OSWorld-G."""
        results = self.data

        # Per category
        by_category = self.evaluate_by_category(results)

        # Refusal
        refusal = self.evaluate_refusal(results)

        # Overall (respecting include_refusal setting)
        overall = self.evaluate_overall(results)

        metrics = {
            "overall": overall,
            "by_category": by_category,
            "refusal": refusal,
            "include_refusal": self.include_refusal,
        }

        # Print formatted table
        self._print_table(by_category, refusal, overall)

        return metrics

    def _print_table(self, by_category: Dict, refusal: Dict, overall: Dict):
        """Print metrics in a formatted table."""
        print("\n" + "=" * 110)
        print("OSWorld-G Evaluation Results")
        if not self.include_refusal:
            print("(Refusal samples excluded from Overall accuracy)")
        else:
            print("(Refusal samples INCLUDED in Overall accuracy)")
        print("=" * 110)

        col_w = 16

        # Header
        header = ""
        for cat in CATEGORIES:
            header += f" {CATEGORY_SHORT[cat]:>{col_w}}"
        header += f" {'Refusal':>{col_w}}"
        header += f" {'Overall':>{col_w}}"
        print(header)
        print("-" * 110)

        # Accuracy row
        row = ""
        for cat in CATEGORIES:
            info = by_category[cat]
            if info["num_total"] > 0:
                row += f" {info['accuracy']:>{col_w}.2f}"
            else:
                row += f" {'N/A':>{col_w}}"

        if refusal["num_total"] > 0:
            row += f" {refusal['accuracy']:>{col_w}.2f}"
        else:
            row += f" {'N/A':>{col_w}}"

        row += f" {overall['accuracy']:>{col_w}.2f}"
        print(row)

        # Count row
        count_row = ""
        for cat in CATEGORIES:
            info = by_category[cat]
            count_str = f"{info['num_correct']}/{info['num_total']}"
            count_row += f" {count_str:>{col_w}}"
        count_str = f"{refusal['num_correct']}/{refusal['num_total']}"
        count_row += f" {count_str:>{col_w}}"
        count_str = f"{overall['num_correct']}/{overall['num_total']}"
        count_row += f" {count_str:>{col_w}}"
        print(count_row)

        print("=" * 110)

        # Detailed breakdown
        print("\nDetailed Category Breakdown:")
        for cat in CATEGORIES:
            info = by_category[cat]
            print(f"  {CATEGORY_DISPLAY[cat]:<30}: "
                  f"{info['accuracy']:>6.2f}%  ({info['num_correct']}/{info['num_total']})")
        print(f"  {'Refusal':<30}: "
              f"{refusal['accuracy']:>6.2f}%  ({refusal['num_correct']}/{refusal['num_total']})")
        print(f"  {'Overall':<30}: "
              f"{overall['accuracy']:>6.2f}%  ({overall['num_correct']}/{overall['num_total']})")
        print()


def parse_args():
    parser = argparse.ArgumentParser(description='Calculate metrics for OSWorld-G benchmark')
    parser.add_argument('--input_file', type=str, required=True,
                        help='Path to input predictions file (jsonl format)')
    parser.add_argument('--output_file', type=str, required=True,
                        help='Path to output metrics file (json format)')
    parser.add_argument('--exp_name', type=str, default='experiment',
                        help='Experiment name for logging')
    parser.add_argument('--benchmark', type=str, default='osworld-g',
                        help='Benchmark name')
    parser.add_argument('--include_refusal', action='store_true', default=False,
                        help='Include refusal samples in Overall accuracy (default: excluded)')
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
    print(f"Include refusal: {args.include_refusal}")
    print()

    metric_calculator = OSWorldGMetric(
        input_file=args.input_file,
        output_file=args.output_file,
        include_refusal=args.include_refusal,
    )

    metrics = metric_calculator.run()

    print(f"Metrics saved to: {args.output_file}")


if __name__ == "__main__":
    main()
