#!/usr/bin/env python3
import gzip
import json
import tempfile
import zipfile
import unittest
from pathlib import Path
from unittest import mock

import mirror_artwork

class MirrorArtworkTests(unittest.TestCase):
    def test_bundle_excludes_stale_cache_and_is_deterministic(self):
        with tempfile.TemporaryDirectory() as td:
            directory = Path(td)
            (directory / "current.png").write_bytes(b"image")
            (directory / "stale.png").write_bytes(b"stale image")
            first, second = directory / "a.zip", directory / "b.zip"
            mirror_artwork.write_bundle(first, directory, ["artwork://current.png"])
            mirror_artwork.write_bundle(second, directory, ["artwork://current.png"])
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with zipfile.ZipFile(first) as archive:
                self.assertEqual(["current.png"], archive.namelist())

    def test_key_is_stable_and_private(self):
        url = "https://images.example/logo.png?token=secret"
        self.assertEqual(mirror_artwork.key(url), mirror_artwork.key(url))
        self.assertNotIn("secret", mirror_artwork.key(url))

    def test_mirror_prefix_is_local_bundle_reference(self):
        self.assertEqual("artwork://", mirror_artwork.MIRROR_PREFIX)

    def test_convert_rejects_active_svg(self):
        with tempfile.TemporaryDirectory() as td:
            bad = b'<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>'
            with mock.patch.object(mirror_artwork, "fetch", return_value=(bad, "image/svg+xml")):
                with self.assertRaises(ValueError):
                    mirror_artwork.convert("https://example.invalid/bad.svg", "logo", Path(td))

    def test_convert_rejects_non_image(self):
        with tempfile.TemporaryDirectory() as td:
            with mock.patch.object(mirror_artwork, "fetch", return_value=(b"<html>bad</html>", "text/html")):
                with self.assertRaises(ValueError):
                    mirror_artwork.convert("https://example.invalid/bad", "logo", Path(td))

if __name__ == "__main__":
    unittest.main()
