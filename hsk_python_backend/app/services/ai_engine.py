"""
AI Engine service — writing and voice evaluation.
Placeholder for Phase 6 full implementation.
"""


async def evaluate_writing_strokes(stroke_data: list[dict]) -> dict:
    """
    Phase 6: Evaluate Hanzi writing from stroke coordinate vectors.

    Beginner mode: Validate stroke order and direction.
    Native mode: ML image similarity scoring.

    For now: basic validation (check minimum strokes exist).
    """
    if not stroke_data or len(stroke_data) < 2:
        return {
            "is_correct": False,
            "score": 0.0,
            "feedback": "Not enough strokes detected. Please try writing the character.",
        }

    # Placeholder: accept any input with ≥2 strokes
    return {
        "is_correct": True,
        "score": 0.8,
        "feedback": "Writing accepted (AI evaluation coming in Phase 6).",
    }


async def evaluate_pronunciation(audio_file_path: str, expected_pinyin: str) -> dict:
    """
    Phase 6: Evaluate pronunciation using MDD (Mispronunciation Detection).

    For now: placeholder that always returns a passing score.
    Fallback in-app: timed Pinyin multiple choice.
    """
    return {
        "is_correct": True,
        "score": 0.75,
        "tone_accuracy": 0.8,
        "feedback": "Pronunciation accepted (MDD engine coming in Phase 6).",
    }
