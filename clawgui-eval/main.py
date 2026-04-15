# Copyright 2026 Zhejiang University (ZJU), China
# and the ZJU-REAL-GUI team.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.


import json
import argparse
import time
import threading
import os
from pathlib import Path
from typing import List, Dict, Any
from tqdm import tqdm
from concurrent.futures import ThreadPoolExecutor, as_completed
import multiprocessing as mp

# must be set before importing torch
mp.set_start_method('spawn', force=True)

from inference import get_inferencer

BENCHMARK_DATA_MAP = {
    # screenspot-v2
    "screenspot-v2"           : "data/screenspot-v2.json",
    "screenspot-v2-kimi"      : "data/screenspot-v2.json",
    "screenspot-v2-qwen3vl"   : "data/screenspot-v2-qwen3vl.json",
    "screenspot-v2-qwen25vl"  : "data/screenspot-v2-qwen25vl.json",
    "screenspot-v2-uitars"    : "data/screenspot-v2-uitars.json",
    "screenspot-v2-maiui"     : "data/screenspot-v2-maiui.json",
    "screenspot-v2-uivenus15" : "data/screenspot-v2-uivenus15.json",
    "screenspot-v2-guiowl15"  : "data/screenspot-v2-guiowl15.json",
    "screenspot-v2-stepgui"   : "data/screenspot-v2-stepgui.json",
    "screenspot-v2-guig2"     : "data/screenspot-v2-guig2.json",
    "screenspot-v2-uivenus"   : "data/screenspot-v2-guig2.json",
    # screenspot-pro
    "screenspot-pro"          : "data/screenspot-pro.json",
    "screenspot-pro-seed"     : "data/screenspot-pro.json",
    "screenspot-pro-kimi"     : "data/screenspot-pro.json",
    "screenspot-pro-qwen3vl"  : "data/screenspot-pro-qwen3vl.json",
    "screenspot-pro-qwen25vl" : "data/screenspot-pro-qwen25vl.json",
    "screenspot-pro-uitars"   : "data/screenspot-pro-uitars.json",
    "screenspot-pro-maiui"    : "data/screenspot-pro-maiui.json",
    "screenspot-pro-uivenus15": "data/screenspot-pro-uivenus15.json",
    "screenspot-pro-guiowl15" : "data/screenspot-pro-guiowl15.json",
    "screenspot-pro-stepgui"  : "data/screenspot-pro-stepgui.json",
    "screenspot-pro-guig2"    : "data/screenspot-pro-guig2.json",
    "screenspot-pro-uivenus"  : "data/screenspot-pro-guig2.json",
    # uivision
    "uivision"                : "data/uivision.json",
    "uivision-kimi"           : "data/uivision.json",
    "uivision-qwen3vl"        : "data/uivision-qwen3vl.json",
    "uivision-qwen25vl"       : "data/uivision-qwen25vl.json",
    "uivision-uitars"         : "data/uivision-uitars.json",
    "uivision-maiui"          : "data/uivision-maiui.json",
    "uivision-uivenus15"      : "data/uivision-uivenus15.json",
    "uivision-guiowl15"       : "data/uivision-guiowl15.json",
    "uivision-stepgui"        : "data/uivision-stepgui.json",
    "uivision-guig2"          : "data/uivision-guig2.json",
    "uivision-uivenus"        : "data/uivision-guig2.json",
    # osworld-g
    "osworld-g"               : "data/osworld-g.json",
    "osworld-g-kimi"          : "data/osworld-g.json",
    "osworld-g-qwen3vl"       : "data/osworld-g-qwen3vl.json",
    "osworld-g-qwen25vl"      : "data/osworld-g-qwen25vl.json",
    "osworld-g-uitars"        : "data/osworld-g-uitars.json",
    "osworld-g-maiui"         : "data/osworld-g-maiui.json",
    "osworld-g-uivenus15"     : "data/osworld-g-uivenus15.json",
    "osworld-g-guiowl15"      : "data/osworld-g-guiowl15.json",
    "osworld-g-stepgui"       : "data/osworld-g-stepgui.json",
    "osworld-g-guig2"         : "data/osworld-g-guig2.json",
    "osworld-g-uivenus"       : "data/osworld-g-guig2.json",
    # mmbench-gui
    "mmbench-gui"             : "data/mmbench-gui.json",
    "mmbench-gui-kimi"        : "data/mmbench-gui.json",
    "mmbench-gui-qwen3vl"     : "data/mmbench-gui-qwen3vl.json",
    "mmbench-gui-qwen25vl"    : "data/mmbench-gui-qwen25vl.json",
    "mmbench-gui-uitars"      : "data/mmbench-gui-uitars.json",
    "mmbench-gui-maiui"       : "data/mmbench-gui-maiui.json",
    "mmbench-gui-uivenus15"   : "data/mmbench-gui-uivenus15.json",
    "mmbench-gui-guiowl15"    : "data/mmbench-gui-guiowl15.json",
    "mmbench-gui-stepgui"     : "data/mmbench-gui-stepgui.json",
    "mmbench-gui-guig2"       : "data/mmbench-gui-guig2.json",
    "mmbench-gui-uivenus"     : "data/mmbench-gui-guig2.json",
    # androidcontrol
    "androidcontrol-high-qwen3vl" : "data/androidcontrol_high_qwen3vl.json",
    "androidcontrol-low-qwen3vl"  : "data/androidcontrol_low_qwen3vl.json",
}

def load_data(data_path: str) -> List[Dict[str, Any]]:
    data_path = Path(data_path)
    
    if not data_path.exists():
        raise FileNotFoundError(f"data file not found: {data_path}")
    
    samples = []
    
    if data_path.suffix == ".jsonl":
        with open(data_path, 'r', encoding='utf-8') as f:
            for line_num, line in enumerate(f, 1):
                line = line.strip()
                if line:
                    try:
                        sample = json.loads(line)
                        samples.append(sample)
                    except json.JSONDecodeError as e:
                        print(f"warning: failed to parse line {line_num}: {e}")
    
    elif data_path.suffix == ".json":
        with open(data_path, 'r', encoding='utf-8') as f:
            data = json.load(f)
            if isinstance(data, list):
                samples = data
            else:
                samples = [data]
    
    else:
        raise ValueError(f"unsupported file format: {data_path.suffix} (expected .json or .jsonl)")
    
    return samples


def save_single_result(
    result: Dict[str, Any],
    output_path: str,
    output_format: str = "jsonl",
    lock: threading.Lock = None
):
    """Save a single result to file (thread-safe)."""
    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    
    if output_format == "jsonl":
        if lock:
            with lock:
                with open(output_path, 'a', encoding='utf-8') as f:
                    f.write(json.dumps(result, ensure_ascii=False) + '\n')
        else:
            with open(output_path, 'a', encoding='utf-8') as f:
                f.write(json.dumps(result, ensure_ascii=False) + '\n')


def load_completed_ids(output_path: str) -> set:
    output_path = Path(output_path)
    if not output_path.exists():
        return set()
    
    completed_ids = set()
    with open(output_path, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line:
                try:
                    item = json.loads(line)
                    if 'id' in item:
                        completed_ids.add(item['id'])
                except:
                    pass
    return completed_ids


def run_inference(
    inferencer,
    samples: List[Dict[str, Any]],
    output_path: str,
    output_format: str = "jsonl",
    verbose: bool = True,
    resume: bool = True
) -> tuple[List[Dict[str, Any]], float]:
    """Single-process inference (for API backend or single GPU)."""
    results = []
    total_inference_time = 0.0
    
    output_path_obj = Path(output_path)
    output_path_obj.parent.mkdir(parents=True, exist_ok=True)
    
    completed_ids = set()
    if resume and output_path_obj.exists():
        completed_ids = load_completed_ids(output_path)
        print(f"[resume] {len(completed_ids)} samples already completed")
    
    samples_to_process = [s for s in samples if s.get('id') not in completed_ids]
    
    if len(samples_to_process) == 0:
        print("all samples already completed, skipping")
        return results, 0.0
    
    iterator = tqdm(samples_to_process, desc="inference") if verbose else samples_to_process
    
    for idx, sample in enumerate(iterator):
        try:
            result = inferencer.infer_single(sample)
            
            inference_time = result.get('inference_time', 0.0)
            total_inference_time += inference_time
            
            if verbose:
                status = "FAIL" if 'error' in result else "OK"
                
                response_preview = result.get('model_response', 'N/A')
                if len(response_preview) > 50:
                    response_preview = response_preview[:50] + "..."
                
                tqdm.write(
                    f"[{status}] "
                    f"id={result['id']:<30} | "
                    f"model={result['model_type']:<10} | "
                    f"output={response_preview:<52} | "
                    f"{inference_time:.2f}s"
                )
            
            output_sample = sample.copy()
            answer_key = f"{inferencer.model_type}_infer"
            output_sample[answer_key] = result.get("model_response", "")
            
            if 'error' in result:
                output_sample['error'] = result['error']
            results.append(output_sample)
            
            save_single_result(output_sample, output_path, output_format)
        
        except Exception as e:
            output_sample = sample.copy()
            answer_key = f"{inferencer.model_type}_infer"
            output_sample[answer_key] = ""
            output_sample["error"] = str(e)
            
            results.append(output_sample)
            
            save_single_result(output_sample, output_path, output_format)
            
            if verbose:
                tqdm.write(f"[error] id={sample.get('id', 'unknown'):<30} | {e}")
    
    return results, total_inference_time


def process_single_sample(inferencer, sample, output_path, write_lock, pbar=None):
    """Process a single sample (used by multithreaded inference)."""
    try:
        result = inferencer.infer_single(sample)
        
        output_sample = sample.copy()
        answer_key = f"{inferencer.model_type}_infer"
        output_sample[answer_key] = result.get("model_response", "")
        
        if 'error' in result:
            output_sample['error'] = result['error']
        
        save_single_result(output_sample, output_path, "jsonl", write_lock)
        
        if pbar:
            with write_lock:
                pbar.update(1)
                pbar.set_postfix_str(result['id'])
        
        return output_sample, result
    
    except Exception as e:
        output_sample = sample.copy()
        answer_key = f"{inferencer.model_type}_infer"
        output_sample[answer_key] = ""
        output_sample["error"] = str(e)
        
        save_single_result(output_sample, output_path, "jsonl", write_lock)
        
        if pbar:
            with write_lock:
                pbar.update(1)
                tqdm.write(f"[error] id={sample.get('id', 'unknown'):<30} | {e}")
        
        return output_sample, {'error': str(e), 'id': sample.get('id', 'unknown')}


def run_inference_multithreaded(
    inferencer,
    samples: List[Dict[str, Any]],
    output_path: str,
    num_threads: int = 8,
    verbose: bool = True,
    resume: bool = True
) -> tuple[List[Dict[str, Any]], float]:
    """Multithreaded inference (for API backend)."""
    results = []
    total_inference_time = 0.0
    
    output_path_obj = Path(output_path)
    output_path_obj.parent.mkdir(parents=True, exist_ok=True)
    
    completed_ids = set()
    if resume and output_path_obj.exists():
        completed_ids = load_completed_ids(output_path)
        print(f"[resume] {len(completed_ids)} samples already completed")
    
    samples_to_process = [s for s in samples if s.get('id') not in completed_ids]
    
    if len(samples_to_process) == 0:
        print("all samples already completed, skipping")
        return results, 0.0
    
    print(f"[multithreaded] {num_threads} threads")
    print(f"pending: {len(samples_to_process)}")
    
    write_lock = threading.Lock()
    pbar = tqdm(total=len(samples_to_process), desc="inference") if verbose else None
    
    with ThreadPoolExecutor(max_workers=num_threads) as executor:
        future_to_sample = {
            executor.submit(process_single_sample, inferencer, sample, output_path, write_lock, pbar): sample
            for sample in samples_to_process
        }
        
        for future in as_completed(future_to_sample):
            try:
                output_sample, result = future.result()
                results.append(output_sample)
                
                if isinstance(result, dict) and 'inference_time' in result:
                    total_inference_time += result.get('inference_time', 0.0)
                
            except Exception as e:
                sample = future_to_sample[future]
                if verbose and pbar:
                    with write_lock:
                        tqdm.write(f"[thread error] id={sample.get('id', 'unknown')} | {e}")
    
    if pbar:
        pbar.close()
    
    return results, total_inference_time


def worker_process(
    gpu_id: int,
    samples_chunk: List[Dict[str, Any]],
    output_path: str,
    args_dict: Dict[str, Any],
    progress_queue: mp.Queue
):
    """Worker process: run inference on a data shard using a single GPU."""
    # Set CUDA_VISIBLE_DEVICES before importing any CUDA libraries
    import os
    os.environ['CUDA_VISIBLE_DEVICES'] = str(gpu_id)
    
    import torch
    from inference import get_inferencer
    
    visible_gpus = torch.cuda.device_count()
    print(f"Worker-{gpu_id}: visible GPUs = {visible_gpus}")
    
    if visible_gpus != 1:
        print(f"Worker-{gpu_id}: warning: expected 1 GPU, got {visible_gpus}")
    
    torch.cuda.set_device(0)
    
    print(f"Worker-{gpu_id}: initializing model (tensor_parallel_size=1)...")
    
    inferencer = get_inferencer(
        model_type=args_dict['model_type'],
        model_path=args_dict['model_path'],
        backend=args_dict['backend'],
        api_key=args_dict.get('api_key', 'EMPTY'),
        api_base=args_dict.get('api_base'),
        model_name=args_dict.get('model_name', 'qwen-vl-plus'),
        max_tokens=args_dict.get('max_tokens', 512),
        temperature=args_dict.get('temperature', 0.0),
        top_p=args_dict.get('top_p', 1.0),
        top_k=args_dict.get('top_k', -1),
        tv_or_vt=args_dict.get('tv_or_vt', 'vt'),
        min_pixels=args_dict.get('min_pixels'),
        max_pixels=args_dict.get('max_pixels'),
        system_prompt_mode=args_dict.get('system_prompt_mode', ''),
        use_cache=args_dict.get('use_cache', True),
        zoom=args_dict.get('zoom', False),
    )

    print(f"Worker-{gpu_id}: model ready, processing {len(samples_chunk)} samples")
    
    for sample in samples_chunk:
        try:
            result = inferencer.infer_single(sample)
            output_sample = sample.copy()
            answer_key = f"{inferencer.model_type}_infer"
            output_sample[answer_key] = result.get("model_response", "")
            
            if 'error' in result:
                output_sample['error'] = result['error']
            
            save_single_result(output_sample, output_path, "jsonl")
            progress_queue.put(1)
            
        except Exception as e:
            output_sample = sample.copy()
            answer_key = f"{inferencer.model_type}_infer"
            output_sample[answer_key] = ""
            output_sample["error"] = str(e)
            
            save_single_result(output_sample, output_path, "jsonl")
            progress_queue.put(1)


def run_inference_multigpu(
    samples: List[Dict[str, Any]],
    output_path: str,
    num_gpus: int,
    args_dict: Dict[str, Any],
    resume: bool = True
) -> tuple[List[Dict[str, Any]], float]:
    """Multi-GPU inference (one worker process per GPU)."""
    output_path_obj = Path(output_path)
    output_path_obj.parent.mkdir(parents=True, exist_ok=True)
    
    completed_ids = set()
    if resume and output_path_obj.exists():
        completed_ids = load_completed_ids(output_path)
        print(f"[resume] {len(completed_ids)} samples already completed")
    
    samples_to_process = [s for s in samples if s.get('id') not in completed_ids]
    
    if len(samples_to_process) == 0:
        print("all samples already completed, skipping")
        return [], 0.0
    
    print(f"\n[multi-GPU] {num_gpus} GPUs")
    print(f"pending: {len(samples_to_process)}")
    
    
    chunk_size = len(samples_to_process) // num_gpus
    samples_chunks = []
    for i in range(num_gpus):
        start_idx = i * chunk_size
        if i == num_gpus - 1:
            end_idx = len(samples_to_process)
        else:
            end_idx = start_idx + chunk_size
        samples_chunks.append(samples_to_process[start_idx:end_idx])
    
    print(f"data shards: {[len(chunk) for chunk in samples_chunks]}")
    
    start_time = time.time()
    
    progress_queue = mp.Queue()
    
    processes = []
    for gpu_id in range(num_gpus):
        p = mp.Process(
            target=worker_process,
            args=(gpu_id, samples_chunks[gpu_id], output_path, args_dict, progress_queue)
        )
        p.start()
        processes.append(p)
        print(f"  Worker-{gpu_id} started ({len(samples_chunks[gpu_id])} samples)")
    
    print()
    
    total_samples = len(samples_to_process)
    with tqdm(total=total_samples, desc="inference") as pbar:
        completed = 0
        while completed < total_samples:
            try:
                progress_queue.get(timeout=1)
                completed += 1
                pbar.update(1)
            except:
                pass
    
    for i, p in enumerate(processes):
        p.join()
        print(f"  Worker-{i} done")
    
    elapsed_time = time.time() - start_time
    
    print(f"\n{'='*60}")
    print("inference stats:")
    print(f"{'='*60}")
    print(f"  samples:    {total_samples}")
    print(f"  elapsed:    {elapsed_time:.2f}s")
    print(f"  throughput: {total_samples/elapsed_time:.2f} samples/s")
    print(f"{'='*60}\n")
    
    results = []
    if output_path_obj.exists():
        with open(output_path_obj, 'r', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if line:
                    try:
                        results.append(json.loads(line))
                    except:
                        pass
    
    return results, elapsed_time


def print_statistics(
    total_samples: int,
    processed_samples: int,
    total_inference_time: float,
    total_elapsed_time: float
):
    success = processed_samples
    
    avg_inference_time = total_inference_time / success if success > 0 else 0.0
    
    print("\n" + "="*80)
    print("inference stats")
    print("="*80)
    print(f"total:            {total_samples}")
    print(f"processed:        {processed_samples}")
    print(f"inference time:   {total_inference_time:.2f}s")
    print(f"avg infer time:   {avg_inference_time:.2f}s")
    print(f"total elapsed:    {total_elapsed_time:.2f}s")
    print("="*80)


def main():
    parser = argparse.ArgumentParser(
        description="GUI grounding inference script",
        formatter_class=argparse.RawDescriptionHelpFormatter
    )
    
    parser.add_argument(
        "--model_type",
        type=str,
        required=True,
        help="model type (e.g. qwen3vl, qwen25vl, maiui, uitars, etc.)"
    )
    parser.add_argument(
        "--model_path",
        type=str,
        required=True,
        help="model path or HuggingFace model ID"
    )
    parser.add_argument(
        "--experiment_name",
        type=str,
        required=True,
        help="experiment name (used for output directory)"
    )
    parser.add_argument(
        "--benchmark",
        type=str,
        default=None,
        help="benchmark name; if omitted, runs all benchmarks"
    )
    parser.add_argument(
        "--backend",
        type=str,
        default="transformers",
        choices=["transformers", "api"],
        help="inference backend (default: transformers)"
    )
    parser.add_argument(
        "--resume",
        action="store_true",
        default=True,
        help="resume from checkpoint (default: True)"
    )
    parser.add_argument(
        "--api_key",
        type=str,
        default="EMPTY",
        help="API key (for api backend)"
    )
    parser.add_argument(
        "--api_base",
        type=str,
        default=None,
        help="API base URL (for api backend; falls back to model_path if not set)"
    )
    parser.add_argument(
        "--model_name",
        type=str,
        default="qwen-vl-plus",
        help="served model name for API calls (default: qwen-vl-plus)"
    )
    parser.add_argument(
        "--max_tokens",
        type=int,
        default=512,
        help="max generation tokens (default: 512)"
    )
    parser.add_argument(
        "--temperature",
        type=float,
        default=0.0,
        help="sampling temperature (default: 0.0)"
    )
    parser.add_argument(
        "--top_p",
        type=float,
        default=1.0,
        help="nucleus sampling top_p (default: 1.0)"
    )
    parser.add_argument(
        "--top_k",
        type=int,
        default=-1,
        help="top_k sampling (-1 to disable, default: -1)"
    )
    parser.add_argument(
        "--tv_or_vt",
        type=str,
        default="vt",
        choices=["tv", "vt"],
        help="input order: tv=text first, vt=image first (default: vt)"
    )
    parser.add_argument(
        "--min_pixels",
        type=int,
        default=None,
        help="min image pixels for resizing (default: model default)"
    )
    parser.add_argument(
        "--max_pixels",
        type=int,
        default=None,
        help="max image pixels for resizing (default: model default)"
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="enable verbose output"
    )
    parser.add_argument(
        "--num_gpus",
        type=int,
        default=1,
        help="number of GPUs, transformers backend only (default: 1)"
    )
    parser.add_argument(
        "--num_threads",
        type=int,
        default=8,
        help="number of threads for api backend (default: 8)"
    )
    parser.add_argument(
        "--system_prompt",
        type=str,
        default="",
        help="system prompt mode: ''=disabled, 'default'=generic prompt, 'call_user'=read from data"
    )
    parser.add_argument(
        "--use_cache",
        type=lambda x: x.lower() in ('true', '1', 'yes'),
        default=True,
        help="enable KV cache during generation, transformers backend only (default: True)"
    )
    parser.add_argument(
        "--zoom",
        action="store_true",
        default=False,
        help="enable Zoom-In two-stage grounding (default: False)"
    )
    
    args = parser.parse_args()
    
    start_time = time.time()
    
    benchmarks_to_run = []
    if args.benchmark:
        if args.benchmark in BENCHMARK_DATA_MAP:
            benchmarks_to_run = [(args.benchmark, BENCHMARK_DATA_MAP[args.benchmark])]
        else:
            raise ValueError(f"unknown benchmark: {args.benchmark}")
    else:
        benchmarks_to_run = list(BENCHMARK_DATA_MAP.items())
    
    print("\n" + "="*80)
    print("ClawGUI-Eval: GUI Grounding Inference")
    print("="*80)
    print(f"model type:  {args.model_type}")
    print(f"model path:  {args.model_path}")
    print(f"backend:     {args.backend}")
    print(f"experiment:  {args.experiment_name}")
    if args.backend in ["transformers"] and args.num_gpus > 1:
        print(f"GPUs:        {args.num_gpus}")
    print(f"benchmarks:  {', '.join([b[0] for b in benchmarks_to_run])}")
    print("="*80)
    
    inferencer = None
    if args.backend in ["transformers"] and args.num_gpus > 1:
        print("\n[1/2] preparing multi-GPU inference...")
        print(f"  model type: {args.model_type}")
        print(f"  backend:    {args.backend}")
        print(f"  GPUs:       {args.num_gpus}")
        print(f"  model will be initialized independently in each worker")
    else:
        print("\n[1/2] initializing inferencer...")
        print(f"  model type: {args.model_type}")
        print(f"  backend:    {args.backend}")
        
        inferencer = get_inferencer(
            model_type=args.model_type,
            model_path=args.model_path,
            backend=args.backend,
            api_key=args.api_key,
            api_base=args.api_base,
            model_name=args.model_name,
            max_tokens=args.max_tokens,
            temperature=args.temperature,
            top_p=args.top_p,
            top_k=args.top_k,
            tv_or_vt=args.tv_or_vt,
            min_pixels=args.min_pixels,
            max_pixels=args.max_pixels,
            system_prompt_mode=args.system_prompt,
            use_cache=args.use_cache,
            zoom=args.zoom,
        )
        
        print(f"  inferencer ready")
        if args.zoom:
            print(f"  zoom: enabled")
    
    total_samples_all = 0
    total_inference_time_all = 0.0
    processed_samples_all = 0
    
    for benchmark_name, data_path in benchmarks_to_run:
        print(f"\n{'='*80}")
        print(f"benchmark: {benchmark_name}")
        print(f"{'='*80}")
        
        print("\n[2/2] loading data...")
        samples = load_data(data_path)
        print(f"  loaded {len(samples)} samples")
        
        output_path = f"output/{args.experiment_name}/{benchmark_name}/predictions.jsonl"
        
        print("\nrunning inference...")
        print(f"  output: {output_path}")
        print(f"  resume: {'on' if args.resume else 'off'}")
        
        if args.backend == "transformers" and args.num_gpus > 1:
            print(f"  mode: multi-GPU ({args.num_gpus} GPUs)")
            print()
            
            args_dict = {
                'model_type': args.model_type,
                'model_path': args.model_path,
                'backend': args.backend,
                'api_key': args.api_key,
                'api_base': args.api_base,
                'model_name': args.model_name,
                'max_tokens': args.max_tokens,
                'temperature': args.temperature,
                'top_p': args.top_p,
                'top_k': args.top_k,
                'tv_or_vt': args.tv_or_vt,
                'min_pixels': args.min_pixels,
                'max_pixels': args.max_pixels,
                'system_prompt_mode': args.system_prompt,
                'use_cache': args.use_cache,
                'zoom': args.zoom,
            }
            
            results, total_inference_time = run_inference_multigpu(
                samples=samples,
                output_path=output_path,
                num_gpus=args.num_gpus,
                args_dict=args_dict,
                resume=args.resume
            )
        elif args.backend == "api" and args.num_threads > 1:
            print(f"  mode: multithreaded ({args.num_threads} threads)")
            print()
            
            results, total_inference_time = run_inference_multithreaded(
                inferencer=inferencer,
                samples=samples,
                output_path=output_path,
                num_threads=args.num_threads,
                verbose=args.verbose or True,
                resume=args.resume
            )
        else:
            print(f"  mode: single process")
            print()
            
            results, total_inference_time = run_inference(
                inferencer=inferencer,
                samples=samples,
                output_path=output_path,
                output_format="jsonl",
                verbose=args.verbose or True,
                resume=args.resume
            )
        
        total_samples_all += len(samples)
        processed_samples_all += len(results)
        total_inference_time_all += total_inference_time
        
        print(f"\nsaved: {output_path}")
    
    end_time = time.time()
    total_elapsed_time = end_time - start_time
    
    print_statistics(
        total_samples=total_samples_all,
        processed_samples=processed_samples_all,
        total_inference_time=total_inference_time_all,
        total_elapsed_time=total_elapsed_time
    )
    
    print("\ndone.\n")


if __name__ == "__main__":
    main()
