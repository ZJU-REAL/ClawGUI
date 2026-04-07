#!/usr/bin/env python3
"""
演示 GUI Agent 的个性化记忆功能

本示例展示如何使用记忆系统来：
1. 记录用户偏好
2. 自动学习使用习惯
3. 提供个性化的任务执行

参考: TeleMem (https://github.com/TeleAI-UAGI/TeleMem)
"""

import sys
import os

# 添加项目路径
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from phone_agent.memory import MemoryManager, MemoryType


def demo_basic_memory():
    """基础记忆功能演示"""
    print("=" * 60)
    print("🧠 个性化记忆系统演示")
    print("=" * 60)
    
    # 创建记忆管理器
    mm = MemoryManager(
        storage_dir="demo_memory_db",
        user_id="demo_user",
        enable_auto_extract=True,
        enable_thinking_analysis=True,  # 启用思考分析
    )
    
    # 1. 手动添加用户偏好（可选）
    print("\n📝 [可选] 手动添加用户偏好...")
    mm.add_user_preference("喜欢使用深色模式", category="ui")
    print("✅ 已手动添加 1 条用户偏好")
    
    # 2. 模拟任务开始 - 自动从任务描述中提取信息
    print("\n🚀 模拟任务执行（自动学习演示）...")
    print("   任务: 打开微信，给张三发消息说明天一起吃饭")
    mm.start_task("打开微信，给张三发消息说明天一起吃饭")
    print("   ✅ 自动识别: 应用=微信, 联系人=张三")
    
    # 模拟步骤 - 系统会自动学习
    print("\n   📱 执行步骤 1: 启动微信")
    mm.add_step(
        thinking="用户需要联系张三，先打开微信应用",
        action={"action": "Launch", "app": "微信"},
        screenshot_app="com.tencent.mm"
    )
    print("   ✅ 自动记录: 应用使用 (微信)")
    
    print("\n   📱 执行步骤 2: 搜索联系人")
    mm.add_step(
        thinking="在微信中搜索联系人张三的聊天记录",
        action={"action": "Type_Name", "text": "张三"},
        screenshot_app="com.tencent.mm"
    )
    print("   ✅ 自动识别: 联系人 (张三)")
    
    print("\n   📱 执行步骤 3: 发送消息")
    mm.add_step(
        thinking="找到了张三的聊天窗口，准备发送消息",
        action={"action": "Type", "text": "明天一起吃饭"},
        screenshot_app="com.tencent.mm"
    )
    
    # 任务完成 - 自动记录成功模式
    mm.end_task(success=True, result="消息已发送给张三")
    print("\n✅ 任务执行完成")
    print("   ✅ 自动记录: 任务历史、执行流程、成功模式")
    
    # 3. 查看记忆统计
    print("\n📊 记忆统计:")
    stats = mm.get_stats()
    print(f"  - 总记忆数: {stats['total_memories']}")
    print(f"  - 按类型分布: {stats['by_type']}")
    
    # 4. 获取用户画像
    print("\n👤 用户画像:")
    summary = mm.get_user_summary()
    if summary['contacts']:
        print(f"  - 常用联系人: {', '.join(summary['contacts'])}")
    if summary['frequent_apps']:
        print(f"  - 常用应用: {', '.join(summary['frequent_apps'])}")
    if summary['preferences']:
        print(f"  - 偏好: {summary['preferences'][:3]}")
    
    # 5. 搜索相关记忆
    print("\n🔍 搜索「外卖」相关记忆:")
    memories = mm.store.search("外卖", top_k=3)
    for i, mem in enumerate(memories, 1):
        print(f"  {i}. [{mem.memory_type.value}] {mem.content}")
    
    # 6. 获取任务上下文
    print("\n📋 获取新任务的个性化上下文:")
    context = mm.get_relevant_context("帮我点一份外卖")
    if context:
        print(context)
    else:
        print("  (暂无相关记忆)")
    
    print("\n" + "=" * 60)
    print("✅ 演示完成!")
    print("=" * 60)


def demo_with_agent():
    """展示如何在 PhoneAgent 中使用记忆功能"""
    print("\n" + "=" * 60)
    print("🤖 PhoneAgent 记忆集成演示")
    print("=" * 60)
    
    print("""
使用示例代码:

```python
from phone_agent import PhoneAgent
from phone_agent.agent import AgentConfig
from phone_agent.model import ModelConfig

# 创建带记忆功能的 Agent
agent_config = AgentConfig(
    enable_memory=True,      # 启用记忆
    memory_dir="memory_db",  # 记忆存储目录
    user_id="my_user",       # 用户标识
)

model_config = ModelConfig(
    base_url="http://localhost:8000/v1",
)

agent = PhoneAgent(model_config=model_config, agent_config=agent_config)

# 手动添加用户偏好
agent.add_user_preference("喜欢简洁的界面", category="ui")
agent.add_user_preference("常用联系人是老王", category="contact")

# 执行任务 - Agent 会自动学习和使用记忆
result = agent.run("打开微信给老王发消息")

# 查看学到的用户信息
summary = agent.get_user_summary()
print(f"学到的联系人: {summary['contacts']}")
print(f"常用应用: {summary['frequent_apps']}")

# 导出记忆用于备份
memories = agent.export_memories()

# 从备份恢复记忆
agent.import_memories(memories)

# 清除所有记忆
agent.clear_memories()
```
""")


def demo_auto_learning():
    """展示自动学习能力"""
    print("\n" + "=" * 60)
    print("🔄 自动学习机制")
    print("=" * 60)
    
    print("""
📌 自动学习触发时机:

1️⃣  任务开始时 (start_task)
   - 自动从任务描述中提取联系人名称
   - 自动识别要使用的应用名称
   例: "给张三发微信" → 识别出联系人"张三"和应用"微信"

2️⃣  每一步执行时 (add_step)
   - 追踪当前使用的应用
   - 从 Type_Name 动作提取联系人
   - 从 Launch 动作记录应用偏好
   - 从搜索内容学习用户兴趣
   - 分析 Agent 思考过程提取信息

3️⃣  任务完成时 (end_task)
   - 记录完整任务历史
   - 学习成功的执行模式
   - 记录应用使用流程

📌 智能去重:
   - 相似记忆自动合并
   - 同一会话内避免重复记录
   - 基于语义相似度去重 (>85% 相似)

📌 重要性自适应:
   - 高频访问的记忆重要性提升
   - 用户纠正的记忆权重最高
   - 自动提取的记忆初始权重较低
""")


def demo_memory_types():
    """展示所有记忆类型"""
    print("\n" + "=" * 60)
    print("📚 记忆类型说明")
    print("=" * 60)
    
    types_info = [
        (MemoryType.USER_PREFERENCE, "用户偏好", "存储用户的个人喜好和设置"),
        (MemoryType.CONTACT, "联系人", "记录常用联系人信息"),
        (MemoryType.TASK_PATTERN, "任务模式", "学习用户的任务执行模式"),
        (MemoryType.APP_USAGE, "应用使用", "追踪应用使用频率和习惯"),
        (MemoryType.TASK_HISTORY, "任务历史", "记录成功完成的任务"),
        (MemoryType.USER_CORRECTION, "用户纠正", "存储用户的纠正反馈用于学习"),
        (MemoryType.GENERAL, "通用", "其他通用类型的记忆"),
    ]
    
    for mem_type, name, desc in types_info:
        print(f"\n  📌 {name} ({mem_type.value})")
        print(f"     {desc}")


if __name__ == "__main__":
    demo_basic_memory()
    demo_auto_learning()
    demo_memory_types()
    demo_with_agent()

