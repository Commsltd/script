import copy
import gzip
import json
import tempfile
import unittest
from pathlib import Path
from live_source_feed import build, write


class LiveSourceFeedTest(unittest.TestCase):
    def catalogue(self):
        return {'builtAt': 1000, 'sourceRevision': 'test', 'channels': [
            {'id': 'BBCOne.uk@National', 'sources': [
                {'id': str(i), 'url': f'https://cdn.example/{i}.m3u8', 'region': str(i)} for i in range(15)]},
            {'id': 'BBCTwo.uk@National', 'sources': [
                {'id': 'two', 'url': 'https://cdn.example/two.m3u8'}]}]}

    def test_every_alternative_and_identity_is_preserved_without_mutation(self):
        original = self.catalogue()
        before = copy.deepcopy(original)
        result = build(original)
        self.assertEqual(original, before)
        self.assertEqual(result['channels'], original['channels'])
        self.assertEqual(len(result['channels'][0]['sources']), 15)

    def test_web_youtube_drm_and_account_feeds_are_not_native_alternatives(self):
        catalogue = self.catalogue()
        for extra in ({'kind': 'web'}, {'kind': 'youtube'}, {'unsupportedDrm': True},
                      {'url': 'https://user:password@example.org/live.m3u8'}, {'url': 'file:///tmp/private'}):
            catalogue['channels'][0]['sources'].append({'id': 'unsafe', 'url': 'https://example.org/player', **extra})
        catalogue['channels'].append({'id': 'user.private', 'sources': [{'id': 'private', 'url': 'https://example.org/a.m3u8'}]})
        result = build(catalogue)
        self.assertEqual(len(result['channels']), 2)
        self.assertEqual(len(result['channels'][0]['sources']), 15)

    def test_written_inventory_round_trips(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'live-sources.json.gz'
            write(self.catalogue(), path)
            self.assertEqual(json.loads(gzip.decompress(path.read_bytes())), build(self.catalogue()))
