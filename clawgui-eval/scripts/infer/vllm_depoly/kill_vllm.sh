ps aux | grep "VLLM::EngineCore" | grep -v grep | awk '{print $2}' | xargs -r kill -9
ps aux | grep "VLLM" | grep -v grep | awk '{print $2}' | xargs -r kill -9
echo "killed vllm successfully"