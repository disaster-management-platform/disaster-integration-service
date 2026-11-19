"""Directed tests for an isolated, provisioned native project and two Keycloak users."""
import json
import os
import uuid
import sys
from urllib.parse import urlsplit
import requests

base = os.environ['INTEGRATION_TEST_URL'].rstrip('/')
assert urlsplit(base).hostname in ('localhost', '127.0.0.1', '::1')
owner = os.environ['COORDINATION_TEST_OWNER_TOKEN']
other = os.environ['COORDINATION_TEST_OTHER_TOKEN']
activity = json.loads(os.environ['COORDINATION_TEST_ACTIVITY'])
task = json.loads(os.environ['COORDINATION_TEST_TASK'])
assert activity['disasterId'] == task['disasterId'] and activity['projectId'] == task['projectId']

def call(method, path, token, body=None, key=None):
    headers = {'Authorization': 'Bearer ' + token}
    if key:
        headers['Idempotency-Key'] = key
    return requests.request(method, base + '/coordinacion/' + path, json=body, headers=headers, timeout=30)

if '--recovery-only' in sys.argv:
    control = os.environ['COORDINATION_TEST_DROP_CONTROL']
    assert urlsplit(control).hostname in ('localhost', '127.0.0.1', '::1')
    target = os.environ['COORDINATION_TEST_ACTIVITY_ID']
    command = dict(activity, name='Synthetic-recovery-' + uuid.uuid4().hex[:12])
    key = str(uuid.uuid4())
    assert requests.post(control, timeout=5).status_code == 200
    lost = call('PUT', 'actividades/' + target, owner, command, key)
    assert lost.status_code == 503, lost.status_code
    recovered = call('PUT', 'actividades/' + target, owner, command, key)
    assert recovered.status_code == 200 and recovered.json()['id'] == target
    assert recovered.json()['name'] == command['name']
    print('PASS: committed native update recovered with the same UUID after lost response')
    sys.exit(0)

for resource, command in [('actividades', activity), ('tareas', task)]:
    command = dict(command, name='Synthetic-' + uuid.uuid4().hex[:12])
    if resource == 'tareas':
        command['activityId'] = activity_id
    key = str(uuid.uuid4())
    response = call('POST', resource, owner, command, key)
    assert response.status_code == 201, response.status_code
    created = response.json()
    identity = created['id']
    assert str(created['projectId']) == str(command['projectId'])
    if command.get('assignee') is not None:
        assert created['assignee'] == command['assignee']
    replay = call('POST', resource, owner, command, key)
    assert replay.status_code == 200 and replay.json()['id'] == identity
    changed = dict(command, name=command['name'] + '-updated')
    assert call('POST', resource, owner, changed, key).status_code == 409
    denied = call('PUT', resource + '/' + identity, other, changed, str(uuid.uuid4()))
    assert denied.status_code in (403, 404), denied.status_code
    # Replaying the original receipt reads the current native record.
    assert call('POST', resource, owner, command, key).json()['name'] == command['name']
    update = call('PUT', resource + '/' + identity, owner, changed, str(uuid.uuid4()))
    assert update.status_code == 200 and update.json()['name'] == changed['name'], update.status_code
    assert call('POST', resource, owner, command, key).json()['name'] == changed['name']
    if resource == 'actividades':
        activity_id = identity
print('PASS: native coordination create, update, stable identity, conflicting replay and foreign mutation denial')
