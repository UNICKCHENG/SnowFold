#!/usr/bin/env python3
"""Release boundary checks and GitHub-generated notes; credentials come only from env."""

import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tomllib
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen


SIGNING_SECRETS = (
    'ANDROID_KEYSTORE_BASE64',
    'ANDROID_KEYSTORE_PASSWORD',
)


def validate():
    version = tomllib.loads(Path('Cargo.toml').read_text())['workspace']['package']['version']
    tag = os.environ['RELEASE_TAG']
    if tag != f'v{version}':
        raise SystemExit(f'Tag {tag!r} must match workspace version v{version}.')
    missing = [name for name in SIGNING_SECRETS if not os.environ.get(name)]
    if missing:
        raise SystemExit('Missing required Android signing secrets: ' + ', '.join(missing))
    print(f'Tag {tag} matches workspace version; both signing secrets are configured.')


def decode_keystore():
    encoded = ''.join(os.environ['ANDROID_KEYSTORE_BASE64'].split())
    try:
        content = base64.b64decode(encoded, validate=True)
    except ValueError:
        raise SystemExit('ANDROID_KEYSTORE_BASE64 is not valid base64.') from None
    if not content:
        raise SystemExit('ANDROID_KEYSTORE_BASE64 decoded to an empty file.')
    descriptor = os.open(os.environ['SNOWFOLD_ANDROID_KEYSTORE_FILE'],
                         os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'wb') as keystore:
        keystore.write(content)
    print('Temporary Android keystore decoded with owner-only permissions.')


def github_api(path, data=None):
    request = Request(
        f"{os.environ.get('GITHUB_API_URL', 'https://api.github.com')}/{path}",
        data=json.dumps(data).encode() if data is not None else None,
        headers={
            'Authorization': f"Bearer {os.environ['GH_TOKEN']}",
            'Accept': 'application/vnd.github+json',
            'Content-Type': 'application/json',
            'X-GitHub-Api-Version': '2022-11-28',
        },
    )
    with urlopen(request, timeout=60) as response:
        return json.load(response)


def previous_tag(tag):
    # The first release has no previous tag; list its complete reachable history instead.
    result = subprocess.run(
        ['git', 'describe', '--tags', '--abbrev=0', '--match', 'v*', f'{tag}^'],
        capture_output=True, text=True, check=False,
    )
    return result.stdout.strip() if result.returncode == 0 else None


def commit_authors(repository, tag, previous):
    linked, unlinked = set(), set()
    page = 1
    while True:
        parameters = {'per_page': 100, 'page': page}
        if previous:
            path = f'repos/{repository}/compare/{quote(previous, safe="")}...{quote(tag, safe="")}'
            commits = github_api(f'{path}?{urlencode(parameters)}')['commits']
        else:
            parameters['sha'] = tag
            commits = github_api(f'repos/{repository}/commits?{urlencode(parameters)}')
        for commit in commits:
            author = commit.get('author') or {}
            if author.get('login'):
                linked.add(author['login'])
            else:
                # Preserve credit for authors whose email is not linked to a GitHub account.
                name = commit['commit']['author']['name']
                name = re.sub(r'[\r\n]+', ' ', name).replace('@', '＠')
                unlinked.add(re.sub(r'([\\`*_{}\[\]()<>#!|])', r'\\\1', name))
        # Explicit pagination includes direct pushes and histories longer than 250 commits.
        if len(commits) < 100:
            break
        page += 1
    return [f'@{login}' for login in sorted(linked, key=str.casefold)] + sorted(unlinked)


def notes():
    tag = os.environ['RELEASE_TAG']
    repository = os.environ['GITHUB_REPOSITORY']
    previous = previous_tag(tag)
    request = {'tag_name': tag}
    if previous:
        request['previous_tag_name'] = previous
    generated = github_api(f'repos/{repository}/releases/generate-notes', request)
    authors = commit_authors(repository, tag, previous)
    body = generated['body'].rstrip()
    body += '\n\n## 贡献者\n\n' + ', '.join(authors) + '\n'
    Path(os.environ['RELEASE_NOTES_FILE']).write_text(body, encoding='utf-8')
    print(f'GitHub-generated notes preserved; credited {len(authors)} commit authors '
          f'since {previous or "the beginning of the repository"}.')


if __name__ == '__main__':
    commands = {'validate': validate, 'decode-keystore': decode_keystore, 'notes': notes}
    if len(sys.argv) != 2 or sys.argv[1] not in commands:
        raise SystemExit('Usage: release.py {validate|decode-keystore|notes}')
    commands[sys.argv[1]]()
