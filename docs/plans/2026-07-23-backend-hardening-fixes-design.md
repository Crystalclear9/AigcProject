# Backend Hardening Fixes Design

## Scope

This change fixes three verified defects:

1. Image uploads are currently read into one unbounded byte array before the
   size check, and persisted workflow inputs are never removed.
2. The backend card schema does not persist `action_id`, `dependencies`, or
   `evidence_summary`, even though the API and Android client exchange them.
3. Backend tests load the developer `.env`, so valid provider credentials can
   change routing behavior and make the local suite fail.

Authentication and privacy-mask behavior are outside this change.

## Upload And Input Lifecycle

Both image endpoints will use one shared bounded reader. It will reject a
known-oversize `UploadFile` before reading and will otherwise consume fixed-size
chunks, stopping as soon as the configured limit is exceeded. Content-type,
empty-file, and size failures will preserve the existing HTTP status codes.

Image workflow inputs remain on disk while a workflow is active or waiting for
user input. When a workflow enters `completed`, `failed`, or `cancelled`, the
service will atomically detach its recorded input path and delete the file.
Deletion is idempotent so retry and shutdown paths can call it safely.

At service startup, a recovery cleanup will:

- delete input files still referenced by terminal workflow rows;
- retain files referenced by non-terminal workflows;
- delete unreferenced `.bin` files older than 24 hours;
- ignore unrelated files and tolerate already-missing files.

The startup cleanup runs before workflow recovery.

## Card Storage Compatibility

The existing `cards` table will receive an idempotent additive migration for:

- `action_id TEXT`;
- `dependencies TEXT NOT NULL DEFAULT '[]'`;
- `evidence_summary TEXT NOT NULL DEFAULT '[]'`.

The repository will encode and decode the two new list fields with the existing
JSON list mechanism. Create and update operations will include all three
columns. This preserves existing rows and makes Android create, update, and
server-sync round trips lossless.

## Test Isolation

Backend tests will set provider credential environment variables to empty
before application modules are imported. Because `python-dotenv` does not
override existing environment variables by default, the developer `.env`
cannot enable providers during test collection. Individual tests may still
replace settings explicitly when they need to exercise provider behavior.

## Error Handling

- Oversize uploads return HTTP 413 without assembling the full body in memory.
- Input deletion failures are logged and leave the database path available for
  a later startup cleanup instead of pretending cleanup succeeded.
- Card migration inspects existing columns before issuing `ALTER TABLE`, making
  repeated startup safe.
- Cleanup only operates inside the configured workflow input directory.

## Verification

Regression tests will cover:

- known-size and streamed oversize upload rejection;
- terminal input deletion and non-terminal retention;
- startup deletion of terminal and stale orphan inputs;
- card create/update round trips for the missing fields;
- migration of a legacy `cards` table;
- provider routing tests remaining deterministic when a local `.env` exists.

After targeted red-green cycles, the full backend test suite, Python compile
check, Android unit tests, and Android debug assembly will run.
