"""Combined: Start server if not up, hit /api/health, hit /api/build-recommendation, print full JSON output."""
import sys, os, json, time, subprocess, threading
PORT = 8000
BASE = f"http://127.0.0.1:{PORT}"
BACKEND_DIR = os.path.dirname(os.path.abspath(__file__))

try:
    import requests
except ImportError:
    subprocess.check_call([sys.executable, "-m", "pip", "install", "requests==2.32.3", "--no-warn-script-location"])
    import requests

def is_server_up(timeout_s: int = 3) -> bool:
    try:
        r = requests.get(f"{BASE}/api/health", timeout=timeout_s)
        return r.status_code == 200
    except Exception:
        return False

if not is_server_up():
    print("[BOOT] Server not up. Starting uvicorn subprocess in background.")
    env = os.environ.copy()
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    proc = subprocess.Popen(
        [sys.executable, "-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", str(PORT), "--log-level", "error"],
        cwd=BACKEND_DIR,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        env=env,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0,
    )
    # Wait up to 25s for boot
    up = False
    for _ in range(25):
        time.sleep(1)
        if is_server_up(2):
            up = True
            break
    if not up:
        print(f"[BOOT FAIL] Server did NOT come up after 25s. Try running it manually: cd backend ; uvicorn main:app --host 0.0.0.0 --port 8000")
        sys.exit(1)
    print(f"[BOOT OK] Server started in background (pid={proc.pid}) and responding to /api/health")
else:
    print("[BOOT OK] Server already running, responding to /api/health")

try:
    hr = requests.get(f"{BASE}/api/health", timeout=10)
    print(f"[HEALTH] HTTP {hr.status_code}: {hr.text[:180]}")
except Exception as e:
    print(f"[HEALTH FAIL] {e}")
    sys.exit(2)

# Now the actual build recommendation test
payload = {"component_name": "Ryzen 5 5600", "component_category": "cpu"}
print()
print("=" * 90)
print(f"[TEST BUILD-RECO] POST {BASE}/api/build-recommendation")
print(f"[TEST BUILD-RECO] Request payload: {json.dumps(payload, indent=2)}")
print("=" * 90)
t0 = time.time()
try:
    r = requests.post(f"{BASE}/api/build-recommendation", json=payload, timeout=180)
    dt_ms = int((time.time() - t0) * 1000)
    print(f"\n[TEST] HTTP {r.status_code} | total={dt_ms}ms")
    if r.status_code != 200:
        print(f"[TEST FAIL] HTTP != 200. Body:")
        try:
            print(r.text[:4000])
        except Exception:
            pass
        sys.exit(3)
    data = r.json()
    print(f"[TEST OK] JSON parsed. Top keys: {sorted(list(data.keys()))}")
    print(f"[TEST] success={data.get('success')}")
    print(f"[TEST] anchor_component = {data.get('anchor_component')}")
    print(f"[TEST] anchor_category  = {data.get('anchor_category')}")
    print(f"[TEST] processing_time_ms = {data.get('processing_time_ms')} | total wall ms={dt_ms}")
    if data.get("error_message"):
        print(f"[TEST] ERROR_MSG from backend: {data.get('error_message')}")
    print()
    # Show each tier
    def short(s, n=55):
        if not s: return "NULL"
        s = str(s)
        return s if len(s) <= n else (s[:n] + "...")
    for tk, tn in [("entry", "ENTRY"), ("mid", "MIDRANGE"), ("high", "HIGH-END")]:
        t = data.get(tk)
        if not t:
            print(f"  !!! TIER {tn}: NULL")
            continue
        print(f"  ===== TIER {tn} =====")
        print(f"  CPU         = {short(t.get('cpu'))}")
        print(f"  Motherboard = {short(t.get('motherboard'))}")
        print(f"  RAM         = {short(t.get('ram'))}")
        print(f"  Storage     = {short(t.get('storage'))}")
        print(f"  GPU NVIDIA  = {short(t.get('gpu_nvidia'))}")
        print(f"  GPU AMD     = {short(t.get('gpu_amd'))}")
        print(f"  PSU         = {short(t.get('psu'))}")
        print(f"  Est. Watts  = {t.get('estimated_wattage')}W")
        print(f"  Bottleneck  = {t.get('bottleneck_pct')}%")
        anchor_match = str(t.get('cpu')).lower() == str(data.get('anchor_component')).lower()
        marker_anchor = "[OK]" if anchor_match else "[FAIL] FAIL"
        print(f"  {marker_anchor} CPU == anchor (never replaced)? {anchor_match}")
        print(f"  GPU names contain 'Generic'? (Generic = deterministic fallback, AI didn't pick)")
        gnv, gamd = (t.get('gpu_nvidia') or ""), (t.get('gpu_amd') or "")
        is_ai = (not "Generic" in gnv) and (not "Generic" in gamd) and (not "Generic" in (t.get('motherboard') or "")) and (not "Generic" in (t.get('ram') or "")) and (not "Generic" in (t.get('psu') or "")) and (not "Generic" in (t.get('storage') or ""))
        marker_ai = "[OK] = AI ACTUALLY PICKED REAL MODEL NAMES!" if is_ai else "[WARN]  = GENERIC/DETERMINISTIC FALLBACK (AI didn't run or invent names not in DB, backend swapped for known parts)"
        print(f"  {marker_ai}")
        print()
    # Final verdict
    any_ai = False
    for tk in ("entry","mid","high"):
        t = data.get(tk) or {}
        gnv = (t.get('gpu_nvidia') or "")
        if gnv and "Generic" not in gnv:
            any_ai = True
    print("[FINAL VERDICT]:", "AI SUCCESS = actual model names selected by OpenAI from the provided list!" if any_ai else "FALLBACK = All Generic prefix names - AI call or parse failed (check API keys validity / internet connection on backend host)")
except Exception as e:
    print(f"[TEST FAIL] {type(e).__name__}: {e}")
    import traceback; traceback.print_exc()
    sys.exit(4)
