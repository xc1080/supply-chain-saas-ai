"""This write-capable probe must refuse the existing demo and remote hosts."""
import unittest
from closing_contract_probe import isolated_url


class ClosingContractTargetTests(unittest.TestCase):
    def test_only_an_isolated_local_application_is_accepted(self):
        self.assertEqual(isolated_url('http://127.0.0.1:19035/'), 'http://127.0.0.1:19035')
        for url in ('', 'http://127.0.0.1:8035', 'http://127.0.0.1:6001',
                    'http://localhost:19035', 'https://127.0.0.1:19035',
                    'http://example.com:19035', 'http://user:secret@127.0.0.1:19035',
                    'http://127.0.0.1:19035/private', 'http://127.0.0.1:19035?x=1'):
            with self.subTest(url=url), self.assertRaises(ValueError):
                isolated_url(url)
