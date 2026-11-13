import unittest
from unittest.mock import patch
import personal_feed_snapshot as feeds
class PersonalFeedSnapshotTest(unittest.TestCase):
    def test_failure_keeps_observation_and_fetch_times(self):
        prior={'fx':{'source':'ECB','status':'ok','date':'2026-11-05','fetchedAt':'2026-11-05T15:00:00Z','rates':{'GBP':.8}}}
        with patch.object(feeds,'SOURCES',{'fx':('ECB','https://example.com')}),patch.object(feeds,'fetch',return_value=('fx',{'source':'ECB','status':'unavailable'})):
            result=feeds.collect(prior)
        self.assertEqual('stale',result['fx']['status'])
        self.assertEqual(prior['fx']['fetchedAt'],result['fx']['fetchedAt'])
        self.assertEqual('ok',prior['fx']['status'])
    def test_first_failure_is_unavailable_not_zero(self):
        with patch.object(feeds,'SOURCES',{'fx':('ECB','https://example.com')}),patch.object(feeds,'fetch',return_value=('fx',{'source':'ECB','status':'unavailable'})):
            result=feeds.collect()
        self.assertNotIn('rates',result['fx'])
        self.assertEqual('unavailable',result['fx']['status'])
