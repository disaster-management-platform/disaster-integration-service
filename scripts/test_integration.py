#!/usr/bin/env python3
"""
Test suite for Disaster Integration Service (Task 07B.1).
Validates:
1. Health checks (live & ready)
2. JWT authentication & authorization
3. Canonical DTO translation from real Eden responses
4. Query filtering & pagination
5. Error handling
"""

import os
import sys
import time
import json
import urllib.request
import urllib.error
import jwt

BASE_URL = os.environ["INTEGRATION_TEST_URL"].rstrip("/")
PRIVATE_KEY_PATH = os.environ["INTEGRATION_TEST_PRIVATE_KEY"]

with open(PRIVATE_KEY_PATH, "r") as f:
    PRIVATE_KEY = f.read()

def create_token(sub="user-123", org="org-abc", role="org.read", exp_offset=3600, iss=None, aud=None):
    now = int(time.time())
    payload = {
        "sub": sub,
        "organization_id": org,
        "role": [role] if isinstance(role, str) else role,
        "iss": iss or os.environ["JWT_ISSUER"],
        "aud": aud or os.environ["JWT_AUDIENCE"],
        "iat": now,
        "exp": now + exp_offset
    }
    return jwt.encode(payload, PRIVATE_KEY, algorithm="RS256")

def request(path, token=None, params=None):
    url = BASE_URL + path
    if params:
        query_str = urllib.parse.urlencode(params)
        url += ("&" if "?" in url else "?") + query_str

    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            body = resp.read().decode("utf-8")
            data = json.loads(body) if body else None
            return resp.status, data, dict(resp.headers)
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8")
        try:
            data = json.loads(body) if body else None
        except Exception:
            data = body
        return e.code, data, dict(e.headers)

def run_tests():
    passed = 0
    total = 0

    def assert_test(cond, name, detail=""):
        nonlocal passed, total
        total += 1
        if cond:
            print(f"  [PASS] {name}")
            passed += 1
        else:
            print(f"  [FAIL] {name} - {detail}")

    print("=== 1. Health Checks ===")
    status, data, _ = request("/integracion/diagnostico/live")
    assert_test(status == 200 and data.get("status") == "UP", "Liveness check returns 200 UP", f"{status} {data}")

    status, data, _ = request("/integracion/diagnostico/ready")
    assert_test(status == 200 and data.get("status") == "UP" and data.get("eden") == "REACHABLE",
                "Readiness probe verifies Eden connectivity", f"{status} {data}")

    print("\n=== 2. Security & JWT Validation ===")
    status, _, _ = request("/organizaciones")
    assert_test(status in (401, 403), "Rejects unauthenticated request", f"Status: {status}")

    # Invalid token (bad signature)
    bad_token = create_token()[:-10] + "corrupted0"
    status, _, _ = request("/organizaciones", token=bad_token)
    assert_test(status in (401, 403), "Rejects token with corrupted signature", f"Status: {status}")

    # Expired token
    expired_token = create_token(exp_offset=-3600)
    status, _, _ = request("/organizaciones", token=expired_token)
    assert_test(status in (401, 403), "Rejects expired token", f"Status: {status}")

    # Wrong audience
    wrong_aud_token = create_token(aud="wrong-service")
    status, _, _ = request("/organizaciones", token=wrong_aud_token)
    assert_test(status in (401, 403), "Rejects token with invalid audience", f"Status: {status}")

    # Wrong role
    wrong_role_token = create_token(role="random.role")
    status, _, _ = request("/organizaciones", token=wrong_role_token)
    assert_test(status == 403, "Rejects token with insufficient permissions", f"Status: {status}")

    # Valid token with org.read
    valid_token = create_token(role="org.read")
    status, data, headers = request("/organizaciones", token=valid_token)
    assert_test(status == 200, "Accepts valid token with org.read role", f"Status: {status}")

    print("\n=== 3. Canonical DTO & ACL Transformation ===")
    assert_test(isinstance(data, dict) and "items" in data, "Response is OrganizationPage envelope", f"{data}")
    items = data.get("items", [])
    assert_test(len(items) > 0, "Directory contains organization records from real Eden", f"Count: {len(items)}")

    if items:
        first = items[0]
        fields = ["id", "name", "typeId", "acronym", "country", "locationId", "phone", "email", "description"]
        all_fields_present = all(f in first for f in fields)
        assert_test(all_fields_present, "OrganizationDto contains all ORG-01 fields", f"Keys: {list(first.keys())}")
        assert_test(isinstance(first["id"], str) and first["id"], "ID is stable string identifier", f"ID: {first.get('id')}")
        assert_test(isinstance(first["name"], str) and first["name"], "Name is non-empty string", f"Name: {first.get('name')}")
        assert_test("pe_id" not in first and "pe_contact" not in first, "Does not leak internal Eden fields", "Clean DTO")

    print("\n=== 4. Filtering and Pagination ===")
    # Filter by name
    status, data_filtered, _ = request("/organizaciones", token=valid_token, params={"q": "final"})
    assert_test(status == 200 and len(data_filtered.get("items", [])) == 1,
                "Filters organizations by name (q=final)", f"Count: {len(data_filtered.get('items', []))}")
    if data_filtered.get("items"):
        assert_test("final" in data_filtered["items"][0]["name"].lower(),
                    "Filtered item name matches query", f"Name: {data_filtered['items'][0]['name']}")

    # Pagination: page 0, size 1
    status, data_p0, h0 = request("/organizaciones", token=valid_token, params={"pagina": 0, "tamanio": 1})
    assert_test(status == 200 and len(data_p0.get("items", [])) == 1,
                "Pagination returns exactly 1 item for size 1", f"Count: {len(data_p0.get('items', []))}")

    # Pagination: page 1, size 1
    status, data_p1, h1 = request("/organizaciones", token=valid_token, params={"pagina": 1, "tamanio": 1})
    assert_test(status == 200 and len(data_p1.get("items", [])) == 1,
                "Pagination returns next item on page 1", f"Count: {len(data_p1.get('items', []))}")

    if data_p0.get("items") and data_p1.get("items"):
        id0 = data_p0["items"][0]["id"]
        id1 = data_p1["items"][0]["id"]
        assert_test(id0 != id1, "Different pages return different organizations", f"Page0 ID: {id0}, Page1 ID: {id1}")

    print(f"\n==========================================")
    print(f"Results: {passed}/{total} tests passed.")
    print(f"==========================================")
    return passed == total

def targeted():
    token=create_token()
    status,first,headers=request("/organizaciones",token, {"pagina":0,"tamanio":1})
    assert status==200 and len(first["items"])==1 and first["total"] is None and first["hasMore"] and "X-Total-Count" not in headers,(status,first)
    status,second,_=request("/organizaciones",token,{"pagina":1,"tamanio":1})
    assert status==200 and len(second["items"])==1 and not second["hasMore"] and first["items"][0]["id"]!=second["items"][0]["id"],(status,second)
    assert all(row["locationId"] is None and row["typeIds"]==[] for row in first["items"]+second["items"])
    for params in ({"pagina":-1},{"tamanio":0},{"tamanio":101},{"pagina":2147483647},{"q":"x"*201}):assert request("/organizaciones",token,params)[0]==400
    assert request("/organizaciones",create_token(aud="wrong"))[0]==401
    assert request("/organizaciones",create_token(role="wrong"))[0]==403
    assert request("/organizaciones",create_token(org=""))[0]==403
    print("PASS: real Eden translation, two pages and final page, nullable total, invalid parameters and JWT configuration")

def faults():
    from pathlib import Path
    mode=Path(os.environ["INTEGRATION_TEST_FAULT_CONTROL"])
    token=create_token()
    try:
        for state,expected in [("bad-auth",503),("down",503),("redirect",502),("invalid",502),("large",502),("slow",504)]:
            mode.write_text(state)
            assert request("/integracion/diagnostico/ready")[0]==503,state
            status,_,_=request("/organizaciones",token)
            assert status==expected,(state,status)
            assert request("/integracion/diagnostico/live")[0]==200
        mode.write_text("fixture")
        status,value,_=request("/organizaciones",token)
        assert status==200,(status,value)
        item=value["items"][0]
        assert item["typeIds"]==["3","7"] and item["typeId"] is None and item["locationId"]=="45",item
        mode.write_text("real")
        assert request("/integracion/diagnostico/ready")[0]==200
        assert request("/organizaciones",token)[0]==200
        print("PASS: invalid technical credentials, outage/recovery without restart, redirects, malformed/excessive/slow bodies, liveness independence; multiple-type/geographic translation fixture")
    finally:mode.write_text("real")

if __name__ == "__main__":
    if "--targeted" in sys.argv:targeted()
    elif "--faults" in sys.argv:faults()
    else:sys.exit(0 if run_tests() else 1)
