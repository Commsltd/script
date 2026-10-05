#!/usr/bin/env python3
import gzip
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import mirror_artwork

class MirrorArtworkTests(unittest.TestCase):
    def test_key_is_stable_and_private(self):
        url = "https://images.example/logo.png?token=secret"
        self.assertEqual(mirror_artwork.key(url), mirror_artwork.key(url))
        self.assertNotIn("secret", mirror_artwork.key(url))

    def test_mirror_prefix_is_single_origin(self):
        self.assertTrue(mirror_artwork.MIRROR_PREFIX.startswith(
            "https://raw.githubusercontent.com/Commsltd/script/uk-tv-app-data/artwork/"
        ))

    def test_convert_rejects_non_image(self):
        with tempfile.TemporaryDirectory() as td:
            with mock.patch.object(mirror_artwork, "fetch", return_value=(b"<html>bad</html>", "text/html")):
                with self.assertRaises(ValueError):
                    mirror_artwork.convert("https://example.invalid/bad", "logo", Path(td))

if __name__ == "__main__":
    unittest.main()
