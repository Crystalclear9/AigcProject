# Architecture

## Runtime Flow

```mermaid
flowchart LR
    A["Screenshot or image"] --> B["Android OCR"]
    B --> C["FastAPI analyze endpoint"]
    C --> D{"LLM configured?"}
    D -->|yes| E["LLM structured extraction"]
    D -->|no or failed| F["Rule fallback"]
    E --> G["Draft action cards"]
    F --> G
    G --> H["Preview and edit"]
    H --> I["Save card"]
    I --> J["Room and SQLite"]
    I --> K["WorkManager reminders"]
    I --> L["Calendar view"]
```

## Backend Boundaries

- `api/endpoints`: HTTP boundary only.
- `services/analyzer.py`: orchestration between LLM and fallback extraction.
- `services/rule_extractor.py`: deterministic extraction for demo resilience.
- `repositories/cards.py`: persistence and row mapping.
- `schemas/card.py`: public request and response shape.

## Android Boundaries

- `data/remote`: Retrofit DTOs and API interface.
- `data/local`: Room entities and DAO.
- `data/repository`: merge remote, local cache, and fallback behavior.
- `domain`: deterministic extraction and reminder policy.
- `ui/screens`: feature screens.
- `ui/components`: reusable visual primitives.
