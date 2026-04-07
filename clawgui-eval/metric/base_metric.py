"""Base metric class for GUI grounding evaluation."""

from abc import ABC, abstractmethod
from typing import Dict, List, Any
import json


class BaseMetric(ABC):
    """Base class for metric calculation."""
    
    def __init__(self, input_file: str, output_file: str):
        """
        Initialize metric calculator.
        
        Args:
            input_file: Path to input jsonl file with predictions
            output_file: Path to output json file for metrics
        """
        self.input_file = input_file
        self.output_file = output_file
        self.data = []
    
    def load_data(self):
        """Load data from input file."""
        with open(self.input_file, 'r', encoding='utf-8') as f:
            if self.input_file.endswith('.jsonl'):
                self.data = [json.loads(line) for line in f]
            else:
                self.data = json.load(f)
        print(f"Loaded {len(self.data)} samples from {self.input_file}")
    
    @abstractmethod
    def calculate_metrics(self) -> Dict[str, Any]:
        """
        Calculate metrics for the loaded data.
        
        Returns:
            Dictionary containing metrics
        """
        pass
    
    def save_results(self, metrics: Dict[str, Any]):
        """Save metrics to output file."""
        with open(self.output_file, 'w', encoding='utf-8') as f:
            json.dump(metrics, f, indent=2, ensure_ascii=False)
        print(f"Saved metrics to {self.output_file}")
    
    def run(self):
        """Run the complete metric calculation pipeline."""
        self.load_data()
        metrics = self.calculate_metrics()
        self.save_results(metrics)
        return metrics
