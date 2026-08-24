"""Quick test script for /api/build-recommendation endpoint.
Tests with component: Ryzen 5 5600. Prints actual JSON response returned by backend
so we can see if OpenAI was the source of component picks or the deterministic fallback.
"""
import sys
import json
import time
import requests  # pip install requests

BASE_URL = "http://127.0.0.1:8000"
ENDPOINT = f"{BASE_URL}/api/build-recommendation"

COMPONENT = "Ryzen 5 5600"
CATEGORY = "cpu"

req_payload = {
    "component_name": COMPONENT,
    "component_category": CATEGORY
}

print("=" * 80)
print(f"[TEST] POST {ENDPOINT}")
print(f"[TEST] payload: {json.dumps(req_payload, indent=2)}")
print("=" * 80)

t0 = time.time()
try:
    resp = requests.post(ENDPOINT, json=req_payload, timeout=120)
    dt = time.time() - t0
    print(f"[TEST] HTTP {resp.status_code} | elapsed={dt*1000:.0f}ms")
    if resp.status_code != 200:
        print(f"[TEST] ERROR: status != 200")
        try:
            print(resp.text)
        except Exception:
            pass
        sys.exit(1)
    data = resp.json()
    print(f"[TEST] JSON parsed OK. Top-level keys: {sorted(list(data.keys()))}")
    print(f"[TEST] success = {data.get('success')}")
    print(f"[TEST] anchor_component = {data.get('anchor_component')}")
    print(f"[TEST] anchor_category  = {data.get('anchor_category')}")
    print(f"[TEST] processing_time_ms = {data.get('processing_time_ms')}")
    if data.get("error_message"):
        print(f"[TEST] ERROR MESSAGE (from backend): {data.get('error_message')}")
    print()
    for tier_key, tier_name in [("entry","ENTRY"), ("mid","MIDRANGE"), ("high","HIGH-END")]:
        tier = data.get(tier_key)
        if not tier:
            print(f"  ❌ TIER {tier_name} ({tier_key}): NULL!")
            continue
        print(f"  ====== TIER {tier_name} ======")
        cpu = tier.get("cpu")
        mb = tier.get("motherboard")
        ram = tier.get("ram")
        sto = tier.get("storage")
        gpu_nv = tier.get("gpu_nvidia")
        gpu_amd = tier.get("gpu_amd")
        psu = tier.get("psu")
        w = tier.get("estimated_wattage")
        b = tier.get("bottleneck_pct")
        print(f"  CPU         = {cpu}")
        print(f"  Motherboard = {mb}")
        print(f"  RAM         = {ram}")
        print(f"  Storage     = {sto}")
        print(f"  GPU NVIDIA  = {gpu_nv}")
        print(f"  GPU AMD     = {gpu_amd}")
        print(f"  PSU         = {psu}")
        print(f"  Est. Watts  = {w}W")
        print(f"  Bottleneck  = {b}%")
        anchor_match = (cpu == data.get("anchor_component"))
        print(f"  ✅ CPU matches anchor (never replaced)? {anchor_match}")
        if not anchor_match:
            print(f"     ❌ FAIL: CPU expected={data.get('anchor_component')} but got={cpu}!")
        print()
    print("[TEST] Done. If component names look sensible (B550M for MB, RTX 3060 / RX 6600 for entry GPU, NOT generic 'Generic B550M Motherboard'), then OpenAI ACTUALLY PICKED them!")
    print("[TEST] If ALL names are like 'Generic B550M Motherboard' / 'Generic 16GB DDR4', that means deterministic fallback ran because AI call failed (check .env keys?)")
except requests.exceptions.ConnectionError:
    print("[TEST] ❌ ConnectionError: BACKEND SERVER IS NOT RUNNING!")
    print("[TEST]    Start it with:  cd backend ; uvicorn main:app --host 0.0.0.0 --port 8000")
    sys.exit(2)
except Exception as e:
    print(f"[TEST] ❌ Exception: {type(e).__name__}: {e}")
    import traceback; traceback.print_exc()
    sys.exit(3)
