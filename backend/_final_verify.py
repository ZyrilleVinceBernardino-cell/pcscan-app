import os, sys, traceback
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

log = []
def ok(x, msg): log.append(f"  [OK] {msg}") if x else log.append(f"  [FAIL] {msg}")
all_ok = True

log.append("=== FINAL IMPORT & USAGE VERIFICATION ===")
log.append("")

# 1. requirements-like check (top-level imports)
log.append("[1] Third-party packages:")
try:
    import fastapi; ok(True, f"fastapi {fastapi.__version__}")
except Exception as e: all_ok=False; log.append(f"  [FAIL] fastapi: {e}")
try:
    import uvicorn; ok(True, f"uvicorn {uvicorn.__version__}")
except Exception as e: all_ok=False; log.append(f"  [FAIL] uvicorn: {e}")
try:
    import pydantic; ok(True, f"pydantic {pydantic.__version__}")
except Exception as e: all_ok=False; log.append(f"  [FAIL] pydantic: {e}")
try:
    import httpx; ok(True, f"httpx {httpx.__version__}")
except Exception as e: all_ok=False; log.append(f"  [FAIL] httpx: {e}")
try:
    import google.generativeai as genai; ok(True, f"google-generativeai (import OK)")
except Exception as e: all_ok=False; log.append(f"  [FAIL] google-generativeai: {e}")
try:
    from openai import OpenAI; ok(True, f"openai SDK (import OK)")
except Exception as e: all_ok=False; log.append(f"  [FAIL] openai: {e}")
try:
    import PIL; ok(True, f"Pillow {PIL.__version__}")
except Exception as e: all_ok=False; log.append(f"  [FAIL] pillow: {e}")
try:
    import dotenv; ok(True, f"python-dotenv {dotenv.__version__}")
except Exception as e: all_ok=False; log.append(f"  [FAIL] python-dotenv: {e}")

# 2. Project structure files exist
log.append("")
log.append("[2] Project file / folder structure:")
def check(p, is_dir=False):
    full = os.path.join(os.path.dirname(os.path.abspath(__file__)), p)
    exists = (os.path.isdir if is_dir else os.path.isfile)(full)
    return exists
for p in [("requirements.txt", False), (".env", False), (".env.example", False),
          ("main.py", False), ("app", True), ("app/config.py", False),
          ("app/models/schemas.py", False), ("app/services/gemini_service.py", False),
          ("app/services/openai_service.py", False), ("app/services/mock_engine.py", False),
          ("app/services/__init__.py", False), ("app/models/__init__.py", False),
          ("app/__init__.py", False)]:
    x = check(p[0], p[1])
    ok(x, f"{'DIR ' if p[1] else 'FILE'} {p[0]}")
    if not x: all_ok = False

# 3. Schema instantiation
log.append("")
log.append("[3] Pydantic model sanity (can instantiate + dump):")
try:
    from app.models.schemas import (HealthResponse, ComponentInfo, OCRScanResult,
        CompatibilityReport, WattsCalculatorResult, BottleneckAnalysis,
        UpgradePathResult, BuildRecommendationResult, AnalysisResponse,
        ScanAndAnalyzeResponse, AnalyzeTextResponse, HardwareSpecs)
    hr = HealthResponse(status="ok", timestamp="2026-01-01T00:00:00+00:00",
                        gemini_enabled=True, openai_enabled=True)
    ok(True, f"HealthResponse dump OK ({len(hr.model_dump_json())} bytes)")
    sc = ScanAndAnalyzeResponse(success=True, total_processing_time_ms=0, source="mock")
    ok(True, f"ScanAndAnalyzeResponse dump OK ({len(sc.model_dump_json())} bytes)")
    at = AnalyzeTextResponse(success=True, query="test", total_processing_time_ms=0, source="mock")
    ok(True, f"AnalyzeTextResponse dump OK ({len(at.model_dump_json())} bytes)")
    hw = HardwareSpecs()
    ar = AnalysisResponse(success=True, processing_time_ms=0,
                          compatibility=CompatibilityReport(is_compatible=True, overall_score=100, issues=[], summary="x"),
                          watts_calculator=WattsCalculatorResult(total_system_watts=100, breakdown=[], recommended_psu_watts=550, psu_efficiency_tip="x"),
                          bottleneck=BottleneckAnalysis(has_bottleneck=False, description="x", affected_tasks=[]),
                          build_recommendations=None)
    ok(True, f"AnalysisResponse dump OK ({len(ar.model_dump_json())} bytes)")
except Exception as e:
    all_ok = False
    log.append(f"  [FAIL] Pydantic: {e}")
    traceback.print_exc()

# 4. Services
log.append("")
log.append("[4] Services instantiation:")
try:
    from app.services.gemini_service import GeminiService
    g = GeminiService(mock_mode=True)
    ok(True, f"GeminiService mock is_ready={g.is_ready}, model={g.model_name}")
except Exception as e:
    all_ok = False
    log.append(f"  [FAIL] GeminiService: {e}")
    traceback.print_exc()
try:
    from app.services.openai_service import OpenAIService
    o = OpenAIService(mock_mode=True)
    ok(True, f"OpenAIService mock is_ready={o.is_ready}, model={o.model_name}")
except Exception as e:
    all_ok = False
    log.append(f"  [FAIL] OpenAIService: {e}")
    traceback.print_exc()

# 5. main.py + endpoints
log.append("")
log.append("[5] main.py app + endpoints:")
try:
    from main import app, gemini_service, openai_service
    routes = sorted([(list(r.methods)[0] if r.methods else "ANY") + " " + r.path
                     for r in app.routes if hasattr(r, "path") and not r.path.startswith("/openapi")
                     and not r.path.startswith("/docs/oauth2-redirect")
                     and r.path not in ("/docs","/redoc","/openapi.json")])
    ok(True, f"FastAPI app created")
    ok(True, f"Gemini (real) ready = {gemini_service.is_ready} ({gemini_service.model_name})")
    ok(True, f"OpenAI (real) ready = {openai_service.is_ready} ({openai_service.model_name})")
    ok(True, f"Endpoints loaded ({len(routes)}):")
    for r in routes: log.append(f"    - {r}")
except Exception as e:
    all_ok = False
    log.append(f"  [FAIL] main.py: {e}")
    traceback.print_exc()

# 6. End-to-end (TestClient) — no real calls, just verify wiring
log.append("")
log.append("[6] Endpoint wiring (FastAPI TestClient health + empty text validation):")
try:
    from fastapi.testclient import TestClient
    c = TestClient(app)
    h = c.get("/api/health").json()
    ok(h["status"]=="ok", f"/api/health status={h['status']} gemini={h['gemini_enabled']} openai={h['openai_enabled']}")
    r = c.post("/api/analyze-text", json={"text":""})
    ok(r.status_code==422, f"empty analyze-text returns {r.status_code} (expected 422)")
except Exception as e:
    all_ok = False
    log.append(f"  [FAIL] End-to-end: {e}")
    traceback.print_exc()

log.append("")
log.append(f"===== OVERALL: {'ALL SYSTEMS READY 🟢' if all_ok else 'ISSUES FOUND 🔴'} =====")
with open("final_verification.txt","w",encoding="utf-8") as f:
    f.write("\n".join(log))
print("\n".join(log))
