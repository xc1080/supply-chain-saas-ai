"""HTTP checks for uploads and history pages, only in verifier-owned applications."""
from concurrent.futures import ThreadPoolExecutor
import argparse
import json
import os
from pathlib import Path
from urllib.parse import urlparse

import httpx


HISTORY_PATHS = (
    '/commerce/agent-tasks', '/commerce/planning/drafts',
    '/commerce/planning/incoming', '/commerce/planning/incoming/changes',
    '/commerce/costs/reconciliations',
)
PROTECTED_PORTS = {8035, 7050, 7051, 5173, 6001, 6002}


def isolated_url(value):
    parsed = urlparse(value)
    if (parsed.scheme != 'http' or parsed.hostname != '127.0.0.1'
            or not parsed.port or parsed.port in PROTECTED_PORTS
            or parsed.username or parsed.password or parsed.path not in ('', '/')
            or parsed.query or parsed.fragment):
        raise ValueError('An explicitly configured isolated localhost application is required')
    return value.rstrip('/')


def run(output):
    base = isolated_url(os.environ.get('FUSION_JAVA_URL', ''))
    password = os.environ['FUSION_SMOKE_PASSWORD']
    report = {'passed': False, 'checks': [], 'kind': 'ISOLATED_CLOSING_CONTRACTS'}

    def check(name, condition):
        report['checks'].append({'name': name, 'passed': bool(condition)})
        if not condition:
            raise RuntimeError(name)

    def request(client, name, method, path, **kwargs):
        response = client.request(method, path, **kwargs)
        payload = response.json()
        check(name, response.status_code == 200 and payload.get('code') == 200)
        return payload

    try:
        identities = {}
        for username, tenant in (('admin', 'demo'), ('studio_admin', 'studio')):
            with httpx.Client(base_url=base, trust_env=False, timeout=30) as client:
                token = request(client, tenant + '.login', 'POST', '/login',
                                json={'username': username, 'password': password})['token']
                headers = {'Authorization': 'Bearer ' + token, 'X-Shop-ID': 'default'}
                client.headers.update(headers)
                context = request(client, tenant + '.context', 'GET', '/commerce/context')['data']
                check(tenant + '.serverBoundTenant', context['tenantId'] == tenant)
                identities[tenant] = (username, headers)
                for path in HISTORY_PATHS:
                    legacy = request(client, tenant + path + '.legacyArray', 'GET', path)['data']
                    check(tenant + path + '.legacyContractPreserved', isinstance(legacy, list))
                    for page_num in (1, 2):
                        page = request(client, tenant + path + '.page' + str(page_num),
                                       'GET', path + '/page',
                                       params={'pageNum': page_num, 'pageSize': 1, 'status': 'ALL'})['data']
                        check(tenant + path + '.pageShape' + str(page_num),
                              isinstance(page['rows'], list) and len(page['rows']) <= 1
                              and page['total'] >= 0 and page['pageNum'] == page_num and page['pageSize'] == 1)
                    invalid = client.get(path + '/page', params={'pageNum': 0, 'pageSize': 1}).json()
                    check(tenant + path + '.invalidPageRejected', invalid.get('code') == 400)
                    invalid = client.get(path + '/page', params={'status': 'NOT_A_BUSINESS_STATE'}).json()
                    check(tenant + path + '.invalidStatusRejected', invalid.get('code') == 400)

        def upload(entry):
            tenant, index = entry
            username, headers = identities[tenant]
            name = 'closing_' + tenant + '_' + str(index) + '.txt'
            with httpx.Client(base_url=base, headers=headers, trust_env=False, timeout=30) as client:
                payload = request(client, tenant + '.upload' + str(index), 'POST',
                                  '/baseDate/fileSharing/upload',
                                  files={'file': (name, b'Fictional isolated upload verification.', 'text/plain')})
                file_id = payload['fileId']
                record = request(client, tenant + '.readFile' + str(index), 'GET',
                                 '/baseDate/fileSharing/' + str(file_id))['data']
                check(tenant + '.exactFileAuthor' + str(index), record['fileId'] == file_id
                      and record['fileName'] == name and record['createBy'] == username
                      and record['fileUrl'] == payload['fileUrl'])
                return tenant, file_id

        with ThreadPoolExecutor(max_workers=8) as pool:
            uploaded = list(pool.map(upload, [(tenant, index) for tenant in identities for index in range(4)]))
        check('eightDistinctTenantBoundFiles', len(set(uploaded)) == 8)
        for tenant, (username, headers) in identities.items():
            with httpx.Client(base_url=base, headers=headers, trust_env=False, timeout=30) as client:
                rejected = client.put('/baseDate/fileSharing/add', json={}).json()
                check(tenant + '.legacyLatestRowMutationDisabled', rejected.get('code') != 200)
        report['passed'] = True
    except Exception as failure:
        # Do not serialize HTTP bodies, passwords, tokens, or arbitrary exception text.
        report['errorType'] = type(failure).__name__
    finally:
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    raise SystemExit(run(args.output))
