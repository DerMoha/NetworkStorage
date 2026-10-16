#!/usr/bin/env python3
"""Publish an external GitHub release asset with exact Hangar platform versions."""
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = "https://hangar.papermc.io/api/v1"


def request(path, *, data=None, headers=None):
    req = urllib.request.Request(BASE + path, data=data,
                                 headers={"User-Agent": "NetworkStorage-release", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        # Never include the authentication URL or API key in logs.
        raise RuntimeError(f"Hangar request failed (HTTP {error.code})") from None


def main():
    version = os.environ["RELEASE_VERSION"]
    minecraft = "26.3"
    platforms = request("/platforms/paper/versions")
    supported = {value for group in platforms for value in [group["version"], *group["subVersions"]]}
    if minecraft not in supported:
        raise RuntimeError(f"Hangar does not list Paper {minecraft}")
    token = request("/authenticate?apiKey=" + urllib.parse.quote(os.environ["HANGAR_API_TOKEN"], safe=""), data=b"")["token"]
    print(f"::add-mask::{token}")
    repo = os.environ["GITHUB_REPOSITORY"]
    payload = {
        "version": version,
        "channel": os.environ.get("HANGAR_CHANNEL", "Release"),
        "description": Path("CHANGELOG.md").read_text(),
        "files": [{"platforms": ["PAPER"], "url": True,
                   "externalUrl": f"https://github.com/{repo}/releases/download/{version}/NetworkStorage-{version}.jar"}],
        "pluginDependencies": {},
        "platformDependencies": {"PAPER": [minecraft]},
    }
    boundary = "NetworkStorage" + uuid.uuid4().hex
    data = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"versionUpload\"\r\n"
            "Content-Type: application/json\r\n\r\n" + json.dumps(payload) + f"\r\n--{boundary}--\r\n").encode()
    result = request("/projects/NetworkStorage/upload", data=data,
                     headers={"Authorization": token, "Content-Type": f"multipart/form-data; boundary={boundary}"})
    print("Published on Hangar: " + result["url"])


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, KeyError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
