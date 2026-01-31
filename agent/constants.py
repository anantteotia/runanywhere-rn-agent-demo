"""
Constants for Android Action Kernel.
All magic strings, URLs, and fixed values in one place.
"""

# ===========================================
# API Endpoints
# ===========================================
GROQ_API_BASE_URL = "https://api.groq.com/openai/v1"

# ===========================================
# ADB Key Codes
# ===========================================
KEYCODE_ENTER = "66"
KEYCODE_HOME = "KEYCODE_HOME"
KEYCODE_BACK = "KEYCODE_BACK"

# ===========================================
# Default Screen Coordinates (for swipe actions)
# Adjust based on target device resolution
# ===========================================
SCREEN_CENTER_X = 540
SCREEN_CENTER_Y = 1200

# Swipe coordinates: (start_x, start_y, end_x, end_y)
SWIPE_COORDS = {
    "up": (SCREEN_CENTER_X, 1500, SCREEN_CENTER_X, 500),
    "down": (SCREEN_CENTER_X, 500, SCREEN_CENTER_X, 1500),
    "left": (800, SCREEN_CENTER_Y, 200, SCREEN_CENTER_Y),
    "right": (200, SCREEN_CENTER_Y, 800, SCREEN_CENTER_Y),
}
SWIPE_DURATION_MS = "300"

# ===========================================
# Default Models
# ===========================================
DEFAULT_GROQ_MODEL = "llama-3.3-70b-versatile"
DEFAULT_OPENAI_MODEL = "gpt-4o"
DEFAULT_BEDROCK_MODEL = "us.meta.llama3-3-70b-instruct-v1:0"

# ===========================================
# Bedrock Model Identifiers
# ===========================================
BEDROCK_ANTHROPIC_MODELS = ["anthropic"]
BEDROCK_META_MODELS = ["meta", "llama"]

# ===========================================
# File Paths
# ===========================================
DEVICE_DUMP_PATH = "/sdcard/window_dump.xml"
LOCAL_DUMP_PATH = "window_dump.xml"

# ===========================================
# Agent Defaults
# ===========================================
DEFAULT_MAX_STEPS = 10
DEFAULT_STEP_DELAY = 2.0
