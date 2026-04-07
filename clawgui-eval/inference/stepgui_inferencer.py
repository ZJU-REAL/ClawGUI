import torch
import base64
from io import BytesIO
from PIL import Image
from typing import Any
from .base_inferencer import BaseInferencer, _best_attn_implementation


class StepGUIInferencer(BaseInferencer):
    """Inferencer for StepGUI-4B models (Qwen3-VL architecture)."""

    def __init__(self, model_path: str, backend: str = "transformers", **kwargs):
        super().__init__(model_path, backend, **kwargs)

    def _init_model(self):
        print(f"Loading StepGUI-4B model: {self.model_path}")

        if self.backend == "transformers":
            from transformers import AutoModelForImageTextToText, AutoProcessor

            self.model = AutoModelForImageTextToText.from_pretrained(
                self.model_path,
                dtype=torch.bfloat16,
                device_map="cuda",
                attn_implementation=_best_attn_implementation(),
                trust_remote_code=True
            )
            self.processor = AutoProcessor.from_pretrained(
                self.model_path,
                trust_remote_code=True
            )
            print(f"Model loaded: {self.model_path}")

        elif self.backend == "api":
            from openai import OpenAI, Timeout

            self.api_key = self.kwargs.get("api_key", "EMPTY")
            if not self.api_key or self.api_key.strip() == "":
                self.api_key = "EMPTY"

            api_base_str = self.kwargs.get("api_base", None)
            if api_base_str is None:
                raise ValueError("--api_base is required for API backend.")

            self.api_urls = [url.strip() for url in api_base_str.split(',')]
            self.model_name = self.kwargs.get("model_name", "stepfun-ai/GELab-Zero-4B-preview")

            print(f"  API endpoints ({len(self.api_urls)}):")
            for idx, url in enumerate(self.api_urls):
                print(f"    [{idx+1}] {url}")
            print(f"  Model: {self.model_name}")

            self.clients = []
            for url in self.api_urls:
                self.clients.append(OpenAI(
                    api_key=self.api_key,
                    base_url=url,
                    timeout=Timeout(connect=30.0, read=120.0, write=10.0, pool=10.0),
                    max_retries=3
                ))
            print(f"  {len(self.clients)} API client(s) ready (connect=30s, read=120s, retries=3)")

        else:
            raise ValueError(f"Unsupported backend: {self.backend}")

    def _build_prompt(self, question: str, image: Image.Image, system_prompts: list = None) -> Any:
        image_dict = {"type": "image", "image": image}
        if self.min_pixels is not None:
            image_dict["min_pixels"] = self.min_pixels
        if self.max_pixels is not None:
            image_dict["max_pixels"] = self.max_pixels

        if self.tv_or_vt == "tv":
            content = [{"type": "text", "text": question}, image_dict]
        else:
            content = [image_dict, {"type": "text", "text": question}]

        messages = []
        if system_prompts:
            system_content = [{"type": "text", "text": t} for t in system_prompts if t]
            if system_content:
                messages.append({"role": "system", "content": system_content})
        messages.append({"role": "user", "content": content})

        if self.backend == "transformers":
            from qwen_vl_utils import process_vision_info

            text = self.processor.apply_chat_template(
                messages, tokenize=False, add_generation_prompt=True
            )
            # image_patch_size=16 is required for StepGUI (Qwen3-VL backend)
            images, videos = process_vision_info(messages, image_patch_size=16)
            inputs = self.processor(
                text=text,
                images=images,
                videos=videos,
                do_resize=False,
                return_tensors="pt"
            )
            return inputs.to(self.model.device)

        elif self.backend == "api":
            buffered = BytesIO()
            image.save(buffered, format="PNG")
            img_base64 = base64.b64encode(buffered.getvalue()).decode()

            api_messages = []
            for msg in messages:
                api_content = []
                for item in msg["content"]:
                    if item["type"] == "image":
                        api_content.append({
                            "type": "image_url",
                            "image_url": {"url": f"data:image/png;base64,{img_base64}"}
                        })
                    else:
                        api_content.append(item)
                api_messages.append({"role": msg["role"], "content": api_content})
            return api_messages

    def _generate(self, inputs: Any) -> str:
        if self.backend == "transformers":
            generate_kwargs = {
                "max_new_tokens": self.kwargs.get("max_tokens", 128),
                "temperature": self.kwargs.get("temperature", 0.0),
                "top_p": self.kwargs.get("top_p", 1.0),
                "do_sample": self.kwargs.get("temperature", 0.0) > 0,
                "use_cache": self.kwargs.get("use_cache", True),
            }
            top_k = self.kwargs.get("top_k", -1)
            if top_k > 0:
                generate_kwargs["top_k"] = top_k

            generated_ids = self.model.generate(**inputs, **generate_kwargs)
            generated_ids_trimmed = [
                out_ids[len(in_ids):] for in_ids, out_ids in zip(inputs.input_ids, generated_ids)
            ]
            return self.processor.batch_decode(
                generated_ids_trimmed,
                skip_special_tokens=True,
                clean_up_tokenization_spaces=False
            )[0]

        elif self.backend == "api":
            import random
            client = random.choice(self.clients)  # round-robin load balancing
            response = client.chat.completions.create(
                model=self.model_name,
                messages=inputs,
                max_tokens=self.kwargs.get("max_tokens", 2048),
                temperature=self.kwargs.get("temperature", 0.0),
                top_p=self.kwargs.get("top_p", 1.0),
                extra_body={"top_k": self.kwargs.get("top_k", 1.0)}
            )
            return response.choices[0].message.content

        else:
            raise ValueError(f"Unsupported backend: {self.backend}")

    def _post_process(self, output):
        if isinstance(output, str):
            if self.backend == "transformers" and "<|im_start|>assistant" in output:
                output = output.split("<|im_start|>assistant")[-1]
                output = output.replace("<|im_end|>", "").strip()
            return output
        elif isinstance(output, list):
            return [self._post_process(o) for o in output]
        return output
