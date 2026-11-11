import copy
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
loader = importlib.util.spec_from_file_location("pilot", ROOT / "scripts/evaluate-campsite-pilot.py")
pilot = importlib.util.module_from_spec(loader)
loader.loader.exec_module(pilot)


class PilotTests(unittest.TestCase):
    def setUp(self):
        self.spec = pilot.load_spec(ROOT / "data/evaluation/campsite-pilot-v1.json")
        self.results, self.reviews = [], []
        for query in pilot.queries(self.spec):
            candidate = {"rank": 1, "id": query["id"], "detail": {"status": 200,
                         "body": {"id": query["id"], "city": query["city"] + "市"}}}
            self.results.append({**query, "search": {"status": 200, "elapsed_ms": 100}, "candidates": [candidate]})
            self.reviews.append({"query_id": query["id"], "rank": 1, "poi_id": query["id"],
                "decision": "valid", "reason": "Fixture identity and location agree", "evidence_url": "https://example.org/evidence"})

    def test_complete_baseline_passes(self):
        self.assertTrue(pilot.score(self.spec, self.results, self.reviews)["passed"])

    def test_timeouts_and_empty_stay_in_denominator(self):
        for index in range(5):
            self.results[index]["search"] = {"status": 504, "elapsed_ms": 50000}
            self.results[index]["candidates"] = []
        summary = pilot.score(self.spec, self.results, self.reviews[5:])
        self.assertEqual((summary["named_hits"], summary["named_total"], summary["search_errors"]), (25, 30, 5))
        self.assertEqual(summary["search_p95_ms"], 50000)
        self.assertFalse(summary["passed"])

    def test_pending_duplicate_and_missing_reviews_rejected(self):
        for rows in (self.reviews[:-1], self.reviews + [self.reviews[0]],
                     [{**r, "decision": "pending"} for r in self.reviews]):
            with self.assertRaises(ValueError):
                pilot.score(self.spec, self.results, rows)

    def test_uncertain_generic_is_not_removed(self):
        self.reviews[-1]["decision"] = "uncertain"
        summary = pilot.score(self.spec, self.results, self.reviews)
        self.assertEqual((summary["generic_valid"], summary["generic_total"]), (5, 6))
        self.assertFalse(summary["passed"])

    def test_wrong_city_or_detail_error_cannot_pass(self):
        for change in ({"status": 404}, {"body": {"id": "BJ01", "city": "上海市"}}):
            results = copy.deepcopy(self.results)
            results[0]["candidates"][0]["detail"].update(change)
            with self.assertRaises(ValueError):
                pilot.score(self.spec, results, self.reviews)

    def test_missing_or_replaced_query_rejected(self):
        with self.assertRaises(ValueError):
            pilot.score(self.spec, self.results[:-1], self.reviews)
        self.results[0]["keyword"] = "easy replacement"
        with self.assertRaises(ValueError):
            pilot.score(self.spec, self.results, self.reviews)

    def test_five_candidates_count_as_one_named_hit(self):
        result = self.results[0]
        for rank in range(2, 6):
            candidate = copy.deepcopy(result["candidates"][0])
            candidate.update(rank=rank, id=f"BJ01-{rank}")
            candidate["detail"]["body"]["id"] = candidate["id"]
            result["candidates"].append(candidate)
            self.reviews.append({**self.reviews[0], "rank": rank, "poi_id": candidate["id"]})
        self.assertEqual(pilot.score(self.spec, self.results, self.reviews)["named_hits"], 30)


if __name__ == "__main__":
    unittest.main()
