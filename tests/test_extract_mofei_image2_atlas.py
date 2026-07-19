import importlib.util
from pathlib import Path

from PIL import Image


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "extract_mofei_image2_atlas.py"
SPEC = importlib.util.spec_from_file_location("extract_mofei_image2_atlas", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def test_extract_cells_preserves_native_square_cell_size() -> None:
    atlas = Image.new("RGBA", (1536, 1024), "white")

    cells = MODULE.extract_cells(atlas, columns=6, rows=4)

    assert len(cells) == 24
    assert {cell.size for cell in cells} == {(256, 256)}
