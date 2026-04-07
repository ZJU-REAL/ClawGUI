"""Abstract base class for GUI grounding judges."""
import json
from pathlib import Path
from typing import Dict, Any, List, Optional
from abc import ABC, abstractmethod
from tqdm import tqdm


class BaseJudge(ABC):
    """Base judge class defining the standard evaluation pipeline."""

    def __init__(self, benchmark_name: str):
        self.benchmark_name = benchmark_name

    @abstractmethod
    def parse_prediction(self, item: Dict[str, Any]) -> Any:
        """
        Parse raw model output into a usable prediction.

        Args:
            item: A prediction record containing a {model_type}_infer field.

        Returns:
            Parsed prediction (format depends on benchmark).
        """
        pass

    @abstractmethod
    def evaluate_single(self, pred: Any, gt: Any) -> bool:
        """
        Judge a single sample.

        Args:
            pred: Parsed prediction.
            gt: Ground truth.

        Returns:
            True if correct, False otherwise.
        """
        pass

    def load_data(self, file_path: str) -> List[Dict[str, Any]]:
        """Load a JSON or JSONL file and return a list of records."""
        file_path = Path(file_path)

        if file_path.suffix == '.jsonl':
            data = []
            with open(file_path, 'r', encoding='utf-8') as f:
                for line in f:
                    if line.strip():
                        data.append(json.loads(line.strip()))
            return data

        elif file_path.suffix == '.json':
            with open(file_path, 'r', encoding='utf-8') as f:
                data = json.load(f)
                return data if isinstance(data, list) else [data]

        else:
            raise ValueError(f"Unsupported file format: {file_path.suffix}")

    def save_data(self, data: List[Dict[str, Any]], output_file: str, input_file: str) -> str:
        """
        Save judged data, inheriting the format from the output file extension
        (or from the input file if no extension is specified).

        Returns:
            Actual path where the data was saved.
        """
        output_path = Path(output_file)
        output_path.parent.mkdir(parents=True, exist_ok=True)

        if output_path.suffix in ['.json', '.jsonl']:
            output_format = output_path.suffix
        else:
            output_format = Path(input_file).suffix
            output_path = output_path.with_suffix(output_format)

        if output_format == '.jsonl':
            with open(output_path, 'w', encoding='utf-8') as f:
                for item in data:
                    f.write(json.dumps(item, ensure_ascii=False) + '\n')
        else:
            with open(output_path, 'w', encoding='utf-8') as f:
                json.dump(data, f, ensure_ascii=False, indent=2)

        return str(output_path)

    def detect_model_type(self, data: List[Dict[str, Any]]) -> Optional[str]:
        """
        Auto-detect model_type by looking for a {model_type}_infer key
        in the first record.
        """
        if not data:
            return None
        for key in data[0].keys():
            if key.endswith('_infer'):
                model_type = key.replace('_infer', '')
                print(f"  Detected model_type: {model_type}")
                return model_type
        return None

    def evaluate(self, input_file: str, output_file: str, exp_name: str, model_type: Optional[str] = None):
        """
        Run the full evaluation pipeline.

        Args:
            input_file: Path to predictions file (JSON/JSONL).
            output_file: Path to write judged output.
            exp_name: Experiment name for display.
            model_type: Override model type (auto-detected if not provided).
        """
        print(f"\n{'='*60}")
        print(f"{self.benchmark_name} Judge")
        print(f"{'='*60}")
        print(f"Experiment: {exp_name}")
        print(f"Benchmark:  {self.benchmark_name}")
        print(f"Input:      {input_file}")
        print(f"Output:     {output_file}")

        print(f"\nLoading data...")
        data = self.load_data(input_file)
        print(f"  {len(data)} samples")

        if model_type:
            print(f"  Model type (explicit): {model_type}")
            self.model_type = model_type
        else:
            self.model_type = self.detect_model_type(data)
            if not self.model_type:
                print("  Warning: could not detect model_type")

        print(f"\nEvaluating...")
        correct = 0
        total = 0

        for item in tqdm(data, desc="Judging"):
            sample_id = item['id']
            total += 1
            try:
                pred = self.parse_prediction(item)
                is_correct = self.evaluate_single(pred, item['answer'])
                if is_correct:
                    correct += 1
                item['correct'] = is_correct
            except Exception as e:
                print(f"\n  Error on {sample_id}: {e}")
                item['correct'] = False
                item['error'] = str(e)

        actual_output = self.save_data(data, output_file, input_file)

        accuracy = correct / total if total > 0 else 0.0
        print(f"\n{'='*60}")
        print(f"Results")
        print(f"{'='*60}")
        print(f"Total:    {total}")
        print(f"Correct:  {correct}")
        print(f"Accuracy: {accuracy:.4f} ({accuracy*100:.2f}%)")
        print(f"{'='*60}")
        print(f"Saved: {actual_output}\n")

        return {'total': total, 'correct': correct, 'accuracy': accuracy}
