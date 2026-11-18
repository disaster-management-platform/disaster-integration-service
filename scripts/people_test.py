"""Targeted PEO contracts against real local Eden and an isolated integration database."""
import os,json,time,uuid,sys
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import requests,jwt

base=os.environ['INTEGRATION_TEST_URL'].rstrip('/')
DISASTER=int(os.environ['INTEGRATION_TEST_DISASTER_ID'])
MISSING=2147483000
private=Path(os.environ['INTEGRATION_TEST_PRIVATE_KEY']).read_text()
organizations=json.loads(Path(os.environ['EDEN_TEST_IDENTITIES']).expanduser().read_text())

def token(label='A',disasters=(DISASTER,),role='people.write'):
    return jwt.encode({'iss':os.environ['JWT_ISSUER'],'aud':os.environ['JWT_AUDIENCE'],'sub':'peo-test-'+label,'organization_id':str(organizations[label]['organizationId']),'role':[role],'disaster_id':list(disasters),'iat':int(time.time()),'exp':int(time.time())+600},private,algorithm='RS256')

def call(path='',method='GET',body=None,key=None,label='A',disasters=(DISASTER,),role='people.write'):
    headers={'Authorization':'Bearer '+token(label,disasters,role)}
    if key:headers['Idempotency-Key']=key
    return requests.request(method,base+'/personas-afectadas'+path,json=body,headers=headers,timeout=30)

if '--recovery-only' not in sys.argv:
    key=str(uuid.uuid4());body={'firstName':'Platform-PEO-'+key[:8],'lastName':'Synthetic','birthDate':'2000-01-01','phone':'+573001234567','email':'peo-synthetic@example.invalid'}
    response=call(method='POST',body=body,key=key);assert response.status_code==201,(response.status_code,response.text[:150])
    created=response.json();person=created['id'];assert created['phone']==body['phone'] and created['email']==body['email']
    assert call(method='POST',body=body,key=key).status_code==200
    assert call(method='POST',body={**body,'firstName':'different'},key=key).status_code==409
    assert call('/'+person,label='B').status_code==404
    changed={**body,'lastName':'Updated'}
    response=call('/'+person,'PUT',changed,str(uuid.uuid4()));assert response.status_code==200,(response.status_code,response.text[:150])
    assert call('/'+person).json()['lastName']=='Updated'
    assert call(method='POST',body=body,key=key).json()['lastName']=='Updated'
    assert call('/'+person).json()['caseIds'] is None
    assert call('/'+person,label='B',method='PUT',body=changed,key=str(uuid.uuid4())).status_code==404
    assert call(method='POST',body=body,key=str(uuid.uuid4()),role='people.read').status_code==403
    assert call(method='POST',body={**body,'organizationId':str(organizations['B']['organizationId'])},key=str(uuid.uuid4())).status_code==403

    request=str(uuid.uuid4());context={**body,'firstName':body['firstName']+'-context','disasterId':DISASTER}
    response=call(method='POST',body=context,key=request);assert response.status_code==201,(response.status_code,response.text[:150])
    linked=response.json();assert linked['disasterId']==DISASTER
    assert call('/'+linked['id'],disasters=()).status_code==404
    page=call('?disasterId='+str(DISASTER)).json();assert linked['id'] in [item['id'] for item in page['items']]
    assert linked['id'] not in [item['id'] for item in call(disasters=()).json()['items']]
    assert all('phone' not in item and 'email' not in item for item in page['items'])
    assert call('?disasterId='+str(DISASTER),disasters=()).status_code==403
    summary=call('/resumen?disasterId='+str(DISASTER));assert summary.status_code==200,(summary.status_code,summary.text[:150]);assert summary.json()['peopleCount']>=1
    assert call('/resumen?disasterId='+str(DISASTER),disasters=()).status_code==403
    assert call(method='POST',body={**body,'disasterId':MISSING},key=str(uuid.uuid4()),disasters=(MISSING,)).status_code==404
    concurrent=str(uuid.uuid4())
    with ThreadPoolExecutor(max_workers=2) as pool:responses=list(pool.map(lambda _:call(method='POST',body=body,key=concurrent),range(2)))
    assert all(r.status_code in (200,201,503) for r in responses)
    response=call(method='POST',body=body,key=concurrent);assert response.status_code==200
    assert len({r.json()['id'] for r in responses+[response] if r.status_code<300})==1
    print('PASS: PEO list/detail/create/update/summary, native contacts, idempotent creation identity, organization/disaster/record authorization, EVENT correlation and concurrent idempotency')

if '--recovery-only' in sys.argv:
    control=Path(os.environ['INTEGRATION_TEST_DROP_CONTROL'])
    original={'firstName':'ZIP-recovery-'+uuid.uuid4().hex[:10],'lastName':'Original'}
    first=call(method='POST',body=original,key=str(uuid.uuid4()));assert first.status_code==201
    person=first.json()['id'];identity=str(uuid.uuid4());changed={**original,'lastName':'Native committed before response loss'}
    control.write_text('drop-update')
    uncertain=call('/'+person,'PUT',changed,identity);assert uncertain.status_code==503,uncertain.status_code
    assert control.read_text()=='dropped'
    blocked=call('/'+person,'PUT',{**changed,'lastName':'Must not overwrite uncertain work'},str(uuid.uuid4()));assert blocked.status_code==409,blocked.status_code
    recovered=call('/'+person,'PUT',changed,identity);assert recovered.status_code==200,recovered.status_code
    assert recovered.json()['id']==person and recovered.json()['lastName']==changed['lastName']
    assert call('/'+person).json()['lastName']==changed['lastName']
    print('PASS actual Eden update response loss, durable target reservation, native operation-tag recovery and stable person identity')
