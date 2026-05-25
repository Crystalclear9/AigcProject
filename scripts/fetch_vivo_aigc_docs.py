#!/usr/bin/env python3
"""Fetch vivo AIGC contest API documents into local Markdown and HTML files."""

from __future__ import annotations

import argparse
import datetime as dt
import html
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

from bs4 import BeautifulSoup, NavigableString, Tag


BASE_URL = "https://aigc.vivo.com.cn"
BUSINESS_CODE = "9b2ca654118cac5b4eb2883515326b8d"
TREE_URL = f"{BASE_URL}/vstack/webapi/service/doc/tree"
DOC_URL = f"{BASE_URL}/vstack/webapi/service/doc/info/v1"
SECRET_PATTERNS = [
    # Public docs can contain vendor example credentials. Keep local exports push-safe.
    re.compile(r"AKLT[A-Za-z0-9_\-]{12,}"),
    re.compile(r"AKTP[A-Za-z0-9_\-]{12,}"),
    re.compile(r"AKIA[A-Z0-9]{12,}"),
]


def fetch_json(url: str, params: dict[str, str] | None = None) -> dict[str, Any]:
    """Fetch a JSON endpoint with a browser-like User-Agent."""
    if params:
        url = f"{url}?{urllib.parse.urlencode(params)}"
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/json, text/plain, */*",
            "User-Agent": (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Safari/537.36"
            ),
            "Referer": f"{BASE_URL}/#/document/index?id=1746",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def require_success(payload: dict[str, Any], context: str) -> Any:
    """Return the data field or fail loudly if the API reports an error."""
    if payload.get("retcode") != 0:
        raise RuntimeError(f"{context} failed: {payload!r}")
    return payload.get("data")


def redact_text(value: str) -> str:
    """Replace access-key-like tokens with stable placeholders before writing files."""
    for pattern in SECRET_PATTERNS:
        value = pattern.sub("<REDACTED_ACCESS_KEY_ID>", value)
    return value


def redact_payload(value: Any) -> Any:
    """Recursively redact string fields in JSON payloads."""
    if isinstance(value, str):
        return redact_text(value)
    if isinstance(value, list):
        return [redact_payload(item) for item in value]
    if isinstance(value, dict):
        return {key: redact_payload(item) for key, item in value.items()}
    return value


def flatten_docs(nodes: list[dict[str, Any]], parents: list[str] | None = None) -> list[dict[str, Any]]:
    """Flatten the document tree while preserving the display path."""
    parents = parents or []
    docs: list[dict[str, Any]] = []
    for node in nodes:
        path = parents + [str(node["name"])]
        if node.get("dataType") == 2:
            docs.append(
                {
                    "id": node["id"],
                    "name": node["name"],
                    "path": path,
                    "level": node.get("level"),
                    "parentId": node.get("parentId"),
                }
            )
        children = node.get("children") or []
        docs.extend(flatten_docs(children, path))
    return docs


def slugify(value: str) -> str:
    """Create a Windows-safe, stable filename fragment."""
    value = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", value.strip())
    value = re.sub(r"\s+", "_", value)
    value = value.strip("._")
    return value or "untitled"


def timestamp_ms_to_local(ms: int | None) -> str:
    """Convert API millisecond timestamps to a readable local time string."""
    if not ms:
        return ""
    local_time = dt.datetime.fromtimestamp(ms / 1000)
    return local_time.strftime("%Y-%m-%d %H:%M:%S")


def normalize_text(text: str) -> str:
    """Collapse whitespace without destroying meaningful Chinese text."""
    return re.sub(r"[ \t\r\n]+", " ", html.unescape(text)).strip()


def render_inline(node: Tag | NavigableString) -> str:
    """Render inline HTML nodes to Markdown."""
    if isinstance(node, NavigableString):
        return normalize_text(str(node))
    if not isinstance(node, Tag):
        return ""
    name = node.name.lower()
    if name in {"br"}:
        return "\n"
    if name == "code":
        return f"`{node.get_text('', strip=True)}`"
    if name == "a":
        label = normalize_text(node.get_text(" ", strip=True))
        href = node.get("href", "")
        return f"[{label}]({href})" if href else label
    if name in {"strong", "b"}:
        return f"**{render_inline_children(node)}**"
    if name in {"em", "i"}:
        return f"*{render_inline_children(node)}*"
    return render_inline_children(node)


def render_inline_children(node: Tag) -> str:
    """Render a tag's children in inline mode."""
    parts = [render_inline(child) for child in node.children]
    return re.sub(r" {2,}", " ", "".join(parts)).strip()


def render_table(table: Tag) -> str:
    """Render an HTML table as a Markdown table."""
    rows: list[list[str]] = []
    for tr in table.find_all("tr", recursive=True):
        cells = tr.find_all(["th", "td"], recursive=False)
        if not cells:
            continue
        rows.append([render_inline_children(cell).replace("\n", " ").strip() for cell in cells])
    if not rows:
        return ""

    max_cols = max(len(row) for row in rows)
    padded = [row + [""] * (max_cols - len(row)) for row in rows]
    header = padded[0]
    separator = ["---"] * max_cols
    body = padded[1:]

    def fmt(row: list[str]) -> str:
        return "| " + " | ".join(cell.replace("|", "\\|") for cell in row) + " |"

    return "\n".join([fmt(header), fmt(separator), *[fmt(row) for row in body]])


def indent_markdown(text: str, spaces: int) -> str:
    """Indent nested Markdown while keeping blank lines readable."""
    prefix = " " * spaces
    return "\n".join(prefix + line if line else "" for line in text.splitlines())


def render_list(list_node: Tag, ordered: bool) -> str:
    """Render nested HTML lists without flattening code examples."""
    items: list[str] = []
    for index, li in enumerate(list_node.find_all("li", recursive=False), start=1):
        prefix = f"{index}. " if ordered else "- "
        item = render_children(li).strip()
        if not item:
            continue
        lines = item.splitlines()
        rendered = [prefix + lines[0]]
        rendered.extend(indent_markdown("\n".join(lines[1:]), len(prefix)).splitlines())
        items.append("\n".join(rendered).rstrip())
    return "\n\n".join(items)


def render_block(node: Tag | NavigableString, ordered_index: int | None = None) -> str:
    """Render block-level HTML to Markdown."""
    if isinstance(node, NavigableString):
        return normalize_text(str(node))
    if not isinstance(node, Tag):
        return ""

    name = node.name.lower()
    if name in {"style", "script", "link"}:
        return ""
    if name in {"h1", "h2", "h3", "h4", "h5", "h6"}:
        level = int(name[1])
        return f"{'#' * level} {render_inline_children(node)}"
    if name == "p":
        return render_inline_children(node)
    if name == "pre":
        # Highlight spans split syntax tokens; no separator keeps copyable code intact.
        code = node.get_text("").strip("\n")
        return f"```\n{code}\n```"
    if name == "table":
        return render_table(node)
    if name in {"ul", "ol"}:
        return render_list(node, ordered=name == "ol")
    if name == "li":
        prefix = f"{ordered_index}. " if ordered_index else "- "
        return prefix + render_children(node)
    return render_children(node)


def render_children(root: Tag) -> str:
    """Render child nodes and separate non-empty blocks with blank lines."""
    blocks: list[str] = []
    for child in root.children:
        block = render_block(child)
        if block:
            blocks.append(block)
    return "\n\n".join(blocks)


def html_to_markdown(content: str) -> str:
    """Convert the API-provided HTML document body to Markdown."""
    soup = BeautifulSoup(content or "", "html.parser")
    root = soup.find(id="mark-down") or soup.body or soup
    return render_children(root).strip() + "\n"


def write_json(path: Path, payload: Any) -> None:
    """Write formatted UTF-8 JSON."""
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def extract_first(markdown: str, pattern: str) -> str:
    """Extract one line of generated Markdown for the quick API summary."""
    match = re.search(pattern, markdown, flags=re.MULTILINE)
    return match.group(1).strip() if match else ""


def main() -> int:
    parser = argparse.ArgumentParser(description="Fetch vivo AIGC contest documents.")
    parser.add_argument("--out", default="docs/api/vivo-aigc", help="Output directory.")
    parser.add_argument("--sleep", type=float, default=0.15, help="Delay between document requests.")
    args = parser.parse_args()

    out_dir = Path(args.out)
    raw_dir = out_dir / "raw"
    html_dir = out_dir / "html"
    md_dir = out_dir / "markdown"
    for directory in (raw_dir, html_dir, md_dir):
        directory.mkdir(parents=True, exist_ok=True)

    tree_payload = fetch_json(TREE_URL, {"businessCode": BUSINESS_CODE})
    tree = require_success(tree_payload, "doc tree")
    docs = flatten_docs(tree)
    write_json(raw_dir / "tree.json", tree_payload)

    index_rows: list[dict[str, Any]] = []
    markdown_docs: list[tuple[dict[str, Any], str]] = []
    for order, doc in enumerate(docs, start=1):
        doc_id = str(doc["id"])
        payload = fetch_json(DOC_URL, {"docId": doc_id, "businessCode": BUSINESS_CODE})
        title = str(doc["name"])
        path_text = " / ".join(doc["path"])
        file_stem = f"{order:02d}-{doc_id}-{slugify(path_text)}"

        payload = redact_payload(payload)
        data = require_success(payload, f"doc {doc_id}")
        content = data.get("content") or ""
        markdown_body = html_to_markdown(content)
        front_matter = [
            "---",
            f"doc_id: {doc_id}",
            f"title: {title}",
            f"path: {path_text}",
            f"source_url: {BASE_URL}/#/document/index?id={doc_id}",
            f"update_time: {timestamp_ms_to_local(data.get('updateTime'))}",
            "---",
            "",
        ]
        markdown = "\n".join(front_matter) + markdown_body

        write_json(raw_dir / f"{file_stem}.json", payload)
        (html_dir / f"{file_stem}.html").write_text(content, encoding="utf-8")
        (md_dir / f"{file_stem}.md").write_text(markdown, encoding="utf-8")
        markdown_docs.append((doc, markdown))

        index_rows.append(
            {
                "order": order,
                "doc_id": int(doc_id),
                "title": title,
                "path": path_text,
                "update_time": timestamp_ms_to_local(data.get("updateTime")),
                "markdown": str((md_dir / f"{file_stem}.md").as_posix()),
                "html": str((html_dir / f"{file_stem}.html").as_posix()),
                "source_url": f"{BASE_URL}/#/document/index?id={doc_id}",
            }
        )
        time.sleep(args.sleep)

    write_json(out_dir / "index.json", index_rows)

    combined_lines = [
        "# vivo AIGC API docs full export",
        "",
        f"- Source: {BASE_URL}/#/document/index?id=1746",
        f"- Fetched at: {dt.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
        f"- Documents: {len(index_rows)}",
        "",
    ]
    for row, (_, markdown) in zip(index_rows, markdown_docs):
        body = re.sub(r"^---\n.*?\n---\n", "", markdown, flags=re.DOTALL).strip()
        combined_lines.extend(
            [
                f"# {row['order']:02d}. {row['path']}",
                "",
                f"- Doc ID: {row['doc_id']}",
                f"- Updated: {row['update_time']}",
                f"- Source: {row['source_url']}",
                "",
                body,
                "",
            ]
        )
    (out_dir / "ALL_DOCS.md").write_text("\n".join(combined_lines).rstrip() + "\n", encoding="utf-8")

    summary_lines = [
        "# vivo AIGC API quick summary",
        "",
        "| Doc ID | API | Method | Address |",
        "| --- | --- | --- | --- |",
    ]
    for row, (_, markdown) in zip(index_rows, markdown_docs):
        address = extract_first(markdown, r"访问地址[:：]\s*(.+)")
        method = extract_first(markdown, r"请求方式[:：]\s*(.+)")
        if address or method:
            summary_lines.append(
                f"| {row['doc_id']} | {row['path']} | {method or '-'} | {address or '-'} |"
            )
    (out_dir / "API_SUMMARY.md").write_text("\n".join(summary_lines) + "\n", encoding="utf-8")

    index_md_lines = [
        "# vivo AIGC API docs",
        "",
        f"- Source: {BASE_URL}/#/document/index?id=1746",
        f"- Business code: `{BUSINESS_CODE}`",
        f"- Fetched at: {dt.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
        f"- Documents: {len(index_rows)}",
        "",
        "| # | Doc ID | Path | Updated | Markdown |",
        "| --- | --- | --- | --- | --- |",
    ]
    for row in index_rows:
        md_path = urllib.parse.quote(Path(row["markdown"]).name, safe="/:.")
        index_md_lines.append(
            f"| {row['order']} | {row['doc_id']} | {row['path']} | "
            f"{row['update_time']} | [{row['title']}](markdown/{md_path}) |"
        )
    (out_dir / "README.md").write_text("\n".join(index_md_lines) + "\n", encoding="utf-8")

    print(f"Fetched {len(index_rows)} docs into {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
