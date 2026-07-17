import unittest

from api.services.stock_quotes import fast_info_get


class StockQuoteServiceTests(unittest.TestCase):
    def test_fast_info_get_reads_mapping_and_index_only_info(self):
        self.assertEqual(fast_info_get({"last_price": 123.4}, "last_price"), 123.4)

        class IndexOnlyInfo:
            def __getitem__(self, key):
                if key == "previous_close":
                    return 120.0
                raise KeyError(key)

        self.assertEqual(fast_info_get(IndexOnlyInfo(), "previous_close"), 120.0)

    def test_fast_info_get_returns_none_for_missing_or_broken_info(self):
        class BrokenInfo:
            def __getitem__(self, key):
                raise RuntimeError("not loaded")

        self.assertIsNone(fast_info_get({}, "missing"))
        self.assertIsNone(fast_info_get(BrokenInfo(), "last_price"))
        self.assertIsNone(fast_info_get(None, "last_price"))


if __name__ == "__main__":
    unittest.main()
