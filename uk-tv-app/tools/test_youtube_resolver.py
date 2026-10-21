#!/usr/bin/env python3
import json
import unittest
from unittest import mock
import resolve_youtube_live

class ResolverTests(unittest.TestCase):
    @mock.patch("resolve_youtube_live.subprocess.run")
    def test_accepts_current_live_video_only(self, run):
        run.return_value = mock.Mock(
            returncode=0,
            stdout=json.dumps({
                "id": "abcDEF12345",
                "is_live": True,
                "live_status": "is_live",
                "title": "Live",
                "channel": "Sky News",
            }),
        )
        item = resolve_youtube_live.resolve("https://www.youtube.com/@SkyNews/live")
        self.assertEqual("https://www.youtube.com/watch?v=abcDEF12345", item["url"])

    @mock.patch("resolve_youtube_live.subprocess.run")
    def test_rejects_non_live_video(self, run):
        run.return_value = mock.Mock(
            returncode=0,
            stdout=json.dumps({"id": "abcDEF12345", "is_live": False, "live_status": "was_live"}),
        )
        self.assertIsNone(resolve_youtube_live.resolve("https://example.invalid"))

    @mock.patch("resolve_youtube_live.from_live_page")
    @mock.patch("resolve_youtube_live.subprocess.run")
    def test_failed_extractor_uses_public_live_page(self, run, page):
        run.return_value = mock.Mock(returncode=1, stdout="", stderr="unavailable")
        page.return_value = {"videoId": "abcDEF12345"}
        self.assertEqual({"videoId": "abcDEF12345"}, resolve_youtube_live.resolve("https://www.youtube.com/@SkyNews/live"))
        page.assert_called_once()

if __name__ == "__main__":
    unittest.main()
