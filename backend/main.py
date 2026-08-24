import os
import sys
import json
import time
import re
from datetime import datetime, timezone
from typing import Optional
from contextlib import asynccontextmanager

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from dotenv import load_dotenv
load_dotenv()

from fastapi import FastAPI, File, UploadFile, Form, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from pydantic import ValidationError

from app.config import settings
from app.models.schemas import (
    HealthResponse,
    AnalyzeTextRequest,
    AnalyzeTextResponse,
    ScanAndAnalyzeResponse,
    OCRScanResult,
    AnalysisResponse,
    UserInfo,
    CurrentBuild,
    IdentifiedComponent,
    HardwareSpecs,
    ComponentInfo,
    CPUSpec,
    GPUSpec,
    MotherboardSpec,
    RAMSpec,
    StorageSpec,
    PSUSpec,
    CoolerSpec,
    CaseSpec,
    SearchRequest,
    SearchResponse,
    SearchFoundComponent,
    BuildRecoRequest,
    BuildRecoResponse,
    BuildRecoTierBundle,
)
from app.services.gemini_service import GeminiService
from app.services.openai_service import OpenAIService
from app.services.component_database import (
    CPU_DATABASE, GPU_DATABASE, MOTHERBOARD_DATABASE,
    RAM_DATABASE, STORAGE_DATABASE, PSU_DATABASE,
    CPUDatabaseEntry, GPUDatabaseEntry, MotherboardDatabaseEntry,
    RAMDatabaseEntry, StorageDatabaseEntry, PSUDatabaseEntry,
)


# =============================================================
# SPEED OPTIMIZATION #1: KNOWN COMPONENT PRE-IDENTIFY MAP
# For common mainstream desktop parts (CPU/GPU in component_database),
# we SKIP the OpenAI identify call entirely (1000ms → ~0.1ms per search).
# Key = fully normalized query (lowercase, strip non-alphanumerics).
# Value = identity dict with exact 7-key shape _run_exact_search_identify returns.
# =============================================================
def _norm_key(q: str) -> str:
    return re.sub(r"[^a-z0-9]", "", (q or "").strip().lower())


def _build_known_component_identify_map() -> dict:
    m: dict = {}
    # --- CPU ---
    for key, entry in CPU_DATABASE.items():
        canonical = f"{entry.brand} {entry.model}"
        identity = {
            "category": "CPU",
            "brand": entry.brand,
            "model": entry.model,
            "canonical_name": canonical,
            "confidence": 0.98,
            "form_factor": "desktop",
            "is_pc_part": True,
        }
        # Register multiple normalized lookup keys so users don't need exact spacing/brand/formatting
        keys_to_add = [
            entry.model,                # "Ryzen 5 5600"
            key,                        # "Ryzen 5 5600" (often matches entry.model)
            canonical,                  # "AMD Ryzen 5 5600"
            f"{entry.brand} {key}",     # brand + short key
        ]
        # Add "i3 12100F" for "Core i3-12100F" style (Intel Core prefix stripped)
        if entry.brand == "Intel" and "Core " in entry.model:
            short = entry.model.replace("Core ", "", 1).strip()
            keys_to_add.append(short)
            keys_to_add.append(f"{entry.brand} {short}")
        for k in keys_to_add:
            nk = _norm_key(k)
            if nk and nk not in m:
                m[nk] = identity
    # --- GPU ---
    for key, entry in GPU_DATABASE.items():
        canonical = f"{entry.brand} {entry.model}"
        identity = {
            "category": "GPU",
            "brand": entry.brand,
            "model": entry.model,
            "canonical_name": canonical,
            "confidence": 0.98,
            "form_factor": "desktop",
            "is_pc_part": True,
        }
        keys_to_add = [
            entry.model,
            key,
            canonical,
            f"{entry.brand} {key}",
        ]
        # Strip "GeForce" / "Radeon" prefix variants (users type "RTX 4060" not always "NVIDIA GeForce RTX 4060")
        for prefix in ("NVIDIA GeForce ", "NVIDIA ", "AMD Radeon ", "AMD "):
            if entry.model.startswith(prefix):
                keys_to_add.append(entry.model[len(prefix):].strip())
        for k in keys_to_add:
            nk = _norm_key(k)
            if nk and nk not in m:
                m[nk] = identity
    return m


KNOWN_COMPONENT_IDENTIFY_MAP: dict = _build_known_component_identify_map()
print(f"[BOOT] SPEED: Built KNOWN_COMPONENT_IDENTIFY_MAP with {len(KNOWN_COMPONENT_IDENTIFY_MAP)} normalized lookup keys (CPU + GPU known parts → SKIP OpenAI identify!)")


# =============================================================
# SPEED OPTIMIZATION #2: TTL MEMOIZATION CACHE (30 minutes)
# If user searches the EXACT same query within 30 minutes, return
# the previous identify dict WITHOUT any OpenAI/Gemini call at all.
# Uses (time_created, result) tuple; TTL = 1800s (30 min).
# =============================================================
_IDENTIFY_TTL_SECONDS = 30 * 60
_IDENTIFY_CACHE_MAX = 512
_identify_cache: dict = {}


def _cached_identify_lookup(query_text: str):
    global _identify_cache
    key = _norm_key(query_text)
    if not key:
        return None
    entry = _identify_cache.get(key)
    if entry is None:
        return None
    (ts, cached_result) = entry
    if (time.time() - ts) > _IDENTIFY_TTL_SECONDS:
        del _identify_cache[key]
        return None
    print(f"[SEARCH] [DIAG] ✨ IDENTIFY CACHE HIT (TTL {int(_IDENTIFY_TTL_SECONDS/60)}min) for query={query_text!r}")
    return cached_result


def _cached_identify_store(query_text: str, result_tuple):
    global _identify_cache
    if not result_tuple or not result_tuple[0]:
        return
    key = _norm_key(query_text)
    if not key:
        return
    # Simple LRU: if cache full, drop 25% oldest (by insertion order which Python dict preserves)
    if len(_identify_cache) >= _IDENTIFY_CACHE_MAX:
        to_drop = max(1, _IDENTIFY_CACHE_MAX // 4)
        for _ in range(to_drop):
            try:
                _identify_cache.pop(next(iter(_identify_cache)))
            except StopIteration:
                break
    _identify_cache[key] = (time.time(), result_tuple)


# =============================================================
# SPEED OPTIMIZATION #5: COMPONENT DB LOOKUP LRU CACHE (1h TTL)
# Skip repeated Firestore/local query for same canonical.
# =============================================================
_COMPONENT_DB_LOOKUP_TTL_SECONDS = 60 * 60
_COMPONENT_DB_CACHE_MAX = 256
_component_db_cache: dict = {}


def _cached_component_lookup(identity: dict):
    global _component_db_cache
    canonical = _norm_key(identity.get("canonical_name") or identity.get("model") or "")
    if not canonical:
        return None, ("__no_key__",)
    entry = _component_db_cache.get(canonical)
    if entry is None:
        return None, None
    (ts, cached_val) = entry
    if (time.time() - ts) > _COMPONENT_DB_LOOKUP_TTL_SECONDS:
        del _component_db_cache[canonical]
        return None, None
    return cached_val, None


def _cached_component_store(identity: dict, value):
    global _component_db_cache
    canonical = _norm_key(identity.get("canonical_name") or identity.get("model") or "")
    if not canonical:
        return
    if len(_component_db_cache) >= _COMPONENT_DB_CACHE_MAX:
        to_drop = max(1, _COMPONENT_DB_CACHE_MAX // 4)
        for _ in range(to_drop):
            try:
                _component_db_cache.pop(next(iter(_component_db_cache)))
            except StopIteration:
                break
    _component_db_cache[canonical] = (time.time(), value)


# =============================================================
# Service Singletons (one-time init, reused across requests)
# =============================================================
def _env_bool(name: str, default: bool) -> bool:
    v = os.getenv(name)
    if v is None:
        return default
    return v.strip().lower() in ("1", "true", "yes", "on")

_MOCK_MODE = _env_bool("MOCK_MODE", False)
_AUTO_FALLBACK = _env_bool("AUTO_FALLBACK", True)

gemini_service = GeminiService(
    api_key=os.getenv("GOOGLE_API_KEY"),
    model=os.getenv("GEMINI_MODEL", "gemini-1.5-flash"),
    temperature=0.0,
    max_tokens=1024,
    mock_mode=_MOCK_MODE,
    auto_fallback=_AUTO_FALLBACK,
)

openai_service = OpenAIService(
    api_key=os.getenv("OPENAI_API_KEY"),
    model=os.getenv("OPENAI_MODEL", "gpt-4o-mini"),
    temperature=0.0,
    max_tokens=1024,
    org_id=os.getenv("OPENAI_ORG_ID") or None,
    mock_mode=_MOCK_MODE,
    auto_fallback=_AUTO_FALLBACK,
)


# =============================================================
# Firebase Admin Firestore (LAZY init — only if admin SDK available + env configured)
# Serves as the PRIMARY exact-match source for the components collection.
# Falls back to local component_database.py if admin SDK unavailable / unconfigured.
# =============================================================
_FIRESTORE_CLIENT_CACHE: dict = {"db": None, "tried": False}


def _get_firebase_firestore():
    """Lazy-initialize Firebase Admin SDK firestore client.
    Returns client instance or None if not available.
    NEVER raises — returns None on failure so local DB fallback always runs.
    Expects: google-cloud-firestore + firebase-admin installed + either:
      a) GOOGLE_APPLICATION_CREDENTIALS env var pointing to service account JSON,
      b) FIREBASE_PRIVATE_KEY/related set via env (firebase_admin.initialize_app from_dict), or
      c) Application Default Credentials.
    """
    if _FIRESTORE_CLIENT_CACHE["tried"]:
        return _FIRESTORE_CLIENT_CACHE["db"]
    _FIRESTORE_CLIENT_CACHE["tried"] = True
    try:
        import firebase_admin
        from firebase_admin import credentials, firestore  # type: ignore
        global FieldFilter
        from google.cloud.firestore_v1.base_query import FieldFilter  # type: ignore
    except Exception:
        # firebase-admin or google-cloud-firestore not installed → skip
        try:
            from google.cloud.firestore_v1.base_query import FieldFilter  # type: ignore
        except Exception:
            # stub FieldFilter so lookups don't NameError when Firestore unavailable
            class FieldFilter:  # type: ignore[no-redef]
                def __init__(self, *a, **kw):
                    pass
        return None

    try:
        try:
            default_app = firebase_admin.get_app()
        except Exception:
            default_app = None

        if default_app is None:
            creds_path = os.getenv("GOOGLE_APPLICATION_CREDENTIALS")
            fb_private_key = (os.getenv("FIREBASE_PRIVATE_KEY") or "").strip()
            fb_project_id = (os.getenv("FIREBASE_PROJECT_ID") or "").strip()
            fb_client_email = (os.getenv("FIREBASE_CLIENT_EMAIL") or "").strip()
            if creds_path and os.path.isfile(creds_path):
                cred = credentials.Certificate(creds_path)
                firebase_admin.initialize_app(cred, {"projectId": fb_project_id} if fb_project_id else None)
            elif fb_private_key and fb_project_id and fb_client_email:
                cert_dict = {
                    "type": "service_account",
                    "project_id": fb_project_id,
                    "private_key": fb_private_key.replace("\\n", "\n"),
                    "client_email": fb_client_email,
                    "token_uri": "https://oauth2.googleapis.com/token",
                }
                cred = credentials.Certificate(cert_dict)
                firebase_admin.initialize_app(cred)
            else:
                # No credentials → treat Firestore as unavailable
                return None
        db = firestore.client()
        _FIRESTORE_CLIENT_CACHE["db"] = db
        print("[SEARCH] [DIAG] FIREBASE ADMIN FIRESTORE CLIENT INITIALIZED OK")
        return db
    except Exception as fe:
        print(f"[SEARCH] [DIAG] FIREBASE ADMIN INIT SKIPPED {type(fe).__name__}: {fe}")
        return None


try:
    from google.cloud.firestore_v1.base_query import FieldFilter  # noqa: F401  (for where() queries)
except Exception:
    class FieldFilter:  # type: ignore[no-redef]
        def __init__(self, *a, **kw):
            pass


# =============================================================
# Lifespan (startup / shutdown)
# =============================================================
@asynccontextmanager
async def lifespan(app: FastAPI):
    print(f"[startup] PCScan Backend initializing...")
    print(f"[startup] MOCK_MODE = {_MOCK_MODE} | AUTO_FALLBACK = {_AUTO_FALLBACK}")
    print(f"[startup] Gemini ready = {gemini_service.is_ready} (model={gemini_service.model_name})")
    print(f"[startup] OpenAI ready = {openai_service.is_ready} (model={openai_service.model_name})")
    yield
    print("[shutdown] PCScan Backend shutting down...")


# =============================================================
# FastAPI App
# =============================================================
app = FastAPI(
    title="PCScan Backend API",
    description=(
        "PC Hardware Scan & Build Recommendation Engine. "
        "FastAPI backend with Google Gemini 1.5 Flash (OCR + component identification) "
        "and OpenAI GPT-4o Mini (compatibility, watts, bottleneck, upgrade path, tiered builds)."
    ),
    version="1.0.0",
    docs_url="/docs",
    redoc_url="/redoc",
    openapi_url="/openapi.json",
    lifespan=lifespan,
)


# =============================================================
# CORS Middleware (for Android App + Emulator 10.0.2.2:8000)
# =============================================================
cors_origins = [origin.strip() for origin in settings.CORS_ORIGINS.split(",") if origin.strip()]
if "*" in cors_origins:
    cors_origins = ["*"]

app.add_middleware(
    CORSMiddleware,
    allow_origins=cors_origins,
    allow_credentials=settings.CORS_ALLOW_CREDENTIALS,
    allow_methods=[m.strip() for m in settings.CORS_ALLOW_METHODS.split(",")],
    allow_headers=["*"] if settings.CORS_ALLOW_HEADERS == "*" else [h.strip() for h in settings.CORS_ALLOW_HEADERS.split(",")],
)


# =============================================================
# Helpers
# =============================================================
ALLOWED_IMAGE_MIMES = {"image/jpeg", "image/png", "image/webp", "image/jpg"}
_MAX_UPLOAD_BYTES = int(os.getenv("MAX_UPLOAD_SIZE_MB", "10")) * 1024 * 1024


def _utc_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _detect_source() -> str:
    return "mock" if (_MOCK_MODE or (not gemini_service._configured and not openai_service._configured)) else "real"


def _parse_json_field(raw: Optional[str], field_name: str):
    """Safely parse optional JSON Form fields (user_info, current_build) at scan-and-analyze endpoint."""
    if not raw:
        return None
    try:
        return json.loads(raw)
    except (json.JSONDecodeError, TypeError) as e:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Invalid JSON in form field '{field_name}': {e}"
        )


def _build_models_from_json(user_info_json, current_build_json):
    user_info: Optional[UserInfo] = None
    current_build: Optional[CurrentBuild] = None
    try:
        if user_info_json is not None:
            user_info = UserInfo.model_validate(user_info_json)
        if current_build_json is not None:
            current_build = CurrentBuild.model_validate(current_build_json)
    except ValidationError as e:
        first_err = e.errors()[0] if e.errors() else {}
        loc = ".".join(str(x) for x in first_err.get("loc", [])) or "payload"
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Invalid payload: {loc} — {first_err.get('msg','validation error')}"
        )
    return user_info, current_build


def _safe_parse_json(text: str) -> Optional[dict]:
    """Safely extract JSON object from LLM response (handles markdown fences)."""
    text = text.strip()
    if text.startswith("```json"):
        text = text[7:]
    if text.startswith("```"):
        text = text[3:]
    if text.endswith("```"):
        text = text[:-3]
    text = text.strip()
    try:
        return json.loads(text)
    except Exception:
        pass
    start = text.find("{")
    end = text.rfind("}")
    if start >= 0 and end > start:
        try:
            return json.loads(text[start:end + 1])
        except Exception:
            return None
    return None


def _build_specs_from_dict(data: dict) -> Optional[HardwareSpecs]:
    """Build HardwareSpecs Pydantic object from raw nested dict (skipping empty/null fields)."""
    if not data:
        return None
    hs = HardwareSpecs()
    if data.get("cpu") and data["cpu"].get("name"):
        hs.cpu = CPUSpec(**{k: v for k, v in data["cpu"].items() if v is not None and v != ""})
    if data.get("gpu") and data["gpu"].get("name"):
        hs.gpu = GPUSpec(**{k: v for k, v in data["gpu"].items() if v is not None and v != ""})
    if data.get("motherboard") and data["motherboard"].get("name"):
        hs.motherboard = MotherboardSpec(**{k: v for k, v in data["motherboard"].items() if v is not None and v != ""})
    if data.get("ram") and data["ram"].get("name"):
        hs.ram = RAMSpec(**{k: v for k, v in data["ram"].items() if v is not None and v != ""})
    if data.get("storage") and data["storage"].get("name"):
        hs.storage = StorageSpec(**{k: v for k, v in data["storage"].items() if v is not None and v != ""})
    if data.get("psu") and data["psu"].get("name"):
        hs.psu = PSUSpec(**{k: v for k, v in data["psu"].items() if v is not None and v != ""})
    if data.get("cooler") and data["cooler"].get("name"):
        hs.cooler = CoolerSpec(**{k: v for k, v in data["cooler"].items() if v is not None and v != ""})
    if data.get("case") and data["case"].get("name"):
        hs.case = CaseSpec(**{k: v for k, v in data["case"].items() if v is not None and v != ""})
    return hs


# =============================================================
# EXACT SEARCH IDENTIFICATION PROMPT
# RULES:
#   - ONLY identify. NEVER invent specifications.
#   - PRESERVE the exact model suffix the user typed (5600 != 5600G != 5600X != 5500).
#   - If ambiguous or cannot identify, return confidence < 0.5 with best-guess but DO NOT substitute.
#   - DETECT form_factor = "desktop" for PC parts, "laptop" for mobile parts, "unknown" otherwise
#   - DETECT is_pc_part = true only for desktop PC hardware categories (CPU/GPU/Mobo/RAM/Storage/PSU/Cooler/Case)
# =============================================================
EXACT_SEARCH_IDENTIFY_PROMPT = """You are the PC Scan EXACT COMPONENT IDENTIFIER. Your ONE job is to NORMALIZE the user's search query into structured identity. NEVER invent specifications. NEVER silently substitute a similar model.

RULES (NO DEVIATION - FAILURE TO COMPLY = BUG):
1. PRESERVE EXACT SUFFIX / MODIFIER the user typed. Examples:
   - "Ryzen 5 5600"  → model = "Ryzen 5 5600"   (NO G / X / XT suffix added)
   - "Ryzen 5 5600X" → model = "Ryzen 5 5600X"  (X preserved)
   - "Ryzen 5 5600G" → model = "Ryzen 5 5600G"  (G preserved)
   - "RTX 4060"      → model = "RTX 4060"       (NO Ti / Super added)
   - "RTX 4060 Ti"   → model = "RTX 4060 Ti"    (Ti preserved)
   - "ryzan 5 5600"  → correct TYPO -> brand:"AMD" model:"Ryzen 5 5600" (typographical correction ONLY when OBVIOUS)
2. category ∈ { "CPU", "GPU", "Motherboard", "RAM", "Storage", "PSU", "Cooler", "Case", "Unknown" }
3. canonical_name = brand + " " + model. Examples: "AMD Ryzen 5 5600", "NVIDIA GeForce RTX 4060"
4. confidence: 0.0-1.0. High if exact, clear. Lower if ambiguous (e.g. just "B550M" by itself with no brand = partial. confidence < 0.6)
5. form_factor DETECTION (VERY IMPORTANT — user app rejects laptop parts):
   - form_factor = "laptop" if ANY of:
        * CPU suffix contains H / HS / HX / U / Y / G1-G7 (low power mobile) / HQ / MQ / MX / M / Max-Q / Mobile / LM / UM
        * GPU suffix contains M / MX / Mobile / Max-Q / Super Mobile / Ti Mobile / LM / GT1 / GTX M-series / RTX Laptop
        * Brand+model known to be mobile-only (e.g. Apple M1/M2/M3, Snapdragon 8cx, Exynos laptop chips)
        * User query contains "laptop", "notebook", "mobile", "portable", "laptop-only"
   - form_factor = "desktop" if query is standard PC DIY hardware desktop part (socket LGA1700 / AM4 / AM5 CPU suffixes: K / KF / F / X / XT / G / T / S / NONE; desktop GPU: standard suffixes Ti / Super / XT / no letter; RAM DDR4/5 DIMM; etc.)
   - form_factor = "unknown" if not a hardware part (food, name, car, general word) or category="Unknown"
   - form_factor = "na" if not applicable (non-component query)
6. is_pc_part DETECTION:
   - is_pc_part = true ONLY if query refers to a DESKTOP PC DIY hardware component in categories {CPU, GPU, Motherboard, RAM, Storage, PSU, Cooler, Case}
   - is_pc_part = false for laptop parts, phones, food, names, cars, words, software, OS, apps, games, etc.

7. RETURN JSON ONLY. NO markdown fences. NO extra commentary.

RETURN EXACTLY THIS JSON SHAPE — 7 keys, no extra keys:
{
  "category": "CPU",
  "brand": "AMD",
  "model": "Ryzen 5 5600",
  "canonical_name": "AMD Ryzen 5 5600",
  "confidence": 0.95,
  "form_factor": "desktop",
  "is_pc_part": true
}
"""


def _run_exact_search_identify(query_text: str) -> tuple[Optional[dict], Optional[str]]:
    """Call OpenAI (with Gemini fallback) for exact component identity only.
    Returns tuple: (identity_dict_or_None, error_message_or_None)
    identity_dict keys: category, brand, model, canonical_name, confidence, form_factor, is_pc_part
    NEVER returns specs. Specs come from DB / Firestore only per architecture rule.

    SPEED OPTIMIZATIONS inside this function (applied in order):
      1) KNOWN_PARTS MAP (normalized key match) → SKIP OpenAI/Gemini entirely. ~1ms instead of 500-2500ms
      2) 30min TTL MEMO CACHE (exact same query recently processed) → SKIP AI.
      3) OPENAI timeout 45s → 20s (gpt-4o-mini for identify is fast, 45s is excessive wait)
      4) OPENAI max_tokens 320 → 200 (7 small keys; 200 tokens is generous)
    """
    qt_clean = (query_text or "").strip()

    # SPEED OPT 1: KNOWN COMPONENT MAP (desktop CPU/GPU from local DB) — SKIP AI ENTIRELY
    nkey = _norm_key(qt_clean)
    if nkey:
        known = KNOWN_COMPONENT_IDENTIFY_MAP.get(nkey)
        if known is not None:
            print(f"[SEARCH] [DIAG] ⚡ KNOWN-PARTS SHORTCUT HIT (no AI call): query={query_text!r} → canonical={known['canonical_name']!r}")
            # Return a copy to avoid mutating shared dict reference
            identity_copy = dict(known)
            identity_copy["confidence"] = 0.98
            # Store in TTL cache too (so future lookups use same flow)
            _cached_identify_store(query_text, (identity_copy, None))
            return identity_copy, None

    # SPEED OPT 2: TTL CACHE (30 min) — exact query already processed recently
    cached = _cached_identify_lookup(query_text)
    if cached is not None:
        # cached = (identity_dict, None_or_error). Ensure tuple shape.
        if isinstance(cached, tuple) and len(cached) == 2:
            (c_id, c_err) = cached
            if c_id:
                return dict(c_id), c_err

    if _MOCK_MODE or not settings.OPENAI_API_KEY:
        return None, ("Mock mode enabled or no API key." if _MOCK_MODE else "No OPENAI_API_KEY set.")

    print(f"[SEARCH] [DIAG] OPENAI REQUEST STARTED  query={query_text!r}")
    t0 = time.time()
    result_text = ""
    openai_error: Optional[str] = None

    try:
        from openai import OpenAI
        # SPEED OPT 3: timeout 45s → 20s (gpt-4o-mini identify completes in <4s almost always)
        params = {"api_key": settings.OPENAI_API_KEY, "timeout": 20}
        if settings.OPENAI_ORG_ID:
            params["organization"] = settings.OPENAI_ORG_ID
        client = OpenAI(**params)
        response = client.chat.completions.create(
            model=settings.OPENAI_MODEL,
            temperature=0.0,
            # SPEED OPT 4: max_tokens 320 → 200 (7 small keys; 200 tokens is huge headroom)
            max_tokens=200,
            messages=[
                {"role": "system", "content": EXACT_SEARCH_IDENTIFY_PROMPT},
                {"role": "user", "content": f"Identify this EXACT PC component search query:\n{qt_clean}"},
            ],
            response_format={"type": "json_object"},
        )
        try:
            status_code = getattr(response, "status_code", None) or 200
        except Exception:
            status_code = 200
        result_text = response.choices[0].message.content or ""
        dt_ms = int((time.time() - t0) * 1000)
        print(f"[SEARCH] [DIAG] OPENAI RESPONSE RECEIVED  status={status_code} len={len(result_text)} elapsed={dt_ms}ms")
    except Exception as e1:
        openai_error = f"OPENAI API ERROR {type(e1).__name__}: {e1}"
        print(f"[SEARCH] [DIAG] {openai_error}")
        # Gemini fallback for identification
        try:
            print("[SEARCH] [DIAG] GEMINI FALLBACK STARTED (identification-only)")
            gmodel = _get_gemini_model_for_identify()
            resp = gmodel.generate_content(
                EXACT_SEARCH_IDENTIFY_PROMPT + f"\n\nUser component search query:\n{qt_clean}"
            )
            result_text = resp.text if hasattr(resp, "text") else str(resp)
            openai_error = None
        except Exception as e2:
            gem_err = f"GEMINI FALLBACK ERROR {type(e2).__name__}: {e2}"
            print(f"[SEARCH] [DIAG] {gem_err}")
            return None, openai_error

    parsed = _safe_parse_json(result_text)
    if not parsed:
        print(f"[SEARCH] [DIAG] INVALID AI RESPONSE (JSON parse failed). first200={result_text[:200]!r}")
        return None, "Invalid AI response (JSON parse failed)."

    # Validate required shape — 7 keys with form_factor + is_pc_part
    required_keys = ("category", "brand", "model", "canonical_name", "confidence", "form_factor", "is_pc_part")
    for k in required_keys:
        if k not in parsed:
            # Back-compat fill if older prompt response missing new keys
            if k == "form_factor":
                parsed["form_factor"] = "unknown"
                print(f"[SEARCH] [DIAG] WARN: AI response missing form_factor → defaulted unknown")
            elif k == "is_pc_part":
                parsed["is_pc_part"] = (parsed.get("category") or "Unknown") != "Unknown"
                print(f"[SEARCH] [DIAG] WARN: AI response missing is_pc_part → inferred {parsed['is_pc_part']} from category")
            else:
                print(f"[SEARCH] [DIAG] INVALID AI RESPONSE missing key={k}")
                return None, f"Invalid AI response (missing key: {k})."

    try:
        parsed["confidence"] = float(parsed.get("confidence", 0.0))
    except Exception:
        parsed["confidence"] = 0.0

    # Ensure confidence bounds
    parsed["confidence"] = max(0.0, min(1.0, parsed["confidence"]))

    # SPEED OPT 2 (part 2): Cache this result for 30 minutes so identical query next time = NO AI CALL
    result_tuple = (dict(parsed), None)
    _cached_identify_store(query_text, result_tuple)
    return parsed, None


def _lookup_component_in_verified_db(identity: dict) -> Optional[dict]:
    """EXACT match lookup in verified component database.
    Priority order:
      0) COMPONENT DB LRU CACHE (1h TTL — SKIP Firestore/local query entirely for repeated canonical)
      1) Firestore collection("components") whereEqualTo("canonical_name") via firebase-admin (if available + env set)
      2) Local component_database.py (deterministic exact key match on canonical_name / model / brand+model)
    Returns normalized dict for SearchFoundComponent or None if no exact match.
    NEVER returns fuzzy / similar result.
    """
    # SPEED OPT 5: Component lookup 1h TTL cache — skip Firestore + local DB queries for same component
    (cached_val, cache_err_sentinel) = _cached_component_lookup(identity)
    if cached_val is not None or cache_err_sentinel is None:
        if cache_err_sentinel is None:
            # cached_val could be None (explicit "not found" sentinel); return it to avoid re-query
            print(f"[SEARCH] [DIAG] ✨ COMPONENT DB CACHE HIT (1h TTL) for canonical={identity.get('canonical_name')!r}")
            return cached_val

    cat = (identity.get("category") or "").strip()
    brand = (identity.get("brand") or "").strip()
    model = (identity.get("model") or "").strip()
    canonical = (identity.get("canonical_name") or "").strip()
    result: Optional[dict] = None

    # --- (1) Firebase-admin Firestore exact lookup (if available + environment configured) ---
    firebase_db = _get_firebase_firestore()
    if firebase_db is not None:
        try:
            print(f"[SEARCH] [DIAG] FIRESTORE EXACT MATCH STARTED  canonical_name={canonical!r}")
            col = firebase_db.collection("components")
            # Query 1: canonical_name
            q1 = col.where(filter=FieldFilter("canonical_name", "==", canonical)).limit(2).stream()
            docs1 = list(q1)
            best_doc = None
            for d in docs1:
                dbrand = (d.to_dict().get("brand") or "").strip()
                dmodel = (d.to_dict().get("model") or "").strip()
                if (brand and dbrand and brand.lower() != dbrand.lower()) or \
                   (model and dmodel and model.lower() != dmodel.lower()):
                    continue
                best_doc = d
                break
            if best_doc is None:
                # Query 2: brand + model exact equality (if canonical doesn't have doc yet)
                q2 = (
                    col.where(filter=FieldFilter("category", "==", cat))
                       .where(filter=FieldFilter("brand", "==", brand))
                       .where(filter=FieldFilter("model", "==", model))
                       .limit(2)
                       .stream()
                )
                for d in list(q2):
                    best_doc = d
                    break
            if best_doc is not None:
                data = best_doc.to_dict()
                print(f"[SEARCH] [DIAG] FIRESTORE EXACT MATCH FOUND id={best_doc.id}")
                result = {
                    "id": best_doc.id,
                    "category": cat if cat else (data.get("category") or "Unknown"),
                    "brand": brand if brand else (data.get("brand") or ""),
                    "model": model if model else (data.get("model") or ""),
                    "canonical_name": canonical if canonical else (data.get("canonical_name") or (brand + " " + model).strip()),
                    "image_url": data.get("image_url"),
                    "specifications": data.get("specifications") or {},
                }
            print("[SEARCH] [DIAG] FIRESTORE EXACT MATCH NOT FOUND — falling back to local verified component DB.")
        except Exception as fe:
            print(f"[SEARCH] [DIAG] FIRESTORE ERROR {type(fe).__name__}: {fe}")
            # Continue to local DB fallback — never invent component, just fallback check local keys.

    # --- (2) Local component_database.py EXACT key match ---
    print(f"[SEARCH] [DIAG] LOCAL DB EXACT MATCH STARTED  model={model!r} canonical={canonical!r}")
    try:
        from app.services.component_database import (
            CPU_DATABASE, GPU_DATABASE, MOTHERBOARD_DATABASE,
            RAM_DATABASE, STORAGE_DATABASE, PSU_DATABASE,
            CPUDatabaseEntry, GPUDatabaseEntry, MotherboardDatabaseEntry,
            RAMDatabaseEntry, StorageDatabaseEntry, PSUDatabaseEntry,
        )
    except Exception as ie:
        print(f"[SEARCH] [DIAG] LOCAL DB import error {type(ie).__name__}: {ie}")
        return None

    def _exact_lookup(table_like, model_key, cat_name, to_specs_fn):
        """Try exact match: first by table key (==model_key), then by .model==model_key, then by canonical_name equality."""
        if isinstance(table_like, dict):
            # Dict-style DB (CPU / GPU / Motherboard): keys are short model strings
            if model_key in table_like:
                entry = table_like[model_key]
                return {"entry": entry, "key": model_key, "by": "dict_key"}
            for k, v in table_like.items():
                vmodel = getattr(v, "model", None) or ""
                if vmodel and model_key and vmodel.lower() == model_key.lower():
                    return {"entry": v, "key": k, "by": "entry_model"}
                brand_v = getattr(v, "brand", None) or ""
                canon_v = (brand_v + " " + vmodel).strip()
                if canon_v and canonical and canon_v.lower() == canonical.lower():
                    return {"entry": v, "key": k, "by": "entry_canonical"}
            return None
        else:
            # List-style DB (RAM / Storage / PSU)
            def _flatten_spec_attrs(entry) -> str:
                # Generic full identifier build for list entries (depends on class)
                cls = entry.__class__.__name__
                if cls == "RAMDatabaseEntry":
                    return f"{getattr(entry, 'capacity_gb','')}GB DDR{''.join(ch for ch in str(getattr(entry,'type','DDR4')) if ch.isdigit())} {getattr(entry,'speed_mhz','')} {getattr(entry,'kit_configuration','')}".strip()
                if cls == "StorageDatabaseEntry":
                    return f"{getattr(entry, 'capacity_gb','')}GB {getattr(entry,'type','')}"
                if cls == "PSUDatabaseEntry":
                    return f"{getattr(entry, 'wattage','')}W {getattr(entry,'efficiency','')}"
                return ""
            for idx, entry in enumerate(table_like):
                flat = _flatten_spec_attrs(entry)
                # Compare user's model/canonical text against flat identifier OR any string field contains exact
                text_fields = [str(getattr(entry, k, "") or "") for k in entry.__dataclass_fields__.keys()]
                joined = " ".join(text_fields).lower()
                if model_key and model_key.lower() == flat.lower():
                    return {"entry": entry, "key": str(idx), "by": "list_flat_identifier"}
                if canonical and canonical.lower() in joined:
                    return {"entry": entry, "key": str(idx), "by": "list_canonical_substring_exact"}
            return None

    def _cpu_specs(e: CPUDatabaseEntry):
        return {
            "socket": e.socket,
            "cores": e.core_count,
            "threads": e.thread_count,
            "base_clock": None,
            "boost_clock": None,
            "tdp_watts": e.tdp_watts,
            "generation": e.generation,
            "memory_type": e.memory_type,
            "supported_chipsets": list(e.supported_chipsets),
            "performance_tier": e.performance_tier,
        }

    def _gpu_specs(e: GPUDatabaseEntry):
        return {
            "vram": f"{e.vram_gb:g} GB",
            "memory_type": e.memory_type,
            "power_consumption_w": e.power_draw_watts,
            "recommended_psu_w": e.recommended_psu_watts,
            "pcie_interface": e.pcie_interface,
            "target_resolution": e.target_resolution,
            "performance_tier": e.performance_tier,
            "generation": e.generation,
        }

    def _mobo_specs(e: MotherboardDatabaseEntry):
        return {
            "socket": e.socket,
            "chipset": e.chipset,
            "ram_type": e.memory_type,
            "ram_slots": e.ram_slots,
            "m2_slots": e.m2_slots,
            "form_factor": None,
            "max_ram_gb": e.max_ram_gb,
            "pcie_generation": e.pcie_generation,
            "bios_requirements": e.bios_requirements,
        }

    def _ram_specs(e: RAMDatabaseEntry):
        return {
            "capacity": f"{e.capacity_gb}GB",
            "speed": f"{e.speed_mhz} MHz",
            "ddr_generation": e.type,
            "configuration": e.kit_configuration,
            "cas_latency": e.cas_latency,
        }

    def _storage_specs(e: StorageDatabaseEntry):
        d = {
            "capacity": f"{e.capacity_gb}GB",
            "interface": e.interface,
            "type": e.type,
        }
        if e.read_speed_mbps:
            d["read_speed_mbps"] = e.read_speed_mbps
        if e.write_speed_mbps:
            d["write_speed_mbps"] = e.write_speed_mbps
        if e.pcie_generation:
            d["pcie_generation"] = e.pcie_generation
        return d

    def _psu_specs(e: PSUDatabaseEntry):
        return {
            "wattage": f"{e.wattage}W",
            "efficiency_rating": e.efficiency,
            "atx_version": e.atx_version,
            "gpu_connector": e.gpu_connector,
            "quality_tier": e.quality_tier,
        }

    table_mapping: dict = {
        "CPU":          (CPU_DATABASE,          CPUDatabaseEntry,          _cpu_specs),
        "GPU":          (GPU_DATABASE,          GPUDatabaseEntry,          _gpu_specs),
        "Motherboard":  (MOTHERBOARD_DATABASE,  MotherboardDatabaseEntry,  _mobo_specs),
        "RAM":          (RAM_DATABASE,          RAMDatabaseEntry,          _ram_specs),
        "Storage":      (STORAGE_DATABASE,      StorageDatabaseEntry,      _storage_specs),
        "PSU":          (PSU_DATABASE,          PSUDatabaseEntry,          _psu_specs),
    }

    # If Firestore already found result (result != None), skip local DB lookup entirely
    if result is None:
        mapping = table_mapping.get(cat)
        if mapping is None:
            # Category unknown — try every table for EXACT key on model. Still no fuzzy.
            for try_cat, (t, _, specs_fn) in table_mapping.items():
                hit = _exact_lookup(t, model, try_cat, specs_fn)
                if hit is not None:
                    entry = hit["entry"]
                    ebrand = getattr(entry, "brand", "") or brand or ""
                    emodel = getattr(entry, "model", "") or str(hit["key"])
                    specs = specs_fn(entry)
                    result = {
                        "id": f"local-{try_cat.lower()}-{hit['key']}",
                        "category": try_cat,
                        "brand": ebrand,
                        "model": emodel,
                        "canonical_name": (ebrand + " " + emodel).strip() if ebrand else emodel,
                        "image_url": None,
                        "specifications": specs,
                    }
                    break
        else:
            table, _, specs_fn = mapping
            hit = _exact_lookup(table, model, cat, specs_fn)
            if hit is None:
                print(f"[SEARCH] [DIAG] LOCAL DB EXACT MATCH NOT FOUND  (cat={cat} model={model!r})")
            else:
                entry = hit["entry"]
                ebrand = getattr(entry, "brand", None)
                if isinstance(ebrand, str) and ebrand:
                    final_brand = ebrand
                else:
                    final_brand = brand or ""
                emodel_attr = getattr(entry, "model", None)
                if isinstance(emodel_attr, str) and emodel_attr:
                    final_model = emodel_attr
                else:
                    final_model = model or str(hit["key"])

                final_canonical = (final_brand + " " + final_model).strip() if final_brand else final_model

                specs = specs_fn(entry)
                print(f"[SEARCH] [DIAG] LOCAL DB EXACT MATCH FOUND key={hit['key']} by={hit['by']} canonical={final_canonical!r}")
                result = {
                    "id": f"local-{cat.lower()}-{hit['key']}",
                    "category": cat if cat else "Unknown",
                    "brand": final_brand,
                    "model": final_model,
                    "canonical_name": final_canonical,
                    "image_url": None,
                    "specifications": {k: v for k, v in specs.items() if v is not None and v != "" and v != []},
                }

    # SPEED OPT 5 (part 2): CACHE result for 1 hour — whether found or not-found sentinel (so we never re-query for same component)
    _cached_component_store(identity, result)
    if result is None:
        print(f"[SEARCH] [DIAG] COMPONENT NOT FOUND (cached as not-found for 1h). canonical={canonical!r}")
    return result


# =============================================================
# IDENTIFICATION PROMPT — STRONG EMPHASIS ON EXACT MODEL SPECS
# =============================================================
IDENTIFY_PROMPT = """PC hardware SPEC IDENTIFIER. IDENTIFY ONLY the user's component. RETURN JSON IMMEDIATELY. NO INTRO, NO COMMENTARY.

DIRECT RULES (NO DEVIATION):
1. RETURN THE EXACT MODEL THE USER NAMED. Use REAL, FACTUAL specs only for THAT model.
   - "Ryzen 5 5500" = 6C/12T, 3.6/4.2 GHz, 65W, AM4. NEVER use 5600X specs.
2. FILL ALL PRIMARY SPEC FIELDS. If recent/upcoming (RTX 5090, Ryzen 9000), use WIDELY REPORTED specs.
3. 1-2 COMPONENTS MAX. Only what the user SEARCHED FOR — DO NOT invent extra components.
4. EXACT model names with brand. Include mfr + full identifier.

RETURN EXACTLY THIS JSON (no fences, no extra text):
{
  "identified_components": [
    {
      "component_type": "CPU|GPU|Motherboard|RAM|Storage|PSU|Cooler|Case",
      "name": "FULL exact model with brand e.g. 'AMD Ryzen 5 5500'",
      "brand": "AMD|Intel|NVIDIA etc.",
      "confidence": 0.95,
      "raw_text": "original user query"
    }
  ],
  "hardware_specs": {
    "cpu": { "name":"","brand":"","cores":null,"threads":null,"base_clock":"","boost_clock":"","socket":"","tdp_watts":null },
    "gpu": { "name":"","brand":"","vram_gb":null,"memory_type":"","interface":"","tdp_watts":null,"length_mm":null },
    "motherboard": { "name":"","brand":"","socket":"","chipset":"","form_factor":"","memory_slots":null,"max_ram_gb":null,"ram_type":"" },
    "ram": { "name":"","brand":"","total_capacity_gb":null,"module_count":null,"ram_type":"","speed_mhz":null,"cas_latency":null },
    "storage": { "name":"","brand":"","type":"","capacity_gb":null,"interface":"","form_factor":"" },
    "psu": { "name":"","brand":"","wattage":null,"efficiency_rating":"","modular_type":"" },
    "cooler": { "name":"","brand":"","type":"","tdp_rating_watts":null,"height_mm":null },
    "case": { "name":"","brand":"","form_factor_support":[],"max_gpu_length_mm":null,"max_cooler_height_mm":null }
  },
  "overall_confidence": 0.95,
  "analysis_summary": "Identified [exact model] as [type] w/ key specs."
}
FILL ≥3 PRIMARY SPEC FIELDS. NULL ONLY IF ZERO INFO. JSON ONLY."""


async def _identify_component_and_specs(query_text: str) -> tuple[list[IdentifiedComponent], Optional[HardwareSpecs], float, Optional[str]]:
    """Call OpenAI to identify the exact component + extract accurate specifications from a text query."""
    if _MOCK_MODE or not settings.OPENAI_API_KEY:
        empty: list[IdentifiedComponent] = []
        return empty, None, 0.0, None

    try:
        from openai import OpenAI
        params = {"api_key": settings.OPENAI_API_KEY}
        if settings.OPENAI_ORG_ID:
            params["organization"] = settings.OPENAI_ORG_ID
        client = OpenAI(**params)

        response = client.chat.completions.create(
            model=settings.OPENAI_MODEL,
            temperature=0.0,
            max_tokens=1024,
            messages=[
                {"role": "system", "content": IDENTIFY_PROMPT},
                {"role": "user", "content": f"Identify the EXACT component and return REAL, FACTUAL specs for this search query:\n{query_text}"}
            ],
            response_format={"type": "json_object"}
        )
        result_text = response.choices[0].message.content or ""
    except Exception as e1:
        try:
            model = _get_gemini_model_for_identify()
            full_prompt = IDENTIFY_PROMPT + "\n\nUser hardware search query:\n" + query_text
            resp = model.generate_content(full_prompt)
            result_text = resp.text if hasattr(resp, "text") else str(resp)
        except Exception:
            return [], None, 0.0, f"Identification API error: {type(e1).__name__}: {e1}"

    parsed = _safe_parse_json(result_text)
    if not parsed:
        return [], None, 0.0, None

    identified: list[IdentifiedComponent] = []
    for comp in parsed.get("identified_components", []):
        try:
            identified.append(IdentifiedComponent(
                component_type=comp.get("component_type", "Unknown"),
                name=comp.get("name", ""),
                brand=comp.get("brand"),
                confidence=float(comp.get("confidence", 0.0)),
                raw_text=comp.get("raw_text")
            ))
        except Exception:
            continue

    specs = _build_specs_from_dict(parsed.get("hardware_specs", {}))
    conf = float(parsed.get("overall_confidence", 0.0))
    summary = parsed.get("analysis_summary")
    return identified, specs, conf, summary


def _get_gemini_model_for_identify():
    """Lazy-load Gemini model as fallback for identification step."""
    import google.generativeai as genai
    genai.configure(api_key=settings.GOOGLE_API_KEY)
    generation_config = {
        "temperature": 0.0,
        "max_output_tokens": 1024,
    }
    return genai.GenerativeModel(
        model_name=settings.GEMINI_MODEL,
        generation_config=generation_config
    )


def _primary_component_to_component_info(identified: list[IdentifiedComponent], specs: Optional[HardwareSpecs]) -> Optional[ComponentInfo]:
    """Convert the first IdentifiedComponent + HardwareSpecs into a ComponentInfo that openai_service.analyze() expects."""
    if not identified:
        return None
    primary = identified[0]
    ctype = primary.component_type
    brand = primary.brand or ""
    exact = primary.name.replace(brand, "").strip() if brand else primary.name
    if not exact:
        exact = primary.name
    full = primary.name
    return ComponentInfo(
        component_type=ctype,
        brand=brand,
        exact_model=exact,
        full_name=full,
        confidence_score=primary.confidence,
        raw_ocr_text=primary.raw_text,
        specs=specs.model_dump(exclude_none=True) if specs else None
    )


def _specs_to_current_build(specs: Optional[HardwareSpecs], query_text: str) -> Optional[CurrentBuild]:
    """Wrap extracted HardwareSpecs into a CurrentBuild so openai_service.analyze() runs the 5 analyses against the REAL specs."""
    if specs is None:
        return None
    if not any([specs.cpu, specs.gpu, specs.motherboard, specs.ram, specs.storage, specs.psu, specs.cooler, specs.case]):
        return None
    return CurrentBuild(
        build_name=f"Search: {query_text[:30]}",
        hardware=specs,
        build_year=None,
        notes=f"Build assembled from search-query identification of: {query_text}."
    )


# =============================================================
# ROOT
# =============================================================
@app.get("/", tags=["Root"])
async def root():
    return {
        "status": "online",
        "service": "PCScan Backend API",
        "version": "1.0.0",
        "docs": "/docs",
        "endpoints": {
            "health": "GET  /api/health",
            "search": "POST /api/search        (JSON body: { query: string }) → OpenAI identify + Firestore exact match",
            "analyze_text": "POST /api/analyze-text   (JSON body)",
            "scan_and_analyze": "POST /api/scan-and-analyze (multipart/form-data: image + optional JSON fields)",
        },
        "note_for_android_emulator": "Use host 10.0.2.2:8000 to reach PC localhost from Android Emulator.",
    }


# =============================================================
# ENDPOINT 1:  GET /api/health
# =============================================================
@app.get("/api/health", tags=["Health"], response_model=HealthResponse)
async def api_health() -> HealthResponse:
    try:
        g_ready = gemini_service.is_ready
        o_ready = openai_service.is_ready
        overall = "ok"
    except Exception:
        g_ready = False
        o_ready = False
        overall = "error"
    return HealthResponse(
        status=overall,
        service="pcscan-backend",
        version="1.0.0",
        timestamp=_utc_iso(),
        gemini_enabled=g_ready,
        openai_enabled=o_ready,
    )


# =============================================================
# ENDPOINT 1B: POST /api/search   (Exact component search: Android → FastAPI → OpenAI identify → Firestore/local exact match)
# FLOW:
#   Android SearchFragment
#   → POST { "query": "Ryzen 5 5600" }
#   → OpenAI identifies: { category, brand, model, canonical_name, confidence } (NEVER specs)
#   → Firestore (or local DB fallback) QUERY EXACT MATCH on canonical_name / brand+model / category
#   → { found:true + SearchFoundComponent{specifications FROM DB} }
#   OR
#     { found:false, exact_match:false, message:"Exact component not found." }
# =============================================================
@app.post(
    "/api/search",
    tags=["Search"],
    response_model=SearchResponse,
)
async def api_search(req: SearchRequest) -> SearchResponse:
    t_total_start = int(time.time() * 1000)
    query_text = (req.query or "").strip()
    print()
    print("=" * 70)
    print(f"[SEARCH] [DIAG] SEARCH REQUEST RECEIVED  query={query_text!r}")
    print("=" * 70)

    # --- (1) Empty query → 422 (Android client should also pre-validate) ---
    if not query_text:
        ms = int(time.time() * 1000) - t_total_start
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Search query is empty."
        )

    # --- (2) OpenAI identify-only (NEVER specs here) ---
    identity, ai_error = _run_exact_search_identify(query_text)
    if identity is None:
        # Classification of failure:
        # "no api key/mock mode" → 503 (server config)
        # "OPENAI API ERROR AuthenticationError | 401 | 403" → 502 bad gateway upstream
        # "OPENAI API ERROR RateLimitError | 429" → 503 rate limited
        # "OPENAI API ERROR" (network) → 502
        # "Invalid AI response" → 502
        err = (ai_error or "").lower()
        ms = int(time.time() * 1000) - t_total_start
        if "mock mode" in err or "no openai_api_key" in err or "no api key" in err:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail=f"Search service not configured: {ai_error}"
            )
        if "rate" in err or "429" in err or "quota" in err:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail=f"OpenAI rate limit or quota exceeded: {ai_error}"
            )
        if "authenticationerror" in err or "unauthorized" in err or "401" in err or "invalid_api_key" in err:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail=f"OpenAI authentication failed (invalid / revoked key): {ai_error}"
            )
        if "invalid ai response" in err or "json parse" in err or "missing key" in err:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail=f"Upstream AI returned invalid response: {ai_error}"
            )
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"AI identification failed: {ai_error}"
        )

    # --- (2.5) STRICT FORM-FACTOR & PC-PART VALIDATION BEFORE DB LOOKUP ---
    # TWO LAYERS:
    #   Layer A = AI-classified form_factor / is_pc_part from identity dict
    #   Layer B = REGEX suffix heuristic (safety net if AI misclassified)
    #   LAYER 0 = CATEGORY WHITELIST (hard reject ANYTHING not in 8 desktop PC DIY categories)
    VALID_PC_CATEGORIES = {"CPU", "GPU", "Motherboard", "RAM", "Storage", "PSU", "Cooler", "Case"}

    def _classify_form_factor_regex(q: str, brand: str, model: str, category: str) -> tuple[str, bool, Optional[str]]:
        """Heuristic regex classification (2nd safety gate) if AI misclassified.
        Returns (form_factor, is_valid_pc_part, rejection_reason_or_None)
        """
        q_low = (f"{brand} {model} {q}").lower()
        cat = (category or "Unknown").upper()

        # LAYER 0 FIRST: CATEGORY WHITELIST — IF NOT IN {CPU/GPU/Motherboard/RAM/Storage/PSU/Cooler/Case} = not_pc_part.
        # Examples of rejects: Monitor, Keyboard, Mouse, UPS, Webcam, Speaker, Printer, Scanner, AVR, USB Hub, Laptop Chassis.
        if cat not in {c.upper() for c in VALID_PC_CATEGORIES}:
            print(f"[SEARCH] [VALID] LAYER 0 CATEGORY WHITELIST REJECT: category={category!r} not in {VALID_PC_CATEGORIES}")
            return "unknown", False, "not_pc_part"

        # LAYER 0B — KNOWN NON-PC PERIPHERAL KEYWORDS (hard reject — even if AI mislabels as GPU/CPU, we know these aren't PC DIY parts)
        KNOWN_NON_PC_PERIPHERAL_WORDS = (
            "monitor", "display", "screen", "tv", "television",
            "keyboard", "kb ", " kb", "mech kb",
            "mouse", "mice", "trackpad", "trackball",
            "headset", "headphone", "earphone", "earbuds", "speaker", "soundbar", "subwoofer",
            "microphone", "mic ", "webcam", "camera",
            "printer", "scanner", "projector",
            "ups ", " ups", "avr ", " avr", "uninterruptible power supply",
            "usb hub", "docking station", "dock station",
            "chair", "table", "desk", "fan", "aircon",
            "controller", "gamepad", "joystick", "wheel", "vr headset", "vr"
        )
        for w in KNOWN_NON_PC_PERIPHERAL_WORDS:
            if w in q_low:
                print(f"[SEARCH] [VALID] LAYER 0B PERIPHERAL KEYWORD HARD REJECT: keyword={w!r} in query={q_low!r}")
                return "unknown", False, "not_pc_part"

        # Known laptop / mobile CPU suffixes: H, HS, HX, U, Y, G1-G7, HQ, MQ, M, LM, UM, MX (APU)
        cpu_mobile_re = re.compile(
            r"(^|[\s\-_])("
            r"\d{4,5}[hHuUyY]\b|"              # 12400H, 5600U, etc
            r"hs\b|hx\b|hq\b|mq\b|lm\b|um\b|"   # 13700HS, 7940HS
            r"g[1-7]\b|"                         # UHD G1-G7 mobile suffix
            r"[a-z0-9]m\b|"                      # ...M  (mobile)
            r"mobile(?!\s*legend)"              # "mobile" but not "mobile legend" (avoid game brand)
            r")", re.IGNORECASE
        )
        # Known laptop GPU suffixes: M, MX, Max-Q, Laptop, Mobile, GT1, Super Mobile, Ti Mobile
        gpu_mobile_re = re.compile(
            r"(^|[\s\-_])("
            r"mx\d+\b|"                               # MX570, MX450
            r"\d{3,4}m\b|"                             # 1050M, 1660M, 3050M
            r"max[\s\-]?q\b|"                          # 2080 Max-Q
            r"(rtx|gtx|rx|radeon|geforce|arc)\s.*\blaptop\b|"  # RTX 3060 Laptop GPU
            r"laptop\s.*\bgpu\b|"
            r"\bgt\s?1\b|"                              # GT1 = low power mobile
            r"(super|ti)\s+mobile\b|"
            r"quadro\s+\w+\s*m\b"
            r")", re.IGNORECASE
        )
        # Query-level non-pc or laptop keywords
        laptop_keywords = ("laptop", "notebook", "macbook", "surface pro", "thinkpad", "spectre", "envy x360")
        for kw in laptop_keywords:
            if kw in q_low:
                return "laptop", False, "laptop_part"

        if cpu_mobile_re.search(q_low) and cat in ("CPU",):
            print(f"[SEARCH] [VALID] REGEX detected LAPTOP CPU (mobile suffix in {q_low!r})")
            return "laptop", False, "laptop_part"
        if gpu_mobile_re.search(q_low) and cat in ("GPU",):
            print(f"[SEARCH] [VALID] REGEX detected LAPTOP GPU (mobile suffix in {q_low!r})")
            return "laptop", False, "laptop_part"
        return "desktop", True, None

    ai_form = (identity.get("form_factor") or "unknown").lower()
    ai_is_pc = bool(identity.get("is_pc_part"))
    ai_conf = float(identity.get("confidence") or 0.0)
    cat = (identity.get("category") or "Unknown")
    brand = (identity.get("brand") or "")
    model = (identity.get("model") or identity.get("canonical_name") or "")

    regex_form, regex_valid, regex_reject = _classify_form_factor_regex(query_text, brand, model, cat)

    # LAYER 0C — EXPLICIT CATEGORY WHITELIST CHECK (final guard even if helper regex was skipped)
    VALID_PC_CATEGORIES_UPPER = {c.upper() for c in VALID_PC_CATEGORIES}
    if cat.upper() not in VALID_PC_CATEGORIES_UPPER:
        print(f"[SEARCH] [VALID] LAYER 0C EXPLICIT CATEGORY REJECT: category={cat!r} not in allowed 8 PC DIY categories")
        # Override: force reject
        regex_reject = "not_pc_part"
        regex_form = "unknown"
        regex_valid = False

    # Merge results: REGEX WINS if it says laptop/not_pc_part (hard reject), otherwise trust AI with AI confidence
    rejection_reason: Optional[str] = None
    form_final: str
    valid_final: bool

    if regex_reject:
        rejection_reason = regex_reject
        form_final = regex_form
        valid_final = False
        print(f"[SEARCH] [VALID] LAYER B REGEX HARD REJECT: reason={rejection_reason} form={form_final}")
    else:
        form_final = ai_form if ai_form in ("desktop", "laptop", "unknown", "na") else regex_form
        if not ai_is_pc:
            valid_final = False
            if form_final == "laptop":
                rejection_reason = "laptop_part"
            elif form_final in ("unknown", "na") and cat == "Unknown":
                rejection_reason = "not_pc_part"
            elif ai_conf < 0.35:
                rejection_reason = "unknown_model"
            else:
                rejection_reason = "wrong_model"
            print(f"[SEARCH] [VALID] LAYER A AI SAYS NOT PC: ai_is_pc={ai_is_pc} form={form_final} reason={rejection_reason}")
        else:
            if form_final == "desktop":
                valid_final = True
            else:
                valid_final = False
                rejection_reason = "laptop_part" if form_final == "laptop" else ("wrong_model" if ai_conf >= 0.35 else "unknown_model")
                print(f"[SEARCH] [VALID] LAYER A form_factor={form_final} (conf={ai_conf}) → invalid reject reason={rejection_reason}")

    ms = int(time.time() * 1000) - t_total_start

    if not valid_final:
        # Build a HUMAN-READABLE message based on rejection_reason (Android dialog shows this)
        reason_human_map: dict[str, str] = {
            "laptop_part":   f"⚠️ LAPTOP / MOBILE PART DETECTED — '{model or query_text}' uses a mobile/laptop suffix or is a laptop-only component. This app supports DESKTOP PC DIY parts only (CPU/GPU/Motherboard/RAM/Storage/PSU/Cooler/Case for desktop PCs).",
            "not_pc_part":   f"⚠️ NOT A PC PART — your search '{query_text}' is NOT a desktop PC component. Only DESKTOP PC hardware (CPU, GPU, Motherboard, RAM, Storage, PSU, Cooler, Case) is accepted.",
            "wrong_model":   f"⚠️ MODEL NOT RECOGNIZED AS VALID DESKTOP PART — AI identified '{brand} {model}' with low confidence or non-desktop classification. Check spelling, include exact full model (e.g. 'Ryzen 5 5600' not just 'Ryzen'), and verify this is a DESKTOP (not laptop/mobile) part.",
            "unknown_model": f"⚠️ CANNOT IDENTIFY — the system could not recognize '{query_text}' as a valid desktop PC component. Check spelling / exact model suffix (5600 vs 5600X/5600G), and make sure it's a standard desktop PC hardware product.",
        }
        msg = reason_human_map.get(rejection_reason or "wrong_model", reason_human_map["wrong_model"])
        print(f"[SEARCH] [VALID] FINAL REJECT: query={query_text!r} reason={rejection_reason} msg_len={len(msg)}")
        return SearchResponse(
            found=False,
            exact_match=False,
            query=query_text,
            message=msg,
            component=None,
            ai_confidence=ai_conf,
            processing_time_ms=ms,
            is_valid_pc_part=False,
            rejection_reason=rejection_reason,
            form_factor=form_final,
        )

    # Proceed only if valid_final = True (validated DESKTOP PC part)
    print(f"[SEARCH] [VALID] ✅ PASSED (form={form_final}, valid=True, conf={ai_conf}). Proceeding to DB lookup.")

    # --- (3) Exact match lookup in Firestore/local verified DB ---
    print(f"[SEARCH] [DIAG] AI IDENTITY = category={identity.get('category')!r} brand={identity.get('brand')!r} model={identity.get('model')!r} canonical={identity.get('canonical_name')!r} confidence={identity.get('confidence')}")
    component_found: Optional[dict] = None
    try:
        component_found = _lookup_component_in_verified_db(identity)
    except Exception as e:
        ms = int(time.time() * 1000) - t_total_start
        print(f"[SEARCH] [DIAG] FIRESTORE EXCEPTION {type(e).__name__}: {e}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Firestore lookup error: {type(e).__name__}: {e}"
        )

    ms = int(time.time() * 1000) - t_total_start

    if component_found is None:
        # --- (4a) EXACT NOT FOUND — never return a similar component ---
        reason_msg = f"Exact component not found. (AI identified {identity.get('canonical_name')!r} with confidence {int(identity.get('confidence',0)*100)}%; no exact entry in Firestore/component database.)"
        print(f"[SEARCH] [DIAG] SEARCH RESPONSE: found=false exact_match=false reason={reason_msg!r}")
        return SearchResponse(
            found=False,
            exact_match=False,
            query=query_text,
            message="Exact component not found.",
            component=None,
            ai_confidence=float(identity.get("confidence", 0.0)),
            processing_time_ms=ms,
            is_valid_pc_part=True,
            rejection_reason=None,
            form_factor=form_final,
        )

    # --- (4b) EXACT FOUND ---
    print(f"[SEARCH] [DIAG] SEARCH RESPONSE: found=true exact_match=true canonical={component_found.get('canonical_name')!r}")
    try:
        comp_model = SearchFoundComponent(**component_found)
    except Exception as ve:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Database returned invalid component shape: {type(ve).__name__}: {ve}"
        )

    return SearchResponse(
        found=True,
        exact_match=True,
        query=query_text,
        message=None,
        component=comp_model,
        ai_confidence=float(identity.get("confidence", 0.0)),
        processing_time_ms=ms,
        is_valid_pc_part=True,
        rejection_reason=None,
        form_factor=form_final,
    )


# =============================================================
# ENDPOINT 2:  POST /api/analyze-text   (Pure text query → OpenAI)
# TWO-STEP FLOW:
#   Step 1: IDENTIFY exact component + extract REAL, FACTUAL specs (OpenAI)
#   Step 2: Run the 5 analyses (compatibility, watts, bottleneck, upgrade, build recos)
#           using the IDENTIFIED specs so they're based on the real component.
# =============================================================
@app.post(
    "/api/analyze-text",
    tags=["Analysis"],
    response_model=AnalyzeTextResponse,
)
async def api_analyze_text(req: AnalyzeTextRequest) -> AnalyzeTextResponse:
    t_total_start = int(time.time() * 1000)
    source = _detect_source()
    query_text = (req.text or "").strip()
    if not query_text:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Field 'text' is required and cannot be empty."
        )

    # ─── STEP 1: IDENTIFICATION — get exact model + real factual specs ──
    identified_list, hardware_specs, ident_conf, ident_summary = await _identify_component_and_specs(query_text)

    # Convert identification results into the shapes openai_service.analyze() expects
    identified_component_info = _primary_component_to_component_info(identified_list, hardware_specs)
    merged_build = req.current_build
    if merged_build is None and hardware_specs is not None:
        merged_build = _specs_to_current_build(hardware_specs, query_text)

    # ─── STEP 2: 5 ANALYSES (compatibility, watts, bottleneck, upgrade, build recos) ──
    analysis: Optional[AnalysisResponse] = None
    analysis_err: Optional[str] = None
    try:
        analysis = openai_service.analyze(
            identified_component=identified_component_info,
            current_build=merged_build,
            user_info=req.user_info,
            query_text=query_text,
            include_recommendations=req.include_recommendations,
            include_upgrade_path=req.include_upgrade_path,
        )
    except HTTPException:
        raise
    except Exception as e:
        analysis_err = f"Analyze-step failed: {type(e).__name__}: {e}"

    total_ms = int(time.time() * 1000) - t_total_start
    success_overall = (analysis is not None and analysis.success) or bool(identified_list)
    error_out = None
    if analysis_err is not None and not bool(identified_list):
        error_out = analysis_err
    elif analysis is not None and analysis.error_message and not bool(identified_list):
        error_out = analysis.error_message
    elif not bool(identified_list) and not analysis:
        error_out = "Failed to identify component and run analyses."

    return AnalyzeTextResponse(
        success=success_overall,
        query=query_text,
        identified_components=identified_list,
        hardware_specs=hardware_specs,
        identification_confidence=ident_conf,
        identification_summary=ident_summary,
        analysis=analysis,
        total_processing_time_ms=total_ms,
        source=source,
        error_message=error_out,
    )


# =============================================================
# ENDPOINT 3:  POST /api/scan-and-analyze   (Image → Gemini → OpenAI)
# =============================================================
@app.post(
    "/api/scan-and-analyze",
    tags=["Analysis"],
    response_model=ScanAndAnalyzeResponse,
)
async def api_scan_and_analyze(
    image: UploadFile = File(..., description="Image file (JPEG/PNG/WEBP) of PC component / label"),
    user_info: Optional[str] = Form(
        default=None,
        description="Optional JSON string matching UserInfo schema — { budget_php, use_case, target_resolution, ... }",
    ),
    current_build: Optional[str] = Form(
        default=None,
        description="Optional JSON string matching CurrentBuild schema — { hardware, build_name, build_year, notes }",
    ),
    include_recommendations: bool = Form(default=True, description="Include Entry/Mid/High-End tiered build recommendations"),
    include_upgrade_path: bool = Form(default=True, description="Include upgrade path steps (only if current_build is provided)"),
) -> ScanAndAnalyzeResponse:
    t_total_start = int(time.time() * 1000)
    source = _detect_source()

    # --- Validate image ---
    if not image:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Missing required field: image")
    mime = (image.content_type or "").lower()
    if mime not in ALLOWED_IMAGE_MIMES:
        raise HTTPException(
            status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=f"Unsupported image type '{image.content_type}'. Allowed: JPEG / PNG / WEBP."
        )

    # --- Read image bytes (enforce size limit) ---
    image_bytes: bytes = await image.read()
    if len(image_bytes) == 0:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Uploaded image is empty.")
    if len(image_bytes) > _MAX_UPLOAD_BYTES:
        raise HTTPException(
            status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=f"Image too large: {len(image_bytes)} bytes. Max allowed: {_MAX_UPLOAD_BYTES} bytes ({_MAX_UPLOAD_BYTES // 1024 // 1024}MB)."
        )

    # --- Parse optional JSON form fields ---
    user_info_json = _parse_json_field(user_info, "user_info")
    current_build_json = _parse_json_field(current_build, "current_build")
    user_obj, current_build_obj = _build_models_from_json(user_info_json, current_build_json)

    # =====================================================================
    # FLOW PER PROMPT 5:   IMAGE  →  GEMINI (OCR + identify)  →  OPENAI (5 analyses)  →  RESULT
    # =====================================================================
    ocr_result: Optional[OCRScanResult] = None
    analysis: Optional[AnalysisResponse] = None
    error_msg: Optional[str] = None
    success = True

    # Step 1: Gemini
    try:
        ocr_result = gemini_service.scan_image(image_bytes, mime_type=mime, build_hardware_from_components=True)
        if not ocr_result.success:
            success = False
            error_msg = ocr_result.error_message or "Gemini OCR/identification step failed."
    except HTTPException:
        raise
    except Exception as e:
        success = False
        error_msg = f"Gemini step failed: {type(e).__name__}: {e}"

    # Step 2: OpenAI (continue if OCR produced specs, even with minor issues)
    if ocr_result is not None:
        primary_component = ocr_result.primary_component

        merged_build = current_build_obj
        if merged_build is None and ocr_result.extracted_hardware_specs is not None:
            merged_build = CurrentBuild(
                build_name="Scanned Build",
                hardware=ocr_result.extracted_hardware_specs,
                build_year=None,
                notes="Build hardware inferred from Gemini OCR scan.",
            )

        query_for_ai = (ocr_result.raw_ocr_text or "")[:2000]
        try:
            analysis = openai_service.analyze(
                identified_component=primary_component,
                current_build=merged_build,
                user_info=user_obj,
                query_text=query_for_ai or f"Scan of {primary_component.full_name if primary_component else 'PC component'}",
                include_recommendations=include_recommendations,
                include_upgrade_path=include_upgrade_path,
            )
            if not analysis.success:
                success = False
                error_msg = (error_msg + " | " if error_msg else "") + (analysis.error_message or "OpenAI analysis step failed.")
        except HTTPException:
            raise
        except Exception as e:
            success = False
            error_msg = (error_msg + " | " if error_msg else "") + f"OpenAI step failed: {type(e).__name__}: {e}"

    total_ms = int(time.time() * 1000) - t_total_start
    return ScanAndAnalyzeResponse(
        success=success,
        ocr_result=ocr_result,
        analysis=analysis,
        total_processing_time_ms=total_ms,
        source=source,
        error_message=error_msg,
    )


# =============================================================
# ENDPOINT 4:  POST /api/build-recommendation   (Anchor → 3 Tier Builds)
# =============================================================
@app.post(
    "/api/build-recommendation",
    response_model=BuildRecoResponse,
    tags=["Build"],
)
async def api_build_recommendation(req: BuildRecoRequest):
    t0 = time.time()
    try:
        import math
        import json

        # ─────────────────────────────────────────────────────────
        # STEP 1: ANCHOR LOOKUP (fast — KNOWN_COMPONENT_MAP + direct scan)
        # ─────────────────────────────────────────────────────────
        component_name = req.component_name.strip()
        nk = _norm_key(component_name)

        anchor_category = req.component_category
        if anchor_category:
            anchor_category = anchor_category.strip().upper()

        anchor_cpu_entry = None
        anchor_gpu_entry = None
        anchor_mb_entry = None
        anchor_canonical = component_name
        cpu_tdp_watts = 65
        cpu_socket = None
        cpu_memory_type = "DDR4"
        cpu_performance_tier = "entry"
        anchor_gpu_power = 150
        anchor_gpu_tier = "mid"

        known_identity = None
        if nk in KNOWN_COMPONENT_IDENTIFY_MAP:
            known_identity = KNOWN_COMPONENT_IDENTIFY_MAP[nk]
            if not anchor_category:
                anchor_category = known_identity.get("category", "CPU")
            anchor_canonical = known_identity.get("canonical_name", component_name)

        if not anchor_category:
            for key, entry in CPU_DATABASE.items():
                if key.lower() in component_name.lower() or component_name.lower() in key.lower():
                    anchor_category = "CPU"
                    anchor_canonical = f"{entry.brand} {entry.model}"
                    break
        if not anchor_category:
            for key, entry in GPU_DATABASE.items():
                if key.lower() in component_name.lower() or component_name.lower() in key.lower():
                    anchor_category = "GPU"
                    anchor_canonical = f"{entry.brand} {entry.model}"
                    break
        if not anchor_category:
            for key, entry in MOTHERBOARD_DATABASE.items():
                if key.lower() in component_name.lower() or component_name.lower() in key.lower():
                    anchor_category = "MOTHERBOARD"
                    anchor_canonical = f"{entry.brand} {entry.model}"
                    break
        if not anchor_category:
            anchor_category = "CPU"

        anchor_category = anchor_category.strip().upper()

        def _make_error_response(msg: str, cpu_name: str = None) -> BuildRecoResponse:
            cn = cpu_name if cpu_name is not None else component_name
            empty = BuildRecoTierBundle(
                cpu=cn, motherboard="", ram="", gpu_nvidia="", gpu_amd="",
                storage="", psu="", estimated_wattage=0, bottleneck_pct=0,
            )
            return BuildRecoResponse(
                success=False, anchor_component=component_name,
                anchor_category=anchor_category, entry=empty, mid=empty, high=empty,
                processing_time_ms=int((time.time() - t0) * 1000), error_message=msg,
            )

        if anchor_category == "CPU":
            found = False
            for key, entry in CPU_DATABASE.items():
                canon = f"{entry.brand} {entry.model}"
                if key.lower() == component_name.lower() or canon.lower() == component_name.lower() \
                        or key.lower() in component_name.lower() or component_name.lower() in key.lower() \
                        or (nk and nk == _norm_key(canon)):
                    anchor_cpu_entry = entry
                    anchor_canonical = canon
                    cpu_socket = entry.socket
                    cpu_memory_type = entry.memory_type.split("/")[0] if "/" in entry.memory_type else entry.memory_type
                    cpu_tdp_watts = entry.tdp_watts
                    cpu_performance_tier = entry.performance_tier
                    found = True
                    break
            if not found:
                return _make_error_response(
                    f"Anchor CPU '{component_name}' not found in local database. Try: Ryzen 5 5600, i5-12400F, Ryzen 7 7800X3D, i7-14700K."
                )

        elif anchor_category == "GPU":
            found = False
            for key, entry in GPU_DATABASE.items():
                canon = f"{entry.brand} {entry.model}"
                if key.lower() == component_name.lower() or canon.lower() == component_name.lower() \
                        or key.lower() in component_name.lower() or component_name.lower() in key.lower() \
                        or (nk and nk == _norm_key(canon)):
                    anchor_gpu_entry = entry
                    anchor_canonical = canon
                    anchor_gpu_power = entry.power_draw_watts
                    anchor_gpu_tier = entry.performance_tier
                    found = True
                    break
            if not found:
                return _make_error_response(
                    f"Anchor GPU '{component_name}' not found in local database. Try: RTX 4060, RX 7600, RTX 4080, RX 7900 XTX.",
                    cpu_name="Unknown CPU"
                )
            tier_to_cpu = {
                "entry": ("Ryzen 5 5600", "AMD"),
                "mid": ("Ryzen 5 7600", "AMD"),
                "high": ("Ryzen 7 7800X3D", "AMD"),
                "extreme": ("Ryzen 9 7950X", "AMD"),
            }
            cpu_key, cpu_brand = tier_to_cpu.get(anchor_gpu_tier, tier_to_cpu["mid"])
            if cpu_key in CPU_DATABASE:
                anchor_cpu_entry = CPU_DATABASE[cpu_key]
                cpu_socket = anchor_cpu_entry.socket
                cpu_memory_type = anchor_cpu_entry.memory_type.split("/")[0] if "/" in anchor_cpu_entry.memory_type else anchor_cpu_entry.memory_type
                cpu_tdp_watts = anchor_cpu_entry.tdp_watts
                cpu_performance_tier = anchor_cpu_entry.performance_tier

        elif anchor_category == "MOTHERBOARD":
            found = False
            for key, entry in MOTHERBOARD_DATABASE.items():
                canon = f"{entry.brand} {entry.model}"
                if key.lower() == component_name.lower() or canon.lower() == component_name.lower() \
                        or key.lower() in component_name.lower() or component_name.lower() in key.lower():
                    anchor_mb_entry = entry
                    anchor_canonical = canon
                    cpu_socket = entry.socket
                    cpu_memory_type = entry.memory_type
                    found = True
                    break
            if not found:
                return _make_error_response(
                    f"Anchor Motherboard '{component_name}' not found in local database. Try: B550M, Z790, B650M, X670E.",
                    cpu_name="Unknown CPU"
                )
            cpu_for_socket = {}
            for ckey, centry in CPU_DATABASE.items():
                if centry.socket == cpu_socket:
                    cpu_for_socket.setdefault(centry.performance_tier, ckey)
            for tier in ["entry", "mid", "high", "extreme"]:
                if tier in cpu_for_socket:
                    ckey = cpu_for_socket[tier]
                    anchor_cpu_entry = CPU_DATABASE[ckey]
                    cpu_tdp_watts = anchor_cpu_entry.tdp_watts
                    cpu_performance_tier = anchor_cpu_entry.performance_tier
                    break
            if anchor_cpu_entry is None:
                cpu_tdp_watts = 65
                cpu_performance_tier = "entry"

        else:
            default_key = "Ryzen 5 5600"
            if default_key in CPU_DATABASE:
                anchor_cpu_entry = CPU_DATABASE[default_key]
                cpu_socket = anchor_cpu_entry.socket
                cpu_memory_type = anchor_cpu_entry.memory_type
                cpu_tdp_watts = anchor_cpu_entry.tdp_watts
                cpu_performance_tier = anchor_cpu_entry.performance_tier

        if anchor_cpu_entry is not None:
            cpu_canonical = f"{anchor_cpu_entry.brand} {anchor_cpu_entry.model}"
        else:
            cpu_canonical = anchor_canonical if anchor_category == "CPU" else "AMD Ryzen 5 5600"

        # ─────────────────────────────────────────────────────────
        # INNER DETERMINISTIC FALLBACK HELPERS (kept as fallback)
        # ─────────────────────────────────────────────────────────
        tier_order_map = {"entry": 0, "mid": 1, "high": 2, "extreme": 3}
        default_descriptors = {
            "entry": {"motherboard_tier": "entry", "ram_size_gb": 16, "ram_speed_mhz_min": 3200,
                      "storage_capacity_gb": 1000, "storage_type": "NVMe",
                      "gpu_nvidia_tier": "entry", "gpu_amd_tier": "entry",
                      "psu_efficiency_tier": "Bronze"},
            "mid":   {"motherboard_tier": "mid", "ram_size_gb": 32, "ram_speed_mhz_min": 3600,
                      "storage_capacity_gb": 2000, "storage_type": "NVMe",
                      "gpu_nvidia_tier": "mid", "gpu_amd_tier": "mid",
                      "psu_efficiency_tier": "Gold"},
            "high":  {"motherboard_tier": "high", "ram_size_gb": 64, "ram_speed_mhz_min": 6000,
                      "storage_capacity_gb": 4000, "storage_type": "NVMe",
                      "gpu_nvidia_tier": "high", "gpu_amd_tier": "high",
                      "psu_efficiency_tier": "Gold"},
        }
        if cpu_performance_tier == "entry":
            default_descriptors["high"]["gpu_nvidia_tier"] = "mid"
            default_descriptors["high"]["gpu_amd_tier"] = "mid"

        tier_descriptors_deterministic = default_descriptors

        entry_chipsets = ["A520", "H610", "A620", "B450"]
        mid_chipsets = ["B550", "B660", "B650", "B760"]
        high_chipsets = ["X570", "Z790", "X670E", "Z690"]
        chipset_tier_map = {}
        for c in entry_chipsets: chipset_tier_map[c.upper()] = "entry"
        for c in mid_chipsets: chipset_tier_map[c.upper()] = "mid"
        for c in high_chipsets: chipset_tier_map[c.upper()] = "high"

        def _deterministic_pick_mb(tier: str) -> Optional[MotherboardDatabaseEntry]:
            candidates = [m for m in MOTHERBOARD_DATABASE.values() if m.socket.upper() == (cpu_socket or "AM4").upper()]
            if not candidates:
                for m in MOTHERBOARD_DATABASE.values():
                    return m
                return None
            target_tier = tier_descriptors_deterministic[tier]["motherboard_tier"]
            tier_prefs = {"entry": ["entry", "mid", "high"], "mid": ["mid", "entry", "high"],
                          "high": ["high", "mid", "entry"]}.get(target_tier, ["mid", "entry", "high"])
            for tp in tier_prefs:
                tier_chipsets = [c for c, t in chipset_tier_map.items() if t == tp]
                for cset in tier_chipsets:
                    for m in candidates:
                        if m.chipset.upper() == cset:
                            if m.memory_type.upper() == cpu_memory_type.upper():
                                return m
                            if cpu_memory_type.upper() in m.memory_type.upper() or m.memory_type.upper() in cpu_memory_type.upper():
                                return m
            for m in candidates:
                if m.memory_type.upper() == cpu_memory_type.upper():
                    return m
                if cpu_memory_type.upper() in m.memory_type.upper():
                    return m
            return candidates[0]

        def _deterministic_pick_ram(tier: str) -> RAMDatabaseEntry:
            target_size = tier_descriptors_deterministic[tier]["ram_size_gb"]
            target_speed_min = tier_descriptors_deterministic[tier]["ram_speed_mhz_min"]
            candidates = [r for r in RAM_DATABASE if r.type.upper() == cpu_memory_type.upper()]
            if not candidates:
                candidates = list(RAM_DATABASE)
            qualifying = [r for r in candidates if r.capacity_gb >= target_size]
            if not qualifying:
                qualifying = sorted(candidates, key=lambda r: -r.capacity_gb)
            else:
                qualifying = sorted(qualifying, key=lambda r: (r.capacity_gb, abs(r.speed_mhz - target_speed_min)))
            return qualifying[0]

        def _deterministic_pick_storage(tier: str) -> StorageDatabaseEntry:
            target_cap = tier_descriptors_deterministic[tier]["storage_capacity_gb"]
            want_nvme = "NVME" in tier_descriptors_deterministic[tier]["storage_type"].upper()
            cands = [s for s in STORAGE_DATABASE if ("NVME" in s.type.upper()) == want_nvme]
            if not cands:
                cands = list(STORAGE_DATABASE)
            qualifying = [s for s in cands if s.capacity_gb >= target_cap]
            if not qualifying:
                qualifying = sorted(cands, key=lambda s: -s.capacity_gb)
            else:
                qualifying = sorted(qualifying, key=lambda s: (s.capacity_gb, -(int(float(s.pcie_generation)) if s.pcie_generation else 0)))
            return qualifying[0]

        def _deterministic_pick_gpu_nvidia(tier: str) -> GPUDatabaseEntry:
            target_tier = tier_descriptors_deterministic[tier]["gpu_nvidia_tier"]
            tier_idx = tier_order_map.get(target_tier, 1)
            cpu_tier_idx = tier_order_map.get(cpu_performance_tier, 1)
            if cpu_tier_idx + 1 < tier_idx:
                new_tier_key = list(tier_order_map.keys())[min(cpu_tier_idx + 1, len(tier_order_map) - 1)]
                target_tier = new_tier_key
                tier_idx = tier_order_map.get(target_tier, 1)
            if cpu_performance_tier == "entry" and target_tier == "high":
                target_tier = "mid"
                tier_idx = tier_order_map[target_tier]
            candidates = [g for g in GPU_DATABASE.values() if g.brand.upper() == "NVIDIA" and g.performance_tier == target_tier]
            if not candidates:
                for fti in range(tier_idx, -1, -1):
                    ft = list(tier_order_map.keys())[fti]
                    candidates = [g for g in GPU_DATABASE.values() if g.brand.upper() == "NVIDIA" and g.performance_tier == ft]
                    if candidates:
                        break
            if not candidates:
                for g in GPU_DATABASE.values():
                    if g.brand.upper() == "NVIDIA":
                        return g
            sorted_cands = sorted(candidates, key=lambda g: (g.vram_gb, g.power_draw_watts))
            mid_idx = len(sorted_cands) // 2
            return sorted_cands[mid_idx] if sorted_cands else candidates[0]

        def _deterministic_pick_gpu_amd(tier: str) -> GPUDatabaseEntry:
            target_tier = tier_descriptors_deterministic[tier]["gpu_amd_tier"]
            tier_idx = tier_order_map.get(target_tier, 1)
            cpu_tier_idx = tier_order_map.get(cpu_performance_tier, 1)
            if cpu_tier_idx + 1 < tier_idx:
                new_tier_key = list(tier_order_map.keys())[min(cpu_tier_idx + 1, len(tier_order_map) - 1)]
                target_tier = new_tier_key
                tier_idx = tier_order_map.get(target_tier, 1)
            if cpu_performance_tier == "entry" and target_tier == "high":
                target_tier = "mid"
                tier_idx = tier_order_map[target_tier]
            candidates = [g for g in GPU_DATABASE.values() if g.brand.upper() == "AMD" and g.performance_tier == target_tier]
            if not candidates:
                for fti in range(tier_idx, -1, -1):
                    ft = list(tier_order_map.keys())[fti]
                    candidates = [g for g in GPU_DATABASE.values() if g.brand.upper() == "AMD" and g.performance_tier == ft]
                    if candidates:
                        break
            if not candidates:
                for g in GPU_DATABASE.values():
                    if g.brand.upper() == "AMD":
                        return g
            sorted_cands = sorted(candidates, key=lambda g: (g.vram_gb, g.power_draw_watts))
            mid_idx = len(sorted_cands) // 2
            return sorted_cands[mid_idx] if sorted_cands else candidates[0]

        def _deterministic_pick_psu(total_tdp: int, tier: str) -> PSUDatabaseEntry:
            target_watts_raw = total_tdp * 1.2
            target_psu_watts = int(math.ceil(target_watts_raw / 50.0) * 50)
            target_eff = tier_descriptors_deterministic[tier]["psu_efficiency_tier"]
            eff_order = {"Bronze": 0, "Gold": 1, "Platinum": 2, "Titanium": 3}
            target_eff_idx = eff_order.get(target_eff, 1)
            min_eff = 0 if tier == "entry" else 1
            cands_ge = [p for p in PSU_DATABASE if p.wattage >= target_psu_watts]
            if not cands_ge:
                cands_ge = sorted(PSU_DATABASE, key=lambda p: -p.wattage)
            def _eff_idx(p):
                for name in ["Titanium", "Platinum", "Gold", "Bronze"]:
                    if name.lower() in p.efficiency.lower():
                        return eff_order.get(name, 0)
                return 0
            exact_eff = [p for p in cands_ge if _eff_idx(p) >= min_eff and _eff_idx(p) >= target_eff_idx]
            if exact_eff:
                return sorted(exact_eff, key=lambda p: (p.wattage, -_eff_idx(p)))[0]
            fallback = [p for p in cands_ge if _eff_idx(p) >= min_eff]
            if fallback:
                return sorted(fallback, key=lambda p: (p.wattage, -_eff_idx(p)))[0]
            return sorted(cands_ge, key=lambda p: p.wattage)[0]

        # Name generators (canonical synthetic names for list-based DBs)
        def _mb_canonical_name(mb: MotherboardDatabaseEntry) -> str:
            return f"{mb.brand} {mb.model}"

        def _ram_canonical_name(ram: RAMDatabaseEntry) -> str:
            return f"{ram.type} {ram.capacity_gb}GB {ram.speed_mhz}MHz ({ram.kit_configuration})"

        def _storage_canonical_name(st: StorageDatabaseEntry) -> str:
            base = f"{st.capacity_gb}GB {st.type}"
            if st.pcie_generation:
                base += f" PCIe {st.pcie_generation}"
            return base

        def _gpu_canonical_name(gpu: GPUDatabaseEntry) -> str:
            return f"{gpu.brand} {gpu.model}"

        def _psu_canonical_name(psu: PSUDatabaseEntry) -> str:
            return f"{psu.wattage}W {psu.efficiency}"

        # Reverse lookup maps (name -> DB entry)
        mb_name_map: dict = {}
        for mb in MOTHERBOARD_DATABASE.values():
            mb_name_map[_mb_canonical_name(mb)] = mb
            mb_name_map[mb.model] = mb
            mb_name_map[f"{mb.brand} {mb.model}"] = mb

        ram_name_map: dict = {}
        for r in RAM_DATABASE:
            ram_name_map[_ram_canonical_name(r)] = r

        storage_name_map: dict = {}
        for s in STORAGE_DATABASE:
            storage_name_map[_storage_canonical_name(s)] = s

        gpu_name_map: dict = {}
        for g in GPU_DATABASE.values():
            gpu_name_map[_gpu_canonical_name(g)] = g
            gpu_name_map[g.model] = g
            gpu_name_map[f"{g.brand} {g.model}"] = g

        psu_name_map: dict = {}
        for p in PSU_DATABASE:
            psu_name_map[_psu_canonical_name(p)] = p

        # ─────────────────────────────────────────────────────────
        # STEP 2: BUILD COMPONENT CHOICE LISTS FOR AI PROMPT
        # ─────────────────────────────────────────────────────────
        valid_mbs = [m for m in MOTHERBOARD_DATABASE.values() if m.socket.upper() == (cpu_socket or "").upper()]
        valid_rams = [r for r in RAM_DATABASE if r.type.upper() == cpu_memory_type.upper()]
        all_storages = list(STORAGE_DATABASE)
        nvidia_gpus = [g for g in GPU_DATABASE.values() if g.brand.lower() == "nvidia"]
        amd_gpus = [g for g in GPU_DATABASE.values() if g.brand.lower() == "amd"]
        all_psus = list(PSU_DATABASE)

        def _gpu_balance_ok(gpu_tier: str, tier_context: str) -> bool:
            ct = tier_order_map.get(cpu_performance_tier, 1)
            gt = tier_order_map.get(gpu_tier, 1)
            tt = tier_order_map.get(tier_context, 1)
            if ct == 0:
                if tt == 0:
                    return gt <= 0
                elif tt == 1:
                    return gt <= 1
                else:
                    return gt <= 1
            elif ct == 1:
                return gt <= 2
            elif ct == 2:
                return True
            elif ct == 3:
                return True
            return gt <= ct + 1

        mb_lines = "\n".join(
            f"{_mb_canonical_name(m)}|{m.chipset}|{m.memory_type}|{m.pcie_generation}"
            for m in valid_mbs
        ) or "(none)"

        ram_lines = "\n".join(
            f"{_ram_canonical_name(r)}|{r.capacity_gb}|{r.speed_mhz}|{r.type}"
            for r in valid_rams
        ) or "(none)"

        storage_lines = "\n".join(
            f"{_storage_canonical_name(s)}|{s.capacity_gb}|{s.type}|{s.pcie_generation or 'N/A'}"
            for s in all_storages
        ) or "(none)"

        def _format_gpu_lines(gpu_list, tier_ctx):
            lines = []
            for g in gpu_list:
                bal = "YES" if _gpu_balance_ok(g.performance_tier, tier_ctx) else "NO"
                lines.append(
                    f"{_gpu_canonical_name(g)}|{g.performance_tier}|{g.power_draw_watts}|balance:{bal}"
                )
            return "\n".join(lines) or "(none)"

        nvidia_lines_entry = _format_gpu_lines(nvidia_gpus, "entry")
        nvidia_lines_mid = _format_gpu_lines(nvidia_gpus, "mid")
        nvidia_lines_high = _format_gpu_lines(nvidia_gpus, "high")
        amd_lines_entry = _format_gpu_lines(amd_gpus, "entry")
        amd_lines_mid = _format_gpu_lines(amd_gpus, "mid")
        amd_lines_high = _format_gpu_lines(amd_gpus, "high")
        nvidia_lines_all = nvidia_lines_high
        amd_lines_all = amd_lines_high

        psu_lines = "\n".join(
            f"{_psu_canonical_name(p)}|{p.wattage}|{p.efficiency}"
            for p in all_psus
        ) or "(none)"

        # ─────────────────────────────────────────────────────────
        # STEP 3: AI PROMPT — OpenAI DIRECT PICKS with Gemini fallback
        # ─────────────────────────────────────────────────────────
        ai_prompt = f"""You are a PC BUILD ENGINEER. Your job: given 1 ANCHOR CPU that the user SELECTED AND MUST APPEAR EXACTLY AS IS IN ALL 3 FINAL BUILDS (ENTRY/MID/HIGH-END), PICK EXACTLY ONE each of motherboard, RAM, storage, PSU FROM THE PROVIDED CHOICE LISTS BELOW, PLUS EXACTLY ONE NVIDIA GPU AND ONE AMD GPU FROM THEIR RESPECTIVE LISTS. DO NOT INVENT COMPONENTS NOT IN THE LISTS.

ANCHOR CPU (MUST BE IN ALL 3 BUILDS, NEVER CHANGED, NEVER REPLACED):
- Canonical name: {cpu_canonical}
- Socket: {cpu_socket}
- RAM type: {cpu_memory_type}
- TDP: {cpu_tdp_watts}W
- Performance tier: {cpu_performance_tier}

VALID MOTHERBOARDS (socket {cpu_socket}) — pick exactly 1 per tier from this list. Format: canonical_name|chipset|ram_type|pcie_gen:
{mb_lines}

VALID RAMs (type {cpu_memory_type}) — pick exactly 1 per tier from this list. Format: name|size_gb|speed_mhz|ram_type:
{ram_lines}

STORAGE OPTIONS (any — pick exactly 1 per tier). Format: name|capacity_gb|type|pcie_gen:
{storage_lines}

NVIDIA GPUs — pick exactly 1 per tier. NOTE: if balance says NO for your tier, it is allowed ONLY for High-End tier and even then try to avoid extreme ones. Balanced builds = highest priority. Format: canonical_name|performance_tier|power_draw_watts|balance:YES/NO:
{nvidia_lines_all}

AMD GPUs — same pattern as NVIDIA. Format: canonical_name|performance_tier|power_draw_watts|balance:YES/NO:
{amd_lines_all}

PSU OPTIONS — pick exactly 1 per tier from this list. The PSU wattage must be AT LEAST (CPU TDP + max GPU power) x 1.2 for safety margin. Format: name|wattage|efficiency:
{psu_lines}

RULES FOR 3 TIERS:
ENTRY BUILD: Cheapest reasonable components. Lowest chipset MB, smallest reasonable RAM (16GB minimum for gaming), smallest 1TB NVMe or similar SSD, entry-tier balanced GPUs, lowest PSU wattage that meets headroom requirement.
MIDRANGE BUILD: Balanced. Good B-series MB, 32GB RAM if available, 2TB NVMe if exists, mid-tier GPUs that are PERFECT balance for the anchor CPU (no bottleneck > 12-15%), PSU with Gold efficiency if available.
HIGH-END BUILD: Strong but still REASONABLE for anchor CPU — NOT EXTREME. Best X/Z chipset for socket, 64GB only if sensible, 4TB only if exists, strongest GPUs where balance:YES OR just 1 tier above CPU (1 tier above OK, 2 tiers above is BANNED unless no choices). Avoid RTX 4090/RX 7900 XTX unless CPU is Ryzen 7/i7 or higher tier. PSU wattage with headroom but no overkill.

YOUR RESPONSE FORMAT (JSON ONLY, NO TEXT BEFORE/AFTER):
{{
  "entry": {{"motherboard": "exact_line_canonical_name", "ram": "exact_name", "storage": "exact_name", "gpu_nvidia": "exact_canonical_name", "gpu_amd": "exact_canonical_name", "psu": "exact_name"}},
  "mid": {{"motherboard": "exact_line_canonical_name", "ram": "exact_name", "storage": "exact_name", "gpu_nvidia": "exact_canonical_name", "gpu_amd": "exact_canonical_name", "psu": "exact_name"}},
  "high": {{"motherboard": "exact_line_canonical_name", "ram": "exact_name", "storage": "exact_name", "gpu_nvidia": "exact_canonical_name", "gpu_amd": "exact_canonical_name", "psu": "exact_name"}}
}}
MOTHERBOARD/RAM/STORAGE/GPU_NVIDIA/GPU_AMD/PSU MUST MATCH EXACTLY WITH ONE OF THE PROVIDED NAMES IN THE LISTS. Do not paraphrase. Do not invent.
Also remember: entry tier for Ryzen 5 5600 CPU should NOT use RTX 4090 GPU — that is 2 tiers above CPU.
"""

        ai_picks = None

        def _validate_ai_picks(parsed: dict) -> bool:
            if not isinstance(parsed, dict):
                return False
            for tn in ["entry", "mid", "high"]:
                if tn not in parsed:
                    return False
                tier_obj = parsed[tn]
                if not isinstance(tier_obj, dict):
                    return False
                for k in ["motherboard", "ram", "storage", "gpu_nvidia", "gpu_amd", "psu"]:
                    if k not in tier_obj:
                        return False
                    if not isinstance(tier_obj[k], str):
                        return False
            return True

        try:
            if openai_service is not None and hasattr(openai_service, 'client') and openai_service.client is not None:
                try:
                    resp = openai_service.client.chat.completions.create(
                        model="gpt-4o-mini",
                        temperature=0,
                        max_tokens=1200,
                        response_format={"type": "json_object"},
                        messages=[
                            {"role": "system", "content": "You are a PC build engineer. Return ONLY valid JSON. Pick components EXACTLY from the provided lists. Do not invent."},
                            {"role": "user", "content": ai_prompt},
                        ],
                    )
                    raw = resp.choices[0].message.content or "{}"
                    parsed = json.loads(raw)
                    if _validate_ai_picks(parsed):
                        ai_picks = parsed
                        print(f"[BUILD-RECO] ✅ OpenAI direct component picks OK")
                except Exception as ai_err:
                    print(f"[BUILD-RECO] OpenAI picks failed: {ai_err}")
                    ai_picks = None
        except Exception:
            ai_picks = None

        if ai_picks is None:
            try:
                if gemini_service is not None and hasattr(gemini_service, 'model'):
                    try:
                        gem_resp = gemini_service.model.generate_content(
                            ai_prompt,
                            generation_config={"response_mime_type": "application/json", "temperature": 0, "max_output_tokens": 1200}
                        )
                        parsed = json.loads(gem_resp.text or "{}")
                        if _validate_ai_picks(parsed):
                            ai_picks = parsed
                            print(f"[BUILD-RECO] ✅ Gemini fallback direct picks OK")
                    except Exception as gem_err:
                        print(f"[BUILD-RECO] Gemini picks failed: {gem_err}")
                        ai_picks = None
            except Exception:
                ai_picks = None

        # ─────────────────────────────────────────────────────────
        # STEP 4: DATABASE VALIDATION of AI picks + fallback to deterministic
        # ─────────────────────────────────────────────────────────
        tier_components = {}
        for tier_name in ["entry", "mid", "high"]:
            tc = {}

            det_mb = _deterministic_pick_mb(tier_name)
            if ai_picks is not None:
                ai_mb_name = ai_picks[tier_name]["motherboard"]
                mb_entry = mb_name_map.get(ai_mb_name)
                if mb_entry is None:
                    for k, v in mb_name_map.items():
                        if ai_mb_name.lower() in k.lower() or k.lower() in ai_mb_name.lower():
                            mb_entry = v
                            break
                if mb_entry is None or (cpu_socket and mb_entry.socket.upper() != cpu_socket.upper()):
                    print(f"[BUILD-RECO] [TIER={tier_name}] ⚠️ AI motherboard invalid → deterministic fallback")
                    mb_entry = det_mb
            else:
                mb_entry = det_mb
            tc["motherboard"] = mb_entry
            tc["motherboard_name"] = _mb_canonical_name(mb_entry) if mb_entry else "Generic Motherboard"

            mb_mem_type = cpu_memory_type
            if mb_entry:
                mb_mem_type = mb_entry.memory_type.split("/")[0] if "/" in mb_entry.memory_type else mb_entry.memory_type

            det_ram = _deterministic_pick_ram(tier_name)
            if ai_picks is not None:
                ai_ram_name = ai_picks[tier_name]["ram"]
                ram_entry = ram_name_map.get(ai_ram_name)
                if ram_entry is None:
                    for k, v in ram_name_map.items():
                        if ai_ram_name.lower() in k.lower() or k.lower() in ai_ram_name.lower():
                            ram_entry = v
                            break
                if ram_entry is None or ram_entry.type.upper() != mb_mem_type.upper():
                    if ram_entry is not None and (mb_mem_type.upper() in ram_entry.type.upper() or ram_entry.type.upper() in mb_mem_type.upper()):
                        pass
                    else:
                        print(f"[BUILD-RECO] [TIER={tier_name}] ⚠️ AI ram invalid → deterministic fallback")
                        ram_entry = det_ram
            else:
                ram_entry = det_ram
            tc["ram"] = ram_entry
            tc["ram_name"] = _ram_canonical_name(ram_entry)

            det_storage = _deterministic_pick_storage(tier_name)
            if ai_picks is not None:
                ai_st_name = ai_picks[tier_name]["storage"]
                st_entry = storage_name_map.get(ai_st_name)
                if st_entry is None:
                    for k, v in storage_name_map.items():
                        if ai_st_name.lower() in k.lower() or k.lower() in ai_st_name.lower():
                            st_entry = v
                            break
                if st_entry is None:
                    print(f"[BUILD-RECO] [TIER={tier_name}] ⚠️ AI storage missing → deterministic fallback")
                    st_entry = det_storage
            else:
                st_entry = det_storage
            tc["storage"] = st_entry
            tc["storage_name"] = _storage_canonical_name(st_entry)

            det_gpu_nv = _deterministic_pick_gpu_nvidia(tier_name)
            if ai_picks is not None:
                ai_gn_name = ai_picks[tier_name]["gpu_nvidia"]
                gn_entry = gpu_name_map.get(ai_gn_name)
                if gn_entry is None:
                    for k, v in gpu_name_map.items():
                        if ai_gn_name.lower() in k.lower() or k.lower() in ai_gn_name.lower():
                            gn_entry = v
                            break
                if gn_entry is None or gn_entry.brand.lower() != "nvidia":
                    print(f"[BUILD-RECO] [TIER={tier_name}] ⚠️ AI nvidia gpu invalid → deterministic fallback")
                    gn_entry = det_gpu_nv
            else:
                gn_entry = det_gpu_nv
            tc["gpu_nvidia"] = gn_entry
            tc["gpu_nvidia_name"] = _gpu_canonical_name(gn_entry)

            det_gpu_amd = _deterministic_pick_gpu_amd(tier_name)
            if ai_picks is not None:
                ai_ga_name = ai_picks[tier_name]["gpu_amd"]
                ga_entry = gpu_name_map.get(ai_ga_name)
                if ga_entry is None:
                    for k, v in gpu_name_map.items():
                        if ai_ga_name.lower() in k.lower() or k.lower() in ai_ga_name.lower():
                            ga_entry = v
                            break
                if ga_entry is None or ga_entry.brand.lower() != "amd":
                    print(f"[BUILD-RECO] [TIER={tier_name}] ⚠️ AI amd gpu invalid → deterministic fallback")
                    ga_entry = det_gpu_amd
            else:
                ga_entry = det_gpu_amd
            tc["gpu_amd"] = ga_entry
            tc["gpu_amd_name"] = _gpu_canonical_name(ga_entry)

            tier_components[tier_name] = tc

        for tier_name in ["entry", "mid", "high"]:
            tc = tier_components[tier_name]
            gn = tc["gpu_nvidia"]
            ga = tc["gpu_amd"]
            max_gpu_power = max(gn.power_draw_watts, ga.power_draw_watts)
            total_tdp_est = cpu_tdp_watts + max_gpu_power + 50
            min_required_watts = int(math.ceil((total_tdp_est * 1.2) / 50.0) * 50)

            det_psu = _deterministic_pick_psu(total_tdp_est, tier_name)
            if ai_picks is not None:
                ai_psu_name = ai_picks[tier_name]["psu"]
                psu_entry = psu_name_map.get(ai_psu_name)
                if psu_entry is None:
                    for k, v in psu_name_map.items():
                        if ai_psu_name.lower() in k.lower() or k.lower() in ai_psu_name.lower():
                            psu_entry = v
                            break
                if psu_entry is None or psu_entry.wattage < min_required_watts:
                    print(f"[BUILD-RECO] [TIER={tier_name}] ⚠️ AI psu invalid/wattage low → deterministic fallback")
                    psu_entry = det_psu
            else:
                psu_entry = det_psu
            tc["psu"] = psu_entry
            tc["psu_name"] = _psu_canonical_name(psu_entry)

        # ─────────────────────────────────────────────────────────
        # STEP 5: 5 COMPATIBILITY CHECKS (swap single component on failure)
        # ─────────────────────────────────────────────────────────
        for tier_name in ["entry", "mid", "high"]:
            tc = tier_components[tier_name]
            mb = tc["motherboard"]
            ram = tc["ram"]
            storage = tc["storage"]
            gn = tc["gpu_nvidia"]
            ga = tc["gpu_amd"]
            psu = tc["psu"]

            if mb and cpu_socket and mb.socket.upper() != cpu_socket.upper():
                print(f"[BUILD-RECO] [TIER={tier_name}] Compat#1 socket mismatch → fixing MB")
                new_mb = _deterministic_pick_mb(tier_name)
                tc["motherboard"] = new_mb
                tc["motherboard_name"] = _mb_canonical_name(new_mb) if new_mb else tc["motherboard_name"]
                mb = new_mb

            mb_mem_type = cpu_memory_type
            if mb:
                mb_mem_type = mb.memory_type.split("/")[0] if "/" in mb.memory_type else mb.memory_type
            if ram and ram.type.upper() != mb_mem_type.upper() and not (mb_mem_type.upper() in ram.type.upper() or ram.type.upper() in mb_mem_type.upper()):
                print(f"[BUILD-RECO] [TIER={tier_name}] Compat#2 RAM type mismatch → fixing RAM")
                new_ram = _deterministic_pick_ram(tier_name)
                tc["ram"] = new_ram
                tc["ram_name"] = _ram_canonical_name(new_ram)
                ram = new_ram

            ct_idx = tier_order_map.get(cpu_performance_tier, 1)
            for gpu_key, gpu_obj in (("gpu_nvidia", gn), ("gpu_amd", ga)):
                gt_idx = tier_order_map.get(gpu_obj.performance_tier, 1)
                if gt_idx > ct_idx + 1:
                    print(f"[BUILD-RECO] [TIER={tier_name}] Compat#3 {gpu_key} tier 2+ notches above CPU → fixing GPU")
                    if gpu_key == "gpu_nvidia":
                        new_g = _deterministic_pick_gpu_nvidia(tier_name)
                        tc["gpu_nvidia"] = new_g
                        tc["gpu_nvidia_name"] = _gpu_canonical_name(new_g)
                    else:
                        new_g = _deterministic_pick_gpu_amd(tier_name)
                        tc["gpu_amd"] = new_g
                        tc["gpu_amd_name"] = _gpu_canonical_name(new_g)

            if mb and storage and storage.pcie_generation:
                try:
                    mb_pcie = int(float(mb.pcie_generation))
                    st_pcie = int(float(storage.pcie_generation))
                    if mb_pcie < st_pcie:
                        print(f"[BUILD-RECO] [TIER={tier_name}] Compat#4 PCIe gen MB<Storage → fixing storage")
                        new_st = _deterministic_pick_storage(tier_name)
                        tc["storage"] = new_st
                        tc["storage_name"] = _storage_canonical_name(new_st)
                except Exception:
                    pass

            gn2 = tc["gpu_nvidia"]
            ga2 = tc["gpu_amd"]
            max_gpu_power = max(gn2.power_draw_watts, ga2.power_draw_watts)
            total_tdp_est = cpu_tdp_watts + max_gpu_power + 50
            min_req_w = int(math.ceil((total_tdp_est * 1.2) / 50.0) * 50)
            if psu and psu.wattage < min_req_w:
                print(f"[BUILD-RECO] [TIER={tier_name}] Compat#5 PSU wattage headroom → fixing PSU")
                new_psu = _deterministic_pick_psu(total_tdp_est, tier_name)
                tc["psu"] = new_psu
                tc["psu_name"] = _psu_canonical_name(new_psu)

        # ─────────────────────────────────────────────────────────
        # STEP 6: SIMPLE OUTPUTS (wattage int, bottleneck pct int)
        # ─────────────────────────────────────────────────────────
        def _calc_bn_pct(cpu_tn, gpu_tn) -> int:
            ct = tier_order_map.get(cpu_tn, 1)
            gt = tier_order_map.get(gpu_tn, 1)
            diff = ct - gt
            if diff == 0:
                return 7
            elif diff == -1:
                return 15
            elif diff == 1:
                return 12
            elif abs(diff) >= 2:
                return 30
            return 10

        bundles = {}
        for tier_name in ["entry", "mid", "high"]:
            tc = tier_components[tier_name]
            gn = tc["gpu_nvidia"]
            ga = tc["gpu_amd"]
            gpu_tier_for_bn = gn.performance_tier if tier_order_map.get(gn.performance_tier, 0) >= tier_order_map.get(ga.performance_tier, 0) else ga.performance_tier
            bn_pct = _calc_bn_pct(cpu_performance_tier, gpu_tier_for_bn)
            max_gpu_power = max(gn.power_draw_watts, ga.power_draw_watts)
            total_tdp = int(cpu_tdp_watts + max_gpu_power + 50)

            bundles[tier_name] = BuildRecoTierBundle(
                cpu=cpu_canonical,
                motherboard=tc["motherboard_name"],
                ram=tc["ram_name"],
                gpu_nvidia=tc["gpu_nvidia_name"],
                gpu_amd=tc["gpu_amd_name"],
                storage=tc["storage_name"],
                psu=tc["psu_name"],
                estimated_wattage=total_tdp,
                bottleneck_pct=bn_pct,
            )

        # ─────────────────────────────────────────────────────────
        # STEP 7: RETURN BuildRecoResponse
        # ─────────────────────────────────────────────────────────
        processing_ms = int((time.time() - t0) * 1000)
        return BuildRecoResponse(
            success=True,
            anchor_component=anchor_canonical,
            anchor_category=anchor_category,
            entry=bundles["entry"],
            mid=bundles["mid"],
            high=bundles["high"],
            processing_time_ms=processing_ms,
            error_message=None,
        )

    except Exception as e:
        import traceback
        traceback.print_exc()
        processing_ms = int((time.time() - t0) * 1000)
        fallback_cpu = req.component_name
        fallback_bundle = BuildRecoTierBundle(
            cpu=fallback_cpu,
            motherboard="",
            ram="",
            gpu_nvidia="",
            gpu_amd="",
            storage="",
            psu="",
            estimated_wattage=0,
            bottleneck_pct=0,
        )
        return BuildRecoResponse(
            success=False,
            anchor_component=req.component_name,
            anchor_category=req.component_category or "CPU",
            entry=fallback_bundle,
            mid=fallback_bundle,
            high=fallback_bundle,
            processing_time_ms=processing_ms,
            error_message=f"{type(e).__name__}: {e}",
        )


# =============================================================
# Error Handlers (top-level)
# =============================================================
@app.exception_handler(ValidationError)
async def validation_error_handler(request, exc: ValidationError):
    from fastapi.responses import JSONResponse
    first_err = exc.errors()[0] if exc.errors() else {}
    loc = ".".join(str(x) for x in first_err.get("loc", [])) or "payload"
    return JSONResponse(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        content={
            "success": False,
            "error_message": f"Invalid payload: {loc} — {first_err.get('msg','validation error')}",
        },
    )


@app.exception_handler(Exception)
async def unhandled_exception_handler(request, exc: Exception):
    from fastapi.responses import JSONResponse
    import traceback
    traceback.print_exc()
    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content={
            "success": False,
            "error_message": f"Internal server error: {type(exc).__name__}: {exc}",
        },
    )


# =============================================================
# Dev entrypoint (python main.py)
# =============================================================
if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host=settings.HOST,
        port=settings.PORT,
        reload=settings.ENVIRONMENT == "development",
        log_level=settings.LOG_LEVEL.lower()
    )
