import unittest
from stream_sources import augment, sky_urls, valid

class StreamsTest(unittest.TestCase):
    def channel(self,id='Example.uk@UK'):
        return {'id':id,'name':'Example','sources':[{'id':'old','url':'https://old.example/live.m3u8','headers':{}}]}
    def row(self,feed='UK',url='https://public.example/live.m3u8'):
        return {'channel':'Example.uk','feed':feed,'url':url}
    def test_exact_identity_never_merges_regions_or_plus_one(self):
        rows=[self.row('UK'),self.row('US','https://us.example/live.m3u8'),self.row('UKPlus1','https://plus.example/live.m3u8')]
        result=augment([self.channel()],rows)
        self.assertEqual(len(result[0]['sources']),2)
        self.assertEqual(result[0]['sources'][0]['id'],'old')
    def test_no_three_source_cap_and_no_duplicate_on_refresh(self):
        rows=[self.row(url=f'https://public.example/{i}.m3u8') for i in range(10)]
        first=augment([self.channel()],rows)
        self.assertEqual(len(first[0]['sources']),11)
        self.assertEqual(augment(first,rows),first)
    def test_headers_are_preserved_and_credentials_are_rejected(self):
        row=self.row();row.update(user_agent='provider-agent',referrer='https://provider.example/')
        source=augment([self.channel()],[row])[0]['sources'][-1]
        self.assertEqual(source['headers'],{'User-Agent':'provider-agent','Referer':'https://provider.example/'})
        self.assertFalse(valid(self.row(url='https://user:secret@example.com/live.m3u8')))
        row['user_agent']='bad\nheader';self.assertFalse(valid(row))
    def test_sky_keeps_ad_stream_first_existing_backups_and_no_duplicate(self):
        sky=[{'kind':'dynamic','url':'https://a.delivery.skycdp.com/ads/master.m3u8'}, {'kind':'fallback','url':'https://b.delivery.skycdp.com/backup/master.m3u8'}]
        first=augment([self.channel('SkyNews.uk@HD')],sky=sky)
        self.assertEqual([s['priority'] for s in first[0]['sources']],[0,1,2])
        self.assertEqual(first[0]['sources'][-1]['id'],'old')
        self.assertEqual(augment(first,sky=sky),first)
    def test_partial_or_untrusted_sky_page_is_not_accepted(self):
        partial='const fallbackStreamUrl = "https://a.delivery.skycdp.com/live.m3u8";'
        self.assertEqual(sky_urls(partial),[])
        self.assertEqual(sky_urls('const dynamicStreamUrl = "https://bad.example/live.m3u8";'+partial),[])
        full='const dynamicStreamUrl = "https://a.delivery.skycdp.com/ads/live.m3u8";'+partial
        self.assertEqual(len(sky_urls(full)),2)
