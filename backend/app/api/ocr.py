from fastapi import APIRouter, UploadFile, File, HTTPException
from fastapi.responses import JSONResponse
import time
import io
import json
import os
from PIL import Image
from typing import Optional

from app.config import settings
from app.models.schemas import (
    OCRScanResponse,
    IdentifiedComponent,
    HardwareSpecs,
    CPUSpec,
    GPUSpec,
    MotherboardSpec,
    RAMSpec,
    StorageSpec,
    PSUSpec,
    ScanAndAnalyzeResponse,
    AnalyzeTextResponse,
    BuildRecommendationResponse
)
from app.services.mock_engine import mock_ocr_response, should_use_mock

router = APIRouter()

ALLOWED_TYPES = [t.strip() for t in settings.ALLOWED_IMAGE_TYPES.split(",")]
MAX_SIZE_BYTES = settings.MAX_UPLOAD_SIZE_MB * 1024 * 1024


def _get_gemini_model():
    import google.generativeai as genai
    genai.configure(api_key=settings.GOOGLE_API_KEY)
    generation_config = {
        "temperature": settings.GEMINI_TEMPERATURE,
        "max_output_tokens": settings.GEMINI_MAX_OUTPUT_TOKENS,
    }
    model = genai.GenerativeModel(
        model_name=settings.GEMINI_MODEL,
        generation_config=generation_config
    )
    return model


GEMINI_OCR_PROMPT = """You are a PC hardware OCR and identification expert. Given an image of PC components (boxes, labels, receipts, product listings, build screenshots, etc.), do the following:

1. Extract ALL readable text from the image (raw OCR).
2. Identify each specific PC hardware component found.
3. For each component, determine its detailed specifications.

Return ONLY a valid JSON object with the following structure (do NOT wrap in markdown code blocks):
{
  "raw_ocr_text": "complete extracted text",
  "identified_components": [
    {
      "component_type": "CPU" or "GPU" or "Motherboard" or "RAM" or "Storage" or "PSU" or "Cooler" or "Case",
      "name": "exact component model name",
      "confidence": 0.0 to 1.0,
      "raw_text": "the exact text that identified this component"
    }
  ],
  "hardware_specs": {
    "cpu": { "name": "", "cores": null, "threads": null, "base_clock": "", "boost_clock": "", "socket": "", "tdp": "" },
    "gpu": { "name": "", "vram": "", "memory_type": "", "interface": "", "tdp": "" },
    "motherboard": { "name": "", "socket": "", "chipset": "", "form_factor": "", "memory_slots": null, "max_ram": "" },
    "ram": { "name": "", "capacity": "", "modules": null, "type": "", "speed": "", "latency": "" },
    "storage": { "name": "", "type": "", "capacity": "", "interface": "" },
    "psu": { "name": "", "wattage": "", "efficiency": "", "modular": "" },
    "cooler": { "name": "", "type": "", "tdp_rating": "" },
    "case": { "name": "", "form_factor": "" }
  },
  "overall_confidence": 0.0 to 1.0
}

Rules:
- Only populate fields for components actually identified in the image.
- Be precise with model names (e.g., "RTX 4070 Ti" not just "RTX 4000 series").
- confidence scores must be between 0 and 1.
- If a component is not found, omit it from identified_components and leave its spec as null/empty.
- Return valid JSON only. No extra text.
"""


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
        from app.models.schemas import CoolerSpec
        hs.cooler = CoolerSpec(**{k: v for k, v in data["cooler"].items() if v is not None and v != ""})
    if data.get("case") and data["case"].get("name"):
        from app.models.schemas import CaseSpec
        hs.case = CaseSpec(**{k: v for k, v in data["case"].items() if v is not None and v != ""})
    return hs


@router.post("/scan", response_model=OCRScanResponse, summary="OCR Scan Image (Gemini)")
async def ocr_scan(file: UploadFile = File(..., description="Image file to scan (JPG/PNG/WEBP)")):
    start_time = time.time()

    if file.content_type not in ALLOWED_TYPES:
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported image type: {file.content_type}. Allowed: {ALLOWED_TYPES}"
        )

    contents = await file.read()
    if len(contents) > MAX_SIZE_BYTES:
        raise HTTPException(
            status_code=400,
            detail=f"File too large. Max {settings.MAX_UPLOAD_SIZE_MB}MB, got {len(contents) / 1024 / 1024:.2f}MB"
        )

    try:
        pil_image = Image.open(io.BytesIO(contents))
        pil_image.verify()
        pil_image = Image.open(io.BytesIO(contents))
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid or corrupted image file")

    file_size_hint = f"{file.filename or 'uploaded_img'}_{len(contents)}"

    if settings.MOCK_MODE:
        time.sleep(0.35)
        resp = mock_ocr_response(image_file_hint=file_size_hint)
        resp.processing_time_ms = int((time.time() - start_time) * 1000)
        return resp

    try:
        model = _get_gemini_model()
        response = model.generate_content([
            GEMINI_OCR_PROMPT,
            {
                "mime_type": file.content_type,
                "data": contents
            }
        ])
        result_text = response.text if hasattr(response, "text") else str(response)
    except Exception as e:
        if should_use_mock(e, settings):
            resp = mock_ocr_response(image_file_hint=file_size_hint)
            resp.processing_time_ms = int((time.time() - start_time) * 1000)
            resp.error = None if settings.AUTO_FALLBACK else f"Gemini API Error: {str(e)}"
            return resp
        processing_ms = int((time.time() - start_time) * 1000)
        return OCRScanResponse(
            success=False,
            raw_ocr_text="",
            overall_confidence=0.0,
            processing_time_ms=processing_ms,
            error=f"Gemini API Error: {str(e)}"
        )

    parsed = _safe_parse_json(result_text)
    processing_ms = int((time.time() - start_time) * 1000)

    if not parsed:
        if should_use_mock(None, settings):
            resp = mock_ocr_response(image_file_hint=file_size_hint)
            resp.processing_time_ms = processing_ms
            return resp
        return OCRScanResponse(
            success=False,
            raw_ocr_text=result_text[:2000] if result_text else "",
            overall_confidence=0.0,
            processing_time_ms=processing_ms,
            error="Failed to parse AI response JSON"
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

    return OCRScanResponse(
        success=True,
        raw_ocr_text=parsed.get("raw_ocr_text", "")[:5000],
        identified_components=identified,
        hardware_specs=specs,
        overall_confidence=float(parsed.get("overall_confidence", 0.0)),
        processing_time_ms=processing_ms
    )


@router.post("/scan-and-analyze", response_model=ScanAndAnalyzeResponse, summary="Full pipeline: Scan + Analyze + Recommend")
async def scan_and_analyze(file: UploadFile = File(..., description="Image file to scan")):
    start_time = time.time()

    ocr_resp = await ocr_scan(file)
    if not ocr_resp.success:
        total_ms = int((time.time() - start_time) * 1000)
        return ScanAndAnalyzeResponse(
            success=False,
            ocr_result=ocr_resp,
            total_processing_time_ms=total_ms,
            error=ocr_resp.error or "OCR scan failed"
        )

    analysis = None
    recommendations = None

    if ocr_resp.hardware_specs:
        try:
            from app.api.analyze import _analyze_hardware_specs
            identified_text_parts = [f"{c.component_type}: {c.name}" for c in ocr_resp.identified_components]
            analysis_query = "\n".join(identified_text_parts) or ocr_resp.raw_ocr_text
            analysis = await _analyze_hardware_specs(analysis_query, ocr_resp.hardware_specs)
        except Exception as e:
            analysis = AnalyzeTextResponse(
                success=False,
                query="",
                overall_confidence=0.0,
                error=f"Analysis step error: {str(e)}"
            )

        try:
            from app.api.recommend import _generate_recommendations
            recommendations = await _generate_recommendations(ocr_resp.hardware_specs)
        except Exception as e:
            recommendations = BuildRecommendationResponse(
                success=False,
                overall_score=0.0,
                error=f"Recommendation step error: {str(e)}"
            )

    total_ms = int((time.time() - start_time) * 1000)
    return ScanAndAnalyzeResponse(
        success=True,
        ocr_result=ocr_resp,
        analysis=analysis,
        recommendations=recommendations,
        total_processing_time_ms=total_ms
    )
