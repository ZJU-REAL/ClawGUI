"""
AndroidControl Judge - Multi-model action prediction evaluation.
"""
import re
import json
import math
import argparse
from pathlib import Path
from typing import Dict, Any, List, Optional, Tuple
from base_judge import BaseJudge
from tqdm import tqdm

# Official AndroidControl evaluation thresholds
SCREEN_WIDTH = 1080
SCREEN_HEIGHT = 2400
CLICK_THRESHOLD = math.sqrt((SCREEN_WIDTH * 0.14) ** 2 + (SCREEN_HEIGHT * 0.14) ** 2)
TEXT_F1_THRESHOLD = 0.5


def calculate_f1_score(predicted_str, ground_truth_str):
    """Calculate token-level F1 score for text matching."""
    if not predicted_str or not ground_truth_str:
        return 0.0

    predicted_tokens = set(predicted_str.lower().split())
    ground_truth_tokens = set(ground_truth_str.lower().split())

    common_tokens = predicted_tokens.intersection(ground_truth_tokens)

    if len(predicted_tokens) == 0:
        precision = 0
    else:
        precision = len(common_tokens) / len(predicted_tokens)

    if len(ground_truth_tokens) == 0:
        recall = 0
    else:
        recall = len(common_tokens) / len(ground_truth_tokens)

    if precision + recall == 0:
        f1_score = 0
    else:
        f1_score = 2 * (precision * recall) / (precision + recall)

    return f1_score


def click_matching(gt_x, gt_y, pred_x, pred_y):
    """
    Click matching (official AndroidControl logic).
    Uses Euclidean distance with a threshold of 14% of screen dimensions.
    """
    distance = math.sqrt((pred_x - gt_x) ** 2 + (pred_y - gt_y) ** 2)
    return distance <= CLICK_THRESHOLD


def text_matching(gt_text, pred_text):
    """Text matching (official AndroidControl logic)."""
    if gt_text == pred_text:
        return True
    return calculate_f1_score(pred_text, gt_text) > TEXT_F1_THRESHOLD


def scroll_matching(gt_direction, pred_direction):
    """Scroll direction matching (gt_direction is reversed before comparison)."""
    if not gt_direction or not pred_direction:
        return False
    
    # Reverse mapping for gt_direction
    reverse_map = {
        "left": "right",
        "right": "left",
        "up": "down",
        "down": "up"
    }
    gt_direction_lower = gt_direction.lower()

    gt_direction_reversed = reverse_map.get(gt_direction_lower, gt_direction_lower)
    return gt_direction_reversed == pred_direction.lower()


def check_point_in_bboxes(x, y, bbox_list):
    """
    Check if point (x, y) falls inside any bbox.
    x, y: normalized coordinates (0-1)
    bbox_list: [[x_min, y_min, x_max, y_max], ...] normalized (0-1)
    Returns: True/False
    """
    if not bbox_list:
        return False
    
    for bbox in bbox_list:
        x_min, y_min, x_max, y_max = bbox
        if x_min <= x <= x_max and y_min <= y <= y_max:
            return True
    
    return False


def get_nearest_bboxes(x, y, bbox_list, top_k=5):
    """
    Get the top_k nearest bboxes to point (x, y).
    x, y: normalized coordinates (0-1)
    bbox_list: [[x_min, y_min, x_max, y_max], ...] normalized (0-1)
    Returns: top_k nearest bboxes
    """
    if not bbox_list:
        return []
    
    def get_center_distance(bbox):
        x_min, y_min, x_max, y_max = bbox
        center_x = (x_min + x_max) / 2
        center_y = (y_min + y_max) / 2
        return ((center_x - x) ** 2 + (center_y - y) ** 2) ** 0.5
    
    sorted_boxes = sorted(bbox_list, key=get_center_distance)
    return sorted_boxes[:top_k]


def qwen25vl_parse(infer_str: str) -> Optional[Dict[str, Any]]:
    """
    Parse Qwen2.5-VL action output into GT format (AndroidControl official format).
    
    Qwen2.5-VL prediction format (similar to Qwen3-VL):
    "qwen25vl_infer": "Thought: ...\n\nAction: ...\n<tool_call>\n{...}\n</tool_call>"
    
    GT format:
    {"name": "mobile", "action": "click", "arguments": {"points": [[500, 940]]}}
    
    Note: Qwen2.5-VL outputs absolute coordinates (1080x2400),
    which need to be converted to [0, 1000] coordinate system.
    
    Qwen2.5-VL action space (has one extra action 'open' compared to Qwen3-VL):
    - click: tap, param coordinate (absolute 1080x2400)
    - long_press: long press, param coordinate (absolute 1080x2400)
    - swipe: swipe, params coordinate and coordinate2 (absolute 1080x2400)
    - type: text input, param text
    - system_button: system key, param button (Back/Home/Menu/Enter)
    - wait: wait, param time
    - open: open app, param text (extra action not in Qwen3-VL)
    - answer: answer (not in GT action space)
    - terminate: terminate (not in GT action space)
    
    Args:
        infer_str: Qwen2.5-VL model output string
        
    Returns:
        GT-format action dict, or None if unparseable or action not in GT action space
    """
    if not infer_str:
        return None
    
    try:
        # Extract content between <tool_call> and </tool_call>
        if '<tool_call>' not in infer_str or '</tool_call>' not in infer_str:
            return None
            
        start_idx = infer_str.find('<tool_call>') + len('<tool_call>')
        end_idx = infer_str.find('</tool_call>')
        json_str = infer_str[start_idx:end_idx].strip()
        
        tool_call = json.loads(json_str)
        
        if 'arguments' not in tool_call:
            return None
            
        args = tool_call['arguments']
        action = args.get('action', '')
        
        result = {
            "name": "mobile",
            "action": None,
            "arguments": {}
        }
        
        # Coordinate conversion: absolute (1080x2400) -> [0, 1000]
        def abs_to_normalized(x, y):
            norm_x = int(x * 1000 / 1080)
            norm_y = int(y * 1000 / 2400)
            return [norm_x, norm_y]
        
        # Qwen2.5-VL action -> GT action mapping
        if action == "click":
            result["action"] = "click"
            coord = args.get('coordinate', [])
            if len(coord) >= 2:
                norm_coord = abs_to_normalized(coord[0], coord[1])
                result["arguments"]["points"] = [norm_coord]
        
        elif action == "long_press":
            result["action"] = "long_press"
            coord = args.get('coordinate', [])
            if len(coord) >= 2:
                norm_coord = abs_to_normalized(coord[0], coord[1])
                result["arguments"]["points"] = [norm_coord]
        
        elif action == "type":
            result["action"] = "type"
            result["arguments"]["text"] = args.get('text', '')
        
        elif action == "open":
            # Qwen2.5-VL open -> GT open_app
            result["action"] = "open_app"
            result["arguments"]["app"] = args.get('text', '')
        
        elif action == "swipe":
            # Qwen2.5-VL swipe -> GT scroll
            result["action"] = "scroll"
            coord = args.get('coordinate', [])
            coord2 = args.get('coordinate2', [])
            if len(coord) >= 2 and len(coord2) >= 2:
                dx = coord2[0] - coord[0]
                dy = coord2[1] - coord[1]
                
                if abs(dx) > abs(dy):
                    direction = "right" if dx > 0 else "left"
                else:
                    direction = "down" if dy > 0 else "up"
                
                norm_coord = abs_to_normalized(coord[0], coord[1])
                result["arguments"]["points"] = [norm_coord]
                result["arguments"]["direction"] = direction
        
        elif action == "system_button":
            # Qwen2.5-VL system_button -> GT button_press
            result["action"] = "button_press"
            button = args.get('button', '')
            if button == "Back":
                result["arguments"]["type"] = "back"
            elif button == "Home":
                result["arguments"]["type"] = "home"
            else:
                # Menu/Enter not in GT action space
                return None
        
        elif action == "wait":
            result["action"] = "wait"
            result["arguments"]["time"] = args.get('time', 0)
        
        elif action in ["answer", "terminate"]:
            # Not in GT action space
            return None
        
        else:
            return None
        
        return result if result["action"] else None
        
    except (json.JSONDecodeError, KeyError, ValueError, TypeError, AttributeError) as e:
        return None


def qwen3vl_parse(infer_str: str) -> Optional[Dict[str, Any]]:
    """
    Parse Qwen3-VL action output into GT format (AndroidControl official format).
    
    Qwen3-VL prediction formats:
    1. "<tool_call>{\"name\": \"mobile_use\", \"arguments\": {\"action\": \"click\", \"coordinate\": [x, y]}}</tool_call>"
    2. JSON: {"Thought": "...", "ToolCall": {"name": "mobile", "action": "click", "arguments": {"points": [[x, y]]}}}
    3. Direct GT format: {"name": "mobile", "action": "click", "arguments": {"points": [[x, y]]}}
    
    GT format:
    {"name": "mobile", "action": "click", "arguments": {"points": [[500, 940]]}}
    
    Note: Coordinates are already in [0, 1000] range, no conversion needed.
    
    Qwen3-VL action space:
    - click: tap, param coordinate ([0, 1000])
    - long_press: long press, param coordinate ([0, 1000])
    - swipe: swipe, params coordinate and coordinate2 ([0, 1000])
    - type: text input, param text
    - system_button: system key, param button (Back/Home/Menu/Enter)
    - wait: wait, param time
    - answer: answer (not in GT action space)
    - terminate: terminate (not in GT action space)
    
    GT action space (AndroidControl official):
    - click: tap, param points ([0, 1000])
    - long_press: long press, param points ([0, 1000])
    - scroll: scroll, params points and direction
    - type: text input, param text
    - button_press: key press, param type (home/back)
    - open_app: open app, param app
    - wait: wait, param time
    
    Args:
        infer_str: Qwen3-VL model output string
        
    Returns:
        GT-format action dict, or None if unparseable or action not in GT action space
    """
    if not infer_str:
        return None
    
    try:
        # Try parsing as JSON first
        try:
            parsed_json = json.loads(infer_str.strip())
            if isinstance(parsed_json, dict):
                # Format 1: {"Thought": "...", "ToolCall": {...}}
                if 'ToolCall' in parsed_json:
                    tool_call = parsed_json['ToolCall']
                    if isinstance(tool_call, dict) and 'action' in tool_call:
                        result = {
                            "name": tool_call.get("name", "mobile"),
                            "action": tool_call.get("action"),
                            "arguments": tool_call.get("arguments", {})
                        }
                        return result if result["action"] else None
                
                # Format 2: direct GT format {"name": "mobile", "action": "click", ...}
                if 'action' in parsed_json:
                    result = {
                        "name": parsed_json.get("name", "mobile"),
                        "action": parsed_json.get("action"),
                        "arguments": parsed_json.get("arguments", {})
                    }
                    return result if result["action"] else None
        except json.JSONDecodeError:
            pass  # Not pure JSON, try other formats
        
        # Extract content between <tool_call> and </tool_call>
        if '<tool_call>' not in infer_str or '</tool_call>' not in infer_str:
            return None
            
        start_idx = infer_str.find('<tool_call>') + len('<tool_call>')
        end_idx = infer_str.find('</tool_call>')
        json_str = infer_str[start_idx:end_idx].strip()
        
        tool_call = json.loads(json_str)
        
        if 'arguments' not in tool_call:
            return None
            
        args = tool_call['arguments']
        action = args.get('action', '')
        
        result = {
            "name": "mobile",
            "action": None,
            "arguments": {}
        }
        
        # Qwen3-VL action -> GT action mapping
        if action == "click":
            result["action"] = "click"
            coord = args.get('coordinate', [])
            if len(coord) >= 2:
                result["arguments"]["points"] = [[coord[0], coord[1]]]
        
        elif action == "long_press":
            result["action"] = "long_press"
            coord = args.get('coordinate', [])
            if len(coord) >= 2:
                result["arguments"]["points"] = [[coord[0], coord[1]]]
        
        elif action == "type":
            result["action"] = "type"
            result["arguments"]["text"] = args.get('text', '')
        
        elif action == "swipe":
            # Qwen3-VL swipe -> GT scroll
            result["action"] = "scroll"
            coord = args.get('coordinate', [])
            coord2 = args.get('coordinate2', [])
            if len(coord) >= 2 and len(coord2) >= 2:
                dx = coord2[0] - coord[0]
                dy = coord2[1] - coord[1]
                
                if abs(dx) > abs(dy):
                    direction = "right" if dx > 0 else "left"
                else:
                    direction = "down" if dy > 0 else "up"
                
                result["arguments"]["points"] = [[coord[0], coord[1]]]
                result["arguments"]["direction"] = direction
        
        elif action == "system_button":
            # Qwen3-VL system_button -> GT button_press
            result["action"] = "button_press"
            button = args.get('button', '')
            if button == "Back":
                result["arguments"]["type"] = "back"
            elif button == "Home":
                result["arguments"]["type"] = "home"
            else:
                # Menu/Enter not in GT action space
                return None
        
        elif action == "wait":
            result["action"] = "wait"
            result["arguments"]["time"] = args.get('time', 0)
        
        elif action in ["answer", "terminate"]:
            # Not in GT action space
            return None
        
        else:
            return None
        
        return result if result["action"] else None
        
    except (json.JSONDecodeError, KeyError, ValueError, TypeError, AttributeError) as e:
        return None


def action_matching(pred_action, pred_info, gt_action, gt_info, candidate_bbox=None):
    """
    Action matching logic (based on official AndroidControl evaluation).
    
    Args:
        pred_action: predicted action type
        pred_info: predicted action arguments
        gt_action: ground truth action type
        gt_info: ground truth action arguments
        candidate_bbox: candidate bbox list, format [[x_min, y_min, x_max, y_max], ...]
        
    Returns:
        dict: {
            'is_correct': 'yes'/'no',
            'info': 'action_correct/action_fail/...',
            'type_match': True/False,
            'match_success': True/False
        }
    """
    pred_action = pred_action.strip()
    gt_action = gt_action.strip()

    # Action type mismatch
    if pred_action != gt_action:
        return {
            "is_correct": "no",
            "info": "action_fail",
            "type_match": False,
            "match_success": False
        }

    # Click and long press
    if gt_action in ["click", "long_press"]:
        try:
            gt_points = gt_info.get("points", [[]])[0]
            pred_points = pred_info.get("points", [[]])[0]

            if len(gt_points) < 2 or len(pred_points) < 2:
                return {
                    "is_correct": "no",
                    "info": "click_fail",
                    "type_match": True,
                    "match_success": False
                }

            gt_x = int(gt_points[0] * SCREEN_WIDTH / 1000)
            gt_y = int(gt_points[1] * SCREEN_HEIGHT / 1000)
            pred_x = int(pred_points[0] * SCREEN_WIDTH / 1000)
            pred_y = int(pred_points[1] * SCREEN_HEIGHT / 1000)
            
            if candidate_bbox is None:
                candidate_bbox = []
            
            if candidate_bbox:
                normalized_bbox = []
                for bbox in candidate_bbox:
                    x_min, y_min, x_max, y_max = bbox
                    x_min_norm = x_min / SCREEN_WIDTH
                    y_min_norm = y_min / SCREEN_HEIGHT
                    x_max_norm = x_max / SCREEN_WIDTH
                    y_max_norm = y_max / SCREEN_HEIGHT
                    normalized_bbox.append([x_min_norm, y_min_norm, x_max_norm, y_max_norm])
                
                pred_x_norm = pred_x / SCREEN_WIDTH
                pred_y_norm = pred_y / SCREEN_HEIGHT
                
                nearest_bboxes = get_nearest_bboxes(pred_x_norm, pred_y_norm, normalized_bbox, top_k=5)
                
                if check_point_in_bboxes(pred_x_norm, pred_y_norm, nearest_bboxes):
                    return {
                        "is_correct": "yes",
                        "info": "click_correct",
                        "type_match": True,
                        "match_success": True
                    }
            
            if click_matching(gt_x, gt_y, pred_x, pred_y):
                return {
                    "is_correct": "yes",
                    "info": "click_correct",
                    "type_match": True,
                    "match_success": True
                }
            else:
                return {
                    "is_correct": "no",
                    "info": "click_fail",
                    "type_match": True,
                    "match_success": False
                }
        except:
            return {
                "is_correct": "no",
                "info": "click_fail",
                "type_match": True,
                "match_success": False
            }

    # Text input
    elif gt_action == "type":
        try:
            gt_text = gt_info.get("text", "")
            pred_text = pred_info.get("text", "")

            if text_matching(gt_text, pred_text):
                return {
                    "is_correct": "yes",
                    "info": "type_correct",
                    "type_match": True,
                    "match_success": True
                }
            else:
                return {
                    "is_correct": "no",
                    "info": "type_fail",
                    "type_match": True,
                    "match_success": False
                }
        except:
            return {
                "is_correct": "no",
                "info": "type_fail",
                "type_match": True,
                "match_success": False
            }

    # Scroll
    elif gt_action == "scroll":
        try:
            gt_direction = gt_info.get("direction", "")
            pred_direction = pred_info.get("direction", "")

            if scroll_matching(gt_direction, pred_direction):
                return {
                    "is_correct": "yes",
                    "info": "scroll_correct",
                    "type_match": True,
                    "match_success": True
                }
            else:
                return {
                    "is_correct": "no",
                    "info": "scroll_fail",
                    "type_match": True,
                    "match_success": False
                }
        except:
            return {
                "is_correct": "no",
                "info": "scroll_fail",
                "type_match": True,
                "match_success": False
            }

    # Button press
    elif gt_action == "button_press":
        try:
            gt_type = gt_info.get("type", "")
            pred_type = pred_info.get("type", "")

            if gt_type == pred_type:
                return {
                    "is_correct": "yes",
                    "info": "button_correct",
                    "type_match": True,
                    "match_success": True
                }
            else:
                return {
                    "is_correct": "no",
                    "info": "button_fail",
                    "type_match": True,
                    "match_success": False
                }
        except:
            return {
                "is_correct": "no",
                "info": "button_fail",
                "type_match": True,
                "match_success": False
            }

    # Open app
    elif gt_action == "open_app":
        try:
            gt_app = gt_info.get("app", "")
            pred_app = pred_info.get("app", "")
            if pred_app == "":
                pred_app = pred_info.get("text", "")
            if gt_app == pred_app or calculate_f1_score(gt_app, pred_app) > TEXT_F1_THRESHOLD:
                return {
                    "is_correct": "yes",
                    "info": "open_app_correct",
                    "type_match": True,
                    "match_success": True
                }
            else:
                return {
                    "is_correct": "no",
                    "info": "open_app_fail",
                    "type_match": True,
                    "match_success": False
                }
        except:
            return {
                "is_correct": "no",
                "info": "open_app_fail",
                "type_match": True,
                "match_success": False
            }

    # Wait
    elif gt_action == "wait":
        try:
            return {
                "is_correct": "yes",
                "info": "wait_correct",
                "type_match": True,
                "match_success": True
            }
        except:
            return {
                "is_correct": "no",
                "info": "wait_fail",
                "type_match": True,
                "match_success": False
            }
    else:
        raise ValueError(f"Unknown action type: {gt_action}")


class AndroidControlJudge(BaseJudge):
    """
    AndroidControl evaluator — supports multiple models.
    
    Workflow:
    1. Call the appropriate parse function based on model_type
    2. Parse function converts model action space to GT action space (AndroidControl official format)
    3. Use unified action_matching for evaluation
    
    GT action space (AndroidControl official):
    - click: tap, param points [[x, y]] ([0, 1000] coords)
    - long_press: long press, param points [[x, y]] ([0, 1000] coords)
    - scroll: scroll, params points [[x, y]] and direction (up/down/left/right)
    - type: text input, param text
    - button_press: key press, param type (home/back)
    - open_app: open app, param app
    - wait: wait, param time
    """
    
    def __init__(self):
        super().__init__("androidcontrol")
        self.gt_action_types = [
            "click",
            "long_press",
            "type",
            "scroll",
            "button_press",
            "open_app",
            "wait",
        ]
    
    def parse_prediction(self, item: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        """
        Automatically select the parse function based on model_type
        and convert prediction to GT format.
        
        Args:
            item: prediction record containing a {model_type}_infer field
            
        Returns:
            GT-format action dict (AndroidControl official format)
        """
        for key, value in item.items():
            if key.endswith('_infer'):
                model_type = key.replace('_infer', '')
                result = None
                if model_type == 'qwen3vl':
                    result = qwen3vl_parse(value)
                elif model_type == 'qwen25vl':
                    result = qwen25vl_parse(value)
                else:
                    # Default: try qwen3vl parser
                    result = qwen3vl_parse(value)
                
                if result is None:
                    print(f"[PARSE_ERROR] sample_id={item.get('id', 'unknown')}")
                    print(f"[PARSE_ERROR] content: {value[:500] if len(str(value)) > 500 else value}")
                
                return result
        return None
    
    def evaluate_single(self, pred: Optional[Dict[str, Any]], 
                       gt: Dict[str, Any]) -> Dict[str, Any]:
        """
        Evaluate a single sample.
        
        Note: pred has already been converted to GT format by parse_prediction,
        so both pred and gt share the same format (AndroidControl official).
        
        Args:
            pred: predicted action (GT format, converted by parse_prediction)
                  Format: {"name": "mobile", "action": "click", "arguments": {"points": [[x, y]]}}
            gt: ground truth
                Format: {"name": "mobile", "action": "click", "arguments": {"points": [[x, y]]}}
            
        Returns:
            dict: {
                'correct': True/False,
                'type_match': True/False,
                'match_success': True/False,
                'gt_action': str,
                'pred_action': str,
                'info': str
            }
        """
        result_dict = {
            'correct': False,
            'type_match': False,
            'match_success': False,
            'gt_action': None,
            'pred_action': None,
            'info': 'parse_fail'
        }
        
        if pred is None:
            result_dict['info'] = 'parse_fail'
            return result_dict
        
        try:
            gt_answer = gt
            candidate_bbox = gt_answer.get('candidate_bbox', [])
            gt_action = gt_answer.get('action')
            gt_info = gt_answer.get('arguments', {})
            pred_action = pred.get('action')
            pred_info = pred.get('arguments', {})
            
            result_dict['gt_action'] = gt_action
            result_dict['pred_action'] = pred_action
            
            if gt_action not in self.gt_action_types:
                result_dict['info'] = 'invalid_gt_action'
                return result_dict
            
            result = action_matching(pred_action, pred_info, gt_action, gt_info, candidate_bbox)
            
            result_dict['correct'] = result["is_correct"] == "yes"
            result_dict['type_match'] = result.get("type_match", False)
            result_dict['match_success'] = result.get("match_success", False)
            result_dict['info'] = result.get("info", "unknown")
            
            return result_dict
            
        except Exception as e:
            result_dict['info'] = f'exception: {str(e)}'
            return result_dict

    def evaluate(self, input_file: str, output_file: str, exp_name: str, model_type: Optional[str] = None):
        """
        Full evaluation pipeline — overrides base class to support detailed metrics.
        
        Args:
            input_file: prediction results file
            output_file: evaluation output file
            exp_name: experiment name
            model_type: model type (optional, auto-detected if not provided)
            
        Returns:
            dict containing detailed evaluation results
        """
        print(f"\n{'='*60}")
        print(f"{self.benchmark_name} Judge")
        print(f"{'='*60}")
        print(f"Experiment: {exp_name}")
        print(f"Input: {input_file}")
        print(f"Output: {output_file}")
        
        data = self.load_data(input_file)
        print(f"Loaded {len(data)} samples")
        
        if model_type:
            self.model_type = model_type
        else:
            self.model_type = self.detect_model_type(data)
        
        total = 0
        for item in tqdm(data, desc="Evaluating"):
            sample_id = item['id']
            total += 1
            
            try:
                pred = self.parse_prediction(item)
                eval_result = self.evaluate_single(pred, item['answer'])
                
                item['correct'] = eval_result['correct']
                item['type_match'] = eval_result['type_match']
                item['match_success'] = eval_result['match_success']
                item['gt_action'] = eval_result['gt_action']
                item['pred_action'] = eval_result['pred_action']
                item['eval_info'] = eval_result['info']
                
            except Exception as e:
                item['correct'] = False
                item['type_match'] = False
                item['match_success'] = False
                item['eval_info'] = f'exception: {str(e)}'
        
        actual_output = self.save_data(data, output_file, input_file)
        print(f"\nResults saved to: {actual_output}")
        
        return {'total': total, 'output_file': actual_output}


def main():
    parser = argparse.ArgumentParser(description='AndroidControl action evaluation')
    
    parser.add_argument('--input_file', type=str, required=True,
                       help='Path to prediction results file (JSON/JSONL)')
    parser.add_argument('--output_file', type=str, required=True,
                       help='Path to evaluation output file')
    parser.add_argument('--exp_name', type=str, required=True,
                       help='Experiment name')
    parser.add_argument('--model_type', type=str, default=None,
                       help='Model type (optional, auto-detected if not provided)')
    
    args = parser.parse_args()
    
    if not Path(args.input_file).exists():
        raise FileNotFoundError(f"Input file not found: {args.input_file}")
    
    judge = AndroidControlJudge()
    judge.evaluate(
        input_file=args.input_file,
        output_file=args.output_file,
        exp_name=args.exp_name,
        model_type=args.model_type
    )


if __name__ == '__main__':
    main()
