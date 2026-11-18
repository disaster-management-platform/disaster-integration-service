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

import sys
import time
import json
import urllib.request
import urllib.error
import jwt

BASE_URL = "http://127.0.0.1:8180/api"
PRIVATE_KEY_PATH = "/tmp/jwt-keys/private.pem"

with open(PRIVATE_KEY_PATH, "r") as f:
    PRIVATE_KEY = f.read()

def create_token(sub="user-123", org="org-abc", role="org.read", exp_offset=3600, iss="test-issuer", aud="integration-service"):
    now = int(time.time())
    payload = {
        "sub": sub,
        "organization_id": org,
        "role": [role] if isinstance(role, str) else role,
        "iss": iss,
        "aud": aud,
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
        with urllib.request.urlopen(req, timeout=10) as resp:
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

if __name__ == "__main__":
    success = run_tests()
    sys.exit(0 if success else 1)
