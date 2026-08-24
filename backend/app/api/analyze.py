from fastapi import APIRouter, HTTPException
import json
import time
from typing import Optional

from app.config import settings
from app.models.schemas import (
    AnalyzeTextRequest,
    AnalyzeTextResponse,
    IdentifiedComponent,
    HardwareSpecs,
    CPUSpec,
    GPUSpec,
    MotherboardSpec,
    RAMSpec,
    StorageSpec,
    PSUSpec,
    CoolerSpec,
    CaseSpec
)
from app.services.mock_engine import mock_analyze_text_response, should_use_mock

router = APIRouter()


def _get_openai_client():
    from openai import OpenAI
    params = {"api_key": settings.OPENAI_API_KEY}
    if settings.OPENAI_ORG_ID:
        params["organization"] = settings.OPENAI_ORG_ID
    return OpenAI(**params)


def _get_gemini_model():
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


def _safe_parse_json(text: str) -> Optional[dict]:
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


ANALYZE_PROMPT = """PC HARDWARE IDENTIFIER + SPECS EXTRACTOR. DIRECT, TUMPAK. RETURN JSON AGAD.

1. IDENTIFY: ALL specific components mentioned. 1-2 LANG — hindi damihan. Exact model, not vague series.
2. EXTRACT: Detailed specs for each.

RETURN EXACT JSON ONLY (no fences, no extra text, no intro):
{
  "identified_components": [
    {
      "component_type": "CPU|GPU|Motherboard|RAM|Storage|PSU|Cooler|Case",
      "name": "exact full model e.g. 'Intel Core i5-13600KF'",
      "confidence": 0.0-1.0,
      "raw_text": "original identifying text"
    }
  ],
  "hardware_specs": {
    "cpu": { "name":"","cores":null,"threads":null,"base_clock":"","boost_clock":"","socket":"","tdp":"" },
    "gpu": { "name":"","vram":"","memory_type":"","interface":"","tdp":"" },
    "motherboard": { "name":"","socket":"","chipset":"","form_factor":"","memory_slots":null,"max_ram":"" },
    "ram": { "name":"","capacity":"","modules":null,"type":"","speed":"","latency":"" },
    "storage": { "name":"","type":"","capacity":"","interface":"" },
    "psu": { "name":"","wattage":"","efficiency":"","modular":"" },
    "cooler": { "name":"","type":"","tdp_rating":"" },
    "case": { "name":"","form_factor":"" }
  },
  "overall_confidence": 0.0-1.0,
  "analysis_summary": "1-2 sentence summary"
}

RULES:
- Exact lang. "Ryzen 5 5600X" → 5600X specs, never 5600X → 5800X specs
- 1-2 COMPONENTS MAX — only explicitly mentioned
- Leave empty/null if unsure. Be CONSERVATIVE.
- JSON LANG. No intro sentences."""


def _build_specs_from_dict(data: dict) -> Optional[HardwareSpecs]:
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


async def _analyze_hardware_specs(query_text: str, existing_specs: Optional[HardwareSpecs] = None) -> AnalyzeTextResponse:
    """Internal helper to analyze hardware - used by scan-and-analyze pipeline."""
    if settings.MOCK_MODE:
        time.sleep(0.25)
        return mock_analyze_text_response(query_text or "hardware query")

    last_error = None
    result_text = ""

    try:
        client = _get_openai_client()
        system_msg = ANALYZE_PROMPT
        if existing_specs:
            import json as _json
            system_msg += (
                "\n\nADDITIONAL CONTEXT (from OCR step - you may use these specs to fill in missing details):\n"
                + _json.dumps(existing_specs.model_dump(exclude_none=True), default=str, indent=2)
            )
        response = client.chat.completions.create(
            model=settings.OPENAI_MODEL,
            temperature=0.0,
            max_tokens=1024,
            messages=[
                {"role": "system", "content": system_msg},
                {"role": "user", "content": f"Analyze this hardware text:\n{query_text}"}
            ],
            response_format={"type": "json_object"}
        )
        result_text = response.choices[0].message.content or ""
    except Exception as e1:
        last_error = e1
        if should_use_mock(e1, settings):
            return mock_analyze_text_response(query_text or "hardware query")
        try:
            model = _get_gemini_model()
            full_prompt = ANALYZE_PROMPT + "\n\nUser hardware text:\n" + query_text
            if existing_specs:
                import json as _json
                full_prompt += (
                    "\n\nADDITIONAL CONTEXT (from OCR step - use these specs):\n"
                    + _json.dumps(existing_specs.model_dump(exclude_none=True), default=str, indent=2)
                )
            resp = model.generate_content(full_prompt)
            result_text = resp.text if hasattr(resp, "text") else str(resp)
        except Exception as e2:
            last_error = e2
            if should_use_mock(e2, settings):
                return mock_analyze_text_response(query_text or "hardware query")
            return AnalyzeTextResponse(
                success=False,
                query=query_text,
                overall_confidence=0.0,
                error=f"AI API Error: {str(e2)}"
            )

    parsed = _safe_parse_json(result_text)
    if not parsed:
        if should_use_mock(None, settings):
            return mock_analyze_text_response(query_text or "hardware query")
        return AnalyzeTextResponse(
            success=False,
            query=query_text,
            overall_confidence=0.0,
            error="Failed to parse AI response"
        )

    identified = []
    for comp in parsed.get("identified_components", []):
        try:
            identified.append(IdentifiedComponent(
                component_type=comp.get("component_type", "Unknown"),
                name=comp.get("name", ""),
                confidence=float(comp.get("confidence", 0.0)),
                raw_text=comp.get("raw_text")
            ))
        except Exception:
            continue

    specs = _build_specs_from_dict(parsed.get("hardware_specs"))
    if existing_specs and not specs:
        specs = existing_specs

    return AnalyzeTextResponse(
        success=True,
        query=query_text,
        identified_components=identified,
        hardware_specs=specs,
        overall_confidence=float(parsed.get("overall_confidence", 0.0)),
        analysis_summary=parsed.get("analysis_summary")
    )


@router.post("/analyze-text", response_model=AnalyzeTextResponse, summary="Analyze hardware text/search query")
async def analyze_text(request: AnalyzeTextRequest):
    if not request.text or len(request.text.strip()) == 0:
        raise HTTPException(status_code=400, detail="Text query cannot be empty")

    return await _analyze_hardware_specs(request.text.strip())
