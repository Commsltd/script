import copy,json,unittest
from datetime import datetime, timezone
from unittest.mock import patch
import xml.etree.ElementTree as ET
import business_channels as business

class BusinessChannelsTest(unittest.TestCase):
    def test_expansion_preserves_old_routes_and_is_repeatable(self):
        spec=business.seed()[0]; old=business.expand([], [spec]);old[0]['sources'].insert(0,dict(old[0]['sources'][0],id='saved-backup',url='https://example.org/old.m3u8'))
        before=copy.deepcopy(old)
        rows=business.expand(old,[spec])
        self.assertEqual(before,old)
        self.assertEqual(rows,business.expand(rows,[spec]))
        self.assertEqual(rows[0]['sources'][0]['id'],'saved-backup')
        self.assertEqual(rows[0]['id'],spec['id'])

    def test_seed_is_native_english_and_business_only(self):
        from spanish_channels import public_hls
        specs=business.seed()
        self.assertEqual(len(specs),len({s['id'] for s in specs}))
        for spec in specs:
            self.assertEqual(spec['group'],business.GROUP)
            for route in spec['routes']:
                self.assertTrue(public_hls(route['url']) or route['url'].startswith('https://jmp2.uk/stvp-'),route['url'])
                self.assertEqual(bool(route['guideId']),bool(route['guideChannelId']))

    def test_refresh_accepts_only_exact_service_and_approved_host(self):
        spec=business.seed()[0]
        from urllib.parse import urlsplit
        host=urlsplit(spec['routes'][0]['url']).hostname
        good={'channel':spec['family'],'feed':spec['variant'],'url':'https://'+host+'/renewed.m3u8'}
        bad=[dict(good,url='https://unknown.example/live.m3u8'),dict(good,feed='OtherLanguage'),dict(good,url='https://user:password@'+host+'/live.m3u8')]
        old=business.expand([],[spec])
        rows=business.expand(old,[spec],[good]+bad)
        addresses={s['url'] for s in rows[0]['sources']}
        self.assertIn(good['url'],addresses)
        self.assertTrue({s['url'] for s in old[0]['sources']}<=addresses)
        self.assertNotIn(bad[0]['url'],addresses)
        self.assertNotIn(bad[2]['url'],addresses)

    def test_provider_outage_retains_seed_sources(self):
        with patch.object(business,'fetch',side_effect=OSError('offline')):
            self.assertEqual(business.seed(),business.refreshed_inventory())

    def test_schwab_current_show_is_real_and_timezone_aware(self):
        show={'name':'Market Review','description':'<p>Actual &amp; supplied description</p>','scheduleTime':{'startTime':'06:30 AM America/Chicago','endTime':'08:00 AM America/Chicago'}}
        content=('<script id="__NEXT_DATA__">'+json.dumps({'props':{'pageProps':{'context':{'currentShow':{'value':show}}}}})+'</script>').encode()
        root=ET.fromstring(business.schwab_xmltv(content,datetime(2026,10,25,12,0,tzinfo=timezone.utc)))
        programme=root.find('programme');self.assertIsNotNone(programme)
        self.assertEqual('20261025063000 -0500',programme.get('start'))
        self.assertEqual('Actual & supplied description',programme.findtext('desc'))
        expired=ET.fromstring(business.schwab_xmltv(content,datetime(2026,10,25,15,0,tzinfo=timezone.utc)))
        self.assertEqual([],expired.findall('programme'))

    def test_wrong_edition_schedule_is_not_copied(self):
        from export_manifest import stamp
        specs=[s for s in business.seed() if s['family']=='CNBCTV18.in']
        data={'channels':business.expand([],specs),'programmes':[],'supplementalGuideSources':{}}
        wrong=b'<tv><programme channel="CNBC.Awaaz.in" start="20261025100000 +0000" stop="20261025110000 +0000"><title>Different service</title></programme></tv>'
        business.add_guides(data,{'news_in':wrong},stamp('20261025103000 +0000'))
        self.assertEqual([],data['programmes'])
