"""Directed checks after provisioning a scoped native EVENT/PROJECT fixture in Eden."""
import os
import time
from pathlib import Path
import jwt
import requests

base = os.environ['INTEGRATION_TEST_URL'].rstrip('/')
key = Path(os.environ['INTEGRATION_TEST_PRIVATE_KEY']).read_text()
org = os.environ['INTEGRATION_TEST_ORGANIZATION']
disaster = int(os.environ['INTEGRATION_TEST_DISASTER_ID'])


def request(resource, permissions=(disaster,), role='coordination.read', organization=org):
    now = int(time.time())
    token = jwt.encode({'iss': os.environ['JWT_ISSUER'], 'aud': os.environ['JWT_AUDIENCE'],
                        'sub': 'synthetic-coordination', 'organization_id': organization, 'role': [role],
                        'disaster_id': list(permissions), 'iat': now, 'exp': now + 300}, key, algorithm='RS256')
    return requests.get(base + '/coordinacion/' + resource, params={'disasterId': disaster, 'size': 10},
                        headers={'Authorization': 'Bearer ' + token}, timeout=30)


if __name__ == '__main__':
    assert request('tareas', permissions=()).status_code == 403
    assert request('actividades', role='org.read').status_code == 403
    for resource, expected_key in [('tareas', 'INTEGRATION_TEST_TASK_ID'), ('actividades', 'INTEGRATION_TEST_ACTIVITY_ID')]:
        result = request(resource)
        assert result.status_code == 200, result.status_code
        data = result.json()
        assert data['total'] is None and len(data['items']) <= 10
        assert os.environ[expected_key] in [row['id'] for row in data['items']]
        assert all(row['organizationId'] == org and row['disasterId'] == disaster for row in data['items'])
    print('PASS scoped native coordination reads (fixture must be in the first page)')
