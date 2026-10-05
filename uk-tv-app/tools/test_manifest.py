import unittest
from export_manifest import build, parse_playlist, stamp

class ExportTests(unittest.TestCase):
    def test_quotes_and_headers(self):
        p = '#EXTM3U\n#EXTINF:-1 tvg-id="A.uk" tvg-logo="https://image/a,b",A\n#EXTVLCOPT:http-user-agent=Test\nhttps://stream/a.m3u8|Referer=https%3A%2F%2Fexample.org\n'
        rows = parse_playlist(p)
        self.assertEqual(rows[0]['name'], 'A')
        self.assertEqual(rows[0]['headers']['User-Agent'], 'Test')
        self.assertEqual(rows[0]['headers']['Referer'], 'https://example.org')
    def test_dst_offsets(self):
        self.assertEqual(stamp('20261005090000 +0100'), stamp('20261005080000 +0000'))
        self.assertEqual(stamp('20261005080000'), stamp('20261005080000 +0000'))
        self.assertIsNone(stamp('rubbish'))
    def test_description_and_distinct_regions(self):
        p = '#EXTM3U\n#EXTINF:-1 tvg-id="BBCOne.uk@London",BBC One London\nhttps://s/l.m3u8\n#EXTINF:-1 tvg-id="BBCOne.uk@Wales",BBC One Wales\nhttps://s/w.m3u8\n'
        x = b'<tv><programme channel="BBCOne.uk@London" start="20261005090000 +0100" stop="20261005100000 +0100"><title>A &amp; B</title><desc>Full synopsis.</desc><sub-title>Episode title</sub-title><episode-num system="xmltv_ns">1.2.</episode-num></programme></tv>'
        d = build(p, x)
        self.assertEqual(len(d['channels']), 2)
        self.assertEqual(len(d['programmes']), 1)
        self.assertEqual(d['programmes'][0]['description'], 'Full synopsis.')
        self.assertIn('Series 2 Episode 3', d['programmes'][0]['details'])
    def test_exact_duplicates_collapsed_not_plus_one(self):
        p = '#EXTM3U\n#EXTINF:-1 tvg-id="ITV1.uk@London",ITV1\nhttps://s/a\n#EXTINF:-1 tvg-id="ITV1.uk@London",ITV1 Alt\nhttps://s/b\n#EXTINF:-1 tvg-id="ITV1.uk@LondonPlus1",ITV1 +1\nhttps://s/c\n'
        d = build(p, b'<tv/>')
        self.assertEqual(len(d['channels']), 2)
        self.assertEqual(len(d['channels'][0]['sources']), 2)
    def test_stv_not_given_itv_epg(self):
        p = '#EXTM3U\n#EXTINF:-1 tvg-id="ITV1.uk@STV",STV\nhttps://s/a\n'
        d = build(p, b'<tv/>')
        self.assertEqual(d['channels'][0]['epgId'], '')
        self.assertEqual(d['channels'][0]['family'], 'ITV1.uk')
    def test_invalid_times_and_entities_rejected(self):
        with self.assertRaises(ValueError):
            build('', b'<!DOCTYPE tv [<!ENTITY x "hello">]><tv/>')
        self.assertEqual(build('', b'<tv/>')['programmes'], [])
    def test_stable_stream_identity(self):
        a = '#EXTINF:-1 tvg-id="A.uk",A\nhttps://s/a\n'
        b = '#EXTINF:-1 tvg-id="A.uk",A renamed\nhttps://s/a\n'
        self.assertEqual(build(a,b'<tv/>')['channels'][0]['sources'][0]['id'], build(b,b'<tv/>')['channels'][0]['sources'][0]['id'])
    def test_current_official_youtube_live_is_added_as_fixed_video(self):
        p = '#EXTM3U\n#EXTINF:-1 tvg-id="SkyNews.uk@HD",Sky News\nhttps://stream.example/sky.m3u8\n'
        live = {
            'SkyNews.uk': {
                'videoId': 'abcDEF12345',
                'url': 'https://www.youtube.com/watch?v=abcDEF12345',
            }
        }
        d = build(p, b'<tv/>', youtube_live=live)
        youtube = [s for s in d['channels'][0]['sources'] if s['kind'] == 'youtube']
        self.assertEqual(len(youtube), 1)
        self.assertEqual(youtube[0]['url'], 'https://www.youtube.com/watch?v=abcDEF12345')
        self.assertEqual(d['youtubeLiveSources'], 1)

    def test_broken_channel_live_embed_is_not_published(self):
        p = '#EXTM3U\n#EXTINF:-1 tvg-id="SkyNews.uk@HD",Sky News\nhttps://stream.example/sky.m3u8\n'
        d = build(p, b'<tv/>')
        self.assertFalse(any(s['kind'] == 'youtube' for s in d['channels'][0]['sources']))

    def test_drm_not_silently_treated_as_unprotected(self):
        p = '#EXTINF:-1 tvg-id="A.uk",A\n#KODIPROP:inputstream.adaptive.license_type=widevine\nhttps://s/a.mpd\n'
        self.assertTrue(build(p,b'<tv/>')['channels'][0]['sources'][0]['unsupportedDrm'])

if __name__ == '__main__':
    unittest.main()
