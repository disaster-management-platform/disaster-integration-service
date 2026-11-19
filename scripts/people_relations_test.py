"""Real native relationships and scoped binding/filtering with externally issued JWTs."""
import json
import os
import uuid
import sys
from pathlib import Path
import requests

base = os.environ['INTEGRATION_TEST_URL'].rstrip('/')
identities = json.loads(Path(os.environ['EDEN_TEST_IDENTITIES']).expanduser().read_text())
disaster = int(os.environ['INTEGRATION_TEST_DISASTER_ID'])
tokens = {'A': os.environ['INTEGRATION_TEST_OWNER_TOKEN'],
          'B': os.environ['INTEGRATION_TEST_OTHER_TOKEN']}

def call(path='', method='GET', body=None, label='A', key=None):
    headers = {'Authorization': 'Bearer ' + tokens[label]}
    if key:
        headers['Idempotency-Key'] = key
    return requests.request(method, base + '/personas-afectadas' + path, json=body,
                            headers=headers, timeout=30)

if '--recovery-only' not in sys.argv:
    person = str(identities['A']['personId'])
    response = call('/' + person + '/vincular', 'POST', {'disasterId': disaster})
    assert response.status_code == 200, response.status_code
    value = response.json()
    assert str(identities['A']['familyId']) in value['familyIds']
    assert value['caseIds'] and value['needs']
    assert str(identities['A']['shelterId']) in value['shelterIds']
    assert call('/' + person + '/vincular', 'POST', {'disasterId': disaster}).status_code == 200
    foreign = str(identities['B']['personId'])
    assert call('/' + foreign + '/vincular', 'POST', {'disasterId': disaster}).status_code in (403, 404)
    assert call('/' + person, label='B').status_code in (403, 404)
    response = call('/resumen?disasterId=' + str(disaster))
    assert response.status_code == 200, response.status_code
    assert response.json()['peopleCount'] == 2 and response.json()['familyCount'] == 1
    assert response.json()['openCases'] == 2 and response.json()['shelteredCount'] == 1
    query = '?disasterId=' + str(disaster) + '&search=Integrante&page=0&size=1'
    response = call(query)
    assert response.status_code == 200, response.status_code
    assert len(response.json()['items']) == 1 and response.json()['total'] == 1
    assert not response.json()['hasMore']
    assert call(query.replace('page=0', 'page=1')).json()['items'] == []
    print('PASS real scoped binding/replay, native family/care/shelter aggregates, foreign denial and filtering before pagination')

member = os.environ.get('INTEGRATION_TEST_WRITE_PERSON', str(identities['A']['memberPersonId']))
original = call('/' + member).json()
fields = ('firstName', 'lastName', 'identificationRef', 'birthDate', 'phone', 'email', 'locationId', 'organizationId', 'disasterId')
body = {field: original.get(field) for field in fields}
invalid = dict(body, locationId='999999999')
key_name = 'rejectedUpdateKey' if member == str(identities['A']['memberPersonId']) else 'rejectedUpdateKey_' + member
rejected_key = identities['A'].get(key_name)
if rejected_key is None:
    rejected_key = identities['A'][key_name] = str(uuid.uuid4())
    private = Path(os.environ['EDEN_TEST_IDENTITIES']).expanduser()
    with private.open('w') as stream:
        json.dump(identities, stream)
    private.chmod(0o600)
response = call('/' + member, 'PUT', invalid, key=rejected_key)
assert response.status_code == 422, response.status_code
assert call('/' + member, 'PUT', invalid, key=rejected_key).status_code == 422
assert call('/' + member).json()['locationId'] == original['locationId']
accepted_key = str(uuid.uuid4())
response = call('/' + member, 'PUT', body, key=accepted_key)
assert response.status_code == 200 and response.json()['id'] == member
assert call('/' + member, 'PUT', body, key=accepted_key).status_code == 200
print('PASS definitive native rejection, preserved data, subsequent update and idempotent recovery')
