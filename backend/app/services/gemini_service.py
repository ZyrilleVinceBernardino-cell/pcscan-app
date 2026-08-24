import json
import time
import re
import os
from typing import Optional, List, Dict, Any, Tuple

try:
    import google.generativeai as genai
    HAS_GOOGLE_GENAI = True
except ImportError:
    HAS_GOOGLE_GENAI = False

from dotenv import load_dotenv

from app.models.schemas import (
    ComponentInfo,
    OCRScanResult,
    HardwareSpecs,
    CPUSpec,
    GPUSpec,
    MotherboardSpec,
    RAMSpec,
    StorageSpec,
    PSUSpec,
    CoolerSpec,
    CaseSpec,
)
from app.services.mock_engine import (
    SAMPLE_BUILDS,
    build_hardware_specs_from_sample,
    _hash_pick,
    _is_quota_error,
)


load_dotenv()


VALID_COMPONENT_TYPES = [
    "CPU", "GPU", "Motherboard", "RAM", "Storage", "PSU", "Cooler", "Case"
]


OCR_IDENTIFY_PROMPT = """PC OCR + COMPONENT IDENTIFIER. RETURN JSON IMMEDIATELY. DIRECT, NO FLUFF.

STEP 1 — OCR: Read ALL visible text in the image. Exact model numbers only.

STEP 2 — IDENTIFY: Identify the EXACT PC component. 1-2 COMPONENTS ONLY — primary first.

RETURN EXACTLY THIS JSON (no markdown, no fences, no extra text):
{
  "raw_ocr_text": "all verbatim text from image",
  "components": [
    {
      "component_type": "CPU|GPU|Motherboard|RAM|Storage|PSU|Cooler|Case|Unknown",
      "brand": "exact brand",
      "exact_model": "exact specific model e.g. 'GeForce RTX 3060 12GB GDDR6'",
      "full_name": "brand + exact_model",
      "confidence_score": 0.XX,
      "specs": { "key1": "value1", "key2": "value2" }
    }
  ]
}

RULES:
- component_type: one of the 8 choices or Unknown
- confidence 0.0-1.0
- Primary component (clearest) = FIRST element in array
- specs = socket/wattage/speed/vram/capacity/form factor only
- If unsure → low confidence + Unknown type
- JSON ONLY. NO extra words.
- ALWAYS RESPOND IN NATURAL, PROFESSIONAL ENGLISH. Never respond in Tagalog, Filipino, or Taglish."""


def _clean_json_response(text: str) -> str:
    text = text.strip()
    if "```" in text:
        text = re.sub(r"```(?:json)?", "", text, flags=re.IGNORECASE).strip()
        text = text.strip("`").strip()
    start = text.find("{")
    end = text.rfind("}")
    if start != -1 and end != -1 and end > start:
        text = text[start : end + 1]
    return text


def _detect_tagalog(text: str) -> bool:
    """Detect if text contains Tagalog/Taglish words or phrases."""
    tagalog_indicators = [
        # Common Tagalog words
        "ang", "mga", "sa", "at", "ng", "ito", "iyon", "siya", "kami", "kayo", "sila",
        "ako", "ikaw", "tayo", "namin", "ninyo", "kanila", "amin", "inyo", "kanila",
        "may", "wala", "kailangan", "gusto", "ayaw", "pwede", "hindi", "oo", "hindi",
        "bakit", "paano", "kailan", "saan", "sino", "ano", "alin",
        # Taglish connectors
        "naman", "din", "rin", "lang", "na", "pa", "ba", "kasi", "eh", "ah", "yung",
        "nung", "pag", "kung", "para", "habang", "kahit", "tulad", "tungkol", "tama",
        # Verbs
        "gagawin", "ginagawa", "ginawa", "pupunta", "pumunta", "nagpunta", "mag",
        "nag", "pag", "i", "ina", "an", "um", "in",
        # Common phrases
        "pinakamataas", "kasalukuyang", "panatilihin", "palitan", "dagdagan", "maaari",
        "sapat", "angkop", "maganda", "mas", "mahusay", "piliin", "gamitin", "ito",
        "yun", "din", "rin", "naman", "lang", "kasi", "eh", "ah"
    ]
    
    text_lower = text.lower()
    for indicator in tagalog_indicators:
        if re.search(rf"\b{re.escape(indicator)}\b", text_lower):
            return True
    return False


def _validate_language(text: str) -> str:
    """Validate that AI-generated text is in English. If Tagalog detected, return error message."""
    if _detect_tagalog(text):
        raise ValueError(
            "AI-generated response contains Tagalog/Taglish text. "
            "The system requires English-only output. Please regenerate with English-only instructions."
        )
    return text


class GeminiService:
    """Gemini 1.5 Flash service for OCR + PC component identification."""

    def __init__(self, api_key: Optional[str] = None, model: Optional[str] = None,
                 temperature: Optional[float] = None, max_tokens: Optional[int] = None,
                 mock_mode: bool = False, auto_fallback: bool = True):
        self.api_key = api_key or os.getenv("GOOGLE_API_KEY", "")
        self.model_name = model or os.getenv("GEMINI_MODEL", "gemini-1.5-flash")
        self.temperature = temperature if temperature is not None else 0.0
        self.max_tokens = max_tokens if max_tokens is not None else 1024
        self.mock_mode = mock_mode if mock_mode is not None else (os.getenv("MOCK_MODE", "false").lower() == "true")
        self.auto_fallback = auto_fallback if auto_fallback is not None else (os.getenv("AUTO_FALLBACK", "true").lower() == "true")

        self._client = None
        self._model = None
        self._configured = False

        if HAS_GOOGLE_GENAI and self.api_key and not self.mock_mode:
            try:
                genai.configure(api_key=self.api_key)
                generation_config = {
                    "temperature": 0.0,
                    "max_output_tokens": 1024,
                }
                self._model = genai.GenerativeModel(self.model_name, generation_config=generation_config)
                self._configured = True
            except Exception:
                self._configured = False

    @property
    def is_ready(self) -> bool:
        if self.mock_mode:
            return True
        return self._configured and self._model is not None

    # ------------------------------------------------------------------
    # Internal: Real API call
    # ------------------------------------------------------------------
    def _call_gemini_with_image(self, image_bytes: bytes, mime_type: str, prompt: str) -> str:
        if not self._configured or self._model is None:
            raise RuntimeError("Gemini service is not configured. Check GOOGLE_API_KEY.")
        response = self._model.generate_content(
            [
                prompt,
                {
                    "mime_type": mime_type,
                    "data": image_bytes,
                },
            ]
        )
        if not response or not response.text:
            raise RuntimeError("Empty response from Gemini API")
        # Validate language - reject Tagalog/Taglish responses
        _validate_language(response.text)
        return response.text

    # ------------------------------------------------------------------
    # Public method 1: Raw OCR
    # ------------------------------------------------------------------
    def extract_ocr_text(self, image_bytes: bytes, mime_type: str = "image/jpeg") -> str:
        """Run Gemini OCR on the image and return raw extracted text only."""
        if self.mock_mode:
            idx = _hash_pick(str(len(image_bytes)) + str(time.time()), len(SAMPLE_BUILDS))
            sample = SAMPLE_BUILDS[idx]
            parts = [f"{k.upper()}: {v.get('name','')}" for k, v in sample.items()
                     if k in ("cpu", "gpu", "motherboard", "ram", "storage", "psu", "cooler", "case")]
            return "[MOCK OCR] " + sample["name"] + "\n" + "\n".join(parts)

        try:
            ocr_only_prompt = (
                "Extract ALL visible text, labels, numbers, barcodes, branding, "
                "and printed specifications from the image VERBATIM. Do NOT summarize. "
                "Do NOT add your own commentary. Just return everything you can read, "
                "preserving line breaks where possible."
            )
            return self._call_gemini_with_image(image_bytes, mime_type, ocr_only_prompt).strip()
        except Exception:
            if self.auto_fallback:
                idx = _hash_pick(str(len(image_bytes)), len(SAMPLE_BUILDS))
                sample = SAMPLE_BUILDS[idx]
                parts = [f"{k.upper()}: {v.get('name','')}" for k, v in sample.items()
                         if k in ("cpu", "gpu", "motherboard", "ram", "storage", "psu", "cooler", "case")]
                return "[FALLBACK OCR] " + sample["name"] + "\n" + "\n".join(parts)
            raise

    # ------------------------------------------------------------------
    # Public method 2: Component identification (parsed JSON)
    # ------------------------------------------------------------------
    def identify_components(self, image_bytes: bytes, mime_type: str = "image/jpeg") -> Tuple[str, List[ComponentInfo]]:
        """Run OCR + identification and return (raw_ocr_text, list of ComponentInfo)."""
        raw_ocr_text = ""

        if not self.mock_mode and self._configured:
            try:
                resp_text = self._call_gemini_with_image(
                    image_bytes, mime_type, OCR_IDENTIFY_PROMPT
                )
                cleaned = _clean_json_response(resp_text)
                parsed = json.loads(cleaned)
                raw_ocr_text = str(parsed.get("raw_ocr_text", "")).strip()
                components_raw = parsed.get("components", []) or []
                component_list: List[ComponentInfo] = []
                for c in components_raw:
                    ctype = str(c.get("component_type", "Unknown")).strip()
                    if ctype not in VALID_COMPONENT_TYPES and ctype != "Unknown":
                        ctype = "Unknown"
                    brand = str(c.get("brand", "")).strip()
                    exact_model = str(c.get("exact_model", "")).strip()
                    full_name = str(c.get("full_name", "")).strip() or f"{brand} {exact_model}".strip()
                    try:
                        confidence = float(c.get("confidence_score", 0.5))
                    except (TypeError, ValueError):
                        confidence = 0.5
                    confidence = max(0.0, min(1.0, confidence))
                    specs = c.get("specs")
                    if not isinstance(specs, dict):
                        specs = None
                    component_list.append(ComponentInfo(
                        component_type=ctype,
                        brand=brand,
                        exact_model=exact_model or "Unknown",
                        full_name=full_name,
                        confidence_score=confidence,
                        raw_ocr_text=raw_ocr_text[:2000] if raw_ocr_text else None,
                        specs=specs,
                    ))
                return raw_ocr_text, component_list
            except Exception as e:
                if not self.auto_fallback and not _is_quota_error(e):
                    raise

        idx = _hash_pick(str(len(image_bytes)) + str(time.time()), len(SAMPLE_BUILDS))
        sample = SAMPLE_BUILDS[idx]
        parts = [f"{k.upper()}: {v.get('name','')}" for k, v in sample.items()
                 if k in ("cpu", "gpu", "motherboard", "ram", "storage", "psu", "cooler", "case")]
        raw_ocr_text = f"[{'MOCK' if self.mock_mode else 'FALLBACK'}] " + sample["name"] + "\n" + "\n".join(parts)

        spec_map = [
            ("CPU", sample.get("cpu"), "cores", "tdp"),
            ("GPU", sample.get("gpu"), "vram", "tdp"),
            ("Motherboard", sample.get("motherboard"), "socket", "chipset"),
            ("RAM", sample.get("ram"), "capacity", "speed"),
            ("Storage", sample.get("storage"), "type", "capacity"),
            ("PSU", sample.get("psu"), "wattage", "efficiency"),
            ("Cooler", sample.get("cooler"), "type", "tdp_rating"),
            ("Case", sample.get("case"), "form_factor", None),
        ]
        component_list: List[ComponentInfo] = []
        for ctype, spec, k1, k2 in spec_map:
            if spec and spec.get("name"):
                name = spec["name"]
                brand = name.split()[0] if name else ""
                exact_model = " ".join(name.split()[1:]) if len(name.split()) > 1 else name
                specs_extra = {k: spec[k] for k in (k1, k2) if k and spec.get(k)}
                component_list.append(ComponentInfo(
                    component_type=ctype,
                    brand=brand,
                    exact_model=exact_model or name,
                    full_name=name,
                    confidence_score=round(0.82 + (hash(name) % 17) / 100, 2),
                    raw_ocr_text=raw_ocr_text[:2000],
                    specs=specs_extra or None,
                ))
        return raw_ocr_text, component_list

    # ------------------------------------------------------------------
    # Public method 3: Unified scan → OCRScanResult
    # ------------------------------------------------------------------
    def scan_image(self, image_bytes: bytes, mime_type: str = "image/jpeg",
                   build_hardware_from_components: bool = True) -> OCRScanResult:
        """
        High-level entry point: accept image bytes, run Gemini OCR + identify,
        return OCRScanResult matching the schemas.
        """
        t0 = int(time.time() * 1000)
        error_msg: Optional[str] = None
        success = True
        raw_ocr_text = ""
        component_list: List[ComponentInfo] = []
        primary: Optional[ComponentInfo] = None
        hardware_specs: Optional[HardwareSpecs] = None

        try:
            raw_ocr_text, component_list = self.identify_components(image_bytes, mime_type)
        except Exception as e:
            success = False
            error_msg = f"Gemini scan failed: {type(e).__name__}: {e}"

        if component_list:
            primary = max(component_list, key=lambda c: c.confidence_score)

            if build_hardware_from_components:
                hardware_specs = self._build_hardware_specs_from_components(component_list)

                if hardware_specs is None:
                    idx = _hash_pick(raw_ocr_text or "default", len(SAMPLE_BUILDS))
                    hardware_specs = build_hardware_specs_from_sample(SAMPLE_BUILDS[idx])

        overall_conf = 0.0
        if component_list:
            overall_conf = sum(c.confidence_score for c in component_list) / len(component_list)

        elapsed = int(time.time() * 1000) - t0

        return OCRScanResult(
            success=success,
            raw_ocr_text=raw_ocr_text,
            identified_components=component_list,
            primary_component=primary,
            extracted_hardware_specs=hardware_specs,
            overall_confidence=round(overall_conf, 3),
            processing_time_ms=elapsed,
            error_message=error_msg,
        )

    # ------------------------------------------------------------------
    # Helper: ComponentInfo list → HardwareSpecs
    # ------------------------------------------------------------------
    @staticmethod
    def _build_hardware_specs_from_components(components: List[ComponentInfo]) -> Optional[HardwareSpecs]:
        """Best-effort conversion of ComponentInfo[] → HardwareSpecs."""
        specs = HardwareSpecs()
        any_filled = False

        def _parse_int(val: Any) -> Optional[int]:
            if val is None:
                return None
            try:
                digits = re.sub(r"[^\d]", "", str(val))
                return int(digits) if digits else None
            except Exception:
                return None

        def _parse_float(val: Any) -> Optional[float]:
            if val is None:
                return None
            try:
                digits = re.sub(r"[^\d.]", "", str(val))
                return float(digits) if digits else None
            except Exception:
                return None

        for c in components:
            s = c.specs or {}
            name = c.full_name or c.exact_model
            if c.component_type == "CPU":
                specs.cpu = CPUSpec(
                    name=name,
                    brand=c.brand or None,
                    cores=_parse_int(s.get("cores")),
                    threads=_parse_int(s.get("threads")),
                    base_clock=str(s.get("base_clock")) if s.get("base_clock") else None,
                    boost_clock=str(s.get("boost_clock")) if s.get("boost_clock") else None,
                    socket=str(s.get("socket")) if s.get("socket") else None,
                    tdp_watts=_parse_int(s.get("tdp") or s.get("tdp_watts")),
                )
                any_filled = True
            elif c.component_type == "GPU":
                specs.gpu = GPUSpec(
                    name=name,
                    brand=c.brand or None,
                    vram_gb=_parse_float(s.get("vram") or s.get("vram_gb")),
                    memory_type=str(s.get("memory_type")) if s.get("memory_type") else None,
                    interface=str(s.get("interface")) if s.get("interface") else None,
                    tdp_watts=_parse_int(s.get("tdp") or s.get("tdp_watts")),
                    length_mm=_parse_int(s.get("length_mm")),
                )
                any_filled = True
            elif c.component_type == "Motherboard":
                specs.motherboard = MotherboardSpec(
                    name=name,
                    brand=c.brand or None,
                    socket=str(s.get("socket")) if s.get("socket") else None,
                    chipset=str(s.get("chipset")) if s.get("chipset") else None,
                    form_factor=str(s.get("form_factor")) if s.get("form_factor") else None,
                    memory_slots=_parse_int(s.get("memory_slots")),
                    max_ram_gb=_parse_int(s.get("max_ram") or s.get("max_ram_gb")),
                    ram_type=str(s.get("ram_type")) if s.get("ram_type") else None,
                )
                any_filled = True
            elif c.component_type == "RAM":
                specs.ram = RAMSpec(
                    name=name,
                    brand=c.brand or None,
                    total_capacity_gb=_parse_int(s.get("capacity") or s.get("total_capacity_gb")),
                    module_count=_parse_int(s.get("modules") or s.get("module_count")),
                    ram_type=str(s.get("type") or s.get("ram_type")) if (s.get("type") or s.get("ram_type")) else None,
                    speed_mhz=_parse_int(s.get("speed") or s.get("speed_mhz")),
                    cas_latency=_parse_int(s.get("latency") or s.get("cas_latency")),
                )
                any_filled = True
            elif c.component_type == "Storage":
                specs.storage = StorageSpec(
                    name=name,
                    brand=c.brand or None,
                    type=str(s.get("type")) if s.get("type") else None,
                    capacity_gb=_parse_int(s.get("capacity") or s.get("capacity_gb")),
                    interface=str(s.get("interface")) if s.get("interface") else None,
                    form_factor=str(s.get("form_factor")) if s.get("form_factor") else None,
                )
                any_filled = True
            elif c.component_type == "PSU":
                specs.psu = PSUSpec(
                    name=name,
                    brand=c.brand or None,
                    wattage=_parse_int(s.get("wattage")),
                    efficiency_rating=str(s.get("efficiency") or s.get("efficiency_rating")) if (s.get("efficiency") or s.get("efficiency_rating")) else None,
                    modular_type=str(s.get("modular") or s.get("modular_type")) if (s.get("modular") or s.get("modular_type")) else None,
                )
                any_filled = True
            elif c.component_type == "Cooler":
                specs.cooler = CoolerSpec(
                    name=name,
                    brand=c.brand or None,
                    type=str(s.get("type")) if s.get("type") else None,
                    tdp_rating_watts=_parse_int(s.get("tdp_rating") or s.get("tdp_rating_watts")),
                    height_mm=_parse_int(s.get("height_mm")),
                )
                any_filled = True
            elif c.component_type == "Case":
                form_factor_val = s.get("form_factor") or s.get("form_factor_support")
                ff_list: List[str] = []
                if isinstance(form_factor_val, list):
                    ff_list = [str(x) for x in form_factor_val]
                elif isinstance(form_factor_val, str) and form_factor_val.strip():
                    ff_list = [form_factor_val.strip()]
                specs.case = CaseSpec(
                    name=name,
                    brand=c.brand or None,
                    form_factor_support=ff_list or None,
                    max_gpu_length_mm=_parse_int(s.get("max_gpu_length_mm")),
                    max_cooler_height_mm=_parse_int(s.get("max_cooler_height_mm")),
                )
                any_filled = True

        return specs if any_filled else None
