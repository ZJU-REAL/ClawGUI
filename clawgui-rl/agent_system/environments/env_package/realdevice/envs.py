"""
Real Device Environment for online RL training with a physical Android phone.

Key differences from MobileWorldEnvs (emulator-based):
- No predefined task list (task/init, task/tear_down, task/eval APIs not used)
- No spare server / failover logic (single real device)
- No container restart logic
- Task goals are provided externally via env_kwargs (from training data parquet)
- Reward comes from step reward judge (VLM) or is set to 0 for intermediate steps
- Episode ends when agent outputs "answer"/"terminate" or reaches max_steps
"""

import os
import time
import numpy as np
import ray
import requests
from typing import List, Tuple, Dict, Any


def load_server_url(server_file: str = None) -> str:
    """
    Load server URL from file. For real device, we only need one server URL.
    The file should contain exactly one URL line.
    """
    if server_file is None:
        current_dir = os.path.dirname(os.path.abspath(__file__))
        server_file = os.path.join(current_dir, "realdevice_server.txt")

    if not os.path.exists(server_file):
        raise FileNotFoundError(
            f"Server file {server_file} does not exist. "
            f"Please create it with the real device MobileWorld server URL."
        )

    with open(server_file, 'r') as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith('#'):
                if not line.startswith('http://') and not line.startswith('https://'):
                    line = 'http://' + line
                return line

    raise ValueError(f"No valid server URL found in {server_file}.")


class RealDeviceWorker:
    """
    Ray Actor that connects to a real device MobileWorld server via HTTP API.
    
    Simplified version of MobileWorldWorker:
    - No task/init or task/tear_down (no predefined tasks)
    - No spare server failover (single device)
    - Screenshot + step via HTTP API same as emulator version
    - Episode lifecycle: reset sets the goal, step executes actions
    """

    def __init__(self, worker_id: int, max_interactions: int, server_url: str,
                 device: str = "emulator-5554"):
        """
        Args:
            worker_id: Unique identifier for this worker
            max_interactions: Maximum steps per episode
            server_url: URL of MobileWorld server connected to the real device
            device: ADB device ID (e.g., the real phone's serial number)
        """
        self.worker_id = worker_id
        self.max_interactions = max_interactions
        self.server_url = server_url
        self.device = device

        self.current_step_count = 0
        self.current_task_goal = None
        self.current_task_name = 'unknown_task'

    def reset(self, env_kwargs: Dict[str, Any]) -> Tuple[str, Dict[str, Any]]:
        """
        Reset for a new episode.
        
        Since there are no predefined tasks on the server, we just:
        1. Check server health
        2. Extract task goal from env_kwargs (provided by training data)
        
        Args:
            env_kwargs: Dict with 'question' (task goal text) from training data
            
        Returns:
            obs: Task goal string
            info: Metadata dict
        """
        # Extract task goal from training data
        task_goal = env_kwargs.get('question', env_kwargs.get('task_goal', 'No task specified'))
        task_name = env_kwargs.get('task_name', 'unknown_task')

        # Health check with simple retry
        health_ok = self._check_health()
        if not health_ok:
            print(f"[RealDevice Worker {self.worker_id}] WARNING: Server unhealthy, proceeding anyway")

        # Reset state
        self.current_step_count = 0
        self.current_task_goal = task_goal
        self.current_task_name = task_name

        obs = task_goal
        info = {
            "task_goal": task_goal,
            "worker_id": self.worker_id,
            "server_url": self.server_url,
            "won": False,
        }
        return obs, info

    def step(self, action: dict) -> Tuple[str, float, bool, Dict[str, Any]]:
        """
        Execute one action on the real device via /step API.
        
        No task/eval — reward is always 0 for intermediate steps.
        Step reward judge (if enabled) handles reward at the EnvironmentManager level.
        
        Episode ends when:
        - action_type == "answer" or "terminate"
        - current_step_count >= max_interactions
        """
        self.current_step_count += 1
        print(f'[RealDevice Worker {self.worker_id}] Step {self.current_step_count}, action: {action}')

        # Execute action via /step API with retry
        max_retries = 3
        step_timeouts = [30, 60, 90]
        obs = ""
        step_success = False

        attempt = 0
        while attempt < max_retries and not step_success:
            try:
                timeout = step_timeouts[attempt]
                resp = requests.post(
                    f"{self.server_url}/step",
                    json={"device": self.device, "action": action},
                    timeout=timeout
                )
                resp.raise_for_status()
                result = resp.json()
                obs = str(result.get("result", ""))
                step_success = True
            except requests.exceptions.Timeout as e:
                attempt += 1
                print(f"[RealDevice Worker {self.worker_id}] Timeout (attempt {attempt}/{max_retries}): {e}")
                if attempt >= max_retries:
                    return f"Error: Timeout after {max_retries} retries", 0.0, True, {
                        "won": False,
                        "error": f"Timeout after {max_retries} retries",
                        "step_count": self.current_step_count,
                        "task_goal": self.current_task_goal,
                    }
                time.sleep(2 ** (attempt - 1))
            except Exception as e:
                print(f"[RealDevice Worker {self.worker_id}] Step error: {e}")
                return f"Error: {str(e)}", 0.0, True, {
                    "won": False,
                    "error": str(e),
                    "step_count": self.current_step_count,
                    "task_goal": self.current_task_goal,
                    "task_name": self.current_task_name,
                }

        # Determine if episode should end
        action_type = action.get("action_type", "")
        is_answer = (action_type == "answer")
        is_terminate = (action_type == "terminate" or action_type == "status")
        is_max_step = (self.current_step_count >= self.max_interactions)
        done = is_answer or is_terminate or is_max_step

        # No server-side eval for real device — reward is 0
        # Step reward judge (if enabled) overrides this at EnvironmentManager level
        reward = 0.0

        if done:
            reason = "answer" if is_answer else ("terminate" if is_terminate else "max_step")
            print(f"[RealDevice Worker {self.worker_id}] Episode done: {reason}, "
                  f"steps={self.current_step_count}")

        info = {
            "won": False,  # No auto-eval for real device
            "step_count": self.current_step_count,
            "task_goal": self.current_task_goal,
            "task_name": self.current_task_name,
        }
        return obs, reward, done, info

    def _check_health(self) -> bool:
        """Simple health check with one retry."""
        for attempt in range(2):
            try:
                resp = requests.get(f"{self.server_url}/health", timeout=10)
                resp.raise_for_status()
                health = resp.json()
                if health.get("ok", False):
                    return True
                print(f"[RealDevice Worker {self.worker_id}] Server unhealthy (attempt {attempt+1}): {health}")
            except Exception as e:
                print(f"[RealDevice Worker {self.worker_id}] Health check failed (attempt {attempt+1}): {e}")
            if attempt == 0:
                time.sleep(5)
        return False

    @staticmethod
    def _validate_b64_png(b64_data: str) -> bool:
        """Quick check if base64 data is a valid PNG."""
        try:
            import base64 as _b64
            from io import BytesIO as _BytesIO
            from PIL import Image as _Image
            raw = _b64.b64decode(b64_data)
            img = _Image.open(_BytesIO(raw))
            img.load()
            return True
        except Exception:
            return False

    def get_screenshot(self, prefix: str = None, return_b64: bool = False) -> Dict[str, Any]:
        """
        Get screenshot via /screenshot API with simple retry.
        """
        def _do_request():
            params = {"device": self.device, "return_b64": return_b64}
            if prefix:
                params["prefix"] = prefix
            resp = requests.get(f"{self.server_url}/screenshot", params=params, timeout=30)
            resp.raise_for_status()
            result = resp.json()
            if return_b64:
                b64 = result.get('b64_png', '')
                if b64 and self._validate_b64_png(b64):
                    return result, None
                return None, "empty_or_corrupted"
            return result, None

        # Attempt 1
        try:
            result, err = _do_request()
            if result is not None:
                return result
            print(f"[RealDevice Worker {self.worker_id}] Screenshot {err} (attempt 1)")
        except Exception as e:
            print(f"[RealDevice Worker {self.worker_id}] Screenshot error (attempt 1): {e}")

        # Attempt 2 after wait
        time.sleep(3)
        try:
            result, err = _do_request()
            if result is not None:
                return result
            return {"error": err, "error_type": err}
        except Exception as e:
            return {"error": str(e), "error_type": "other"}

    def close(self):
        """No cleanup needed for real device."""
        pass


class RealDeviceEnvs:
    """
    Ray-based distributed wrapper for real device environment.
    
    Supports multiple physical devices:
    - Pass comma-separated device IDs (e.g., "device1,device2") to use multiple phones
    - env_num must match the number of devices provided
    - Each device gets group_n workers (for GRPO rollout)
    - If device='auto' or not specified, auto-detect connected ADB devices from server
    
    Lifecycle:
    1. Parse/detect device list
    2. Call /init for each device (register with MobileWorld server, only once)
    3. Verify /health
    4. Create Ray workers, each bound to its assigned device
    """

    def __init__(
        self,
        max_interactions: int,
        seed: int,
        env_num: int,
        group_n: int,
        resources_per_worker: dict,
        server_file: str = None,
        device: str = None,
    ):
        super().__init__()

        self.max_interactions = max_interactions
        self.env_num = env_num
        self.group_n = group_n
        self.num_processes = env_num * group_n
        self.seed = seed

        # Load server URL
        self.server_url = load_server_url(server_file)
        print(f"[RealDeviceEnvs] Server URL: {self.server_url}")

        # --- Parse device list ---
        if device is None or device.strip().lower() == 'auto':
            # Auto-detect: ask the server which ADB devices are connected
            self.devices = self._auto_detect_devices(expected_count=env_num)
        else:
            # User-specified: comma-separated list (e.g., "R5CRA1XXX,R5CRA2XXX")
            self.devices = [d.strip() for d in device.split(',') if d.strip()]

        if len(self.devices) != self.env_num:
            raise ValueError(
                f"[RealDeviceEnvs] Number of devices ({len(self.devices)}) does not match "
                f"env_num ({self.env_num}). Please provide exactly {self.env_num} device IDs "
                f"(comma-separated) or ensure the server has {self.env_num} ADB devices connected."
            )

        print(f"[RealDeviceEnvs] Devices ({len(self.devices)}): {self.devices}")

        # --- Init each device on the server (only once) ---
        self._init_devices_on_server()

        # --- Verify health after init ---
        self._verify_health()

        self.resources_per_worker = resources_per_worker

        # Init Ray
        if not ray.is_initialized():
            ray_temp_dir = f"/tmp/ray_{os.getlogin()}_{os.getpid()}"
            os.makedirs(ray_temp_dir, exist_ok=True)
            ray.init(
                include_dashboard=False,
                namespace=f"realdevice_{os.getpid()}",
                _temp_dir=ray_temp_dir
            )

        # Create Ray workers — each device gets group_n workers
        env_worker = ray.remote(**resources_per_worker)(RealDeviceWorker)
        self.workers = []
        for env_idx in range(self.env_num):
            device_id = self.devices[env_idx]
            for g in range(self.group_n):
                worker_id = env_idx * self.group_n + g
                worker = env_worker.remote(
                    worker_id=worker_id,
                    max_interactions=self.max_interactions,
                    server_url=self.server_url,
                    device=device_id,
                )
                self.workers.append(worker)

        self.rng = np.random.RandomState(seed)

    def _auto_detect_devices(self, expected_count: int) -> List[str]:
        """
        Auto-detect connected ADB devices by querying the server's /health endpoint.
        
        The /health endpoint returns {"devices": [...], ...} listing already-registered
        device IDs. If none are registered yet, we try calling /init with common device
        names or fall back to 'emulator-5554'.
        
        Args:
            expected_count: How many devices we expect to find.
            
        Returns:
            List of device ID strings.
        """
        print(f"[RealDeviceEnvs] Auto-detecting devices (expecting {expected_count})...")
        
        # First, check if any devices are already registered on the server
        try:
            resp = requests.get(f"{self.server_url}/health", timeout=10)
            resp.raise_for_status()
            health = resp.json()
            registered_devices = health.get("devices", [])
            if registered_devices and len(registered_devices) >= expected_count:
                devices = registered_devices[:expected_count]
                print(f"[RealDeviceEnvs] Auto-detected {len(devices)} device(s) from server: {devices}")
                return devices
            elif registered_devices:
                print(f"[RealDeviceEnvs] Server has {len(registered_devices)} device(s) "
                      f"but need {expected_count}")
        except Exception as e:
            print(f"[RealDeviceEnvs] Could not query /health for auto-detect: {e}")

        # Fallback: cannot auto-detect enough devices
        if expected_count == 1:
            print("[RealDeviceEnvs] WARNING: Could not auto-detect device, using 'emulator-5554'")
            return ["emulator-5554"]
        else:
            raise ValueError(
                f"[RealDeviceEnvs] Auto-detect failed: could not find {expected_count} devices. "
                f"Please specify device IDs explicitly via --device (comma-separated)."
            )

    def _init_devices_on_server(self):
        """
        Register each device with the MobileWorld server via /init.
        This must be called once before any /step, /screenshot, or /health calls
        so that the server creates AndroidController instances for each device.
        """
        for device_id in self.devices:
            try:
                print(f"[RealDeviceEnvs] Initializing device '{device_id}' on server...")
                resp = requests.get(
                    f"{self.server_url}/init",
                    params={"device": device_id},
                    timeout=30,
                )
                resp.raise_for_status()
                result = resp.json()
                viewport = result.get("viewport_size", "unknown")
                print(f"[RealDeviceEnvs] Device '{device_id}' initialized, viewport={viewport}")
            except Exception as e:
                raise RuntimeError(
                    f"[RealDeviceEnvs] Failed to init device '{device_id}' on server: {e}. "
                    f"Make sure the device is connected via ADB and the server is reachable."
                )

    def _verify_health(self):
        """
        Verify that all devices are healthy after init.
        """
        try:
            resp = requests.get(f"{self.server_url}/health", timeout=10)
            resp.raise_for_status()
            health = resp.json()
            device_status = health.get("device_status", {})
            all_ok = health.get("ok", False)
            
            for device_id in self.devices:
                status = device_status.get(device_id, None)
                if status is False:
                    print(f"[RealDeviceEnvs] WARNING: Device '{device_id}' is unhealthy!")
                elif status is None:
                    print(f"[RealDeviceEnvs] WARNING: Device '{device_id}' not found in health status")
                else:
                    print(f"[RealDeviceEnvs] Device '{device_id}' health OK")
            
            if not all_ok:
                print("[RealDeviceEnvs] WARNING: Not all devices are healthy, proceeding anyway")
        except Exception as e:
            print(f"[RealDeviceEnvs] WARNING: Health check failed: {e}, proceeding anyway")

    def reset(self, env_kwargs_list: List[Dict[str, Any]] = None) -> Tuple[List[str], List[dict]]:
        """
        Reset all workers with task goals from env_kwargs_list.
        
        env_kwargs_list must be provided (from training data parquet).
        Each dict should have 'question' key with the task goal text.
        
        Workers on the same device are staggered to avoid concurrent Home presses.
        Workers on different devices can run in parallel.
        """
        if env_kwargs_list is None:
            raise ValueError(
                "RealDeviceEnvs.reset() requires env_kwargs_list with task goals. "
                "No predefined task list — goals must come from training data."
            )
        
        assert len(env_kwargs_list) == self.num_processes, \
            f"Expected {self.num_processes} env_kwargs, got {len(env_kwargs_list)}"

        # Group workers by device: workers sharing a device are staggered
        # Worker layout: [dev0_g0, dev0_g1, ..., dev1_g0, dev1_g1, ...]
        futures = []
        for idx, (worker, env_kwargs) in enumerate(zip(self.workers, env_kwargs_list)):
            # Stagger workers on the same device (group_n > 1)
            group_offset = idx % self.group_n
            if group_offset > 0:
                time.sleep(0.5)
            futures.append(worker.reset.remote(env_kwargs))

        results = ray.get(futures)

        obs_list, info_list = [], []
        for obs, info in results:
            obs_list.append(obs)
            info_list.append(info)
        return obs_list, info_list

    def step(self, actions: List[dict]) -> Tuple[List[str], List[float], List[bool], List[dict]]:
        """Execute actions on all workers. Different devices run in parallel."""
        assert len(actions) == self.num_processes

        # Submit all actions — different devices can truly parallelize
        futures = []
        for idx, (worker, action) in enumerate(zip(self.workers, actions)):
            futures.append(worker.step.remote(action))

        results = ray.get(futures)

        obs_list, reward_list, done_list, info_list = [], [], [], []
        for obs, reward, done, info in results:
            obs_list.append(obs)
            reward_list.append(reward)
            done_list.append(done)
            info_list.append(info)
        return obs_list, reward_list, done_list, info_list

    def get_screenshots(self, prefix: str = None, return_b64: bool = False) -> List[Dict[str, Any]]:
        """Get screenshots from all workers."""
        futures = [w.get_screenshot.remote(prefix, return_b64) for w in self.workers]
        return ray.get(futures)

    def get_screenshots_for_indices(self, indices: List[int], prefix: str = None,
                                     return_b64: bool = False) -> Dict[int, Dict[str, Any]]:
        """Get screenshots for specific worker indices."""
        futures = {idx: self.workers[idx].get_screenshot.remote(prefix, return_b64) for idx in indices}
        return {idx: ray.get(fut) for idx, fut in futures.items()}

    def close(self):
        """Close all workers."""
        futures = [w.close.remote() for w in self.workers]
        ray.get(futures)
        for w in self.workers:
            try:
                ray.kill(w)
            except Exception:
                pass

    def render(self):
        pass


def build_realdevice_envs(
    max_interactions: int = 50,
    seed: int = 0,
    env_num: int = 1,
    group_n: int = 1,
    resources_per_worker: dict = None,
    server_file: str = None,
    device: str = None,
):
    """
    Factory function to build RealDevice environments.
    
    Args:
        device: Comma-separated device IDs (e.g., "R5CRA1XXX,R5CRA2XXX").
                Number of devices must equal env_num.
                Set to 'auto' or None to auto-detect from server.
    """
    if resources_per_worker is None:
        resources_per_worker = {"num_cpus": 0.1}

    return RealDeviceEnvs(
        max_interactions=max_interactions,
        seed=seed,
        env_num=env_num,
        group_n=group_n,
        resources_per_worker=resources_per_worker,
        server_file=server_file,
        device=device,
    )
