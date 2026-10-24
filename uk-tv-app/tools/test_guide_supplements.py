import unittest
from export_manifest import build
from guide_supplements import mappings, parse_xml

class GuideSupplementTests(unittest.TestCase):
    def guide(self, cid, title='Programme'):
        return f'<tv><programme channel="{cid}" start="20261025013000 +0000" stop="20261025020000 +0000"><title>{title}</title><desc>Synopsis</desc></programme></tv>'.encode()
    def test_exact_pluto_id_maps_despite_misleading_display_name(self):
        p='#EXTINF:-1 tvg-id="PlutoTVMovies.de@GB",Old name\nhttps://jmp2.uk/plu-5ad8d3a31b95267e225e4e09.m3u8\n'
        d=build(p,b'<tv/>',extra_guides={'pluto':self.guide('5ad8d3a31b95267e225e4e09')})
        self.assertEqual(d['programmes'][0]['channelId'],'PlutoTVMovies.de@GB')
        self.assertEqual(d['supplementalGuideSources']['pluto'],['PlutoTVMovies.de@GB'])
    def test_similar_pluto_title_with_different_id_does_not_match(self):
        p='#EXTINF:-1 tvg-id="PlutoTVMovies.de@GB",Pluto TV Movies\nhttps://jmp2.uk/plu-aaaaaaaaaaaaaaaaaaaaaaaa.m3u8\n'
        self.assertEqual(build(p,b'<tv/>',extra_guides={'pluto':self.guide('5ad8d3a31b95267e225e4e09')})['programmes'],[])
    def test_pluto_id_not_taken_from_unrelated_host_or_query(self):
        channels={'c':{'sources':[{'url':'https://example.com/plu-5ad8d3a31b95267e225e4e09.m3u8'},{'url':'https://jmp2.uk/video?plu-5ad8d3a31b95267e225e4e09.m3u8'}]}}
        self.assertEqual(mappings('pluto',channels),{})
    def test_combined_bbc_service_uses_combined_guide_only(self):
        p='#EXTINF:-1 tvg-id="BBCFourCBeebies.uk@HD",BBC Four/CBeebies\nhttps://s/bbc\n'
        self.assertEqual(build(p,b'<tv/>',extra_guides={'openepg':self.guide('BBCFour.uk')})['programmes'],[])
        d=build(p,b'<tv/>',extra_guides={'openepg':self.guide('BBCFourCBeebies.uk')})
        self.assertEqual(d['channelsWithGuide'],1)
    def test_plus_one_and_ambiguous_stv_do_not_inherit_main_schedule(self):
        p='#EXTINF:-1 tvg-id="TinyPop.uk@Plus1",Tiny Pop +1\nhttps://s/t\n#EXTINF:-1 tvg-id="ITV1.uk@STV",STV\nhttps://s/stv\n'
        self.assertEqual(build(p,b'<tv/>',extra_guides={'openepg':self.guide('STV.uk'),'samsung':self.guide('GBBD3200003T6')})['programmes'],[])
    def test_second_guide_does_not_overlap_first(self):
        p='#EXTINF:-1 tvg-id="MUTV.uk@SD",MUTV\nhttps://s/m\n'
        primary=self.guide('MUTV.uk@SD','Primary')
        d=build(p,primary,extra_guides={'openepg':self.guide('MUTV.uk','Other')})
        self.assertEqual([x['title'] for x in d['programmes']],['Primary'])
    def test_invalid_primary_programme_does_not_block_valid_supplement(self):
        p='#EXTINF:-1 tvg-id="MUTV.uk@SD",MUTV\nhttps://s/m\n'
        bad=self.guide('MUTV.uk@SD').replace(b'20261025020000',b'20261025000000')
        d=build(p,bad,extra_guides={'openepg':self.guide('MUTV.uk')})
        self.assertEqual(len(d['programmes']),1)
    def test_australian_fast_stream_is_not_given_uk_guide(self):
        p='#EXTINF:-1 tvg-id="GhostHunters.us@UK",Ghost Hunters\nhttps://amg00353-amg00353c30-samsung-au-3440.playouts.now.amagi.tv/playlist.m3u8\n'
        self.assertEqual(build(p,b'<tv/>',extra_guides={'samsung':self.guide('GB2000008XP')})['programmes'],[])
    def test_html_redirect_response_is_not_a_guide(self):
        with self.assertRaises(ValueError):parse_xml(b'<html><body>Download</body></html>')

class InternationalGuideTests(unittest.TestCase):
    def test_regional_fallback_uses_its_own_schedule_and_unmapped_region_stays_blank(self):
        from international_channels import expand
        from international_guides import add_guides
        data=build('',b'<tv/>')
        data['channels']=expand([])
        xml=b'<tv><programme channel="ABCSydney.au" start="20261022100000 +0000" stop="20261022110000 +0000"><title>Sydney show</title></programme><programme channel="ABCPerth.au" start="20261022100000 +0000" stop="20261022110000 +0000"><title>Perth show</title></programme></tv>'
        add_guides(data,{'au':xml})
        abc=next(c for c in data['channels'] if c['family']=='ABCTV.au')
        self.assertEqual(next(p for p in data['programmes'] if p['channelId']==abc['id'])['title'],'Sydney show')
        perth=next(s for s in abc['sources'] if s['region']=='WA')
        self.assertEqual(next(p for p in data['regionalProgrammes'] if p['channelId']==perth['guideChannelId'])['title'],'Perth show')
        self.assertNotIn('guideChannelId',next(s for s in abc['sources'] if s['region']=='NT'))
    def test_rakuten_uk_is_not_assigned_foreign_schedule(self):
        playlist='#EXTINF:-1 tvg-id="RakutenTVActionMovies.es@UK",Action\nhttps://example.com/action.m3u8\n'
        uk=GuideSupplementTests().guide('UK:.Action.Movies.-.Rakuten.TV.be')
        wrong=GuideSupplementTests().guide('DE:.Actionfilme.-.Rakuten.TV.be')
        self.assertEqual(build(playlist,b'<tv/>',extra_guides={'rakuten':uk})['channelsWithGuide'],1)
        self.assertEqual(build(playlist,b'<tv/>',extra_guides={'rakuten':wrong})['channelsWithGuide'],0)

class NewsGuideTests(unittest.TestCase):
    def fixture(self):
        from international_channels import add_english_news
        data=build('',b'<tv/>');data['channels']=add_english_news([])
        return data

    def test_news_import_uses_exact_language_and_utc_offsets(self):
        from international_guides import add_guides
        data=self.fixture()
        xml=b'<tv><programme channel="CNN.NEWS.18.in" start="20261025153000 +0530" stop="20261025160000 +0530"><title>Real bulletin</title><desc>Actual synopsis</desc></programme><programme channel="NDTV.India.in" start="20261025153000 +0530" stop="20261025160000 +0530"><title>Wrong Hindi service</title></programme></tv>'
        add_guides(data,{'news_in':xml},now_ms=0)
        self.assertEqual(len(data['programmes']),1)
        p=data['programmes'][0]
        self.assertEqual(p['channelId'],'CNNNews18.in@English')
        self.assertEqual(p['description'],'Actual synopsis')
        from export_manifest import stamp
        self.assertEqual(p['start'],stamp('20261025100000 +0000'))

    def test_expired_guide_cannot_block_fresh_exact_service_fallback(self):
        from international_guides import add_guides
        from export_manifest import stamp
        data=self.fixture()
        old=GuideSupplementTests().guide('NHK.World.HD.uk','Expired').replace(b'20261025',b'20261023')
        fresh=GuideSupplementTests().guide('NHK.World.–.Japan.(HD).sg','Current')
        add_guides(data,{'uk1':old,'news_sg':fresh},now_ms=stamp('20261024000000 +0000'))
        self.assertEqual([p['title'] for p in data['programmes']],['Current'])

    def test_primary_schedule_wins_and_missing_guide_is_not_fabricated(self):
        from international_guides import add_guides
        data=self.fixture()
        cid='CNNNews18.in@English'
        data['programmes']=[{'channelId':cid,'start':1000,'stop':2000,'title':'Primary','description':'Original'}]
        add_guides(data,{'news_in':GuideSupplementTests().guide('CNN.NEWS.18.in','Other')},now_ms=0)
        self.assertEqual([p['title'] for p in data['programmes']],['Primary'])
        self.assertFalse(any(p['channelId']=='CBNNews.us@English' for p in data['programmes']))

class BroadcasterGuideTests(unittest.TestCase):
    def test_cbn_uses_dated_times_offsets_and_filters_other_channels(self):
        from guide_supplements import cbn_xmltv
        import xml.etree.ElementTree as ET
        xml=b'<RiGHTvEPG xmlns="http://www.orca.tv/RiGHTv/7.0/EPG"><ChannelsList><channel id="newsChannel"><program time="20261101013000" duration="PT30M"><title>Invalid date</title></program><program time="2026-11-01T01:30:00-05:00" duration="PT00H30M"><title>Actual show</title><description>Actual description</description></program></channel><channel id="family"><program time="2026-11-01T01:30:00-05:00" duration="PT30M"><title>Wrong channel</title></program></channel></ChannelsList></RiGHTvEPG>'
        rows=ET.fromstring(cbn_xmltv(xml)).findall('programme')
        self.assertEqual(len(rows),1)
        self.assertEqual(rows[0].get('start'),'20261101063000 +0000')
        self.assertEqual(rows[0].get('stop'),'20261101070000 +0000')
        self.assertEqual(rows[0].findtext('desc'),'Actual description')

if __name__=='__main__':unittest.main()
