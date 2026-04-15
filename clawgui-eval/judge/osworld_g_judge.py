"""
OSWorld-G Judge.

GT regions have three box_type values:
  - bbox:    answer = [x1, y1, x2, y2], point-in-rectangle check
  - polygon: answer = flat [x1,y1,x2,y2,...], ray-casting point-in-polygon check
  - refusal: target does not exist; model should output a refusal signal (REFUSAL sentinel)

Model refusal signals:
  - uivenus15: outputs [-1,-1]
  - guiowl15:  outputs terminate action (tool_call with "terminate" keyword)

Refusal samples are excluded from accuracy denominator by default (--include_refusal to include).
"""
import re
import json
import argparse
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
from tqdm import tqdm
from base_judge import BaseJudge
from grounding_judge import guig2_parse as _grounding_guig2_parse
from grounding_judge import seed18_parse as _grounding_seed18_parse
from grounding_judge import kimi_parse as _grounding_kimi_parse

REFUSAL = (-1, -1)  # sentinel for model refusal output


def _is_refusal_output(infer_str: str) -> bool:
    """Check if the model output is a refusal signal ([-1,-1] or terminate action)."""
    if not infer_str:
        return False
    if '"action": "terminate"' in infer_str or "'action': 'terminate'" in infer_str:
        return True
    numbers = re.findall(r'-?\d+\.?\d*', infer_str)
    return len(numbers) >= 2 and all(float(n) == -1 for n in numbers[:2])


# ---------- parse functions (aligned with grounding_judge.py) ----------

def qwen3vl_parse(infer_str: str, image_size: List[int]) -> Optional[Tuple[float, float]]:
    if not infer_str:
        return None
    if _is_refusal_output(infer_str):
        return REFUSAL
    coords = None
    if '<tool_call>' in infer_str and '</tool_call>' in infer_str:
        try:
            start_idx = infer_str.find('<tool_call>') + len('<tool_call>')
            end_idx = infer_str.find('</tool_call>')
            tool_call_json = json.loads(infer_str[start_idx:end_idx].strip())
            if 'arguments' in tool_call_json and 'coordinate' in tool_call_json['arguments']:
                coordinate = tool_call_json['arguments']['coordinate']
                if isinstance(coordinate, list) and len(coordinate) >= 2:
                    coords = [float(coordinate[0]), float(coordinate[1])]
        except (json.JSONDecodeError, KeyError, ValueError, TypeError, AttributeError):
            pass
    if coords is None:
        numbers = re.findall(r'-?\d+\.?\d*', infer_str)
        if numbers:
            coords = [float(n) for n in numbers]
    if not coords:
        return None
    width, height = image_size
    if len(coords) == 2:
        return (int(coords[0] / 1000 * width), int(coords[1] / 1000 * height))
    elif len(coords) >= 4:
        return (int(coords[0] / 999 * width), int(coords[1] / 999 * height))
    return None


def qwen25vl_parse(infer_str: str, image_size: List[int]) -> Optional[Tuple[float, float]]:
    if not infer_str:
        return None
    if _is_refusal_output(infer_str):
        return REFUSAL
    coords = None
    if '<tool_call>' in infer_str and '</tool_call>' in infer_str:
        try:
            start_idx = infer_str.find('<tool_call>') + len('<tool_call>')
            end_idx = infer_str.find('</tool_call>')
            tool_call_json = json.loads(infer_str[start_idx:end_idx].strip())
            if 'arguments' in tool_call_json and 'coordinate' in tool_call_json['arguments']:
                coordinate = tool_call_json['arguments']['coordinate']
                if isinstance(coordinate, list) and len(coordinate) >= 2:
                    coords = [float(coordinate[0]), float(coordinate[1])]
        except (json.JSONDecodeError, KeyError, ValueError, TypeError, AttributeError):
            pass
    if coords is None:
        numbers = re.findall(r'-?\d+\.?\d*', infer_str)
        if numbers:
            coords = [float(n) for n in numbers]
    if not coords:
        return None
    if len(coords) == 2:
        return (int(coords[0]), int(coords[1]))
    elif len(coords) >= 4:
        return (int((coords[0] + coords[2]) / 2), int((coords[1] + coords[3]) / 2))
    return None


def guig2_parse(infer: Any, image_size: List[int]) -> Optional[Tuple[float, float]]:
    """GUI-G2: normalized [0,1] box in JSONL; string path may carry OSWorld refusal signals."""
    if isinstance(infer, str) and infer and _is_refusal_output(infer):
        return REFUSAL
    return _grounding_guig2_parse(infer, image_size)


def stepgui_parse(infer_str: str, image_size: List[int]) -> Optional[Tuple[float, float]]:
    if not infer_str:
        return None
    if _is_refusal_output(infer_str):
        return REFUSAL
    numbers = re.findall(r'-?\d+\.?\d*', infer_str)
    if len(numbers) < 2:
        return None
    width, height = image_size
    return (int(float(numbers[0]) / 999 * width), int(float(numbers[1]) / 999 * height))


def uitars_parse(infer_str: str, image_size: List[int]) -> Optional[Tuple[float, float]]:
    import math
    if not infer_str:
        return None
    point_match = re.search(r"<point>(\d+)\s+(\d+)</point>", infer_str)
    if point_match:
        model_x, model_y = int(point_match.group(1)), int(point_match.group(2))
    else:
        coord_match = re.search(r"\((\d+),\s*(\d+)\)", infer_str)
        if coord_match:
            model_x, model_y = int(coord_match.group(1)), int(coord_match.group(2))
        else:
            return None
    orig_w, orig_h = image_size
    IMAGE_FACTOR = 28
    MIN_PIXELS = 100 * 28 * 28
    MAX_PIXELS = 16384 * 28 * 28
    round_by = lambda n, f: round(n / f) * f
    floor_by = lambda n, f: math.floor(n / f) * f
    ceil_by  = lambda n, f: math.ceil(n / f) * f
    h_bar = max(IMAGE_FACTOR, round_by(orig_h, IMAGE_FACTOR))
    w_bar = max(IMAGE_FACTOR, round_by(orig_w, IMAGE_FACTOR))
    if h_bar * w_bar > MAX_PIXELS:
        beta = math.sqrt((orig_h * orig_w) / MAX_PIXELS)
        h_bar = floor_by(int(orig_h / beta), IMAGE_FACTOR)
        w_bar = floor_by(int(orig_w / beta), IMAGE_FACTOR)
    elif h_bar * w_bar < MIN_PIXELS:
        beta = math.sqrt(MIN_PIXELS / (orig_h * orig_w))
        h_bar = ceil_by(int(orig_h * beta), IMAGE_FACTOR)
        w_bar = ceil_by(int(orig_w * beta), IMAGE_FACTOR)
    return (int(model_x / w_bar * orig_w), int(model_y / h_bar * orig_h))


def uivenus15_parse(infer_str: str, image_size: List[int]) -> Optional[Tuple[float, float]]:
    if not infer_str:
        return None
    if _is_refusal_output(infer_str):
        return REFUSAL
    numbers = re.findall(r'-?\d+\.?\d*', infer_str)
    if not numbers:
        return None
    coords = [float(n) for n in numbers]
    width, height = image_size
    if len(coords) == 2:
        return (coords[0] / 1000 * width, coords[1] / 1000 * height)
    elif len(coords) >= 4:
        return ((coords[0] + coords[2]) / 2 / 1000 * width,
                (coords[1] + coords[3]) / 2 / 1000 * height)
    return None


def maiui_parse(infer_str: str, image_size: List[int]) -> Optional[Tuple[float, float]]:
    if not infer_str:
        return None
    if _is_refusal_output(infer_str):
        return REFUSAL
    coords = None
    if '<answer>' in infer_str and '</answer>' in infer_str:
        try:
            start_idx = infer_str.find('<answer>') + len('<answer>')
            end_idx = infer_str.find('</answer>')
            answer_json = json.loads(infer_str[start_idx:end_idx].strip())
            if 'coordinate' in answer_json:
                coordinate = answer_json['coordinate']
                if isinstance(coordinate, list) and len(coordinate) >= 2:
                    coords = [float(coordinate[0]), float(coordinate[1])]
        except (json.JSONDecodeError, KeyError, ValueError, TypeError, AttributeError):
            pass
    if coords is None:
        numbers = re.findall(r'-?\d+\.?\d*', infer_str)
        if numbers:
            coords = [float(n) for n in numbers]
    if not coords:
        return None
    width, height = image_size
    if len(coords) == 2:
        return (int(coords[0] / 999 * width), int(coords[1] / 999 * height))
    elif len(coords) >= 4:
        return (int((coords[0] + coords[2]) / 2 / 999 * width),
                int((coords[1] + coords[3]) / 2 / 999 * height))
    return None


def seed18_parse(infer_str: str, image_size: List[int]) -> Optional[Tuple[float, float]]:
    """Seed 1.8: [0,1000] <point>x y</point>, with refusal check."""
    if not infer_str:
        return None
    if _is_refusal_output(infer_str):
        return REFUSAL
    return _grounding_seed18_parse(infer_str, image_size)


def kimi_parse(infer_str: str, image_size: List[int]) -> Optional[Tuple[float, float]]:
    """Kimi K2.5: [0,1000] (x,y), with refusal check."""
    if not infer_str:
        return None
    if _is_refusal_output(infer_str):
        return REFUSAL
    return _grounding_kimi_parse(infer_str, image_size)


# ---------- geometry helpers ----------

def is_point_in_rectangle(point: Tuple[float, float], rect: List[float]) -> bool:
    x, y = point
    x1, y1, x2, y2 = rect
    return x1 <= x <= x2 and y1 <= y <= y2


def is_point_in_polygon(point: Tuple[float, float], polygon: List[float]) -> bool:
    """Ray-casting point-in-polygon test. polygon: flat list [x1,y1, x2,y2, ...]."""
    x, y = point
    n = len(polygon) // 2
    inside = False
    j = n - 1
    for i in range(n):
        xi, yi = polygon[i * 2], polygon[i * 2 + 1]
        xj, yj = polygon[j * 2], polygon[j * 2 + 1]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            inside = not inside
        j = i
    return inside


# ---------- Judge ----------

class OSWorldGJudge(BaseJudge):
    """OSWorld-G judge supporting bbox, polygon, and refusal GT types."""

    def __init__(self, benchmark_name: str = "osworld-g"):
        super().__init__(benchmark_name)

    def parse_prediction(self, item: Dict[str, Any]) -> Optional[Tuple[float, float]]:
        image_size = item.get('image_size')
        if not image_size:
            return None
        for key, value in item.items():
            if key.endswith('_infer'):
                model_type = key.replace('_infer', '')
                if model_type == 'qwen3vl':
                    return qwen3vl_parse(value, image_size)
                elif model_type == 'uitars':
                    return uitars_parse(value, image_size)
                elif model_type == 'stepgui':
                    return stepgui_parse(value, image_size)
                elif model_type == 'qwen25vl':
                    return qwen25vl_parse(value, image_size)
                elif model_type == 'uivenus15':
                    return uivenus15_parse(value, image_size)
                elif model_type == 'maiui':
                    return maiui_parse(value, image_size)
                elif model_type == 'guiowl15':
                    return qwen3vl_parse(value, image_size)
                elif model_type in ('guig2', 'uivenus'):
                    return guig2_parse(value, image_size)
                elif model_type == 'seed':
                    return seed18_parse(value, image_size)
                elif model_type == 'kimi':
                    return kimi_parse(value, image_size)
                else:
                    supported = ['qwen3vl', 'guiowl15', 'qwen25vl', 'guig2', 'uivenus', 'uitars', 'stepgui', 'uivenus15', 'maiui', 'seed', 'kimi']
                    raise ValueError(f"Unsupported model_type: '{model_type}'. Supported: {supported}")
        return None

    def evaluate_single(self, pred: Optional[Tuple[float, float]],
                        item: Dict[str, Any]) -> bool:
        """Evaluate a single prediction against GT based on box_type."""
        if pred is None:
            return False

        box_type = item.get('box_type', 'bbox')
        answer = item['answer']

        if box_type == 'refusal':
            return pred == REFUSAL

        if pred == REFUSAL:
            return False

        if box_type == 'bbox':
            return is_point_in_rectangle(pred, answer)
        elif box_type == 'polygon':
            return is_point_in_polygon(pred, answer)
        else:
            return False

    def evaluate(self, input_file: str, output_file: str, exp_name: str,
                 model_type=None, include_refusal: bool = False):
        """
        include_refusal:
          False (default) - refusal samples are reported separately, excluded from accuracy denominator
          True            - refusal samples are included in denominator; correct refusal counts as correct
        """
        print(f"\n{'='*60}")
        print(f"{self.benchmark_name} Judge")
        print(f"{'='*60}")
        print(f"Experiment:       {exp_name}")
        print(f"Input:            {input_file}")
        print(f"Output:           {output_file}")
        print(f"Include refusal:  {include_refusal}")

        data = self.load_data(input_file)
        print(f"\nloaded {len(data)} items")

        if model_type:
            self.model_type = model_type
        else:
            self.model_type = self.detect_model_type(data)

        correct = 0
        total = 0
        box_type_stats: Dict[str, Dict[str, int]] = {}

        for item in tqdm(data, desc="judging"):
            box_type = item.get('box_type', 'bbox')
            if box_type not in box_type_stats:
                box_type_stats[box_type] = {'total': 0, 'correct': 0}
            box_type_stats[box_type]['total'] += 1

            if box_type == 'refusal' and not include_refusal:
                try:
                    pred = self.parse_prediction(item)
                    is_correct = self.evaluate_single(pred, item)
                    box_type_stats[box_type]['correct'] += int(is_correct)
                    item['correct'] = is_correct
                    item['counted_in_accuracy'] = False
                except Exception as e:
                    item['correct'] = False
                    item['counted_in_accuracy'] = False
                    item['error'] = str(e)
                continue

            total += 1
            try:
                pred = self.parse_prediction(item)
                is_correct = self.evaluate_single(pred, item)
                if is_correct:
                    correct += 1
                    box_type_stats[box_type]['correct'] += 1
                item['correct'] = is_correct
                item['counted_in_accuracy'] = True
            except Exception as e:
                print(f"\n  error: {item.get('id', '?')} - {e}")
                item['correct'] = False
                item['counted_in_accuracy'] = True
                item['error'] = str(e)

        actual_output = self.save_data(data, output_file, input_file)

        accuracy = correct / total if total > 0 else 0.0
        refusal_stats = box_type_stats.get('refusal', {'total': 0, 'correct': 0})

        print(f"\n{'='*60}")
        print(f"Results")
        print(f"{'='*60}")
        if include_refusal:
            print(f"Total (incl. refusal): {total}")
        else:
            print(f"Total (excl. refusal): {total}  "
                  f"(refusal samples: {refusal_stats['total']}, reported separately)")
        print(f"Correct:  {correct}")
        print(f"Accuracy: {accuracy:.4f} ({accuracy*100:.2f}%)")
        print(f"\nBreakdown by type:")
        for bt, stats in sorted(box_type_stats.items()):
            n = stats['total']
            c = stats['correct']
            acc = c / n if n > 0 else 0
            counted = '' if (bt != 'refusal' or include_refusal) else ' [excluded from accuracy]'
            print(f"  {bt:<10}: {c:>4}/{n:>4}  ({acc*100:.2f}%){counted}")
        print(f"{'='*60}\n")
        print(f"saved: {actual_output}")

        return {
            'total': total,
            'correct': correct,
            'accuracy': accuracy,
            'refusal_total': refusal_stats['total'],
            'refusal_correct': refusal_stats['correct'],
        }


def main():
    parser = argparse.ArgumentParser(description='OSWorld-G judge')
    parser.add_argument('--input_file', type=str, required=True)
    parser.add_argument('--output_file', type=str, required=True)
    parser.add_argument('--exp_name', type=str, required=True)
    parser.add_argument('--benchmark', type=str, default='osworld-g')
    parser.add_argument('--model_type', type=str, default=None)
    parser.add_argument(
        '--include_refusal',
        action='store_true',
        default=False,
        help='include refusal samples in accuracy denominator (default: excluded)'
    )
    args = parser.parse_args()

    if not Path(args.input_file).exists():
        raise FileNotFoundError(f"input file not found: {args.input_file}")

    judge = OSWorldGJudge(benchmark_name=args.benchmark)
    judge.evaluate(
        input_file=args.input_file,
        output_file=args.output_file,
        exp_name=args.exp_name,
        model_type=args.model_type,
        include_refusal=args.include_refusal,
    )


if __name__ == '__main__':
    main()
