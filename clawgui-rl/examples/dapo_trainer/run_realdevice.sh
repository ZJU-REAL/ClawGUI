set -x
export CUDA_VISIBLE_DEVICES=4,5,6,7
export NCCL_P2P_DISABLE=1

# cd to script directory so relative paths work from any working directory
cd "$(dirname "$0")"

# ============ User-configurable parameters ============
model_path=<path-to-your-model>
model_type=owl
data_dir=~/data/realdevice_online_rl/visual
n_gpus=2
history_length=3
max_steps=7
save_freq=5
total_epochs=1
train_data_size=2
val_data_size=1
group_size=1
adv_estimator=grpo
num_cpus_per_env_worker=0.10
experiment_name=dapo_realdevice
shuffle=False
checkpoints_path=<path-to-save-checkpoints>
server_file=../env_server/realdevice_server.txt

# Real device ADB device IDs (run 'adb devices' to find these)
# - Single device:   device=DEVICE_ID
# - Multiple devices: device=DEVICE_ID1,DEVICE_ID2  (comma-separated, count must == train_batch_size * group_size)
# - Auto-detect:      device=auto  (query server for connected devices)
device=<your-device-id>

# ============ DAPO-specific parameters ============
clip_ratio_low=0.2
clip_ratio_high=0.28
enable_filter_groups=True
max_num_gen_batches=10

# ============ Step reward judge parameters ============
step_reward_judge=False
step_reward_judge_base_url=""
step_reward_judge_model_name=""
step_reward_judge_api_key=""

# ============ Task eval judge parameters ============
# VLM-based task completion evaluation
# Called when agent outputs answer/terminate/status to determine if task succeeded
# If enabled, score=1 → reward=1 + won=True; score=0 → reward=0 + won=False
task_eval_judge=True
task_eval_judge_base_url=<task-eval-judge-url>
task_eval_judge_model_name=<task-eval-judge-model>
task_eval_judge_api_key=""

# No container restart for real device
env_restart_enable=False

# Task file (one task per line)
task_file=../env_server/realdevice_tasks.txt

# ============ Data preprocessing ============
python ../data_preprocess/realdevice_onlinerl.py \
    --task_file $task_file \
    --total_epochs $total_epochs

HYDRA_FULL_ERROR=1 python3 -m verl.trainer.main_ppo \
    algorithm.adv_estimator=$adv_estimator \
    data.train_files=$data_dir/train.parquet \
    data.val_files=$data_dir/test.parquet \
    data.train_batch_size=$train_data_size \
    data.val_batch_size=$val_data_size \
    data.shuffle=$shuffle \
    data.max_prompt_length=17000 \
    data.max_response_length=512 \
    data.filter_overlong_prompts=True \
    data.truncation='error' \
    data.image_key=images \
    data.return_raw_chat=True \
    actor_rollout_ref.model.path=$model_path \
    actor_rollout_ref.actor.optim.lr=1e-6 \
    actor_rollout_ref.model.use_remove_padding=True \
    actor_rollout_ref.actor.ppo_mini_batch_size=4 \
    actor_rollout_ref.actor.ppo_micro_batch_size_per_gpu=1 \
    actor_rollout_ref.actor.use_kl_loss=True \
    actor_rollout_ref.actor.kl_loss_coef=0.01 \
    actor_rollout_ref.actor.kl_loss_type=low_var_kl \
    actor_rollout_ref.actor.clip_ratio_low=${clip_ratio_low} \
    actor_rollout_ref.actor.clip_ratio_high=${clip_ratio_high} \
    actor_rollout_ref.model.enable_gradient_checkpointing=True \
    actor_rollout_ref.actor.fsdp_config.param_offload=True \
    actor_rollout_ref.actor.fsdp_config.optimizer_offload=True \
    actor_rollout_ref.rollout.log_prob_micro_batch_size_per_gpu=1 \
    actor_rollout_ref.rollout.tensor_model_parallel_size=1 \
    actor_rollout_ref.rollout.name=vllm \
    actor_rollout_ref.rollout.temperature=0.7 \
    actor_rollout_ref.rollout.gpu_memory_utilization=0.65 \
    actor_rollout_ref.rollout.enable_chunked_prefill=False \
    actor_rollout_ref.rollout.enforce_eager=False \
    actor_rollout_ref.rollout.free_cache_engine=False \
    actor_rollout_ref.rollout.val_kwargs.temperature=0.4 \
    actor_rollout_ref.rollout.val_kwargs.do_sample=True \
    actor_rollout_ref.ref.log_prob_micro_batch_size_per_gpu=1 \
    actor_rollout_ref.ref.fsdp_config.param_offload=True \
    actor_rollout_ref.actor.use_invalid_action_penalty=True \
    actor_rollout_ref.actor.invalid_action_penalty_coef=0.1 \
    algorithm.use_kl_in_reward=False \
    algorithm.filter_groups.enable=${enable_filter_groups} \
    algorithm.filter_groups.max_num_gen_batches=${max_num_gen_batches} \
    env.env_name=RealDevice \
    env.model_type=$model_type \
    env.server_file=$server_file \
    +env.device=$device \
    env.seed=0 \
    env.history_length=$history_length \
    env.max_steps=$max_steps \
    env.rollout.n=$group_size \
    env.resources_per_worker.num_cpus=$num_cpus_per_env_worker \
    env.step_reward_judge=$step_reward_judge \
    env.step_reward_judge_base_url=$step_reward_judge_base_url \
    env.step_reward_judge_model_name=$step_reward_judge_model_name \
    env.step_reward_judge_api_key=$step_reward_judge_api_key \
    env.task_eval_judge=$task_eval_judge \
    env.task_eval_judge_base_url=$task_eval_judge_base_url \
    env.task_eval_judge_model_name=$task_eval_judge_model_name \
    env.task_eval_judge_api_key=$task_eval_judge_api_key \
    env.restart.enable=$env_restart_enable \
    trainer.critic_warmup=0 \
    trainer.default_local_dir=$checkpoints_path \
    trainer.logger=['console','swanlab'] \
    trainer.project_name='online_rl_realdevice' \
    trainer.experiment_name=$experiment_name \
    trainer.n_gpus_per_node=$n_gpus \
    trainer.nnodes=1 \
    trainer.save_freq=$save_freq \
    trainer.test_freq=-1 \
    trainer.total_epochs=1 \
    trainer.val_before_train=False $@
