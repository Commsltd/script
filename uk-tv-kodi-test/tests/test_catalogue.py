import json
import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'service.uktelevision.crewtest'))
from catalogue import CrewCatalogue, PREFIX

class CatalogueTests(unittest.TestCase):
    def test_real_directory_request_and_next_page_handle(self):
        calls=[]
        next_url=PREFIX+'?action=movies&url=https%3A%2F%2Fexample.invalid%2Fpage2%3Fapi_key%3Dprivate'
        def rpc(raw):
            calls.append(json.loads(raw))
            return json.dumps({'result':{'files':[{'file':next_url,'label':'[B]Next page[/B]','filetype':'directory'}]}})
        reader=CrewCatalogue(rpc)
        page=reader.directory({'section':'movie'})
        self.assertEqual('Files.GetDirectory',calls[0]['method'])
        self.assertEqual(PREFIX+'?action=movieNavigator',calls[0]['params']['directory'])
        self.assertEqual('Next page',page['folders'][0]['label'])
        self.assertNotIn('api_key',json.dumps(page))
        reader.directory({'folder':page['folders'][0]['id']})
        self.assertEqual(next_url,calls[1]['params']['directory'])

    def test_actual_movie_and_tv_page_routes_are_retained(self):
        reader=CrewCatalogue(None)
        page=reader.parse([{'file':PREFIX+'?action='+action+'&url=opaque','filetype':'directory','label':'Next Page'} for action in ('moviePage','tvshowPage')],'tv')
        self.assertEqual(2,len(page['folders']))
        self.assertTrue(all(f['nextPage'] for f in page['folders']))
        self.assertNotIn('url',json.dumps(page))

    def test_search_can_use_installed_indexers_catalogue_route(self):
        calls=[]
        reader=CrewCatalogue(lambda raw:(calls.append(json.loads(raw)) or '{"result":{"files":[]}}'),lambda section,query:PREFIX+'?action=tvshows&url=private-indexer-search')
        reader.directory({'section':'tv','query':'Porridge'})
        self.assertIn('action=tvshows',calls[0]['params']['directory'])

    def test_titles_are_actual_crew_results_with_canonical_identity(self):
        reader=CrewCatalogue(None)
        page=reader.parse([
            {'file':PREFIX+'?action=play&title=Example&year=1994&tmdb=278','label':'[COLOR red]Example[/COLOR]', 'filetype':'file','genre':['Drama'],'thumbnail':'image://https%3A%2F%2Fimage.tmdb.org%2Fposter.jpg/'},
            {'file':PREFIX+'?action=seasons&tvshowtitle=A+Series&tmdb=1396&year=2008','filetype':'directory'},
            {'file':PREFIX+'?action=play&tmdb=1396&tvshowtitle=A+Series&season=1&episode=1','filetype':'file'},
        ],'movie')
        self.assertEqual(['crew://movie/278','crew://tv/1396'],[t['url'] for t in page['titles']])
        self.assertEqual(['movie','show'],[t['kind'] for t in page['titles']])
        self.assertEqual('A Series',page['titles'][1]['title'])
        self.assertEqual('https://image.tmdb.org/poster.jpg',page['titles'][0]['poster'])
        self.assertEqual(1,page['skipped'])

    def test_only_returned_catalogue_directories_can_be_opened(self):
        reader=CrewCatalogue(lambda _:self.fail('No RPC expected'))
        for body in ({'folder':PREFIX+'?action=play'},{'folder':'0'*32},{'section':'tools'}):
            with self.assertRaises(ValueError):reader.directory(body)
        page=reader.parse([
            {'file':PREFIX+'?action=openSettings','filetype':'directory','label':'Settings'},
            {'file':'plugin://some.other.addon/?action=movies','filetype':'directory','label':'Other'},
            {'file':'https://example.invalid/?action=movies','filetype':'directory','label':'Web'},
        ],'movie')
        self.assertEqual([],page['folders'])
        self.assertEqual(3,page['skipped'])

    def test_native_search_sends_encoded_query_to_the_crews_search(self):
        calls=[]
        reader=CrewCatalogue(lambda raw:(calls.append(json.loads(raw)) or '{"result":{"files":[]}}'))
        reader.directory({'section':'tv','query':'A & B'})
        self.assertIn('action=tvSearchterm&name=A+%26+B',calls[0]['params']['directory'])
        with self.assertRaises(ValueError):reader.directory({'query':'x'})

    def test_rpc_errors_and_truncation_are_reported_not_silent_empty_catalogues(self):
        for payload in ({'error':{'message':'Failed'}},{'result':{'files':[],'limits':{'total':501}}}):
            with self.assertRaises(RuntimeError):CrewCatalogue(lambda _:json.dumps(payload)).directory({})

if __name__=='__main__':unittest.main()
