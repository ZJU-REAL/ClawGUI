# Copyright 2025 Nanyang Technological University (NTU), Singapore
# and the verl-agent (GiGPO) team.
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

import os
import time
import subprocess
import numpy as np
import ray
import requests
from typing import List, Tuple, Dict, Any
from urllib.parse import urlparse


def load_available_servers(server_file: str = None) -> List[str]:
    """
    Load available server URLs from file.
    Each line should contain one URL (e.g., http://localhost:6800 or 10.130.138.49:7000)
    """
    if server_file is None:
        # Default path
        current_dir = os.path.dirname(os.path.abspath(__file__))
        server_file = os.path.join(current_dir, "mobileworld_server.txt")
    
    if not os.path.exists(server_file):
        raise FileNotFoundError(
            f"Server file {server_file} does not exist. "
            f"Please create it with one URL per line."
        )
    
    servers = []
    seen = set()
    with open(server_file, 'r') as f:
        for line in f:
            line = line.strip()
            if line:
                # Add http:// prefix if not present
                if not line.startswith('http://') and not line.startswith('https://'):
                    line = 'http://' + line
                # Deduplicate: skip if already seen
                if line not in seen:
                    servers.append(line)
                    seen.add(line)
                else:
                    print(f"Warning: Duplicate server URL skipped: {line}")
    
    if not servers:
        raise ValueError(f"No valid server URLs found in {server_file}.")
    
    return servers


def load_task_ids_from_server(server_url: str) -> List[str]:
    """
    Load available task IDs from server via /task/list API.
    """
    try:
        resp = requests.get(f"{server_url}/task/list", timeout=10)
        resp.raise_for_status()
        task_list = resp.json()
        return [task["name"] for task in task_list]
    except Exception as e:
        print(f"Warning: Failed to load task list from {server_url}: {e}")
        return []



class MobileWorldWorker:
    """
    Ray Actor that connects to MobileWorld backend via HTTP API.
    Each worker connects to one server URL and manages one device.
    Supports automatic server failover: if health check or task_init fails,
    the worker will switch to a spare server from the backup pool.
    """
    
    def __init__(self, worker_id: int, max_interactions: int, server_url: str, 
                 spare_servers: List[str] = None, device: str = "emulator-5554"):
        """
        Initialize worker - just store configuration.
        
        Args:
            worker_id: Unique identifier for this worker
            max_interactions: Maximum steps per episode
            server_url: Full URL of MobileWorld backend (e.g., http://localhost:6800)
            spare_servers: List of backup server URLs for failover
            device: ADB device ID (default: emulator-5554)
        """
        self.worker_id = worker_id
        self.max_interactions = max_interactions
        self.server_url = server_url
        self.device = device
        
        # Server failover state
        self.spare_servers = list(spare_servers) if spare_servers else []
        
        self.current_step_count = 0
        self.current_task_name = None
        self.current_task_goal = None  # Store task goal for step info

    def _mark_server_bad(self, server_url: str):
        """Mark a server as bad: move it to the end of spare_servers.
        
        Since spare_servers is consumed in order (pop(0)), placing bad servers
        at the tail means they'll only be retried after all other candidates
        are exhausted. No separate bad_servers set needed.
        """
        # Remove from current position in spare list if present (avoid duplicates)
        if server_url in self.spare_servers:
            self.spare_servers.remove(server_url)
        # Append to tail — will be retried last
        self.spare_servers.append(server_url)
        print(f"[Worker {self.worker_id}] Marked server {server_url} as bad, "
              f"moved to spare tail. Spare servers: {len(self.spare_servers)}")

    def _switch_to_spare_server(self) -> bool:
        """
        Switch current server to a spare one by iterating through ALL spare servers.
        
        Strategy:
        1. Mark current server as bad (move to spare tail)
        2. Round 1: iterate through all spare servers with 1s sleep between each,
           health check with 3s timeout. Healthy → switch; unhealthy → move to tail.
        3. If round 1 fails, wait 10s, then do round 2 (same logic).
        4. If both rounds fail, return False (caller decides fallback).
        
        Returns True if switch succeeded, False if no healthy spare found after 2 rounds.
        """
        # Mark current server as bad (moves to spare tail)
        self._mark_server_bad(self.server_url)
        
        if not self.spare_servers:
            print(f"[Worker {self.worker_id}] No spare servers available! Staying on {self.server_url}")
            return False
        
        for round_num in range(1, 3):  # Round 1 and Round 2
            print(f"[Worker {self.worker_id}] Spare server scan round {round_num}, "
                  f"spare pool size: {len(self.spare_servers)}")
            
            # Take a snapshot of current spare list length to know when we've tried all
            num_to_try = len(self.spare_servers)
            
            for i in range(num_to_try):
                candidate = self.spare_servers.pop(0)
                
                # Sleep 1s between attempts to avoid overwhelming servers
                if i > 0:
                    time.sleep(1.0)
                
                print(f"[Worker {self.worker_id}] Round {round_num}, trying spare server "
                      f"({i+1}/{num_to_try}): {candidate}")
                try:
                    resp = requests.get(f"{candidate}/health", timeout=3)
                    resp.raise_for_status()
                    health = resp.json()
                    if health.get("ok", False):
                        self.server_url = candidate
                        print(f"[Worker {self.worker_id}] Switched to new server: {candidate}")
                        return True
                    else:
                        print(f"[Worker {self.worker_id}] Spare server {candidate} unhealthy: {health}")
                        # Move to tail for future retry
                        self.spare_servers.append(candidate)
                except Exception as e:
                    print(f"[Worker {self.worker_id}] Spare server {candidate} health check failed: {e}")
                    # Move to tail for future retry
                    self.spare_servers.append(candidate)
            
            # Round failed — if this is round 1, wait 10s before round 2
            if round_num == 1:
                print(f"[Worker {self.worker_id}] Round 1 exhausted all {num_to_try} spare servers, "
                      f"waiting 10s before round 2...")
                time.sleep(10)
        
        print(f"[Worker {self.worker_id}] Both rounds failed, no healthy spare server found! "
              f"Staying on {self.server_url}")
        return False

    @staticmethod
    def _parse_host_port(server_url: str) -> Tuple[str, int]:
        """Extract host IP and port from server URL like 'http://10.130.138.49:7003'."""
        parsed = urlparse(server_url)
        host = parsed.hostname or ""
        port = parsed.port or 7000
        return host, port

    @staticmethod
    def _container_name_from_port(port: int) -> str:
        """
        Derive container name from port by taking the last 3 digits.
        Port 7000 → env_0, 7003 → env_3, 7011 → env_11, 7123 → env_123.
        """
        env_index = port % 1000
        return f"mobile_world_env_{env_index}"

    def _check_health_with_failover(self) -> bool:
        """
        Check server health with stabilization retry and server switch.
        
        Strategy:
        1. First attempt on current server
        2. If failed, wait 20s for server to stabilize, then retry once
        3. If still failed, switch to spare server (last resort)
        
        Note: No emulator/container restart here. Periodic full container restart
        is handled by MobileWorldEnvs.restart_all_containers() at the training loop level.
        """
        # --- Phase 1: First attempt ---
        try:
            resp = requests.get(f"{self.server_url}/health", timeout=10)
            resp.raise_for_status()
            health = resp.json()
            if health.get("ok", False):
                return True
            else:
                print(f"[Worker {self.worker_id}] Server {self.server_url} unhealthy (attempt 1): {health}")
        except Exception as e:
            print(f"[Worker {self.worker_id}] Health check failed for {self.server_url} (attempt 1): {e}")
        
        # --- Phase 2: Wait 20s for stabilization, retry once ---
        print(f"[Worker {self.worker_id}] Health check failed, waiting 20s for server to stabilize...")
        time.sleep(20)
        try:
            resp = requests.get(f"{self.server_url}/health", timeout=10)
            resp.raise_for_status()
            health = resp.json()
            if health.get("ok", False):
                print(f"[Worker {self.worker_id}] Server recovered after 20s wait")
                return True
            else:
                print(f"[Worker {self.worker_id}] Server {self.server_url} still unhealthy after 20s wait: {health}")
        except Exception as e:
            print(f"[Worker {self.worker_id}] Health check still failed after 20s wait: {e}")
        
        # --- Phase 3: Switch to spare server (last resort) ---
        print(f"[Worker {self.worker_id}] Server {self.server_url} failed all health checks, switching...")
        if self._switch_to_spare_server():
            return True  # Switched successfully, new server is healthy
        return False  # No spare available

    def _task_init_with_failover(self, task_name: str) -> bool:
        """
        Try task/init on current server with stabilization retry and server switch.
        
        Strategy:
        1. First attempt on current server
        2. If failed, wait 20s for server to stabilize, then retry once
        3. If still failed, switch to spare servers and retry (last resort)
        
        Note: No emulator/container restart here. Periodic full container restart
        is handled by MobileWorldEnvs.restart_all_containers() at the training loop level.
        """
        base_timeout = 120
        
        # --- Phase 1: First attempt on current server ---
        try:
            resp = requests.post(
                f"{self.server_url}/task/init",
                json={"task_name": task_name, "req_device": self.device},
                timeout=base_timeout
            )
            resp.raise_for_status()
            return True  # Success
        except Exception as e:
            print(f"[Worker {self.worker_id}] task/init failed on {self.server_url} (attempt 1): {e}")
        
        # --- Phase 2: Wait 20s for stabilization, retry once ---
        print(f"[Worker {self.worker_id}] task/init failed, waiting 20s for server to stabilize...")
        time.sleep(20)
        try:
            resp = requests.post(
                f"{self.server_url}/task/init",
                json={"task_name": task_name, "req_device": self.device},
                timeout=base_timeout
            )
            resp.raise_for_status()
            print(f"[Worker {self.worker_id}] task/init succeeded after 20s wait")
            return True  # Success
        except Exception as e:
            print(f"[Worker {self.worker_id}] task/init still failed after 20s wait: {e}")
        
        # --- Phase 3: Failover to spare servers (2 rounds of full iteration) ---
        # Mark current server as bad (move to spare tail)
        self._mark_server_bad(self.server_url)
        
        if not self.spare_servers:
            print(f"[Worker {self.worker_id}] No spare servers for task/init failover!")
            return False
        
        for round_num in range(1, 3):  # Round 1 and Round 2
            print(f"[Worker {self.worker_id}] task/init failover round {round_num}, "
                  f"spare pool size: {len(self.spare_servers)}")
            
            num_to_try = len(self.spare_servers)
            
            for i in range(num_to_try):
                candidate = self.spare_servers.pop(0)
                
                # Sleep 1s between attempts
                if i > 0:
                    time.sleep(1.0)
                
                print(f"[Worker {self.worker_id}] Round {round_num}, trying spare "
                      f"({i+1}/{num_to_try}): {candidate}")
                
                # First health check (3s timeout)
                try:
                    resp = requests.get(f"{candidate}/health", timeout=3)
                    resp.raise_for_status()
                    health = resp.json()
                    if not health.get("ok", False):
                        print(f"[Worker {self.worker_id}] Spare {candidate} unhealthy: {health}")
                        self.spare_servers.append(candidate)
                        continue
                except Exception as e:
                    print(f"[Worker {self.worker_id}] Spare {candidate} health check failed: {e}")
                    self.spare_servers.append(candidate)
                    continue
                
                # Health OK → try task/init
                try:
                    resp = requests.post(
                        f"{candidate}/task/init",
                        json={"task_name": task_name, "req_device": self.device},
                        timeout=base_timeout
                    )
                    resp.raise_for_status()
                    self.server_url = candidate
                    print(f"[Worker {self.worker_id}] task/init succeeded on spare {candidate}")
                    return True
                except Exception as e:
                    print(f"[Worker {self.worker_id}] task/init failed on spare {candidate}: {e}")
                    self.spare_servers.append(candidate)
            
            # Round failed — wait 10s before round 2
            if round_num == 1:
                print(f"[Worker {self.worker_id}] task/init failover round 1 exhausted, "
                      f"waiting 10s before round 2...")
                time.sleep(10)
        
        print(f"[Worker {self.worker_id}] task/init failover: both rounds failed!")
        return False

    def reset(self, env_kwargs: Dict[str, Any]) -> Tuple[str, Dict[str, Any]]:
        """
        Reset environment with a new task.
        
        Logic:
        1. Check server health (with failover on 3 consecutive failures)
        2. Get task_name and task_goal from env_kwargs
        3. Initialize task via /task/init (with failover on failure)
        
        Args:
            env_kwargs: Dictionary containing 'task_name' and optionally 'goal'
            
        Returns:
            obs: Task goal string
            info: Task metadata
        """
        # Extract task_name and goal from env_kwargs
        # Support both 'task_name' and 'Task Name' for compatibility
        task_name = env_kwargs.get('task_name', None) or env_kwargs.get('Task Name', None)
        if task_name is None:
            raise ValueError("env_kwargs must contain 'task_name' or 'Task Name'")
        
        # Goal can be provided in env_kwargs or fetched from API
        task_goal = env_kwargs.get('question', None)
        
        # Step 1: Check server health (with automatic failover)
        health_ok = self._check_health_with_failover()
        if not health_ok:
            print(f"[Worker {self.worker_id}] WARNING: No healthy server available, proceeding with {self.server_url}")
        
        # Step 2: Reset counters and state (no need to call /init - server-side ensure_controller handles it)
        self.current_step_count = 0
        self.current_task_name = task_name
        
        # Step 4: Get task goal (from env_kwargs or API)
        if task_goal is None:
            try:
                resp = requests.get(
                    f"{self.server_url}/task/goal", 
                    params={"task_name": task_name}, 
                    timeout=10
                )
                resp.raise_for_status()
                task_goal = resp.json()
                if not isinstance(task_goal, str):
                    task_goal = str(task_goal)
            except Exception as e:
                print(f"Warning: Failed to get goal for task {task_name}: {e}")
                task_goal = f"Task: {task_name}"
        
        # Store task_goal for step info
        self.current_task_goal = task_goal
        
        # Step 5: Initialize task (with automatic failover on failure)
        task_init_ok = self._task_init_with_failover(task_name)
        if not task_init_ok:
            return f"Error: task/init failed on all servers", {
                "task_name": task_name, 
                "error": "task/init failed on all servers", 
                "won": False
            }
        
        # Wait for UI to stabilize after task init
        time.sleep(1.0)
        
        # Return observation and info
        obs = task_goal
        info = {
            "task_name": task_name,
            "task_goal": task_goal,
            "worker_id": self.worker_id,
            "server_url": self.server_url,
            "won": False
        }
        
        return obs, info

    def step(self, action: dict) -> Tuple[str, float, bool, Dict[str, Any]]:
        """
        Execute one step via /step API.
        
        Logic:
        1. Call POST /step with device and action (with retry on timeout)
        2. Only eval task completion when:
           - action_type == "answer" (agent declares task finished)
           - current_step_count >= max_interactions (max step reached)
        3. For other actions, skip eval and continue episode (done=False, reward=0)
        
        Args:
            action: Action dictionary with structure matching server.py's StepRequest
                   e.g., {"action_type": "click", "x": 100, "y": 200}
            
        Returns:
            obs: Action result string
            reward: 1.0 if task completed (eval score), 0.0 otherwise
            done: Episode finished flag (answer action or max_step)
            info: Step metadata including 'won' status
        """
        self.current_step_count += 1
        print('step 的action: ', action)
        
        # Execute action via /step API with retry logic for timeout
        # Note: server-side ensure_controller handles device init lazily
        max_retries = 3
        step_timeouts = [30, 60, 90]  # Timeout: 30s, 60s, 90s
        obs = None
        step_success = False
        
        attempt = 0
        while attempt < max_retries and not step_success:
            try:
                # Increase timeout for each retry: 30s, 60s, 90s
                timeout = step_timeouts[attempt]
                
                # Execute action via /step API
                resp = requests.post(
                    f"{self.server_url}/step",
                    json={"device": self.device, "action": action},
                    timeout=timeout
                )
                resp.raise_for_status()
                result = resp.json()
                obs = str(result.get("result", ""))
                print('返回的result: ', result)
                step_success = True
                
            except requests.exceptions.Timeout as e:
                attempt += 1
                print(f"Timeout in step for worker {self.worker_id} (attempt {attempt}/{max_retries}): {e}")
                if attempt >= max_retries:
                    # Last attempt failed
                    return f"Error: Timeout after {max_retries} retries", 0.0, True, {
                        "won": False, 
                        "error": f"Timeout after {max_retries} retries", 
                        "step_count": self.current_step_count,
                        "task_name": self.current_task_name,
                        "task_goal": getattr(self, 'current_task_goal', 'No goal specified')
                    }
                # Wait before retry (exponential backoff)
                time.sleep(2 ** (attempt - 1))
                
            except Exception as e:
                print(f"Error in step for worker {self.worker_id}: {e}")
                return f"Error: {str(e)}", 0.0, True, {
                    "won": False, 
                    "error": str(e), 
                    "step_count": self.current_step_count,
                    "task_name": self.current_task_name,
                    "task_goal": getattr(self, 'current_task_goal', 'No goal specified')
                }
        
        # Determine whether to eval: when action is "answer", "terminate", or max_step reached
        action_type = action.get("action_type", "")
        is_answer_action = (action_type == "answer")
        is_terminate_action = (action_type == "terminate")
        is_max_step = (self.current_step_count >= self.max_interactions)
        should_eval = is_answer_action or is_terminate_action or is_max_step
        
        task_completed = False
        score = 0.0
        eval_reason = ""
        done = False
        
        if should_eval:
            # Wait for UI to stabilize before eval
            time.sleep(1.0)
            
            def _do_eval_request():
                """Single eval request. Returns (score, reason) or raises."""
                eval_resp = requests.get(
                    f"{self.server_url}/task/eval",
                    json={"task_name": self.current_task_name, "req_device": self.device},
                    timeout=30
                )
                eval_resp.raise_for_status()
                eval_result = eval_resp.json()
                return float(eval_result.get("score", 0.0)), eval_result.get("reason", "")
            
            eval_success = False
            
            # --- Phase 1: First attempt ---
            try:
                score, eval_reason = _do_eval_request()
                task_completed = (score == 1.0)
                eval_success = True
                print(f'Task evaluation: score={score}, reason={eval_reason}, completed={task_completed}')
            except Exception as e:
                print(f"Warning: Failed to evaluate task for worker {self.worker_id} (attempt 1): {e}")
            
            if not eval_success:
                # --- Phase 2: Wait 5s for stabilization, retry once ---
                print(f"[Worker {self.worker_id}] Eval failed, waiting 10s for server to stabilize...")
                time.sleep(5)
                try:
                    score, eval_reason = _do_eval_request()
                    task_completed = (score == 1.0)
                    eval_success = True
                    print(f'Task evaluation after 10s wait: score={score}, reason={eval_reason}, completed={task_completed}')
                except Exception as e:
                    print(f"Warning: Eval still failed after 10s wait for worker {self.worker_id}: {e}")
            
            if not eval_success:
                print(f"Warning: All eval attempts failed for worker {self.worker_id}, "
                      f"defaulting score=0.0")
                score = 0.0
                task_completed = False
                eval_reason = "eval_failed_all_retries"
            
            # Episode ends when: answer/terminate action or max_step reached
            done = True
            if is_answer_action:
                print(f"[Worker {self.worker_id}] Agent output 'answer', eval score={score}")
            if is_terminate_action:
                print(f"[Worker {self.worker_id}] Agent output 'terminate', eval score={score}")
            if is_max_step:
                print(f"[Worker {self.worker_id}] Reached max_step={self.max_interactions}, eval score={score}")
        else:
            # Normal step (not answer, not max_step): no eval, episode continues
            print(f"[Worker {self.worker_id}] Step {self.current_step_count}/{self.max_interactions}, "
                  f"action={action_type}, skipping eval")
        
        # Reward: score from eval (0 or 1) if eval'd, otherwise 0
        reward = score
        
        # Note: tear_down is NOT called here anymore.
        # It is called centrally by rollout_loop after all workers finish,
        # to avoid race conditions where tear_down resets initialized=False
        # before eval retries can complete.

        info = {
            "won": task_completed, 
            "step_count": self.current_step_count,
            "task_name": self.current_task_name,
            "task_goal": getattr(self, 'current_task_goal', 'No goal specified'),
            "eval_score": score,
            "eval_reason": eval_reason
        }
        return obs, reward, done, info
    
    def _tear_down_task(self, task_name: str) -> bool:
        """
        Tear down the current task via POST /task/tear_down.
        This cleans up task state on the server side (stop backends, clear configs, etc.).
        Non-fatal: if tear_down fails, we just log a warning and continue.
        
        Returns:
            True if tear_down succeeded, False otherwise.
        """
        try:
            resp = requests.post(
                f"{self.server_url}/task/tear_down",
                json={"task_name": task_name, "req_device": self.device},
                timeout=30
            )
            resp.raise_for_status()
            print(f"[Worker {self.worker_id}] tear_down succeeded for task={task_name}")
            return True
        except Exception as e:
            print(f"[Worker {self.worker_id}] WARNING: tear_down failed for task={task_name}: {e}")
            return False

    def tear_down_current_task(self) -> dict:
        """
        Tear down the current task (called remotely by MobileWorldEnvs.tear_down_all).
        
        Returns:
            Dict with tear_down result:
                {"worker_id": int, "task_name": str, "success": bool, "error": str or None}
        """
        task_name = self.current_task_name
        if not task_name:
            return {
                "worker_id": self.worker_id,
                "task_name": "N/A",
                "success": True,
                "error": None
            }
        
        try:
            success = self._tear_down_task(task_name)
            return {
                "worker_id": self.worker_id,
                "task_name": task_name,
                "success": success,
                "error": None if success else "tear_down request failed"
            }
        except Exception as e:
            return {
                "worker_id": self.worker_id,
                "task_name": task_name,
                "success": False,
                "error": str(e)
            }

    @staticmethod
    def _validate_b64_png(b64_data: str) -> bool:
        """Quick check if base64 data is a valid, non-truncated PNG."""
        try:
            import base64 as _b64
            from io import BytesIO as _BytesIO
            from PIL import Image as _Image
            raw = _b64.b64decode(b64_data)
            img = _Image.open(_BytesIO(raw))
            img.load()  # Force full decode to catch truncated images
            return True
        except Exception:
            return False

    def get_screenshot(self, prefix: str = None, return_b64: bool = False) -> Dict[str, Any]:
        """
        Get screenshot via /screenshot API with stabilization retry.
        
        Strategy:
        1. First attempt
        2. If failed, wait 20s for stabilization, retry once
        3. If still failed, return error (upper layer uses fallback image)
        
        Note: No server restart here. Periodic full container restart
        is handled by MobileWorldEnvs.restart_all_containers() at the training loop level.
        
        Returns:
            Dictionary with 'b64_png' on success, or {'error': ..., 'error_type': ...} on failure
        """
        # Note: No need to call _ensure_initialized() - server handles lazy init via ensure_controller
        
        def _do_screenshot_request():
            """Single screenshot request. Returns (result_dict, error_type) or (None, error_type)."""
            params = {
                "device": self.device,
                "return_b64": return_b64
            }
            if prefix:
                params["prefix"] = prefix
            
            resp = requests.get(
                f"{self.server_url}/screenshot",
                params=params,
                timeout=30
            )
            resp.raise_for_status()
            result = resp.json()
            
            if return_b64:
                b64_data = result.get('b64_png', '')
                if b64_data and len(b64_data) > 0:
                    if self._validate_b64_png(b64_data):
                        return result, None  # Success
                    else:
                        return None, "corrupted"
                else:
                    return None, "empty"
            else:
                return result, None  # Success
        
        # --- Phase 1: First attempt ---
        first_error_type = None
        try:
            result, err_type = _do_screenshot_request()
            if result is not None:
                return result
            first_error_type = err_type
            print(f"Worker {self.worker_id}: Screenshot {first_error_type} (attempt 1)")
        except requests.exceptions.HTTPError as e:
            first_error_type = "http"
            print(f"Worker {self.worker_id}: HTTP error getting screenshot (attempt 1): {e}")
        except Exception as e:
            first_error_type = "other"
            print(f"Worker {self.worker_id}: Error getting screenshot (attempt 1): {e}")
        
        # --- Phase 2: Wait 20s for stabilization, retry once ---
        print(f"Worker {self.worker_id}: Screenshot failed, waiting 20s for server to stabilize...")
        time.sleep(20)
        try:
            result, err_type = _do_screenshot_request()
            if result is not None:
                print(f"Worker {self.worker_id}: Screenshot recovered after 20s wait")
                return result
            print(f"Worker {self.worker_id}: Screenshot still {err_type} after 20s wait")
            return {"error": err_type, "error_type": err_type}
        except requests.exceptions.HTTPError as e:
            print(f"Worker {self.worker_id}: HTTP error still present after 20s wait: {e}")
            return {"error": str(e), "error_type": "http"}
        except Exception as e:
            print(f"Worker {self.worker_id}: Error still present after 20s wait: {e}")
            return {"error": str(e), "error_type": "other"}

    def close(self):
        """
        Close the environment. Tears down current task if one is running.
        """
        if self.current_task_name:
            self._tear_down_task(self.current_task_name)


class MobileWorldEnvs:
    """
    Ray-based distributed wrapper for MobileWorld.
    Creates multiple workers (env_num * group_n), each connecting to a MobileWorld backend server.
    """
    
    def __init__(
        self,
        dataset_name: str,
        max_interactions: int,
        seed: int,
        env_num: int,
        group_n: int,
        start_server_id: int,
        resources_per_worker: dict,
        server_file: str = None,
        device: str = "emulator-5554"
    ):
        """
        Initialize distributed environment.
        
        Args:
            dataset_name: Dataset name (for compatibility, not used)
            max_interactions: Max steps per episode
            seed: Random seed
            env_num: Number of unique tasks per reset
            group_n: Number of workers per task (for GRPO/GiGPO)
            start_server_id: Starting index in server list
            resources_per_worker: Ray resources (e.g., {"num_cpus": 0.1})
            server_file: Path to server URL list file (default: mobileworld_server.txt in package dir)
            device: ADB device ID
        """
        super().__init__()
        
        self.dataset_name = dataset_name
        self.max_interactions = max_interactions
        self.env_num = env_num
        self.group_n = group_n
        self.num_processes = env_num * group_n  # Total workers = env_num * group_n
        self.seed = seed
        self.device = device
        
        # Load available servers from txt file
        all_servers = load_available_servers(server_file)
        
        # Save for later use (restart, resource allocation)
        self.resources_per_worker = resources_per_worker
        self.start_server_id = start_server_id
        
        # Pre-compute: group ALL servers from txt by host
        # e.g. {"10.130.138.47": ["http://10.130.138.47:7000", ..., ":7007"],
        #        "10.130.138.46": ["http://10.130.138.46:7000", ..., ":7007"]}
        from collections import defaultdict
        self.all_servers_by_host = defaultdict(list)
        for url in all_servers:
            host = urlparse(url).hostname or ""
            self.all_servers_by_host[host].append(url)
        # Also keep the full flat list for reference
        self.all_servers = list(all_servers)
        
        # Assign servers to workers: need num_processes servers
        # 从 start_server_id 开始取 num_processes 个 URL 作为主 server
        self.active_servers = all_servers[start_server_id:start_server_id + self.num_processes]
        
        if len(self.active_servers) < self.num_processes:
            raise ValueError(
                f"Need {self.num_processes} servers (env_num={env_num} * group_n={group_n}), "
                f"but only {len(self.active_servers)} available starting from index {start_server_id}. "
                f"Total servers in file: {len(all_servers)}"
            )
        
        # 剩余的 server 作为备用池，供 worker failover 使用
        # 包括 start_server_id 之前的和 start_server_id + num_processes 之后的
        self.spare_servers = (
            all_servers[:start_server_id] + 
            all_servers[start_server_id + self.num_processes:]
        )
        
        print(f"[MobileWorldEnvs] Active servers: {len(self.active_servers)}, "
              f"Spare servers: {len(self.spare_servers)}")
        host_counts = {h: len(urls) for h, urls in self.all_servers_by_host.items()}
        print(f"[MobileWorldEnvs] All servers by host: {host_counts}")
        
        # For backward compatibility
        self.available_servers = self.active_servers
        
        # Load task list from first server 
        self.task_ids = load_task_ids_from_server(self.active_servers[0])
        
        if not self.task_ids:
            print("Warning: No tasks loaded from server, using empty list")
            self.task_ids = []
        
        if self.task_ids and self.env_num > len(self.task_ids):
            print(f"Warning: env_num ({self.env_num}) > available tasks ({len(self.task_ids)})")
            self.env_num = len(self.task_ids)
            self.num_processes = self.env_num * self.group_n
        
        # Initialize Ray if not already initialized
        # Use include_dashboard=False to avoid connecting to existing Ray instances
        if not ray.is_initialized():
            # ray_temp_dir = f"/data/zju-129/tangfei/tmp/ray_{os.getlogin()}_{os.getpid()}"
            ray_temp_dir = f"/tmp/ray_{os.getlogin()}_{os.getpid()}"
            os.makedirs(ray_temp_dir, exist_ok=True)
            ray.init(
                include_dashboard=False, 
                namespace=f"mobileworld_{os.getpid()}",
                _temp_dir=ray_temp_dir
            )
        
        # Create Ray workers, each with a copy of spare_servers for failover
        env_worker = ray.remote(**resources_per_worker)(MobileWorldWorker)
        self.workers = []
        for i in range(self.num_processes):
            worker = env_worker.remote(
                worker_id=start_server_id + i,
                max_interactions=self.max_interactions,
                server_url=self.active_servers[i],
                spare_servers=list(self.spare_servers),  # Each worker gets its own copy
                device=self.device
            )
            self.workers.append(worker)
        
        self.rng = np.random.RandomState(seed)

    def step(self, actions: List[dict]) -> Tuple[List[str], List[float], List[bool], List[dict]]:
        """
        Execute actions in all workers in parallel.
        
        Args:
            actions: List of action dicts, length must equal num_processes
            
        Returns:
            obs_list: List of observation strings
            reward_list: List of rewards
            done_list: List of done flags
            info_list: List of info dicts
        """
        assert len(actions) == self.num_processes, \
            f"Expected {self.num_processes} actions, got {len(actions)}"
        
        # Send step commands to all workers with staggered delays
        futures = []
        for idx, (worker, action) in enumerate(zip(self.workers, actions)):
            # Add 0.2s delay between each worker to avoid overwhelming the server
            if idx > 0:
                time.sleep(0.2)
            futures.append(worker.step.remote(action))
        
        # Collect results
        results = ray.get(futures)
        
        obs_list, reward_list, done_list, info_list = [], [], [], []
        for obs, reward, done, info in results:
            obs_list.append(obs)
            reward_list.append(reward)
            done_list.append(done)
            info_list.append(info)
        
        return obs_list, reward_list, done_list, info_list

    def tear_down_all(self) -> List[dict]:
        """
        Tear down all workers' current tasks in parallel.
        Called centrally after the rollout loop finishes (all workers done or max_steps reached).
        
        Returns:
            List of result dicts, one per worker:
                {"worker_id": int, "task_name": str, "success": bool, "error": str or None}
        """
        futures = []
        for worker in self.workers:
            futures.append(worker.tear_down_current_task.remote())
        
        results = ray.get(futures)
        
        # Print summary
        success_count = sum(1 for r in results if r.get("success", False))
        fail_count = len(results) - success_count
        print(f"\n{'='*30} TEAR DOWN SUMMARY {'='*30}")
        print(f"  Total workers: {len(results)}  |  Success: {success_count}  |  Failed: {fail_count}")
        for r in results:
            status = "✅" if r.get("success") else "❌"
            worker_id = r.get("worker_id", "?")
            task_name = r.get("task_name", "N/A")
            error = r.get("error", "")
            if r.get("success"):
                print(f"  Worker {worker_id}: {status} tear_down task={task_name}")
            else:
                print(f"  Worker {worker_id}: {status} tear_down task={task_name}, error={error}")
        print(f"{'='*80}")
        
        return results

    def reset(self, env_kwargs_list: List[Dict[str, Any]] = None) -> Tuple[List[str], List[dict]]:
        """
        Reset all workers with new tasks.
        
        Args:
            env_kwargs_list: Optional list of env_kwargs dicts, one per worker.
                            If None, randomly sample tasks from task_ids.
                            Each dict should contain 'task_name' and optionally 'goal'.
        
        Returns:
            obs_list: List of observation strings (task goals)
            info_list: List of info dicts
        """
        if env_kwargs_list is None:
            # Sample env_num tasks and repeat for group_n
            if not self.task_ids:
                task_names = [f"dummy_task_{i}" for i in range(self.env_num)]
            else:
                task_names = self.rng.choice(
                    self.task_ids, 
                    min(self.env_num, len(self.task_ids)), 
                    replace=False
                )
            
            # Repeat each task group_n times
            task_names = np.repeat(task_names, self.group_n).tolist()
            
            # Create env_kwargs for each worker
            env_kwargs_list = [{"task_name": name} for name in task_names]
        else:
            assert len(env_kwargs_list) == self.num_processes, \
                f"Expected {self.num_processes} env_kwargs, got {len(env_kwargs_list)}"
        
        # Send reset commands to all workers with staggered delays
        futures = []
        for idx, (worker, env_kwargs) in enumerate(zip(self.workers, env_kwargs_list)):
            # Add 0.3s delay between each worker to avoid overwhelming the server
            if idx > 0:
                time.sleep(0.3)
            futures.append(worker.reset.remote(env_kwargs))
        
        # Collect results
        results = ray.get(futures)
        
        obs_list, info_list = [], []
        for obs, info in results:
            obs_list.append(obs)
            info_list.append(info)
        
        return obs_list, info_list
    
    def get_screenshots(self, prefix: str = None, return_b64: bool = False) -> List[Dict[str, Any]]:
        """
        Get screenshots from all workers.
        
        Args:
            prefix: Optional prefix for screenshot filenames
            return_b64: If True, return base64-encoded PNG data
            
        Returns:
            List of screenshot result dicts
        """
        futures = [worker.get_screenshot.remote(prefix, return_b64) for worker in self.workers]
        return ray.get(futures)

    def get_screenshots_for_indices(self, indices: List[int], prefix: str = None, return_b64: bool = False) -> Dict[int, Dict[str, Any]]:
        """
        Get screenshots only for specific worker indices (to avoid re-requesting successful workers).
        
        Returns:
            Dict mapping worker index -> screenshot result dict
        """
        futures = {idx: self.workers[idx].get_screenshot.remote(prefix, return_b64) for idx in indices}
        return {idx: ray.get(fut) for idx, fut in futures.items()}

    def reset_single_worker(self, worker_idx: int, env_kwargs: Dict[str, Any]) -> Tuple[str, dict]:
        """
        Reset a single worker with the given env_kwargs.
        Used when a worker needs to be re-initialized on a new server after failover.
        
        Args:
            worker_idx: Index of the worker to reset
            env_kwargs: Environment kwargs dict containing 'task_name' etc.
            
        Returns:
            Tuple of (obs_string, info_dict)
        """
        future = self.workers[worker_idx].reset.remote(env_kwargs)
        return ray.get(future)

    def replace_bad_server_to_spare_tail(self, worker_idx: int):
        """
        Move the current active server of a worker to the spare tail (mark as bad).
        Does NOT assign a new server — the caller should follow up with replace_worker_server()
        to pick the next healthy spare.
        
        This is used in reset-stage failover when a newly replaced server also fails:
        we put it back to spare tail so it's tried last, then call replace_worker_server
        again to get the next candidate.
        
        Args:
            worker_idx: Index of the worker whose server should be moved to spare tail
        """
        bad_server = self.active_servers[worker_idx]
        # Remove from spare if already present (avoid duplicates)
        if bad_server in self.spare_servers:
            self.spare_servers.remove(bad_server)
        self.spare_servers.append(bad_server)
        print(f"[MobileWorldEnvs] Worker {worker_idx}: Moved bad server {bad_server} to spare tail. "
              f"Spare pool size: {len(self.spare_servers)}")

    def replace_worker_server(self, worker_idx: int) -> bool:
        """
        Replace a failed worker's server with a spare one.
        Marks the old server as bad (appends to spare tail), picks a healthy spare,
        re-creates the Ray worker. Unhealthy candidates are also returned to spare tail.
        
        Args:
            worker_idx: Index of the worker to replace
            
        Returns:
            True if replacement succeeded, False if no healthy spare available
        """
        old_server = self.active_servers[worker_idx]
        print(f"[MobileWorldEnvs] Worker {worker_idx}: Replacing bad server {old_server}")
        print(f"[MobileWorldEnvs] Spare servers before replace: {len(self.spare_servers)}")
        
        # Put old server back to spare tail (it may recover after restart)
        # Remove first to avoid duplicates (e.g., if replace is called multiple times for same worker)
        if old_server in self.spare_servers:
            self.spare_servers.remove(old_server)
        self.spare_servers.append(old_server)
        
        # Track tried candidates to avoid infinite loop
        tried_in_this_call = {old_server}
        
        # Try to find a healthy spare
        while self.spare_servers:
            candidate = self.spare_servers[0]
            if candidate in tried_in_this_call:
                # Cycled through all candidates, none healthy
                break
            self.spare_servers.pop(0)
            tried_in_this_call.add(candidate)
            
            try:
                resp = requests.get(f"{candidate}/health", timeout=10)
                resp.raise_for_status()
                health = resp.json()
                if not health.get("ok", False):
                    print(f"[MobileWorldEnvs] Spare server {candidate} unhealthy, moving to spare tail")
                    self.spare_servers.append(candidate)
                    continue
            except Exception as e:
                print(f"[MobileWorldEnvs] Spare server {candidate} health check failed: {e}, moving to spare tail")
                self.spare_servers.append(candidate)
                continue
            
            # Found a healthy spare — kill old worker and create new one
            try:
                ray.kill(self.workers[worker_idx])
            except Exception:
                pass
            
            self.active_servers[worker_idx] = candidate
            env_worker = ray.remote(**self.resources_per_worker)(MobileWorldWorker)
            self.workers[worker_idx] = env_worker.remote(
                worker_id=self.start_server_id + worker_idx,
                max_interactions=self.max_interactions,
                server_url=candidate,
                spare_servers=list(self.spare_servers),
                device=self.device
            )
            print(f"[MobileWorldEnvs] Worker {worker_idx}: Replaced with spare server {candidate}")
            print(f"[MobileWorldEnvs] Spare servers after replace: {len(self.spare_servers)}")
            return True
        
        # Failed to find healthy spare — old_server is now in spare list but still active.
        # Remove it from spare to keep state consistent (it's still the active server for this worker).
        if old_server in self.spare_servers:
            self.spare_servers.remove(old_server)
        print(f"[MobileWorldEnvs] Worker {worker_idx}: No healthy spare servers available, "
              f"keeping {old_server}. Spare pool size: {len(self.spare_servers)}")
        return False

    def restart_all_containers(self, launch_interval: int = 3,
                               wait_after_run: int = 360,
                               rm_poll_interval: int = 10,
                               rm_poll_timeout: int = 120) -> bool:
        """
        Dynamic container restart with smart server pool reallocation.
        
        Only restarts hosts that are used by current active_servers (workers are using).
        Rebuilds containers per host according to the total count from mobileworld_server.txt.
        After restart, health-checks ALL server URLs (restarted + non-restarted hosts) and
        dynamically redistributes them into active / spare pools.
        
        Flow:
        0. Determine which hosts to restart (from active_servers)
        1-3. Per host (parallel): rm → poll → run (count from txt, timeout=60s, timeout is non-fatal)
        4. Poll health on all URLs of restarted hosts every 30s:
           - If healthy count >= num_processes + 4 (spare buffer) → early exit
           - If wait_after_run timeout reached → stop polling
           - Three categories: healthy → active+spare(front), not_ready → spare(mid), bad → spare(tail)
        4.5. Health-check all servers on non-restarted hosts
        5. Dynamic reallocation:
           - Fill active_servers to num_processes from healthy pool
           - Remaining healthy → spare_servers (front, tried first on failover)
           - Not-yet-ready → spare_servers (middle, tried next on failover)
           - Explicitly unhealthy → spare_servers (tail, tried last on failover)
        6. Rebuild Ray workers with new active server assignments
        
        Args:
            launch_interval: Seconds between container launches (default: 3)
            wait_after_run: Seconds to wait after `mw env run` for emulators to boot (default: 360 = 6 min)
            rm_poll_interval: Seconds between each poll of `mw env list` (default: 10)
            rm_poll_timeout: Max seconds to wait for all containers to be removed (default: 120)
            
        Returns:
            True if enough healthy servers to fill active pool, False otherwise.
        """
        import concurrent.futures
        
        # Dynamically detect local IP
        try:
            local_ip = subprocess.run(
                ["bash", "-c", "ip route get 1.1.1.1 | awk '{print $7}'"],
                capture_output=True, text=True, timeout=5
            ).stdout.strip()
        except Exception:
            local_ip = ""
        
        # ================================================================
        # Step 0: Determine which hosts to restart (only hosts used by active_servers)
        # ================================================================
        active_hosts = set()
        for server_url in self.active_servers:
            host = urlparse(server_url).hostname or ""
            active_hosts.add(host)
        
        # For each host to restart, get ALL server URLs from txt (not just active ones)
        # This ensures we rebuild the full set of containers on that host
        hosts_to_restart = {}  # host -> list of ALL server URLs on that host (from txt)
        for host in active_hosts:
            if host in self.all_servers_by_host:
                hosts_to_restart[host] = list(self.all_servers_by_host[host])
            else:
                print(f"[MobileWorldEnvs] WARNING: Host {host} not found in all_servers_by_host, skipping")
        
        if not hosts_to_restart:
            print(f"[MobileWorldEnvs] No hosts to restart, skipping")
            return True
        
        # Compute backend-start-port per host (minimum port from txt)
        host_start_ports = {}
        for host, urls in hosts_to_restart.items():
            ports = [urlparse(u).port or 7000 for u in urls]
            host_start_ports[host] = min(ports)
        
        print(f"[MobileWorldEnvs] === DYNAMIC RESTART ALL CONTAINERS ===")
        print(f"[MobileWorldEnvs] Active servers ({len(self.active_servers)}): {self.active_servers}")
        print(f"[MobileWorldEnvs] Spare servers ({len(self.spare_servers)}): {self.spare_servers}")
        print(f"[MobileWorldEnvs] Hosts to restart (from active_servers): {list(hosts_to_restart.keys())}")
        for host, urls in hosts_to_restart.items():
            ports = sorted([urlparse(u).port for u in urls])
            print(f"[MobileWorldEnvs]   {host}: {len(urls)} containers (ports: {ports}, start_port: {host_start_ports[host]})")
        
        def _run_cmd_on_host(host: str, cmd_str: str, timeout: int = 300) -> subprocess.CompletedProcess:
            """Run a command on the given host (local or remote via SSH)."""
            # Don't source .zshrc (it has zsh-only syntax that breaks under bash).
            # Just ensure uv/cargo/local bin are in PATH.
            full_cmd = (
                f"export PATH=$HOME/.local/bin:$HOME/.cargo/bin:/usr/local/bin:$PATH && "
                f"{cmd_str}"
            )
            is_local = (host == local_ip)
            if is_local:
                cmd = ["bash", "-c", full_cmd]
            else:
                cmd = ["ssh", "-p", "8822", "-o", "StrictHostKeyChecking=no", "-o", "ConnectTimeout=10",
                       f"tangfei@{host}", full_cmd]
            return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        
        def _restart_host(host: str, container_count: int, start_port: int) -> bool:
            """Restart all containers on a single host.
            
            Args:
                host: The host IP address
                container_count: Number of containers to create (from txt count on this host)
                start_port: Backend start port for this host (min port from txt)
            """
            mw_dir = "/home/tangfei/online_rl/MobileWorld"
            
            # Step 1: Remove all containers
            rm_cmd = f"cd {mw_dir} && uv run mw env rm --all"
            print(f"[MobileWorldEnvs] [{host}] Step 1: Removing all containers...")
            try:
                result = _run_cmd_on_host(host, rm_cmd, timeout=180)
                if result.returncode != 0:
                    print(f"[MobileWorldEnvs] [{host}] WARNING: rm --all returned exit code {result.returncode}")
                    print(f"  stderr: {result.stderr.strip()[:500]}")
                else:
                    print(f"[MobileWorldEnvs] [{host}] rm --all succeeded")
            except subprocess.TimeoutExpired:
                print(f"[MobileWorldEnvs] [{host}] WARNING: rm --all timed out after 180s")
            except Exception as e:
                print(f"[MobileWorldEnvs] [{host}] WARNING: rm --all error: {e}")
            
            # Step 2: Poll `mw env list` until no containers remain
            list_cmd = f"cd {mw_dir} && uv run mw env list"
            print(f"[MobileWorldEnvs] [{host}] Step 2: Waiting for all containers to be removed...")
            start_time = time.time()
            while time.time() - start_time < rm_poll_timeout:
                try:
                    result = _run_cmd_on_host(host, list_cmd, timeout=30)
                    output = result.stdout.strip()
                    # If output is empty or contains no container entries, deletion is complete
                    if not output or "mobile_world_env" not in output.lower():
                        print(f"[MobileWorldEnvs] [{host}] All containers removed (took {time.time() - start_time:.1f}s)")
                        break
                    else:
                        remaining = output.count("mobile_world_env")
                        print(f"[MobileWorldEnvs] [{host}] Still {remaining} containers remaining, waiting {rm_poll_interval}s...")
                except Exception as e:
                    print(f"[MobileWorldEnvs] [{host}] Error polling env list: {e}")
                time.sleep(rm_poll_interval)
            else:
                print(f"[MobileWorldEnvs] [{host}] WARNING: Containers not fully removed after {rm_poll_timeout}s, proceeding anyway...")
            
            # Step 3: Recreate containers (count from txt, start_port from txt)
            run_cmd = (
                f"cd {mw_dir} && uv run mw env run "
                f"--count {container_count} "
                f"--backend-start-port {start_port} "
                f"--viewer-start-port 8000 "
                f"--vnc-start-port 5900 "
                f"--adb-start-port 5600 "
                f"--launch-interval {launch_interval}"
            )
            print(f"[MobileWorldEnvs] [{host}] Step 3: Creating {container_count} new containers (start_port={start_port})...")
            try:
                result = _run_cmd_on_host(host, run_cmd, timeout=60)
                if result.returncode != 0:
                    # 命令执行了但返回非零，容器可能已经在后台创建，只打 WARNING
                    print(f"[MobileWorldEnvs] [{host}] WARNING: mw env run returned exit code {result.returncode} (containers may still be starting)")
                    print(f"  stderr: {result.stderr.strip()[:500]}")
                else:
                    print(f"[MobileWorldEnvs] [{host}] mw env run succeeded")
                    print(f"  stdout (last 300): {result.stdout.strip()[-300:]}")
            except subprocess.TimeoutExpired:
                # 超时不算失败：命令已经在执行，容器在后台创建中
                print(f"[MobileWorldEnvs] [{host}] WARNING: mw env run timed out after 60s (containers are likely still starting in background)")
            except Exception as e:
                print(f"[MobileWorldEnvs] [{host}] ERROR: mw env run error: {e}")
                return False
            
            return True
        
        # ================================================================
        # Steps 1-3: Execute restart on all hosts in parallel using threads
        # ================================================================
        all_restart_ok = True
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(hosts_to_restart)) as executor:
            future_to_host = {
                executor.submit(
                    _restart_host, host, len(urls), host_start_ports[host]
                ): host
                for host, urls in hosts_to_restart.items()
            }
            for future in concurrent.futures.as_completed(future_to_host):
                host = future_to_host[future]
                try:
                    if not future.result():
                        print(f"[MobileWorldEnvs] [{host}] Restart FAILED")
                        all_restart_ok = False
                except Exception as e:
                    print(f"[MobileWorldEnvs] [{host}] Restart exception: {e}")
                    all_restart_ok = False
        
        # ================================================================
        # Step 4: Wait for emulators + health check (poll until enough healthy or timeout)
        # Merges old Step 4 (blind wait) and Step 5 (health check) into one smart loop.
        #
        # Three categories for servers on restarted hosts:
        #   - healthy_pool: passed health check → fill active + spare (front)
        #   - not_ready_servers: not yet healthy (timeout/connection refused) → spare (mid)
        #     They may still be booting, so we don't mark them as bad.
        #   - new_bad_servers: explicitly unhealthy (server responded ok=false) → spare (tail)
        #
        # Early exit considers ALL servers (restarted + non-restarted) because
        # non-restarted spare servers can immediately fill active/spare slots.
        # e.g., 12 restarted healthy + 8 non-restarted healthy = 20 >= 16+4 → exit early.
        # ================================================================
        SPARE_BUFFER = 4  # Want at least 4 spare servers before early exit
        
        # Collect all URLs on restarted hosts (from txt)
        all_urls_on_restarted_hosts = []
        for host in hosts_to_restart:
            all_urls_on_restarted_hosts.extend(hosts_to_restart[host])
        
        # Collect all URLs on NON-restarted hosts (from txt, immutable source)
        restarted_hosts_set = set(hosts_to_restart.keys())
        non_restarted_urls = []
        for host, urls in self.all_servers_by_host.items():
            if host not in restarted_hosts_set:
                non_restarted_urls.extend(urls)
        
        poll_interval = 30  # Check every 30 seconds
        elapsed = 0
        healthy_pool = []            # Healthy servers on restarted hosts
        not_ready_servers = []       # Still booting on restarted hosts
        new_bad_servers = []         # Explicitly unhealthy on restarted hosts
        healthy_non_restarted = []   # Healthy servers on non-restarted hosts
        not_ready_non_restarted = [] # Not ready on non-restarted hosts
        bad_non_restarted = []       # Bad on non-restarted hosts
        
        # Early exit target: num_processes + SPARE_BUFFER, counting ALL servers across all hosts
        total_all_urls = len(all_urls_on_restarted_hosts) + len(non_restarted_urls)
        ideal_target = self.num_processes + SPARE_BUFFER
        early_exit_target = min(ideal_target, total_all_urls)
        # At minimum, we need num_processes healthy servers
        early_exit_target = max(early_exit_target, self.num_processes)
        
        if total_all_urls <= self.num_processes:
            print(f"[MobileWorldEnvs] NOTE: txt only has {total_all_urls} total servers "
                  f"(<= num_processes={self.num_processes}), no spare capacity. "
                  f"Early exit target adjusted to {early_exit_target} (no +{SPARE_BUFFER} buffer).")
        
        print(f"[MobileWorldEnvs] Step 4: Polling health on {len(all_urls_on_restarted_hosts)} restarted "
              f"+ {len(non_restarted_urls)} non-restarted = {total_all_urls} total servers "
              f"(need {self.num_processes} active + {SPARE_BUFFER} spare = {ideal_target} ideal, "
              f"early_exit_target={early_exit_target}, timeout {wait_after_run}s)...")
        
        while elapsed < wait_after_run:
            time.sleep(poll_interval)
            elapsed += poll_interval
            
            # Health check all URLs on restarted hosts
            healthy_pool = []
            not_ready_servers = []
            new_bad_servers = []
            for server_url in all_urls_on_restarted_hosts:
                try:
                    resp = requests.get(f"{server_url}/health", timeout=15)
                    resp.raise_for_status()
                    health = resp.json()
                    if health.get("ok", False):
                        healthy_pool.append(server_url)
                    else:
                        new_bad_servers.append(server_url)
                except Exception:
                    not_ready_servers.append(server_url)
            
            # Also health check non-restarted hosts (they may have gone bad during training)
            healthy_non_restarted = []
            not_ready_non_restarted = []
            bad_non_restarted = []
            for server_url in non_restarted_urls:
                try:
                    resp = requests.get(f"{server_url}/health", timeout=15)
                    resp.raise_for_status()
                    health = resp.json()
                    if health.get("ok", False):
                        healthy_non_restarted.append(server_url)
                    else:
                        bad_non_restarted.append(server_url)
                except Exception:
                    not_ready_non_restarted.append(server_url)
            
            total_healthy = len(healthy_pool) + len(healthy_non_restarted)
            print(f"[MobileWorldEnvs] [{elapsed}s/{wait_after_run}s] "
                  f"restarted: healthy={len(healthy_pool)}, not_ready={len(not_ready_servers)}, bad={len(new_bad_servers)} | "
                  f"non-restarted: healthy={len(healthy_non_restarted)}, not_ready={len(not_ready_non_restarted)}, bad={len(bad_non_restarted)} | "
                  f"total_healthy={total_healthy}, target={early_exit_target}")
            
            # Early exit: enough total healthy servers (restarted + non-restarted) for active + spare buffer
            if total_healthy >= early_exit_target:
                print(f"[MobileWorldEnvs] Early exit! {total_healthy} total healthy servers >= "
                      f"{early_exit_target} target (saved {wait_after_run - elapsed}s)")
                break
        else:
            total_healthy = len(healthy_pool) + len(healthy_non_restarted)
            print(f"[MobileWorldEnvs] Wait timeout ({wait_after_run}s) reached. "
                  f"total_healthy={total_healthy}, "
                  f"restarted: healthy={len(healthy_pool)}, not_ready={len(not_ready_servers)}, bad={len(new_bad_servers)}, "
                  f"non-restarted: healthy={len(healthy_non_restarted)}, not_ready={len(not_ready_non_restarted)}, bad={len(bad_non_restarted)}")
        
        print(f"[MobileWorldEnvs] Health check results: "
              f"restarted hosts: {len(healthy_pool)} healthy, {len(not_ready_servers)} not_ready, {len(new_bad_servers)} bad | "
              f"non-restarted hosts: {len(healthy_non_restarted)} healthy, {len(not_ready_non_restarted)} not_ready, {len(bad_non_restarted)} bad")
        
        # ================================================================
        # Step 5: Dynamic reallocation
        #
        # Merge ALL servers (restarted + non-restarted) into one pool by health status,
        # then fill in this priority order:
        #   1. combined_healthy[:num_processes]          → active_servers
        #   2. combined_healthy[num_processes:]          → spare_servers (front, confirmed healthy)
        #   3. combined_not_ready                        → spare_servers (middle, may boot later)
        #   4. combined_bad                              → spare_servers (tail, tried last on failover)
        #
        # No separate bad_servers set — bad servers go to spare tail, so they're
        # only retried after all healthy + not-ready candidates are exhausted.
        # Worker failover uses spare_servers.pop(0) → healthy first, bad last.
        # ================================================================
        
        # Save old servers BEFORE overwriting, for logging
        old_active_servers = list(self.active_servers)
        old_spare_servers = list(self.spare_servers)
        
        # Combined pools from ALL hosts (restarted + non-restarted)
        # Priority: restarted healthy first (freshly restarted, clean state),
        #           then non-restarted healthy (running continuously, verified healthy)
        combined_healthy = healthy_pool + healthy_non_restarted
        combined_not_ready = not_ready_servers + not_ready_non_restarted
        combined_bad = new_bad_servers + bad_non_restarted
        
        print(f"[MobileWorldEnvs] Step 5: Dynamic reallocation")
        print(f"[MobileWorldEnvs]   Combined healthy: {len(combined_healthy)} "
              f"(restarted={len(healthy_pool)}, non-restarted={len(healthy_non_restarted)})")
        print(f"[MobileWorldEnvs]   Combined not-ready: {len(combined_not_ready)} "
              f"(restarted={len(not_ready_servers)}, non-restarted={len(not_ready_non_restarted)})")
        print(f"[MobileWorldEnvs]   Combined bad: {len(combined_bad)}")
        print(f"[MobileWorldEnvs]   Need: {self.num_processes} active")
        
        # Fill active from combined healthy pool
        new_active_servers = combined_healthy[:self.num_processes]
        remaining_healthy = combined_healthy[self.num_processes:]
        
        # Spare order: remaining healthy (front) → not-ready (middle) → bad (tail)
        # Worker failover pops from front, so healthy spares tried first, bad tried last
        new_spare_servers = remaining_healthy + combined_not_ready + combined_bad
        
        # If still not enough active after using all healthy, promote from spare
        if len(new_active_servers) < self.num_processes:
            shortfall = self.num_processes - len(new_active_servers)
            print(f"[MobileWorldEnvs] ⚠️ Not enough healthy servers "
                  f"({len(combined_healthy)}/{self.num_processes}), "
                  f"trying to promote from spare pool...")
            
            # Promote from spare (not-ready first, then bad as last resort)
            promote_from_spare = new_spare_servers[:shortfall]
            new_active_servers.extend(promote_from_spare)
            new_spare_servers = new_spare_servers[shortfall:]
            
            if promote_from_spare:
                print(f"[MobileWorldEnvs]   Promoted {len(promote_from_spare)} servers to active: "
                      f"{promote_from_spare}")
            
            if len(new_active_servers) < self.num_processes:
                still_short = self.num_processes - len(new_active_servers)
                print(f"[MobileWorldEnvs] ⚠️ CRITICAL: Still short by {still_short} after all fallback! "
                      f"Training may encounter errors on missing worker slots. "
                      f"Active: {len(new_active_servers)}/{self.num_processes}")
        
        # Log the reallocation result
        print(f"[MobileWorldEnvs] === REALLOCATION RESULT ===")
        print(f"[MobileWorldEnvs]   Old active ({len(old_active_servers)}): {old_active_servers}")
        print(f"[MobileWorldEnvs]   New active ({len(new_active_servers)}): {new_active_servers}")
        print(f"[MobileWorldEnvs]   Old spare ({len(old_spare_servers)}): {old_spare_servers}")
        print(f"[MobileWorldEnvs]   New spare ({len(new_spare_servers)}): {new_spare_servers}")
        if combined_not_ready or combined_bad:
            not_ready_in_spare = [s for s in new_spare_servers if s in set(combined_not_ready)]
            bad_in_spare = [s for s in new_spare_servers if s in set(combined_bad)]
            print(f"[MobileWorldEnvs]     Spare breakdown: "
                  f"{len(new_spare_servers) - len(not_ready_in_spare) - len(bad_in_spare)} healthy, "
                  f"{len(not_ready_in_spare)} not-ready (mid), "
                  f"{len(bad_in_spare)} bad (tail)")
        
        # Apply new pools
        self.active_servers = new_active_servers
        self.spare_servers = new_spare_servers
        # Also update for backward compatibility
        self.available_servers = self.active_servers
        
        # ================================================================
        # Step 6: Rebuild Ray workers with new active server assignments
        # ================================================================
        print(f"[MobileWorldEnvs] Step 6: Rebuilding Ray workers with new active servers...")
        
        # Kill ALL existing workers (they may hold stale server URLs and spare_servers state)
        for i, worker in enumerate(self.workers):
            try:
                ray.kill(worker)
            except Exception as e:
                print(f"[MobileWorldEnvs] Warning: Failed to kill worker {i}: {e}")
        
        # Create new workers with updated active servers and spare list
        env_worker = ray.remote(**self.resources_per_worker)(MobileWorldWorker)
        self.workers = []
        for i in range(len(self.active_servers)):
            worker = env_worker.remote(
                worker_id=self.start_server_id + i,
                max_interactions=self.max_interactions,
                server_url=self.active_servers[i],
                spare_servers=list(self.spare_servers),  # Each worker gets fresh spare list
                device=self.device
            )
            self.workers.append(worker)
        
        # Update num_processes to match actual active servers (in case of shortfall)
        actual_workers = len(self.workers)
        if actual_workers != self.num_processes:
            print(f"[MobileWorldEnvs] WARNING: Worker count changed from {self.num_processes} to {actual_workers}")
            # Note: we don't change self.num_processes here to avoid breaking the training loop
            # The training loop should still expect num_processes workers
        
        print(f"[MobileWorldEnvs] === DYNAMIC RESTART COMPLETE ===")
        print(f"[MobileWorldEnvs] Active: {len(self.active_servers)}/{self.num_processes}, "
              f"Spare: {len(self.spare_servers)}")
        
        return len(self.active_servers) >= self.num_processes

    def close(self):
        """
        Close all workers (暂不实现清理逻辑).
        """
        futures = [worker.close.remote() for worker in self.workers]
        ray.get(futures)
        
        for worker in self.workers:
            ray.kill(worker)

    def render(self):
        """Render (not implemented)."""
        pass


def build_mobileworld_envs(
    dataset_name: str = "train",
    max_interactions: int = 50,
    seed: int = 0,
    env_num: int = 1,
    group_n: int = 1,
    start_server_id: int = 0,
    resources_per_worker: dict = None,
    server_file: str = None,
    device: str = "emulator-5554"
):
    """
    Factory function to build MobileWorld environments.
    
    Args:
        dataset_name: Dataset name (for compatibility)
        max_interactions: Maximum steps per episode
        seed: Random seed
        env_num: Number of different environments
        group_n: Number of same environments in each group
        start_server_id: Starting server index
        resources_per_worker: Ray resources per worker
        server_file: Path to server URL list file (default: mobileworld_server.txt in package dir)
        device: ADB device ID
    
    Returns:
        MobileWorldEnvs instance
    """
    if resources_per_worker is None:
        resources_per_worker = {"num_cpus": 0.1}
    
    return MobileWorldEnvs(
        dataset_name=dataset_name,
        max_interactions=max_interactions,
        seed=seed,
        env_num=env_num,
        group_n=group_n,
        start_server_id=start_server_id,
        resources_per_worker=resources_per_worker,
        server_file=server_file,
        device=device
    )
