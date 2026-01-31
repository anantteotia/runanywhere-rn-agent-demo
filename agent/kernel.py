"""
Android Action Kernel - Main Agent Loop

An AI agent that controls Android devices through the accessibility API.
Uses LLMs to make decisions based on screen context.

Usage:
    python kernel.py
"""

import os
import json
import time
from typing import List, Dict, Any

from config import Config
from actions import execute_action, run_adb_command
from llm_providers import get_llm_provider
import sanitizer


def get_screen_state() -> str:
    """Dumps the current UI XML and returns the sanitized JSON string."""
    # 1. Capture XML from device
    run_adb_command(["shell", "uiautomator", "dump", Config.SCREEN_DUMP_PATH])

    # 2. Pull to local machine
    run_adb_command(["pull", Config.SCREEN_DUMP_PATH, Config.LOCAL_DUMP_PATH])

    # 3. Read & Sanitize
    if not os.path.exists(Config.LOCAL_DUMP_PATH):
        return "Error: Could not capture screen."

    with open(Config.LOCAL_DUMP_PATH, "r", encoding="utf-8") as f:
        xml_content = f.read()

    elements = sanitizer.get_interactive_elements(xml_content)
    return json.dumps(elements, indent=2)


def run_agent(goal: str, max_steps: int = None) -> None:
    """
    Main agent loop: Perceive -> Reason -> Act

    Args:
        goal: The task to accomplish
        max_steps: Maximum steps before stopping (default from config)
    """
    if max_steps is None:
        max_steps = Config.MAX_STEPS

    print(f"🚀 Android Action Kernel Started")
    print(f"📋 Goal: {goal}")
    print(f"🤖 Provider: {Config.LLM_PROVIDER} ({Config.get_model()})")

    # Initialize LLM provider
    llm = get_llm_provider()
    action_history: List[Dict[str, Any]] = []

    for step in range(max_steps):
        print(f"\n--- Step {step + 1}/{max_steps} ---")

        # 1. Perception: Capture screen state
        print("👀 Scanning Screen...")
        screen_context = get_screen_state()

        # 2. Reasoning: Get LLM decision
        print("🧠 Thinking...")
        decision = llm.get_decision(goal, screen_context, action_history)
        print(f"💡 Decision: {decision.get('reason', 'No reason provided')}")

        # 3. Action: Execute the decision
        execute_action(decision)

        # Track action history for context
        action_history.append(decision)

        # Wait for UI to update
        time.sleep(Config.STEP_DELAY)

    print("\n⚠️ Max steps reached. Task may be incomplete.")


def main():
    """Entry point for the Android Action Kernel."""
    try:
        Config.validate()
    except ValueError as e:
        print(f"❌ Configuration Error: {e}")
        return

    goal = input("Enter your goal: ")
    if not goal.strip():
        print("❌ No goal provided. Exiting.")
        return

    run_agent(goal)


if __name__ == "__main__":
    main()
