#!/usr/bin/env python3
"""Exercise updates and moves against a local/staging instance with its real database and scanner.

Requires local registration and BOOTSTRAP_ADMIN_PASSWORD. Creates a temporary author and
skill; removes the skill after validation. The author remains as part of the audit trail.
"""
import argparse
import http.cookiejar
import io
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile


class Session:
    def __init__(self, base):
        self.base = base.rstrip('/')
        self.cookies = http.cookiejar.CookieJar()
        self.client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(self.cookies))
        self.call('GET', '/api/v1/auth/providers')

    def call(self, method, path, body=None, content_type='application/json', allow_error=False):
        headers = {'Content-Type': content_type, 'Accept-Language': 'en'}
        token = next((cookie.value for cookie in self.cookies if cookie.name == 'XSRF-TOKEN'), None)
        if token:
            headers['X-XSRF-TOKEN'] = urllib.parse.unquote(token)
        data = body if isinstance(body, bytes) else json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            response = self.client.open(request, timeout=70)
        except urllib.error.HTTPError as error:
            response = error
        payload = response.read()
        if allow_error:
            return response.status, payload
        result = json.loads(payload)
        assert response.status < 300 and result.get('code') == 0, (path, response.status, result.get('msg'))
        return result.get('data')

    def upload(self, path, name, version):
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as package:
            package.writestr('SKILL.md', f'---\nname: {name}\ndescription: Format a local weekly report from supplied text.\nversion: {version}\n---\n# Weekly report\nSummarize supplied notes as a short report. Do not access external resources.\n')
        boundary = 'sharing-' + uuid.uuid4().hex
        chunks = []
        for key, value in [('visibility', 'PRIVATE'), ('confirmWarnings', 'true')]:
            chunks.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
        chunks.extend([
            f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="skill.zip"\r\nContent-Type: application/zip\r\n\r\n'.encode(),
            archive.getvalue(), f'\r\n--{boundary}--\r\n'.encode(),
        ])
        return self.call('POST', path, b''.join(chunks), f'multipart/form-data; boundary={boundary}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('base_url', nargs='?', default='http://localhost:8080')
    args = parser.parse_args()
    password = os.environ.get('BOOTSTRAP_ADMIN_PASSWORD')
    if not password:
        parser.error('Set BOOTSTRAP_ADMIN_PASSWORD for the local/staging administrator.')
    admin = Session(args.base_url)
    admin.call('POST', '/api/v1/auth/local/login', {'username': os.environ.get('BOOTSTRAP_ADMIN_USERNAME', 'admin'), 'password': password})
    author = Session(args.base_url)
    suffix = uuid.uuid4().hex[:10]
    name = 'sharing-smoke-' + suffix
    author.call('POST', '/api/v1/auth/local/register', {'username': 'share_' + suffix, 'password': 'Test!' + uuid.uuid4().hex, 'email': suffix + '@example.test'})
    anonymous = Session(args.base_url)
    skill_id = None
    team_slug = None
    try:
        first = author.upload('/api/v1/skills/private/publish', name, '0.1.0')
        skill_id = first['skillId']
        def wait_version(number, expected='UPLOADED'):
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                location = author.call('GET', f'/api/v1/skills/by-id/{skill_id}/location')
                result = author.call('GET', f'/api/v1/skills/{location["namespace"]}/{name}/versions/{number}')
                if result['status'] != 'SCANNING':
                    assert result['status'] == expected, result
                    return result
                time.sleep(1)
            raise AssertionError('Upload scan timed out for ' + number)

        wait_version('0.1.0')
        author.call('POST', f'/api/web/skills/private/{name}/confirm-publish', {'version': '0.1.0'})
        second = author.upload('/api/v1/skills/private/publish', name, '1.0.0')
        assert second['skillId'] == skill_id
        wait_version('1.0.0')
        share_path = f'/api/web/skills/by-id/{skill_id}/sharing'
        settings = author.call('GET', share_path)
        target = next(item for item in settings['targets'] if item['type'] == 'GLOBAL')

        def move(version_number):
            settings = author.call('GET', share_path)
            version = next(item for item in settings['versions'] if item['version'] == version_number)
            assert len(settings['versions']) == 1
            command = {'targetNamespaceId': target['id'], 'idempotencyKey': uuid.uuid4().hex, 'confirmWarnings': True}
            check = author.call('POST', share_path + '/precheck', command)
            assert check['valid'], check
            request = author.call('POST', share_path, command)
            assert request['versionId'] == version['id']
            assert request['targetVisibility'] == 'NAMESPACE_ONLY'
            assert author.call('POST', share_path, command)['id'] == request['id']
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                request = author.call('GET', share_path)['latestRequest']
                if request['status'] != 'SCANNING':
                    break
                time.sleep(1)
            assert request['status'] == 'PENDING_REVIEW', request
            review = admin.call('GET', f'/api/v1/reviews/{request["reviewTaskId"]}/skill-detail')
            assert not any(item['version'] == '0.1.0' for item in review['versions'])
            admin.call('POST', f'/api/v1/reviews/{request["reviewTaskId"]}/approve', {'comment': 'Move smoke test: approved latest available version.'})
            assert author.call('GET', share_path)['latestRequest']['status'] == 'COMPLETED'

        move('1.0.0')
        print('PASS: latest private version moved after real scan and target review', flush=True)
        location = author.call('GET', f'/api/v1/skills/by-id/{skill_id}/location')
        assert location['namespace'] == target['slug']
        base = f'/api/v1/skills/{target["slug"]}/{name}'
        assert anonymous.call('GET', base, allow_error=True)[0] in (400, 401, 403, 404)
        assert author.call('GET', base)['publishedVersion']['version'] == '1.0.0'
        for path in ['/versions/0.1.0', '/versions/0.1.0/files', '/versions/0.1.0/file?path=SKILL.md', '/versions/0.1.0/download', '/versions/compare?from=0.1.0&to=1.0.0']:
            assert admin.call('GET', base + path, allow_error=True)[0] in (400, 401, 403, 404), path
        assert author.call('GET', f'/api/v1/skills/private/{name}')['id'] == skill_id
        third = author.upload(f'/api/web/skills/by-id/{skill_id}/versions', name, '1.1.0')
        assert third['skillId'] == skill_id and third['namespace'] == target['slug']
        wait_version('1.1.0', 'PENDING_REVIEW')
        assert author.call('GET', base)['publishedVersion']['version'] == '1.0.0'
        pending = admin.call('GET', f'/api/web/reviews?status=PENDING&namespaceId={target["id"]}')
        update_review = next(item for item in pending['items'] if item['skillSlug'] == name and item['version'] == '1.1.0')
        admin.call('POST', f'/api/v1/reviews/{update_review["id"]}/approve', {'comment': 'Update smoke test'})
        assert author.call('GET', base)['publishedVersion']['version'] == '1.1.0'
        print('PASS: direct update retains scope and switches latest only after review', flush=True)

        fourth = author.upload(f'/api/web/skills/by-id/{skill_id}/versions', name, '1.2.0')
        assert fourth['skillId'] == skill_id
        wait_version('1.2.0', 'PENDING_REVIEW')
        pending = admin.call('GET', f'/api/web/reviews?status=PENDING&namespaceId={target["id"]}')
        old_review = next(item for item in pending['items'] if item['skillSlug'] == name and item['version'] == '1.2.0')
        target = admin.call('POST', '/api/v1/namespaces', {'slug': 'move-test-' + suffix, 'displayName': 'Move smoke test'})
        team_slug = target['slug']
        author_id = author.call('GET', '/api/v1/auth/me')['userId']
        admin.call('POST', f'/api/v1/namespaces/{team_slug}/members', {'userId': author_id, 'role': 'MEMBER'})
        move('1.1.0')
        moved_base = f'/api/v1/skills/{team_slug}/{name}'
        assert author.call('GET', moved_base)['publishedVersion']['version'] == '1.1.0'
        assert author.call('GET', moved_base + '/versions/1.2.0')['status'] == 'UPLOADED'
        assert len(author.call('GET', moved_base + '/versions')['items']) == 4
        assert admin.call('POST', f'/api/v1/reviews/{old_review["id"]}/approve', {}, allow_error=True)[0] in (400, 403, 404)
        assert author.call('GET', base, allow_error=True)[0] in (400, 404)
        print('PASS: shared skill moves across spaces, pending source review withdrawn, all files retained', flush=True)
    finally:
        if skill_id is not None:
            request = author.call('GET', f'/api/web/skills/by-id/{skill_id}/sharing').get('latestRequest')
            if request and request['status'] in ('SCANNING', 'PENDING_REVIEW'):
                author.call('POST', f'/api/web/skills/by-id/{skill_id}/sharing/{request["id"]}/withdraw')
            admin.call('DELETE', f'/api/v1/skills/id/{skill_id}')
        if team_slug is not None:
            admin.call('DELETE', f'/api/v1/namespaces/{team_slug}')


if __name__ == '__main__':
    main()
