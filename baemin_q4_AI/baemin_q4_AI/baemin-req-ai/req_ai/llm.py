"""LLM 호출 계층.

모든 LLM 출력은 JSON 스키마로 받는다. 자유 텍스트를 파싱하지 않기 위해
Anthropic tool use 를 "강제 호출"(tool_choice) 방식으로 써서 스키마에 맞는 입력만 돌려받는다.

- AnthropicLLM : 실제 호출 (ANTHROPIC_API_KEY 필요)
- ReplayLLM    : 녹화해 둔 응답을 재생 (API 키·네트워크 없이 데모·테스트용)
- RecordingLLM : 실제 호출 결과를 파일로 남겨 다음부터 재생할 수 있게 한다
"""
from __future__ import annotations

import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

PROMPT_DIR = Path(__file__).resolve().parent.parent / "prompts"


def load_prompt(name: str) -> str:
    return (PROMPT_DIR / f"{name}.md").read_text(encoding="utf-8")


class LLM(Protocol):
    def complete_json(self, key: str, system: str, user: str, schema: dict[str, Any]) -> dict[str, Any]:
        """key 는 재생·녹화용 식별자 (예: 'analyze', 'agent/TC-01/3')."""
        ...


@dataclass
class AnthropicLLM:
    model: str = os.environ.get("QA_AGENT_MODEL", "claude-sonnet-5-5")
    max_tokens: int = 4096

    def complete_json(self, key: str, system: str, user: str, schema: dict[str, Any]) -> dict[str, Any]:
        import anthropic  # 선택 의존성: 실제 호출할 때만 필요

        client = anthropic.Anthropic()
        response = client.messages.create(
            model=self.model,
            max_tokens=self.max_tokens,
            system=system,
            tools=[{"name": "respond", "description": "결과를 스키마에 맞춰 반환", "input_schema": schema}],
            tool_choice={"type": "tool", "name": "respond"},
            messages=[{"role": "user", "content": user}],
        )
        for block in response.content:
            if block.type == "tool_use":
                return dict(block.input)
        raise RuntimeError(f"[{key}] 구조화 응답이 없음: {response.stop_reason}")


@dataclass
class ReplayLLM:
    """samples/replay/<key>.json 을 읽어 돌려준다. key 의 '/' 는 '__' 로 바꿔 파일명으로 쓴다."""

    replay_dir: Path

    def complete_json(self, key: str, system: str, user: str, schema: dict[str, Any]) -> dict[str, Any]:
        path = self.replay_dir / (key.replace("/", "__") + ".json")
        if not path.exists():
            raise FileNotFoundError(f"녹화된 응답 없음: {path.name} (실제 LLM 모드로 한 번 실행해 녹화하세요)")
        return json.loads(path.read_text(encoding="utf-8"))


@dataclass
class RecordingLLM:
    inner: LLM
    record_dir: Path

    def complete_json(self, key: str, system: str, user: str, schema: dict[str, Any]) -> dict[str, Any]:
        result = self.inner.complete_json(key, system, user, schema)
        self.record_dir.mkdir(parents=True, exist_ok=True)
        (self.record_dir / (key.replace("/", "__") + ".json")).write_text(
            json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        return result
