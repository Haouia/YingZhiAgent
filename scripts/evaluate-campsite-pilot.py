"""Collect one frozen six-city baseline, then score explicit human reviews. No API keys logged."""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import time
from datetime import datetime, timezone
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen
from collections import Counter

CITIES = ["北京", "哈尔滨", "杭州", "广州", "成都", "乌鲁木齐"]
ROOT = Path(__file__).resolve().parents[1]
REVIEW_FIELDS = ["query_id", "rank", "poi_id", "name", "address", "decision", "reason", "evidence_url"]


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def load_spec(path):
    spec = read_json(path)
    cases = spec["cases"]
    if len(cases) != 30 or len({c["id"] for c in cases}) != 30:
        raise ValueError("Expected 30 unique named cases")
    if Counter(c["city"] for c in cases) != Counter({city: 5 for city in CITIES}):
        raise ValueError("Expected five cases in each of the six cities")
    for case in cases:
        if not all(case.get(k) for k in ("id", "name", "district", "address", "source")):
            raise ValueError("Case lacks independent identity evidence")
        if urlsplit(spec["sources"][case["source"]]["url"]).scheme != "https":
            raise ValueError("Invalid source URL")
    rules = spec["rules"]
    if (rules["named_min_hits"], rules["named_total"], rules["generic_min_precision"],
            rules["generic_required_cities"], rules["top_k"]) != (26, 30, 0.9, 6, 5):
        raise ValueError("Pilot v1 thresholds must not change")
    return spec


def queries(spec):
    return [{"id": c["id"], "kind": "named", "city": c["city"], "keyword": c["name"]}
            for c in spec["cases"]] + [
        {"id": f"GEN{i + 1:02}", "kind": "generic", "city": city, "keyword": spec["rules"]["keyword"]}
        for i, city in enumerate(CITIES)]


def request(base, path, token="", payload=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = Request(base + path, headers=headers,
                  data=None if payload is None else json.dumps(payload).encode("utf-8"))
    start = time.perf_counter()
    try:
        with urlopen(req, timeout=50) as response:
            status, body = response.status, json.load(response)
        return {"status": status, "elapsed_ms": round((time.perf_counter() - start) * 1000), "body": body}
    except HTTPError as error:
        return {"status": error.code, "elapsed_ms": round((time.perf_counter() - start) * 1000), "error": "HTTPError"}
    except (URLError, TimeoutError, OSError, ValueError) as error:
        return {"status": 0, "elapsed_ms": round((time.perf_counter() - start) * 1000), "error": type(error).__name__}


def collect(args):
    spec = load_spec(args.cases)
    if args.base_url != "http://127.0.0.1:18090":
        raise ValueError("This pilot sends demo credentials only to the local SSH tunnel on port 18090")
    out = Path(args.run)
    out.mkdir(parents=True, exist_ok=False)
    # Freeze before login/discovery; never resume into or overwrite an existing run.
    write_json(out / "freeze.json", {"frozen_at": datetime.now(timezone.utc).isoformat(),
               "cases_sha256": digest(args.cases), "script_sha256": digest(__file__), "queries": queries(spec),
               "release": args.release, "rules": spec["rules"]})
    password = next((line.split("=", 1)[1].strip().strip('"').strip("'")
                     for line in Path(args.environment).read_text(encoding="utf-8-sig").splitlines()
                     if line.startswith("DEMO_PASSWORD=")), "")
    if not password:
        raise ValueError("DEMO_PASSWORD is missing; do not send credentials to chat")
    auth = request(args.base_url, "/api/auth/login", payload={"username": "user2", "password": password})
    if auth["status"] != 200 or not auth.get("body", {}).get("token"):
        raise ValueError(f"Login failed, HTTP {auth['status']}; no token or password was saved")
    token = auth["body"]["token"]
    health = request(args.base_url, "/api/health")
    write_json(out / "health.json", health)
    reviews = []
    with (out / "results.jsonl").open("x", encoding="utf-8") as output:
        for query in queries(spec):
            result = {**query, "started_at": datetime.now(timezone.utc).isoformat()}
            search = request(args.base_url, "/api/discovery/campsites?" +
                             urlencode({"city": query["city"], "keyword": query["keyword"]}), token)
            result["search"] = {k: v for k, v in search.items() if k != "body"}
            body = search.get("body", {})
            places = body.get("places", [])
            if not isinstance(places, list) or len(places) > 5:
                raise ValueError("Search violated the top-five response contract")
            result["source"] = body.get("source")
            result["queried_at"] = body.get("queriedAt")
            result["candidates"] = []
            time.sleep(2.5)
            for rank, place in enumerate(places, 1):
                # Save only fields needed to adjudicate this private evaluation, not raw MCP payloads.
                candidate = {"rank": rank, **{k: place.get(k, "") for k in ("id", "name", "address")}}
                detail = request(args.base_url, "/api/discovery/campsites/" + candidate["id"] + "?" +
                                 urlencode({"searchId": body.get("searchId", "")}), token)
                candidate["detail"] = {k: v for k, v in detail.items() if k != "body"}
                if detail["status"] == 200:
                    candidate["detail"]["body"] = {k: detail["body"].get(k, "")
                        for k in ("id", "name", "city", "address", "category")}
                result["candidates"].append(candidate)
                reviews.append(dict(zip(REVIEW_FIELDS, [query["id"], rank, candidate["id"], candidate["name"],
                                   candidate["address"], "pending", "", ""])))
                time.sleep(2.5)
            output.write(json.dumps(result, ensure_ascii=False) + "\n")
            output.flush()
            print(f"{query['id']} {query['city']} HTTP={search['status']} candidates={len(places)} "
                  f"search_ms={search['elapsed_ms']}", flush=True)
    with (out / "review.csv").open("x", encoding="utf-8-sig", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=REVIEW_FIELDS)
        writer.writeheader()
        writer.writerows(reviews)
    write_json(out / "run.json", {"completed_at": datetime.now(timezone.utc).isoformat(),
               "results_sha256": digest(out / "results.jsonl"), "freeze_sha256": digest(out / "freeze.json")})


def score(spec, results, reviews):
    expected = queries(spec)
    if [{k: r[k] for k in ("id", "kind", "city", "keyword")} for r in results] != expected:
        raise ValueError("Missing, reordered, extra or altered queries; denominator must stay 30 + 6")
    decisions = {}
    for row in reviews:
        key = (row["query_id"], int(row["rank"]), row["poi_id"])
        if key in decisions:
            raise ValueError("Duplicate review")
        if row["decision"] not in ("valid", "invalid", "uncertain") or not row["reason"].strip():
            raise ValueError("All candidates require an explicit review and reason")
        if urlsplit(row["evidence_url"]).scheme not in ("https", "http"):
            raise ValueError("All reviews require an evidence URL")
        decisions[key] = row["decision"]
    required, named_hits, generic_total, generic_valid, generic_cities = set(), 0, 0, 0, set()
    by_city = {city: {"named_hits": 0, "named_total": 5, "generic_valid": 0, "generic_total": 0} for city in CITIES}
    errors, detail_errors, uncertain = 0, 0, 0
    for result in results:
        errors += result["search"]["status"] != 200
        candidates = result["candidates"]
        if len(candidates) > 5 or len({c["id"] for c in candidates}) != len(candidates):
            raise ValueError("Invalid or duplicate search candidates")
        hit = False
        for rank, candidate in enumerate(candidates, 1):
            if candidate["rank"] != rank:
                raise ValueError("Candidate ranks are not contiguous")
            key = (result["id"], rank, candidate["id"])
            required.add(key)
            decision = decisions.get(key)
            detail = candidate["detail"]
            detail_errors += detail["status"] != 200
            uncertain += decision == "uncertain"
            if decision == "valid":
                body = detail.get("body", {})
                if (result["search"]["status"] != 200 or detail["status"] != 200
                        or body.get("id") != candidate["id"]
                        or body.get("city", "").removesuffix("市") != result["city"]):
                    raise ValueError("A valid review needs matching detail ID, city and successful HTTP responses")
            if result["kind"] == "named":
                hit |= decision == "valid"
            else:
                generic_total += 1
                by_city[result["city"]]["generic_total"] += 1
                if decision == "valid":
                    generic_valid += 1
                    by_city[result["city"]]["generic_valid"] += 1
                    generic_cities.add(result["city"])
        if result["kind"] == "named" and hit:
            named_hits += 1
            by_city[result["city"]]["named_hits"] += 1
    if set(decisions) != required:
        raise ValueError("Missing or extra candidate reviews")
    latencies = sorted(r["search"]["elapsed_ms"] for r in results)
    precision = generic_valid / generic_total if generic_total else None
    return {"named_hits": named_hits, "named_total": 30, "named_hit_at_5": named_hits / 30,
            "generic_valid": generic_valid, "generic_total": generic_total, "generic_precision": precision,
            "generic_cities_verified": len(generic_cities), "search_errors": errors, "detail_errors": detail_errors,
            "uncertain_candidates": uncertain, "empty_searches": sum(not r["candidates"] for r in results),
            "search_p50_ms": latencies[math.ceil(len(latencies) * .5) - 1],
            "search_p95_ms": latencies[math.ceil(len(latencies) * .95) - 1],
            "search_under_3s": sum(ms <= 3000 for ms in latencies), "search_total": len(results),
            "application_map_requests": len(results) + len(required), "by_city": by_city,
            "passed": named_hits >= 26 and precision is not None and precision >= .9 and len(generic_cities) == 6}


def score_run(args):
    spec = load_spec(args.cases)
    out = Path(args.run)
    freeze, run = read_json(out / "freeze.json"), read_json(out / "run.json")
    if (freeze["cases_sha256"] != digest(args.cases)
            or run["freeze_sha256"] != digest(out / "freeze.json")
            or run["results_sha256"] != digest(out / "results.jsonl")):
        raise ValueError("Frozen cases, manifest or results changed")
    results = [json.loads(line) for line in (out / "results.jsonl").read_text(encoding="utf-8").splitlines()]
    with (out / "review.csv").open(encoding="utf-8-sig", newline="") as source:
        summary = score(spec, results, list(csv.DictReader(source)))
    summary["cases_sha256"] = freeze["cases_sha256"]
    summary["review_sha256"] = digest(out / "review.csv")
    write_json(out / "summary.json", summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["collect", "score"])
    parser.add_argument("--cases", type=Path, default=ROOT / "data/evaluation/campsite-pilot-v1.json")
    parser.add_argument("--run", required=True)
    parser.add_argument("--environment", type=Path, default=ROOT / ".local/server-deploy/server.env")
    parser.add_argument("--base-url", default="http://127.0.0.1:18090")
    parser.add_argument("--release", default="unspecified")
    arguments = parser.parse_args()
    (collect if arguments.action == "collect" else score_run)(arguments)
