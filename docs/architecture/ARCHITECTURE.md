# Architecture

## Runtime Flow

```mermaid
flowchart LR
    A["Screenshot or image"] --> B{"Prefer cloud model?"}
    B -->|yes| C["POST /api/analyze/screenshot-image"]
    C --> D["vivo OCR"]
    D --> E{"Lanxin available?"}
    B -->|no or image API failed| F["Android ML Kit OCR"]
    F --> G["POST /api/analyze/screenshot-text"]
    G --> E
    E -->|yes| H["LLM structured extraction"]
    E -->|no or failed| I["Rule fallback"]
    H --> J["Draft action cards"]
    I --> J
    J --> K["Preview and edit"]
    K --> L["Save card"]
    L --> M["Room and SQLite"]
    L --> N["WorkManager reminders"]
    L --> O["Calendar view"]
```

## Backend Boundaries

- `api/endpoints`: HTTP boundary only.
- `services/analyzer.py`: orchestration between LLM and fallback extraction.
- `services/vivo_ocr.py`: vivo OCR request, response parsing, and screenshot text cleanup.
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
