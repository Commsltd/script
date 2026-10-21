import unittest
import copy
from channel_groups import arrange_channels
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

    def test_sky_keeps_live_page_instead_of_stale_video_without_resolution(self):
        p = '#EXTM3U\n#EXTINF:-1 tvg-id="SkyNews.uk@HD",Sky News\nhttps://stream.example/sky.m3u8\n'
        d = build(p, b'<tv/>')
        youtube = [s for s in d['channels'][0]['sources'] if s['kind'] == 'youtube']
        self.assertEqual(len(youtube), 0)  # 0.3 must never receive an unplayable /live source.
        self.assertEqual(d['channels'][0]['youtubeLivePage'], 'https://www.youtube.com/@SkyNews/live')

    def test_drm_not_silently_treated_as_unprotected(self):
        p = '#EXTINF:-1 tvg-id="A.uk",A\n#KODIPROP:inputstream.adaptive.license_type=widevine\nhttps://s/a.mpd\n'
        self.assertTrue(build(p,b'<tv/>')['channels'][0]['sources'][0]['unsupportedDrm'])

class SupplementTests(unittest.TestCase):
    def test_supplement_fills_missing_exact_service_only(self):
        p = '#EXTINF:-1 tvg-id="CNBCUK.uk@SD",CNBC UK\nhttps://s/c\n#EXTINF:-1 tvg-id="CNBCUK.uk@Plus1",CNBC +1\nhttps://s/p\n'
        x = b'<tv><programme channel="CNBC.HD.uk" start="20261021090000 +0000" stop="20261021100000 +0000"><title>News</title><desc>Business news</desc></programme></tv>'
        d = build(p,b'<tv/>',supplemental_xml=x)
        self.assertEqual([p['channelId'] for p in d['programmes']],['CNBCUK.uk@SD'])
        self.assertEqual(d['channelsWithGuide'],1)
        self.assertEqual(d['supplementedChannels'],['CNBCUK.uk@SD'])
    def test_supplement_never_overwrites_existing_schedule(self):
        p = '#EXTINF:-1 tvg-id="CNBCUK.uk@SD",CNBC UK\nhttps://s/c\n'
        x = b'<tv><programme channel="CNBCUK.uk@SD" start="20261021090000 +0000" stop="20261021100000 +0000"><title>Original</title></programme></tv>'
        d = build(p,x,supplemental_xml=x.replace(b'CNBCUK.uk@SD',b'CNBC.HD.uk'))
        self.assertEqual(len(d['programmes']),1)
        self.assertEqual(d['supplementedChannels'],[])
    def test_supplement_rejects_entities(self):
        with self.assertRaises(ValueError):
            build('',b'<tv/>',supplemental_xml=b'<!DOCTYPE tv [<!ENTITY x "hello">]><tv/>')

class ChannelGroupTests(unittest.TestCase):
    def channel(self, cid, group='95 OTHER / ODDITIES', name='Example'):
        return {'id': cid, 'group': group, 'name': name, 'order': 100,
                'epgId': cid, 'family': cid.partition('@')[0],
                'sources': [{'id': 'keep-me', 'url': 'https://example.org/live.m3u8'}]}

    def test_known_misfiled_channels_move_by_genre(self):
        rows = arrange_channels([
            self.channel('Now70s.uk@SD'), self.channel('MUTV.uk@SD', '07 LOCAL & REGIONAL'),
            self.channel('BBCParliament.uk@SD'), self.channel('GemsTV.uk@SD', '07 LOCAL & REGIONAL'),
        ])
        groups = {c['id']: c['group'] for c in rows}
        self.assertEqual(groups['Now70s.uk@SD'], '11 MUSIC')
        self.assertEqual(groups['MUTV.uk@SD'], '10 SPORT')
        self.assertEqual(groups['BBCParliament.uk@SD'], '02 NEWS & POLITICS')
        self.assertEqual(groups['GemsTV.uk@SD'], '15 SHOPPING')

    def test_scripted_crime_and_adult_animation_are_not_true_crime_or_kids(self):
        rows = arrange_channels([self.channel(cid, '08 INTERNATIONAL') for cid in
            ('CSICrimeSceneInvestigation.de@GB', 'SouthPark.de@GB', 'DeadlyWomen.us@UK', 'Avatar.de@GB')])
        groups = {c['id']: c['group'] for c in rows}
        self.assertEqual(groups['CSICrimeSceneInvestigation.de@GB'], '04 DRAMA & COMEDY')
        self.assertEqual(groups['SouthPark.de@GB'], '04 DRAMA & COMEDY')
        self.assertEqual(groups['DeadlyWomen.us@UK'], '07 CRIME & MYSTERY')
        self.assertEqual(groups['Avatar.de@GB'], '09 KIDS')

    def test_regional_and_main_identities_survive_reordering(self):
        rows = [self.channel(cid, '01 MAIN UK') for cid in
                ('BBCOne.uk@Wales', 'BBCOne.uk@LondonHD', 'BBCTwo.uk@HD', 'STV.uk@Unspecified')]
        before = {c['id']: copy.deepcopy(c) for c in rows}
        after = arrange_channels(rows)
        self.assertEqual([c['id'] for c in after[:2]], ['BBCOne.uk@LondonHD', 'BBCTwo.uk@HD'])
        for c in after:
            self.assertEqual(c['group'], '01 MAIN UK' if c['id'] in
                {'BBCOne.uk@LondonHD', 'BBCTwo.uk@HD'} else '12 REGIONAL & LOCAL')
            self.assertEqual({k: v for k, v in c.items() if k not in {'group', 'order'}},
                             {k: v for k, v in before[c['id']].items() if k not in {'group', 'order'}})

    def test_intermittent_broadcasts_are_not_a_reliability_category(self):
        row = self.channel('V2BEATTV.uk@SD', '99 EXPERIMENTAL / UNRELIABLE', 'V2BEAT TV [Not 24/7]')
        self.assertEqual(arrange_channels([row])[0]['group'], '11 MUSIC')

    def test_events_sort_numerically_and_arrangement_is_idempotent(self):
        rows = arrange_channels([self.channel(f'BBCRedButton{n}.uk@SD') for n in (10, 2, 1)])
        self.assertEqual([c['id'] for c in rows], [f'BBCRedButton{n}.uk@SD' for n in (1, 2, 10)])
        expected = copy.deepcopy(rows)
        self.assertEqual(arrange_channels(list(reversed(rows))), expected)
        self.assertEqual([c['order'] for c in rows], [0, 1, 2])

    def test_unknown_channels_are_retained_without_guessing_from_name_or_country(self):
        rows = arrange_channels([self.channel('Unknown.us@UK', name='News about film crimes'),
                                 self.channel('NewMusic.uk@HD', group='05 MUSIC')])
        self.assertEqual({c['id']: c['group'] for c in rows},
                         {'NewMusic.uk@HD': '11 MUSIC', 'Unknown.us@UK': '99 MORE CHANNELS'})
        self.assertEqual(arrange_channels(copy.deepcopy(rows)), rows)

    def test_export_preserves_distinct_regions_plus_one_streams_and_schedules(self):
        ids = ['BBCOne.uk@Wales', 'BBCOne.uk@LondonHD', 'TinyPop.uk@SD', 'TinyPop.uk@Plus1']
        playlist = '\n'.join(f'#EXTINF:-1 tvg-id="{cid}",Name {i}\nhttps://example.org/{i}.m3u8'
                             for i, cid in enumerate(ids))
        guide = ('<tv>' + ''.join(f'<programme channel="{cid}" start="20261021090000 +0000" '
            f'stop="20261021100000 +0000"><title>Schedule {i}</title></programme>'
            for i, cid in enumerate(ids)) + '</tv>').encode()
        data = build(playlist, guide)
        self.assertEqual({c['id'] for c in data['channels']}, set(ids))
        self.assertEqual({p['channelId']: p['title'] for p in data['programmes']},
                         {cid: f'Schedule {i}' for i, cid in enumerate(ids)})
        for c in data['channels']:
            self.assertEqual(c['sources'][0]['url'], f'https://example.org/{ids.index(c["id"])}.m3u8')

if __name__ == '__main__':
    unittest.main()
