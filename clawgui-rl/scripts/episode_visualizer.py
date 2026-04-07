"""
Episode 可视化服务器
- 后端: FastAPI
- 前端: 内嵌 HTML (单文件)
- 功能: 浏览所有 task/episode, 查看每个 step 的图片 + 标注坐标 + model_response
"""

import json
import os
import re
import glob
import math
from io import BytesIO
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import HTMLResponse, StreamingResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from PIL import Image, ImageDraw, ImageFont

app = FastAPI()

EPISODE_ROOT = Path(__file__).parent / "episode"
COORD_MAX = 1000  # 坐标范围 0-1000

# ── 工具函数 ──────────────────────────────────────────────

def parse_action(model_response: str):
    """从 model_response 中解析 <tool_call> 里的 JSON"""
    match = re.search(r'<tool_call>\s*(\{.*?\})\s*</tool_call>', model_response, re.DOTALL)
    if not match:
        return None
    try:
        call = json.loads(match.group(1))
    except json.JSONDecodeError:
        return None
    return call.get("arguments", {})


def parse_thinking(model_response: str):
    """从 model_response 中解析 <thinking> 内容"""
    match = re.search(r'<thinking>\s*(.*?)\s*</thinking>', model_response, re.DOTALL)
    return match.group(1).strip() if match else ""


def coord_to_abs(coord, img_w, img_h):
    """将 0-1000 坐标转换为绝对像素坐标"""
    x = int(coord[0] / COORD_MAX * img_w)
    y = int(coord[1] / COORD_MAX * img_h)
    return x, y


def draw_click_marker(draw, x, y, radius=18):
    """画红色圆点 + 十字标记"""
    # 外圈
    draw.ellipse([x - radius, y - radius, x + radius, y + radius],
                 outline="red", width=3)
    # 内圈实心
    r2 = radius // 2
    draw.ellipse([x - r2, y - r2, x + r2, y + r2], fill="red")
    # 十字
    draw.line([(x - radius - 5, y), (x + radius + 5, y)], fill="red", width=2)
    draw.line([(x, y - radius - 5), (x, y + radius + 5)], fill="red", width=2)


def draw_long_press_marker(draw, x, y, radius=22):
    """画橙色双圈标记表示长按"""
    draw.ellipse([x - radius, y - radius, x + radius, y + radius],
                 outline="orange", width=4)
    r2 = radius - 6
    draw.ellipse([x - r2, y - r2, x + r2, y + r2],
                 outline="orange", width=3)
    r3 = 5
    draw.ellipse([x - r3, y - r3, x + r3, y + r3], fill="orange")


def draw_swipe_marker(draw, cx, cy, direction, length=200):
    """画 swipe 箭头: 从 coordinate 出发，沿 direction 方向"""
    dir_map = {
        "up": (0, -1),
        "down": (0, 1),
        "left": (-1, 0),
        "right": (1, 0),
    }
    dx, dy = dir_map.get(direction, (0, 0))
    ex = cx + dx * length
    ey = cy + dy * length

    # 主线
    draw.line([(cx, cy), (ex, ey)], fill="blue", width=4)
    # 起点绿色圆
    draw.ellipse([cx - 8, cy - 8, cx + 8, cy + 8], fill="green")
    # 终点箭头
    arrow_len = 20
    angle = math.atan2(dy, dx)
    a1 = angle + math.pi * 0.8
    a2 = angle - math.pi * 0.8
    ax1 = ex + arrow_len * math.cos(a1)
    ay1 = ey + arrow_len * math.sin(a1)
    ax2 = ex + arrow_len * math.cos(a2)
    ay2 = ey + arrow_len * math.sin(a2)
    draw.polygon([(ex, ey), (ax1, ay1), (ax2, ay2)], fill="blue")


def draw_drag_marker(draw, sx, sy, ex, ey):
    """画 drag 箭头: 从起点到终点"""
    draw.line([(sx, sy), (ex, ey)], fill="purple", width=4)
    draw.ellipse([sx - 8, sy - 8, sx + 8, sy + 8], fill="green")
    draw.ellipse([ex - 8, ey - 8, ex + 8, ey + 8], fill="red")


def annotate_image(image_path: str, args: dict) -> Image.Image:
    """根据 action 参数在图片上标注坐标"""
    img = Image.open(image_path).convert("RGB")
    if args is None:
        return img

    draw = ImageDraw.Draw(img)
    action = args.get("action", "")
    w, h = img.size

    if action == "click" and "coordinate" in args:
        coord = args["coordinate"]
        ax, ay = coord_to_abs(coord, w, h)
        draw_click_marker(draw, ax, ay)

    elif action == "long_press" and "coordinate" in args:
        coord = args["coordinate"]
        ax, ay = coord_to_abs(coord, w, h)
        draw_long_press_marker(draw, ax, ay)

    elif action == "swipe" and "coordinate" in args:
        coord = args["coordinate"]
        direction = args.get("direction", "up")
        cx, cy = coord_to_abs(coord, w, h)
        draw_swipe_marker(draw, cx, cy, direction)

    elif action == "drag" and "startCoordinate" in args and "endCoordinate" in args:
        sc = args["startCoordinate"]
        ec = args["endCoordinate"]
        sx, sy = coord_to_abs(sc, w, h)
        ex, ey = coord_to_abs(ec, w, h)
        draw_drag_marker(draw, sx, sy, ex, ey)

    return img


# ── API 路由 ──────────────────────────────────────────────

@app.get("/api/tasks")
def list_tasks():
    """列出所有 task"""
    tasks = []
    for task_dir in sorted(EPISODE_ROOT.iterdir()):
        if task_dir.is_dir():
            episodes = []
            for ep_dir in sorted(task_dir.iterdir()):
                ep_json = ep_dir / "episode.json"
                if ep_json.exists():
                    episodes.append(ep_dir.name)
            if episodes:
                tasks.append({"task_name": task_dir.name, "episodes": episodes})
    return tasks


@app.get("/api/episode/{task_name}/{episode_id}")
def get_episode(task_name: str, episode_id: str):
    """获取单个 episode 的详细信息"""
    ep_json = EPISODE_ROOT / task_name / episode_id / "episode.json"
    if not ep_json.exists():
        raise HTTPException(404, "episode.json not found")

    with open(ep_json) as f:
        data = json.load(f)

    meta = data.get("meta_data", {})
    steps = []
    for step_data in data.get("episode", []):
        model_response = step_data.get("model_response", "")
        args = parse_action(model_response)
        thinking = parse_thinking(model_response)

        action_info = ""
        if args:
            action = args.get("action", "")
            coord = args.get("coordinate", None)
            direction = args.get("direction", "")
            text = args.get("text", "")
            parts = [f"action: {action}"]
            if coord:
                parts.append(f"coordinate: {coord}")
            if direction:
                parts.append(f"direction: {direction}")
            if text:
                parts.append(f"text: {text}")
            if "startCoordinate" in args:
                parts.append(f"startCoordinate: {args['startCoordinate']}")
            if "endCoordinate" in args:
                parts.append(f"endCoordinate: {args['endCoordinate']}")
            if "button" in args:
                parts.append(f"button: {args['button']}")
            action_info = " | ".join(parts)

        steps.append({
            "step": step_data["step"],
            "thinking": thinking,
            "action_info": action_info,
            "model_response": model_response,
            "timestamp": step_data.get("timestamp", ""),
            "has_image": os.path.exists(step_data.get("image_path", "")),
        })

    return {"meta_data": meta, "steps": steps}


@app.get("/api/image/{task_name}/{episode_id}/{step}")
def get_annotated_image(task_name: str, episode_id: str, step: int):
    """返回标注后的图片"""
    ep_json = EPISODE_ROOT / task_name / episode_id / "episode.json"
    if not ep_json.exists():
        raise HTTPException(404, "episode.json not found")

    with open(ep_json) as f:
        data = json.load(f)

    step_data = None
    for s in data.get("episode", []):
        if s["step"] == step:
            step_data = s
            break

    if step_data is None:
        raise HTTPException(404, f"Step {step} not found")

    image_path = step_data.get("image_path", "")
    if not os.path.exists(image_path):
        raise HTTPException(404, f"Image not found: {image_path}")

    args = parse_action(step_data.get("model_response", ""))
    img = annotate_image(image_path, args)

    # 缩放图片以减少传输大小
    max_h = 1200
    if img.height > max_h:
        ratio = max_h / img.height
        img = img.resize((int(img.width * ratio), max_h), Image.LANCZOS)

    buf = BytesIO()
    img.save(buf, format="JPEG", quality=85)
    buf.seek(0)
    return StreamingResponse(buf, media_type="image/jpeg")


@app.get("/api/image_raw/{task_name}/{episode_id}/{step}")
def get_raw_image(task_name: str, episode_id: str, step: int):
    """返回原始图片（未标注）"""
    ep_json = EPISODE_ROOT / task_name / episode_id / "episode.json"
    if not ep_json.exists():
        raise HTTPException(404, "episode.json not found")

    with open(ep_json) as f:
        data = json.load(f)

    step_data = None
    for s in data.get("episode", []):
        if s["step"] == step:
            step_data = s
            break

    if step_data is None:
        raise HTTPException(404, f"Step {step} not found")

    image_path = step_data.get("image_path", "")
    if not os.path.exists(image_path):
        raise HTTPException(404, f"Image not found: {image_path}")

    img = Image.open(image_path).convert("RGB")
    max_h = 1200
    if img.height > max_h:
        ratio = max_h / img.height
        img = img.resize((int(img.width * ratio), max_h), Image.LANCZOS)

    buf = BytesIO()
    img.save(buf, format="JPEG", quality=85)
    buf.seek(0)
    return StreamingResponse(buf, media_type="image/jpeg")


# ── 前端页面 ──────────────────────────────────────────────

FRONTEND_HTML = """<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Episode Visualizer</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #0f1117; color: #e0e0e0; display: flex; height: 100vh; overflow: hidden; }

  /* 左侧任务列表 */
  .sidebar { width: 300px; min-width: 300px; background: #161822; border-right: 1px solid #2a2d3a; display: flex; flex-direction: column; overflow: hidden; }
  .sidebar-header { padding: 16px; border-bottom: 1px solid #2a2d3a; display: flex; align-items: center; justify-content: space-between; }
  .sidebar-header h2 { font-size: 16px; color: #7c8aff; }
  .refresh-btn { padding: 4px 10px; border: 1px solid #2a2d3a; border-radius: 4px; background: transparent; color: #a0a8c0; cursor: pointer; font-size: 12px; transition: all 0.2s; display: flex; align-items: center; gap: 4px; }
  .refresh-btn:hover { border-color: #7c8aff; color: #7c8aff; }
  .refresh-btn.spinning .refresh-icon { animation: spin 0.6s linear; }
  @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
  .task-list { flex: 1; overflow-y: auto; padding: 8px; }
  .task-group { margin-bottom: 4px; }
  .task-name { padding: 8px 12px; cursor: pointer; border-radius: 6px; font-size: 13px; font-weight: 600; color: #a0a8c0; transition: background 0.15s; display: flex; align-items: center; gap: 6px; }
  .task-name:hover { background: #1e2030; }
  .task-name.open { background: #1e2030; color: #7c8aff; }
  .task-name .arrow { font-size: 10px; transition: transform 0.2s; }
  .task-name.open .arrow { transform: rotate(90deg); }
  .episode-list { display: none; padding-left: 16px; }
  .episode-list.show { display: block; }
  .episode-item { padding: 6px 12px; cursor: pointer; border-radius: 4px; font-size: 12px; color: #808899; transition: all 0.15s; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .episode-item:hover { background: #252840; color: #c0c8e0; }
  .episode-item.active { background: #2a3060; color: #7c8aff; }

  /* 主内容区 */
  .main { flex: 1; display: flex; flex-direction: column; overflow: hidden; }

  /* 顶部信息栏 */
  .top-bar { padding: 12px 20px; background: #161822; border-bottom: 1px solid #2a2d3a; min-height: 60px; }
  .top-bar .meta-title { font-size: 15px; font-weight: 700; color: #7c8aff; margin-bottom: 4px; }
  .top-bar .meta-goal { font-size: 13px; color: #a0a8c0; line-height: 1.4; }

  /* step 导航 */
  .step-nav { display: flex; align-items: center; padding: 8px 20px; background: #13151e; border-bottom: 1px solid #2a2d3a; gap: 8px; flex-wrap: wrap; }
  .step-btn { padding: 4px 12px; border: 1px solid #2a2d3a; border-radius: 4px; background: transparent; color: #808899; cursor: pointer; font-size: 12px; transition: all 0.15s; }
  .step-btn:hover { border-color: #7c8aff; color: #7c8aff; }
  .step-btn.active { background: #2a3060; border-color: #7c8aff; color: #fff; }
  .step-nav-arrows { display: flex; gap: 4px; margin-left: auto; }
  .step-nav-arrows button { padding: 4px 10px; border: 1px solid #2a2d3a; border-radius: 4px; background: transparent; color: #a0a8c0; cursor: pointer; font-size: 14px; }
  .step-nav-arrows button:hover { border-color: #7c8aff; color: #7c8aff; }

  /* 内容区: 图片 + step信息 */
  .content { flex: 1; display: flex; overflow: hidden; }
  .image-panel { flex: 1; display: flex; align-items: center; justify-content: center; overflow: auto; padding: 16px; background: #0a0c12; position: relative; }
  .image-panel img { max-height: 100%; max-width: 100%; object-fit: contain; border-radius: 8px; box-shadow: 0 4px 20px rgba(0,0,0,0.5); }
  .image-toggle { position: absolute; top: 12px; right: 12px; display: flex; gap: 4px; }
  .image-toggle button { padding: 4px 10px; border: 1px solid #2a2d3a; border-radius: 4px; background: #161822; color: #a0a8c0; cursor: pointer; font-size: 11px; }
  .image-toggle button.active { background: #2a3060; color: #fff; border-color: #7c8aff; }

  /* 右侧 step 详情 */
  .detail-panel { width: 380px; min-width: 380px; background: #161822; border-left: 1px solid #2a2d3a; overflow-y: auto; padding: 16px; }
  .detail-section { margin-bottom: 16px; }
  .detail-label { font-size: 11px; font-weight: 700; color: #7c8aff; text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 6px; }
  .detail-content { font-size: 13px; color: #c0c8e0; line-height: 1.6; background: #1e2030; padding: 10px 12px; border-radius: 6px; white-space: pre-wrap; word-break: break-word; }
  .action-badge { display: inline-block; padding: 2px 8px; border-radius: 3px; font-size: 11px; font-weight: 700; margin-right: 4px; }
  .action-badge.click { background: #3a1515; color: #ff6b6b; }
  .action-badge.swipe { background: #152a3a; color: #6bb8ff; }
  .action-badge.long_press { background: #3a2a15; color: #ffb86b; }
  .action-badge.open { background: #153a1a; color: #6bff8b; }
  .action-badge.type { background: #2a153a; color: #b86bff; }
  .action-badge.answer { background: #153a35; color: #6bffeb; }
  .action-badge.system_button { background: #2a2a15; color: #e8e86b; }
  .action-badge.drag { background: #3a1535; color: #ff6beb; }
  .action-badge.other { background: #2a2a2a; color: #aaa; }

  .empty-state { display: flex; align-items: center; justify-content: center; height: 100%; color: #555; font-size: 15px; }

  .legend { display: flex; gap: 12px; align-items: center; font-size: 11px; color: #808899; padding: 4px 0; }
  .legend-item { display: flex; align-items: center; gap: 4px; }
  .legend-dot { width: 10px; height: 10px; border-radius: 50%; }

  /* 滚动条 */
  ::-webkit-scrollbar { width: 6px; }
  ::-webkit-scrollbar-track { background: transparent; }
  ::-webkit-scrollbar-thumb { background: #2a2d3a; border-radius: 3px; }
  ::-webkit-scrollbar-thumb:hover { background: #3a3d5a; }
</style>
</head>
<body>

<div class="sidebar">
  <div class="sidebar-header">
    <h2>Episode Visualizer</h2>
    <button class="refresh-btn" onclick="refreshTasks()" id="refreshBtn" title="刷新任务列表">
      <span class="refresh-icon">&#x21bb;</span> Refresh
    </button>
  </div>
  <div class="task-list" id="taskList"></div>
</div>

<div class="main">
  <div class="top-bar" id="topBar">
    <div class="empty-state" style="height:36px;font-size:13px;">选择左侧 Task / Episode 开始浏览</div>
  </div>
  <div class="step-nav" id="stepNav" style="display:none;"></div>
  <div class="content" id="contentArea">
    <div class="image-panel" id="imagePanel">
      <div class="empty-state">暂无图片</div>
    </div>
    <div class="detail-panel" id="detailPanel">
      <div class="empty-state">暂无详情</div>
    </div>
  </div>
</div>

<script>
const API = '';
let currentEpisode = null;  // { meta_data, steps }
let currentStep = 0;
let showAnnotated = true;
let openTaskNames = new Set();  // 记住展开的 task
let activeEpisodeKey = '';      // 记住选中的 episode: "taskName/episodeId"

// ── 刷新按钮 ──
async function refreshTasks() {
  const btn = document.getElementById('refreshBtn');
  btn.classList.add('spinning');
  btn.disabled = true;
  await loadTasks();
  setTimeout(() => { btn.classList.remove('spinning'); btn.disabled = false; }, 600);
}

// ── 加载任务列表 ──
async function loadTasks() {
  const res = await fetch(API + '/api/tasks');
  const tasks = await res.json();
  const container = document.getElementById('taskList');
  container.innerHTML = '';
  tasks.forEach(task => {
    const group = document.createElement('div');
    group.className = 'task-group';

    const isOpen = openTaskNames.has(task.task_name);
    const name = document.createElement('div');
    name.className = 'task-name' + (isOpen ? ' open' : '');
    name.innerHTML = `<span class="arrow">▶</span> ${task.task_name} <span style="color:#555;font-size:11px;">(${task.episodes.length})</span>`;

    const epList = document.createElement('div');
    epList.className = 'episode-list' + (isOpen ? ' show' : '');

    task.episodes.forEach(epId => {
      const item = document.createElement('div');
      const key = task.task_name + '/' + epId;
      item.className = 'episode-item' + (key === activeEpisodeKey ? ' active' : '');
      item.textContent = epId.slice(0, 8) + '...';
      item.title = epId;
      item.onclick = (e) => {
        e.stopPropagation();
        document.querySelectorAll('.episode-item').forEach(el => el.classList.remove('active'));
        item.classList.add('active');
        activeEpisodeKey = key;
        loadEpisode(task.task_name, epId);
      };
      epList.appendChild(item);
    });

    name.onclick = () => {
      const nowOpen = name.classList.toggle('open');
      epList.classList.toggle('show');
      if (nowOpen) openTaskNames.add(task.task_name);
      else openTaskNames.delete(task.task_name);
    };

    group.appendChild(name);
    group.appendChild(epList);
    container.appendChild(group);
  });
}

// ── 加载 episode ──
async function loadEpisode(taskName, episodeId) {
  const res = await fetch(API + `/api/episode/${taskName}/${episodeId}`);
  currentEpisode = await res.json();
  currentEpisode._taskName = taskName;
  currentEpisode._episodeId = episodeId;
  currentStep = 0;

  // 顶栏
  const topBar = document.getElementById('topBar');
  topBar.innerHTML = `
    <div class="meta-title">${currentEpisode.meta_data.task_name}</div>
    <div class="meta-goal">${currentEpisode.meta_data.task_goal}</div>
  `;

  // step 导航
  renderStepNav();
  renderStep();
}

function renderStepNav() {
  const nav = document.getElementById('stepNav');
  nav.style.display = 'flex';
  const steps = currentEpisode.steps;

  let html = '';
  steps.forEach((s, i) => {
    const actionMatch = s.action_info.match(/action:\\s*(\\w+)/);
    const action = actionMatch ? actionMatch[1] : '?';
    html += `<button class="step-btn ${i === currentStep ? 'active' : ''}" onclick="goStep(${i})">
      ${s.step}: ${action}
    </button>`;
  });

  html += `<div class="step-nav-arrows">
    <button onclick="goStep(Math.max(0, currentStep-1))">◀</button>
    <button onclick="goStep(Math.min(currentEpisode.steps.length-1, currentStep+1))">▶</button>
  </div>`;

  html += `<div class="legend">
    <div class="legend-item"><div class="legend-dot" style="background:#ff6b6b;"></div>Click</div>
    <div class="legend-item"><div class="legend-dot" style="background:#ffb86b;"></div>Long Press</div>
    <div class="legend-item"><div class="legend-dot" style="background:#6bb8ff;"></div>Swipe</div>
    <div class="legend-item"><div class="legend-dot" style="background:#b86bff;"></div>Drag</div>
  </div>`;

  nav.innerHTML = html;
}

function goStep(i) {
  currentStep = i;
  renderStepNav();
  renderStep();
}

function renderStep() {
  const step = currentEpisode.steps[currentStep];
  const tn = currentEpisode._taskName;
  const eid = currentEpisode._episodeId;

  // 图片
  const imagePanel = document.getElementById('imagePanel');
  if (step.has_image) {
    const imgUrl = showAnnotated
      ? `${API}/api/image/${tn}/${eid}/${step.step}`
      : `${API}/api/image_raw/${tn}/${eid}/${step.step}`;
    imagePanel.innerHTML = `
      <img src="${imgUrl}" alt="Step ${step.step}" />
      <div class="image-toggle">
        <button class="${showAnnotated ? 'active' : ''}" onclick="toggleAnnotation(true)">标注</button>
        <button class="${!showAnnotated ? 'active' : ''}" onclick="toggleAnnotation(false)">原图</button>
      </div>
    `;
  } else {
    imagePanel.innerHTML = '<div class="empty-state">图片不存在</div>';
  }

  // 详情面板
  const detailPanel = document.getElementById('detailPanel');
  const actionMatch = step.action_info.match(/action:\\s*(\\w+)/);
  const actionType = actionMatch ? actionMatch[1] : 'other';
  const badgeClass = ['click','swipe','long_press','open','type','answer','system_button','drag'].includes(actionType) ? actionType : 'other';

  detailPanel.innerHTML = `
    <div class="detail-section">
      <div class="detail-label">Step ${step.step}</div>
      <div class="detail-content" style="font-size:12px;color:#888;">${step.timestamp}</div>
    </div>
    <div class="detail-section">
      <div class="detail-label">Action</div>
      <div class="detail-content">
        <span class="action-badge ${badgeClass}">${actionType}</span>
        ${step.action_info.replace(/action:\\s*\\w+\\s*\\|?\\s*/, '')}
      </div>
    </div>
    <div class="detail-section">
      <div class="detail-label">Thinking</div>
      <div class="detail-content">${step.thinking || '<span style="color:#555">无</span>'}</div>
    </div>
    <div class="detail-section">
      <div class="detail-label">Raw Response</div>
      <div class="detail-content" style="font-size:11px;color:#888;max-height:300px;overflow-y:auto;">${escapeHtml(step.model_response)}</div>
    </div>
  `;
}

function toggleAnnotation(annotated) {
  showAnnotated = annotated;
  renderStep();
}

function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

// 键盘快捷键
document.addEventListener('keydown', (e) => {
  if (!currentEpisode) return;
  if (e.key === 'ArrowLeft' || e.key === 'a') {
    goStep(Math.max(0, currentStep - 1));
  } else if (e.key === 'ArrowRight' || e.key === 'd') {
    goStep(Math.min(currentEpisode.steps.length - 1, currentStep + 1));
  }
});

loadTasks();
</script>
</body>
</html>"""


@app.get("/", response_class=HTMLResponse)
def index():
    return FRONTEND_HTML


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8799)
