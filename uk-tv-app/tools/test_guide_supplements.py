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

if __name__=='__main__':unittest.main()
