"""Directed contract check against Integration Service and an isolated Desastres owner."""
import os
import time
import uuid
from pathlib import Path
import jwt
import requests

base = os.environ['INTEGRATION_TEST_URL'].rstrip('/')
key = Path(os.environ['INTEGRATION_TEST_PRIVATE_KEY']).read_text()
org = os.environ['INTEGRATION_TEST_ORGANIZATION']


def submit(category, data, role='integration.monitoring', actor='synthetic-monitoring'):
    now = int(time.time())
    token = jwt.encode({'iss': os.environ['JWT_ISSUER'], 'aud': os.environ['JWT_AUDIENCE'],
                        'sub': actor, 'organization_id': org, 'role': [role],
                        'iat': now, 'exp': now + 300}, key, algorithm='RS256')
    return requests.post(base + '/integracion/reportes/' + category, json=data,
                         headers={'Authorization': 'Bearer ' + token}, timeout=30)


if __name__ == '__main__':
    report = {'sourceReportId': str(uuid.uuid4()), 'content': {'synthetic': True, 'severity': 1}}
    first = submit('monitoring', report)
    assert first.status_code == 201, first.status_code
    replay = submit('monitoring', report)
    assert replay.status_code == 200 and replay.json()['id'] == first.json()['id']
    assert submit('monitoring', {**report, 'content': {'synthetic': False}}).status_code == 409
    assert submit('gis', report).status_code == 403
    assert submit('monitoring', {**report, 'sourceId': 'forged'}).status_code == 400
    assert submit('hospital', {**report, 'disaster': {}}, role='integration.hospital').status_code == 400
    other = submit('monitoring', report, actor='other-synthetic-source')
    assert other.status_code == 201 and other.json()['id'] != first.json()['id']
    print('PASS native owner report receipt, replay/conflict and isolated authenticated sources')
