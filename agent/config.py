"""
Configuration management for Android Action Kernel.
Loads settings from environment variables and .env file.
"""

import os
from dotenv import load_dotenv

from constants import (
    DEVICE_DUMP_PATH,
    LOCAL_DUMP_PATH,
    DEFAULT_MAX_STEPS,
    DEFAULT_STEP_DELAY,
    DEFAULT_GROQ_MODEL,
    DEFAULT_OPENAI_MODEL,
    DEFAULT_BEDROCK_MODEL,
)

# Load environment variables from .env file
load_dotenv()


class Config:
    """Application configuration loaded from environment."""

    # ADB Configuration
    ADB_PATH: str = os.environ.get("ADB_PATH", "adb")
    DEVICE_ID: str = os.environ.get("DEVICE_ID", "")
    SCREEN_DUMP_PATH: str = DEVICE_DUMP_PATH
    LOCAL_DUMP_PATH: str = LOCAL_DUMP_PATH

    # Agent Configuration
    MAX_STEPS: int = int(os.environ.get("MAX_STEPS", str(DEFAULT_MAX_STEPS)))
    STEP_DELAY: float = float(os.environ.get("STEP_DELAY", str(DEFAULT_STEP_DELAY)))

    # LLM Provider: "groq", "openai", or "bedrock"
    LLM_PROVIDER: str = os.environ.get("LLM_PROVIDER", "openai")

    # Groq Configuration
    GROQ_API_KEY: str = os.environ.get("GROQ_API_KEY", "")
    GROQ_MODEL: str = os.environ.get("GROQ_MODEL", DEFAULT_GROQ_MODEL)

    # OpenAI Configuration
    OPENAI_API_KEY: str = os.environ.get("OPENAI_API_KEY", "")
    OPENAI_MODEL: str = os.environ.get("OPENAI_MODEL", DEFAULT_OPENAI_MODEL)

    # AWS Bedrock Configuration
    AWS_REGION: str = os.environ.get("AWS_REGION", "us-east-1")
    BEDROCK_MODEL: str = os.environ.get("BEDROCK_MODEL", DEFAULT_BEDROCK_MODEL)

    @classmethod
    def get_model(cls) -> str:
        """Get the model name based on the selected provider."""
        if cls.LLM_PROVIDER == "groq":
            return cls.GROQ_MODEL
        elif cls.LLM_PROVIDER == "bedrock":
            return cls.BEDROCK_MODEL
        else:
            return cls.OPENAI_MODEL

    @classmethod
    def validate(cls) -> None:
        """Validate that required configuration is present."""
        if cls.LLM_PROVIDER == "groq" and not cls.GROQ_API_KEY:
            raise ValueError("GROQ_API_KEY is required when using Groq provider")
        elif cls.LLM_PROVIDER == "openai" and not cls.OPENAI_API_KEY:
            raise ValueError("OPENAI_API_KEY is required when using OpenAI provider")
        # Bedrock uses AWS credential chain, no explicit validation needed
