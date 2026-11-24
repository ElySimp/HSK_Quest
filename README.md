# HSK Quest (MaoCAT) — Multimodal Adaptive Mandarin Learning Platform

An offline-capable gamified Mandarin learning RPG for HSK 1–4 preparation built with an Android Kotlin client, an asynchronous Python FastAPI + PostgreSQL backend, and three on-device TensorFlow Lite (TFLite) machine learning models.

## Key Features

- **RPG Combat & Multi-Modal Challenges:** Vocabulary practice across Reading, Listening (Chinese TTS), Speaking (tone grading), Writing (handwriting tracing), and contextual Cloze story sentences for Elite/Boss encounters.
- **Tianzige Writing Lab & HSK 1–9 Dictionary:** Searchable 11,000+ word dictionary supporting Hanzi, tone-marked Pinyin, tone-stripped ASCII Pinyin, English meaning, and camera-based Chinese OCR (CameraX + ML Kit).
- **Tamagotchi Companion & Progression:** Collectible mythological companion pets with passive buffs, shop inventory, gacha system, daily quests, and weekly competitive leagues.
- **Offline-First Synchronization:** Room local database (`v4`) with spaced repetition mastery tracking synced to centralized PostgreSQL persistence.

## On-Device Machine Learning Pipeline

1. **Hanzi Handwriting Classifier (`MobileNetV2`, $\alpha = 0.75$)**

   - Trained on CASIA-HWDB filtered to **3,087** Simplified Hanzi classes (`96x96` grayscale input).
   - **Evaluation:** **92.67% Top-1** and **98.53% Top-5** held-out accuracy.
2. **Mandarin Pronunciation & 5-Tone Classifier (`Dual-Head ToneNet`)**

   - Architecture: `SeparableConv1D` + `BiGRU` + `CTC` phoneme/tone heads paired with a pure-Kotlin DSP front-end (`80`-bin Log-Mel + `8`-dim `YIN-CMNDF` pitch/energy/spectral-tilt features at `16 kHz`).
   - **Evaluation:** **90.85%** unseen-speaker 5-tone accuracy (**89.01%** zero-shot on BZNSYP), **13.4 ms** INT8 inference latency (**29.2 ms** FP16), and **99.8%** foil-word rejection in 3-tier battle grading (`EXACT_MATCH` / `TONE_LENIENT_MATCH` / `NO_MATCH`).
3. **Computerized Adaptive Testing (CAT) Scheduler (`Multi-Task MLP`)**

   - Compact **3.4K-parameter** (`16 KB` TFLite) model predicting recall probability and memory half-life to target the **70–75% Flow Zone**.
   - **Evaluation:** **ROC-AUC 0.7418**, outperforming the deterministic Shuffle-Bag baseline across `1,000` paired simulations (**+15.09%** Flow Zone time, **+4.78%** 72-hour retention).

## Tech Stack

| Layer                   | Technologies                                                                                                              |
| :---------------------- | :------------------------------------------------------------------------------------------------------------------------ |
| **Mobile Client** | Kotlin, Java 17, MVVM, ViewBinding, Coroutines, Room, Retrofit + Gson, DataStore, Glide, CameraX, ML Kit, TensorFlow Lite |
| **Backend API**   | Python, FastAPI, Uvicorn, Async SQLAlchemy 2.0, `asyncpg`, Alembic, Pydantic, JWT (`python-jose` + `bcrypt`)        |
| **Database**      | PostgreSQL (Aiven SSL)                                                                                                    |

## Repository Structure

```text
HSK_Quest/
├── docs/                 # Game design notes (e.g., PET_BUFFS.md)
├── hsk_android_app/      # Kotlin Android client, DSP/TFLite engines, Room DB
└── hsk_python_backend/   # FastAPI routers, async SQLAlchemy models, adaptive/AI services
```
