"""
LLM Provider module for Android Action Kernel.
Supports OpenAI, Groq, and AWS Bedrock.
"""

import json
import re
from abc import ABC, abstractmethod
from typing import Dict, Any, List

from config import Config
from constants import GROQ_API_BASE_URL, BEDROCK_ANTHROPIC_MODELS, BEDROCK_META_MODELS


# System prompt for the Android agent
SYSTEM_PROMPT = """
You are an Android Driver Agent. Your job is to achieve the user's goal by navigating the UI.

You will receive:
1. The User's Goal.
2. A list of interactive UI elements (JSON) with their (x,y) center coordinates.
3. Your previous actions (so you don't repeat yourself).

You must output ONLY a valid JSON object with your next action.

Available Actions:
- {"action": "tap", "coordinates": [x, y], "reason": "Why you are tapping"}
- {"action": "type", "text": "Hello World", "reason": "Why you are typing"}
- {"action": "enter", "reason": "Press Enter to submit/search"}
- {"action": "swipe", "direction": "up/down/left/right", "reason": "Why you are swiping"}
- {"action": "home", "reason": "Go to home screen"}
- {"action": "back", "reason": "Go back"}
- {"action": "wait", "reason": "Wait for loading"}
- {"action": "done", "reason": "Task complete"}

IMPORTANT RULES:
- If an element has "editable": true or "action": "type", use the "type" action to enter text.
- After tapping on a text field, your NEXT action should be "type" to enter text.
- After typing a URL or search query, use "enter" to submit it.
- Do NOT type the same text again if you already typed it in a previous step. Check PREVIOUS_ACTIONS.
- Do NOT tap the same element repeatedly. If you already tapped it, try a different action.
- If the screen shows your typed text, do NOT type again - use "enter" or tap a search result.
- If you need to find an app that's not on the home screen, swipe UP to open the app drawer.
- Use swipe to scroll through lists, pages, or to open the app drawer.

Example - Tapping a button:
{"action": "tap", "coordinates": [540, 1200], "reason": "Clicking the 'Connect' button"}

Example - Typing in a search box:
{"action": "type", "text": "White House", "reason": "Entering search query"}

Example - After typing a URL:
{"action": "enter", "reason": "Submitting the URL to navigate"}

Example - Opening app drawer to find an app:
{"action": "swipe", "direction": "up", "reason": "Opening app drawer to find Maps"}
"""


def format_action_history(action_history: List[Dict]) -> str:
    """Format action history for LLM context."""
    if not action_history:
        return ""

    history_lines = []
    for i, action in enumerate(action_history):
        action_type = action.get("action", "unknown")
        reason = action.get("reason", "N/A")

        if action_type == "type":
            history_lines.append(f"Step {i+1}: typed \"{action.get('text', '')}\" - {reason}")
        elif action_type == "tap":
            history_lines.append(f"Step {i+1}: tapped {action.get('coordinates', [])} - {reason}")
        else:
            history_lines.append(f"Step {i+1}: {action_type} - {reason}")

    return "\n\nPREVIOUS_ACTIONS:\n" + "\n".join(history_lines)


class LLMProvider(ABC):
    """Abstract base class for LLM providers."""

    @abstractmethod
    def get_decision(self, goal: str, screen_context: str, action_history: List[Dict]) -> Dict[str, Any]:
        """Get the next action decision from the LLM."""
        pass


class OpenAIProvider(LLMProvider):
    """OpenAI and Groq provider (OpenAI-compatible API)."""

    def __init__(self):
        from openai import OpenAI

        if Config.LLM_PROVIDER == "groq":
            self.client = OpenAI(
                api_key=Config.GROQ_API_KEY,
                base_url=GROQ_API_BASE_URL
            )
            self.model = Config.GROQ_MODEL
        else:
            self.client = OpenAI(api_key=Config.OPENAI_API_KEY)
            self.model = Config.OPENAI_MODEL

    def get_decision(self, goal: str, screen_context: str, action_history: List[Dict]) -> Dict[str, Any]:
        history_str = format_action_history(action_history)
        user_content = f"GOAL: {goal}\n\nSCREEN_CONTEXT:\n{screen_context}{history_str}"

        response = self.client.chat.completions.create(
            model=self.model,
            response_format={"type": "json_object"},
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": user_content}
            ]
        )

        return json.loads(response.choices[0].message.content)


class BedrockProvider(LLMProvider):
    """AWS Bedrock provider."""

    def __init__(self):
        import boto3
        self.client = boto3.client(
            service_name="bedrock-runtime",
            region_name=Config.AWS_REGION
        )
        self.model = Config.BEDROCK_MODEL

    def get_decision(self, goal: str, screen_context: str, action_history: List[Dict]) -> Dict[str, Any]:
        history_str = format_action_history(action_history)
        user_content = f"GOAL: {goal}\n\nSCREEN_CONTEXT:\n{screen_context}{history_str}"

        request_body = self._build_request(user_content)

        response = self.client.invoke_model(
            modelId=self.model,
            body=request_body,
            contentType="application/json",
            accept="application/json"
        )

        response_body = json.loads(response["body"].read())
        result_text = self._extract_response(response_body)

        return self._parse_json_response(result_text)

    def _is_anthropic_model(self) -> bool:
        """Check if current model is an Anthropic model."""
        return any(identifier in self.model for identifier in BEDROCK_ANTHROPIC_MODELS)

    def _is_meta_model(self) -> bool:
        """Check if current model is a Meta/Llama model."""
        return any(identifier in self.model.lower() for identifier in BEDROCK_META_MODELS)

    def _build_request(self, user_content: str) -> str:
        """Build request body based on model type."""
        if self._is_anthropic_model():
            return json.dumps({
                "anthropic_version": "bedrock-2023-05-31",
                "max_tokens": 1024,
                "system": SYSTEM_PROMPT,
                "messages": [
                    {"role": "user", "content": user_content + "\n\nRespond with ONLY a valid JSON object."}
                ]
            })
        elif self._is_meta_model():
            return json.dumps({
                "prompt": f"<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n{SYSTEM_PROMPT}<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n{user_content}\n\nRespond with ONLY a valid JSON object, no other text.<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n",
                "max_gen_len": 512,
                "temperature": 0.1
            })
        else:
            return json.dumps({
                "inputText": f"{SYSTEM_PROMPT}\n\n{user_content}\n\nRespond with ONLY a valid JSON object.",
                "textGenerationConfig": {
                    "maxTokenCount": 512,
                    "temperature": 0.1
                }
            })

    def _extract_response(self, response_body: Dict) -> str:
        """Extract text response based on model type."""
        if self._is_anthropic_model():
            return response_body["content"][0]["text"]
        elif self._is_meta_model():
            return response_body.get("generation", "")
        else:
            return response_body["results"][0]["outputText"]

    def _parse_json_response(self, text: str) -> Dict[str, Any]:
        """Parse JSON from LLM response, handling extra text."""
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            # Try to find JSON object in the response
            json_match = re.search(r'\{[^{}]*\}', text)
            if json_match:
                return json.loads(json_match.group())
            else:
                print(f"⚠️ Could not parse LLM response: {text[:200]}")
                return {"action": "wait", "reason": "Failed to parse response, waiting"}


def get_llm_provider() -> LLMProvider:
    """Factory function to get the appropriate LLM provider."""
    if Config.LLM_PROVIDER == "bedrock":
        return BedrockProvider()
    else:
        return OpenAIProvider()
