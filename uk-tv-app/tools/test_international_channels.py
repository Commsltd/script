import unittest
from international_channels import expand, seed, eligible, COUNTRIES

class InternationalTest(unittest.TestCase):
    def test_existing_ids_and_all_sources_are_untouched(self):
        old={'id':'ABCNewsLive.us@SD','name':'ABC News Live','sources':[{'id':'one'},{'id':'two'}],'group':'02 NEWS & POLITICS'}
        result=expand([old])
        self.assertEqual(result[0],dict(old,name='ABC News Live (USA)'))
        self.assertEqual(len([c for c in result if c['id'].startswith('ABCNewsLive.us@')]),1)
        self.assertEqual(expand(result),result)
    def test_regional_alternatives_are_all_retained_under_one_national_entry(self):
        rows=expand([])
        for cid,count in [('ABCTV.au',8),('CBCTelevision.ca',14),('Channel9.au',4)]:
            c=next(c for c in rows if c['family']==cid)
            self.assertGreaterEqual(len(c['sources']),count)
            self.assertEqual(c['epgId'],'')
        abc=next(c for c in rows if c['family']=='ABCTV.au')
        self.assertEqual(abc['sources'][0]['region'],'NSW')
    def test_all_priority_countries_have_services_and_broadcaster_players_stay_inside(self):
        rows=expand([])
        for group,_ in COUNTRIES.values():self.assertTrue(any(c['group']==group for c in rows),group)
        for cid in ['TTT.tt','CCNTV6.tt']:
            c=next(c for c in rows if c['family']==cid)
            self.assertEqual(c['sources'][0]['kind'],'web')
    def test_raw_ip_shared_accounts_and_plus_one_are_not_imported(self):
        base={'channel':'TG4.ie','feed':'SD'}
        for url in ['http://1.2.3.4/live/u/p/1.m3u8','https://private.example/live/user/password/1.m3u8','https://user:password@cdn.pbs.org/a.m3u8']:
            self.assertFalse(eligible(dict(base,url=url)))
        self.assertFalse(eligible(dict(base,feed='Plus1',url='https://cdn.pbs.org/a.m3u8')))
        self.assertTrue(all(eligible(r) for r in seed()['streams']))

    def test_rt_english_is_one_news_channel_with_native_alternatives(self):
        from international_channels import add_rt_english
        rows = add_rt_english([])
        self.assertEqual(len(rows), 1)
        channel = rows[0]
        self.assertEqual(channel['name'], 'RT English')
        self.assertEqual(channel['group'], '02 NEWS & POLITICS')
        self.assertEqual(len(channel['sources']), 3)
        self.assertTrue(all(s['kind'] == 'direct' for s in channel['sources']))
        self.assertEqual(add_rt_english(rows), rows)
        self.assertEqual(channel['epgId'], '')

    def test_rt_refresh_keeps_backups_and_rejects_other_languages(self):
        from international_channels import add_rt_english
        rows = add_rt_english([])
        rows[0]['sources'].append({'id':'saved', 'url':'https://example.org/alternative.m3u8'})
        extra = [
            {'channel':'RT.ru', 'feed':'SD', 'url':'https://rt-glb.rttv.com/live/rtnews/new.m3u8'},
            {'channel':'RT.ru', 'feed':'SD', 'url':'https://rt-glb.rttv.com/live/rtesp/playlist.m3u8'},
            {'channel':'RTArabic.ru', 'feed':'SD', 'url':'https://rt-glb.rttv.com/live/rtnews/other.m3u8'}]
        updated = add_rt_english(rows, extra)[0]
        self.assertEqual(len(updated['sources']), 5)
        self.assertTrue(any(s['id']=='saved' for s in updated['sources']))

    def test_cgtn_english_preserves_news_group_and_excludes_documentary(self):
        from international_channels import add_cgtn_english
        extra = [{'channel':'CGTNDocumentary.cn', 'feed':'SD', 'url':'https://english-livebkali.cgtn.com/live/doccgtn.m3u8'}]
        rows = add_cgtn_english([], extra)
        self.assertEqual(rows[0]['name'], 'CGTN English')
        self.assertEqual(rows[0]['group'], '02 NEWS & POLITICS')
        self.assertEqual(len(rows[0]['sources']), 3)
        self.assertEqual(add_cgtn_english(rows, extra), rows)

    def test_english_news_preserves_identity_epg_backups_and_input(self):
        import copy
        from international_channels import add_english_news
        old={'id':'DWEnglish.de@SD','name':'DW English','epgId':'guide-id','group':'02 NEWS & POLITICS',
             'sources':[{'id':'saved','url':'https://example.org/backup.m3u8'}]}
        before=copy.deepcopy(old)
        result=add_english_news([old])
        self.assertEqual(old,before)
        dw=[c for c in result if c['name']=='DW English']
        self.assertEqual(len(dw),1)
        self.assertEqual(dw[0]['id'],old['id'])
        self.assertEqual(dw[0]['epgId'],'guide-id')
        self.assertIn(old['sources'][0],dw[0]['sources'])
        self.assertEqual(add_english_news(result),result)

    def test_only_verified_english_routes_are_added(self):
        from international_channels import add_english_news, ENGLISH_NEWS
        url=ENGLISH_NEWS['France24.fr']['urls'][0]
        rows=add_english_news([], [
            {'channel':'France24.fr','feed':'French','url':url+'?wrong=language'},
            {'channel':'France24.fr','feed':'English','url':'https://untrusted.example/live.m3u8'},
            {'channel':'France24.fr','feed':'English','url':url+'?refreshed=1'}])
        france=next(c for c in rows if c['family']=='France24.fr')
        urls=[s['url'] for s in france['sources']]
        self.assertIn(url+'?refreshed=1',urls)
        self.assertNotIn(url+'?wrong=language',urls)
        self.assertFalse(any('untrusted.example' in u for u in urls))
        self.assertEqual(len(rows),len(ENGLISH_NEWS))
        self.assertTrue(all(c['variant']==ENGLISH_NEWS[c['family']].get('variant','English') and c['epgId']=='' for c in rows))
        self.assertTrue(all(s['kind']=='direct' for c in rows for s in c['sources']))

    def test_news_country_labels_are_idempotent_and_keep_playback_identity(self):
        from international_channels import label_news_countries
        rows=[{'id':'WION.in@English','name':'WION','group':'02 NEWS & POLITICS','sources':[{'id':'keep'}]},
              {'id':'EuronewsEnglish.fr@SD','name':'Euronews English','group':'02 NEWS & POLITICS'},
              {'id':'AfricanewsEnglish.fr@English','name':'Africanews English','group':'02 NEWS & POLITICS'},
              {'id':'CBCNewsNetwork.ca@National','name':'CBC News Network','group':'20 CANADA'},
              {'id':'CBCTelevision.ca@National','name':'CBC Television','group':'20 CANADA'}]
        result=label_news_countries(rows)
        self.assertEqual([c['name'] for c in result],['WION (India)','Euronews English (Europe)',
            'Africanews English (Pan-African)','CBC News Network (Canada)','CBC Television'])
        self.assertEqual(label_news_countries(result),result)
        self.assertEqual(result[0]['sources'],rows[0]['sources'])
        self.assertEqual(rows[0]['name'],'WION')
