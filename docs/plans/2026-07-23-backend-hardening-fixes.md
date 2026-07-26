# Backend Hardening Fixes Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Bound image upload reads, clean terminal workflow inputs, preserve all card fields, and isolate backend tests from the developer `.env`.

**Architecture:** Add a shared FastAPI upload reader, extend workflow persistence with atomic input-path detachment and lifecycle cleanup, and migrate the existing SQLite card table additively. Keep test isolation in pytest configuration so production settings behavior is unchanged.

**Tech Stack:** Python 3.11+, FastAPI, Pydantic 2, SQLite, pytest, Android/Kotlin regression build.

---

### Task 1: Isolate Tests From Provider Credentials

**Files:**
- Create: `services/api/tests/conftest.py`
- Test: `services/api/tests/test_workflow.py`

**Step 1: Demonstrate the current failure**

Run the concurrency test with provider credential variables set to non-empty
sentinel values and a valid provider base URL.

```powershell
$env:LANXIN_API_KEY = "test-key"
$env:FAST_MODEL_API_KEY = "test-key"
$env:EXPERT_MODEL_API_KEY = "test-key"
.\.venv\Scripts\python.exe -m pytest -q tests/test_workflow.py::PerformanceWorkflowTest::test_twenty_concurrent_rule_workflows_meet_local_budget
```

Expected: FAIL because at least one result routes through
`supervisor_agents` instead of `rules`.

**Step 2: Add pytest environment isolation**

Create `tests/conftest.py` and set all provider credential variables to empty
strings before test modules import application settings:

```python
import os

for name in (
    "LANXIN_API_KEY",
    "FAST_MODEL_API_KEY",
    "EXPERT_MODEL_API_KEY",
    "VIVO_OCR_APP_KEY",
    "VIVO_IMAGE_GENERATION_API_KEY",
):
    os.environ[name] = ""
```

Include a comment explaining that `python-dotenv` preserves existing values,
which prevents a developer `.env` from changing test routing.

**Step 3: Verify deterministic routing**

Run the same command with non-empty shell variables.

Expected: PASS because `conftest.py` replaces them before test collection.

### Task 2: Preserve Card Metadata In SQLite

**Files:**
- Modify: `services/api/app/db/connection.py`
- Modify: `services/api/app/repositories/cards.py`
- Create: `services/api/tests/test_cards.py`

**Step 1: Write failing repository tests**

Add tests that use a temporary database and assert:

- create returns the supplied `action_id`, `dependencies`, and
  `evidence_summary`;
- update changes all three fields without raising;
- opening a legacy table without these columns adds them and preserves the
  existing row.

**Step 2: Run the tests and verify red**

```powershell
.\.venv\Scripts\python.exe -m pytest -q tests/test_cards.py
```

Expected: FAIL because create drops the fields and update references missing
columns.

**Step 3: Implement the additive migration**

After `CREATE TABLE IF NOT EXISTS`, inspect `PRAGMA table_info(cards)` and issue
only the missing `ALTER TABLE` statements. Keep the migration idempotent.

**Step 4: Extend repository serialization**

Add `dependencies` and `evidence_summary` to `ARRAY_FIELDS`; add all three
missing columns to the create field list. Existing generic update logic will
then target real columns and JSON-encode list values.

**Step 5: Verify green**

Run `tests/test_cards.py` and confirm all tests pass.

### Task 3: Bound Image Upload Reads

**Files:**
- Create: `services/api/app/api/uploads.py`
- Modify: `services/api/app/api/endpoints/analyze.py`
- Modify: `services/api/app/api/endpoints/workflows.py`
- Create: `services/api/tests/test_uploads.py`

**Step 1: Write failing bounded-reader tests**

Use real Starlette `UploadFile` instances backed by `BytesIO` and assert:

- a payload at the limit is returned unchanged;
- a known oversize upload raises HTTP 413 before reading;
- an upload with unknown size is read in chunks and raises HTTP 413 as soon as
  the accumulated bytes exceed the limit;
- an empty upload raises HTTP 400.

**Step 2: Run the tests and verify red**

```powershell
.\.venv\Scripts\python.exe -m pytest -q tests/test_uploads.py
```

Expected: FAIL because the shared bounded reader does not exist.

**Step 3: Implement the bounded reader**

Create an async helper that:

- validates the optional declared upload size;
- reads at most 64 KiB per iteration;
- checks the cumulative size before appending more data;
- raises the existing Chinese HTTP 400/413 errors;
- returns the joined bytes for valid input.

**Step 4: Use the helper in both endpoints**

Keep current MIME validation, then delegate empty and size handling to the
shared helper.

**Step 5: Verify green**

Run `tests/test_uploads.py` and the existing analyzer/workflow tests.

### Task 4: Clean Terminal And Stale Workflow Inputs

**Files:**
- Modify: `services/api/app/repositories/workflows.py`
- Modify: `services/api/app/services/workflow_service.py`
- Modify: `services/api/app/main.py`
- Modify: `services/api/tests/test_workflow.py`

**Step 1: Write failing lifecycle tests**

Add repository/service tests that assert:

- atomically detaching an input path returns it once and clears the database
  column;
- terminal workflows delete their input file;
- non-terminal workflow files remain;
- startup cleanup deletes terminal references immediately;
- startup cleanup deletes unreferenced `.bin` files older than 24 hours;
- startup cleanup retains recent or active files and ignores unrelated files.

**Step 2: Run the tests and verify red**

```powershell
.\.venv\Scripts\python.exe -m pytest -q tests/test_workflow.py -k "input_path or input_cleanup"
```

Expected: FAIL because detachment and cleanup APIs do not exist.

**Step 3: Add atomic repository operations**

Add methods to:

- detach and return a run's `input_path` in one locked transaction;
- list run input paths with their workflow status for startup cleanup.

**Step 4: Add safe filesystem cleanup**

Resolve every candidate and verify it remains inside
`settings.workflow_input_directory`. Delete with `missing_ok=True`. Clear the
database path only after successful deletion; log failures for retry.

**Step 5: Wire lifecycle hooks**

Invoke per-run cleanup after transitions to `completed`, `failed`, or
`cancelled`. Invoke startup cleanup before `recover_workflows()`.

**Step 6: Verify green**

Run the targeted workflow tests and confirm they pass.

### Task 5: Full Verification

**Files:**
- Verify all modified files

**Step 1: Run Python compile check**

```powershell
.\.venv\Scripts\python.exe -m compileall -q app
```

Expected: exit code 0.

**Step 2: Run the full backend suite with provider variables populated**

```powershell
$env:LANXIN_API_KEY = "test-key"
$env:FAST_MODEL_API_KEY = "test-key"
$env:EXPERT_MODEL_API_KEY = "test-key"
.\.venv\Scripts\python.exe -m pytest -q
```

Expected: all tests pass, proving pytest isolation is effective.

**Step 3: Run Android tests**

```powershell
.\gradlew.bat testDebugUnitTest --no-daemon
```

Expected: build and unit tests succeed.

**Step 4: Assemble the Android debug APK**

```powershell
.\gradlew.bat assembleDebug --no-daemon
```

Expected: build succeeds.

**Step 5: Inspect final diff**

Confirm only the planned code, tests, and documents changed. Do not stage or
commit existing generated assets or unrelated worktree files.
