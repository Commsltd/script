import unittest
from international_channels import expand, seed, eligible, COUNTRIES

class InternationalTest(unittest.TestCase):
    def test_existing_ids_and_all_sources_are_untouched(self):
        old={'id':'ABCNewsLive.us@SD','name':'ABC News Live','sources':[{'id':'one'},{'id':'two'}],'group':'02 NEWS & POLITICS'}
        result=expand([old])
        self.assertEqual(result[0],old)
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
