import requests
import json
import time
import sys

URL = "https://pcscan-backend-b1ka.onrender.com/api/search"

test_cases = [
    ("Ryzen 5 5600", "CPU"),
    ("i5-12400F", "CPU"),
    ("RTX 4060", "GPU"),
    ("MSI B550 Tomahawk", "MOTHERBOARD"),
    ("Samsung 990 Pro", "STORAGE"),
    ("Corsair Vengeance DDR5 6000", "RAM"),
    ("lemonade", "INVALID"),
    ("i7-13700H", "INVALID_LAPTOP"),
    ("monitor 27 inch", "INVALID_PERIPHERAL"),
]

valid_whitelist = {"CPU", "GPU", "MOTHERBOARD", "RAM", "STORAGE", "PSU", "COOLER", "CASE"}

print("=" * 70)
print(f"Render /api/search endpoint test -> {URL}")
print(f"Test cases: {len(test_cases)} (6 valid PC parts + 3 invalid control tests)")
print("=" * 70)
print()

# Wake-up call: Render cold start ~20s
print("[INFO] [1/2] Waking Render service (cold start may take 20-30s)...")
try:
    wake = requests.get("https://pcscan-backend-b1ka.onrender.com/api/health", timeout=45)
    print(f"   [OK] Health check: status_code={wake.status_code} body={wake.text[:120]}")
except Exception as e:
    print(f"   [WARN]  Health check timed out/error: {e}. Continuing anyway...")
time.sleep(2)
print()

all_passed = True
for idx, (query, expected_label) in enumerate(test_cases, 1):
    t0 = time.time()
    try:
        resp = requests.post(URL, json={"query": query}, timeout=60)
        dt_ms = int((time.time() - t0) * 1000)
        try:
            data = resp.json()
        except json.JSONDecodeError:
            print(f"[FAIL] [{idx}] '{query}' -> HTTP {resp.status_code} NON-JSON: {resp.text[:300]}")
            all_passed = False
            continue

        found = data.get("found", False)
        isValid = data.get("isValidPcPart", None)
        category = ""
        comp = data.get("component") or {}
        if isinstance(comp, dict):
            category = (comp.get("category") or "").upper().strip()
        rejection = data.get("rejectionReason") or data.get("formFactor") or ""
        msg = (data.get("message") or "")[:120]

        passed = False
        verdict_label = ""
        if expected_label in valid_whitelist:
            # Valid PC part: must have found=true AND isValidPcPart=true AND category in whitelist
            if found and (isValid is True or isValid is None) and category in valid_whitelist:
                passed = True
                verdict_label = f"[OK] PASS (category={category})"
            else:
                verdict_label = f"[FAIL] FAIL (expected VALID, got found={found} valid={isValid} cat='{category}' rej='{rejection}')"
                all_passed = False
        else:
            # Invalid control: must have explicit rejection OR isValidPcPart=false
            if isValid == False or rejection in ("not_pc_part", "laptop_part", "wrong_model"):
                passed = True
                verdict_label = f"[OK] PASS (correctly rejected rej='{rejection or isValid}')"
            else:
                verdict_label = f"[FAIL] FAIL (expected INVALID but no rejection found={found} valid={isValid} cat='{category}')"
                all_passed = False

        print(f"[{idx:02d}] '{query}' -> {verdict_label}")
        print(f"      HTTP {resp.status_code} in {dt_ms}ms | found={found} valid={isValid} cat='{category}' rej='{rejection}'")
        if not passed:
            print(f"      Full response snippet: {json.dumps(data, ensure_ascii=False)[:400]}")
        print()

    except Exception as e:
        dt_ms = int((time.time() - t0) * 1000)
        print(f"[FAIL] [{idx}] '{query}' -> EXCEPTION after {dt_ms}ms: {type(e).__name__}: {e}")
        all_passed = False
        print()

print("=" * 70)
if all_passed:
    print("[DONE] ALL TESTS PASSED!")
    sys.exit(0)
else:
    print("[WARN]  SOME TESTS FAILED - see details above")
    sys.exit(1)
