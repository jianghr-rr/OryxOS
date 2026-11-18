#!/usr/bin/env python3
"""Trusted, dependency-free GitHub search snapshot. Not the GitHub Trending ranking.

Three bounded requests; no retry, cache fallback or sample data on errors. No stdout until
all sections validate. Author-approved script networking is outside the tool HTTP allowlist.
"""
import argparse
import datetime as dt
import ipaddress
import json
import os
import re
import sys
import urllib.parse
import urllib.request
from zoneinfo import ZoneInfo


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("redirect refused")


def api_base(value):
    parsed = urllib.parse.urlsplit(value)
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("API base must not contain credentials, query or fragment")
    # The only alternate source is a loopback HTTP test fixture, never an arbitrary token recipient.
    if value.rstrip("/") == "https://api.github.com":
        return value.rstrip("/")
    try:
        loopback = ipaddress.ip_address(parsed.hostname or "").is_loopback
    except ValueError:
        loopback = False
    if parsed.scheme == "http" and loopback and parsed.port:
        return value.rstrip("/")
    raise ValueError("use GitHub HTTPS or an explicit loopback test source")


def fetch(base, query, limit):
    url = base + "/search/repositories?" + urllib.parse.urlencode(
        {"q": query, "sort": "stars", "order": "desc", "per_page": limit})
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "OryxOS-reviewed-example",
               "X-GitHub-Api-Version": "2022-11-28"}
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        if base != "https://api.github.com":
            raise ValueError("credential forwarding to fixture refused")
        headers["Authorization"] = "Bearer " + token
    # Avoid inherited proxy configuration; redirect handling never forwards the credential.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    with opener.open(urllib.request.Request(url, headers=headers), timeout=4) as response:
        body = response.read(262145)
        if len(body) > 262144:
            raise ValueError("source response too large")
        data = json.loads(body)
    if not isinstance(data, dict) or data.get("incomplete_results") is not False:
        raise ValueError("source results incomplete or missing completeness metadata")
    items = data.get("items")
    if not isinstance(items, list) or len(items) > limit:
        raise ValueError("invalid items")
    result = []
    for item in items:
        name = item.get("full_name", "")
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", name):
            raise ValueError("invalid repository name")
        if item.get("html_url") != "https://github.com/" + name:
            raise ValueError("unverifiable repository URL")
        stars = item.get("stargazers_count")
        if type(stars) is not int or stars < 0:
            raise ValueError("invalid star count")
        result.append({"name": name, "url": item["html_url"], "stars": stars,
                       "description": str(item.get("description") or "")[:500]})
    return {"query": query, "source": url, "items": result}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api-base", default="https://api.github.com")
    parser.add_argument("--date", required=True, help="workspace date YYYY-MM-DD, supplied by the runtime prompt")
    parser.add_argument("--timezone", default="Asia/Shanghai")
    parser.add_argument("--limit", type=int, default=3)
    args = parser.parse_args()
    try:
        base = api_base(args.api_base)
        date = dt.date.fromisoformat(args.date)
        if date.isoformat() != args.date or not 1 <= args.limit <= 5:
            raise ValueError("invalid date or limit")
        zone = ZoneInfo(args.timezone)
        if date > dt.datetime.now(zone).date():
            raise ValueError("future date refused")
        month = date.replace(day=1).isoformat()
        queries = {"today": "created:" + args.date + ".." + args.date + " archived:false",
                   "month": "created:" + month + ".." + args.date + " archived:false",
                   "ai": "topic:artificial-intelligence pushed:" + month + ".." + args.date + " archived:false"}
        sections = {key: fetch(base, query, args.limit) for key, query in queries.items()}
        print(json.dumps({"date": args.date, "timezone": args.timezone,
                          "observed_at": dt.datetime.now(zone).isoformat(),
                          "ranking": "search snapshot by total stars; not Trending or star growth",
                          "sections": sections}, ensure_ascii=False))
        return 0
    except Exception:
        # Do not expose URLs with tokens, arbitrary response text, headers or environment values.
        print("GitHub source/script failed or results incomplete; no report produced", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
