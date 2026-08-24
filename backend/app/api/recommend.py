from fastapi import APIRouter, HTTPException
import json
import time
from typing import Optional, List

from app.config import settings
from app.models.schemas import (
    HardwareSpecs,
    BuildRecommendationResponse,
    BuildRecommendationItem,
    BottleneckAnalysis,
    CompatibilityIssue
)
from app.services.mock_engine import mock_build_recommendation_response, should_use_mock
from app.services.compatibility_engine import compatibility_engine

router = APIRouter()


def _get_openai_client():
    from openai import OpenAI
    params = {"api_key": settings.OPENAI_API_KEY}
    if settings.OPENAI_ORG_ID:
        params["organization"] = settings.OPENAI_ORG_ID
    return OpenAI(**params)


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


RECOMMEND_PROMPT = """PC BUILD ANALYZER. DIRECT, TUMPAK NA SAGOT. RETURN JSON AGAD. 1-2 REKOMENDASYON LANG.

GIVEN: User's PC build specs.
RUN:
1. Bottleneck Detection — compute pct, name component
2. Compatibility Check — socket/RAM/PSU issues ONLY (1-2 critical lang)
3. Build Score 0-100 — balance + capability
4. Upgrade Recommendations — PRIORITY: 1-2 items LANG (hindi damihan). Pinakamaganda lang. Specific PHP model names.
5. Performance Level — Entry/Mid/High/Enthusiast
6. 3-5 Games suitable

RETURN EXACT JSON (no fences, no extra text, no intro):
{
  "build_name": "short build name",
  "overall_score": 0-100,
  "bottleneck_analysis": {
    "has_bottleneck": bool,
    "bottleneck_component": "CPU/GPU/RAM/Storage/null",
    "bottleneck_percentage": 0-100 or null,
    "description": "1-2 sentences",
    "affected_tasks": ["task1", "task2"]
  },
  "compatibility_issues": [
    { "severity": "critical/warning/info", "components_involved": ["A","B"], "issue": "...", "resolution": "..." }
  ],
  "recommendations": [
    {
      "priority": 1,
      "category": "CPU/GPU/RAM/Storage/PSU/Cooler/Case/Motherboard",
      "current_component": "existing or null",
      "recommended_component": "EXACT model (PH market)",
      "reason": "Why this upgrade - 1 sentence",
      "estimated_price_range": "PHP X,Y - PHP Z,W",
      "performance_improvement": "+30% FPS / etc.",
      "compatibility_notes": "Check socket/PSU etc."
    }
  ],
  "total_estimated_cost": "PHP total or N/A",
  "estimated_performance_level": "Entry/Mid-Range/High-End/Enthusiast",
  "best_for_games": ["Game1", "Game2", "Game3"]
}

RULES:
- EXACT models lang. Never "upgrade GPU"
- PHP realistic 2024-2025 PH rates
- 1-2 recos ONLY — highest impact per peso
- PSU always check kapag GPU upgrade
- Socket/RAM gen mismatches: IMMEDIATELY flag, walang paligoy
- descriptions maikli, diretso
- JSON LANG. No intro text."""


async def _generate_recommendations(specs: HardwareSpecs) -> BuildRecommendationResponse:
    """Internal helper: Generate build recommendations from HardwareSpecs."""
    if not specs:
        return BuildRecommendationResponse(
            success=False,
            overall_score=0.0,
            error="No hardware specs provided"
        )

    specs_json = specs.model_dump(exclude_none=True)
    if not any(specs_json.values()):
        return BuildRecommendationResponse(
            success=False,
            overall_score=0.0,
            error="Empty hardware specs - no components identified"
        )

    hint_key = (specs.cpu.name if specs.cpu else "") + "|" + (specs.gpu.name if specs.gpu else "")

    if settings.MOCK_MODE:
        time.sleep(0.45)
        return mock_build_recommendation_response(specs, query_hint=hint_key)

    try:
        client = _get_openai_client()
        response = client.chat.completions.create(
            model=settings.OPENAI_MODEL,
            temperature=0.0,
            max_tokens=1024,
            messages=[
                {"role": "system", "content": RECOMMEND_PROMPT},
                {"role": "user", "content": "Analyze this PC build and return the JSON report:\n" + json.dumps(specs_json, indent=2, default=str)}
            ],
            response_format={"type": "json_object"}
        )
        result_text = response.choices[0].message.content or ""
    except Exception as e:
        if should_use_mock(e, settings):
            return mock_build_recommendation_response(specs, query_hint=hint_key)
        return BuildRecommendationResponse(
            success=False,
            overall_score=0.0,
            error=f"OpenAI API Error: {str(e)}"
        )

    parsed = _safe_parse_json(result_text)
    if not parsed:
        if should_use_mock(None, settings):
            return mock_build_recommendation_response(specs, query_hint=hint_key)
        return BuildRecommendationResponse(
            success=False,
            overall_score=0.0,
            error="Failed to parse AI response JSON"
        )

    try:
        bn = parsed.get("bottleneck_analysis") or {}
        bottleneck = BottleneckAnalysis(
            has_bottleneck=bool(bn.get("has_bottleneck", False)),
            bottleneck_component=bn.get("bottleneck_component"),
            bottleneck_percentage=float(bn["bottleneck_percentage"]) if bn.get("bottleneck_percentage") is not None else None,
            description=bn.get("description"),
            affected_tasks=list(bn.get("affected_tasks", []) or [])
        )
    except Exception:
        bottleneck = None

    compat_issues: List[CompatibilityIssue] = []
    for issue in parsed.get("compatibility_issues", []) or []:
        try:
            compat_issues.append(CompatibilityIssue(
                severity=issue.get("severity", "warning"),
                components_involved=list(issue.get("components_involved", []) or []),
                issue=issue.get("issue", ""),
                resolution=issue.get("resolution")
            ))
        except Exception:
            continue

    recs: List[BuildRecommendationItem] = []
    for r in parsed.get("recommendations", []) or []:
        try:
            recs.append(BuildRecommendationItem(
                priority=int(r.get("priority", 99)),
                category=r.get("category", ""),
                current_component=r.get("current_component"),
                recommended_component=r.get("recommended_component", ""),
                reason=r.get("reason", ""),
                estimated_price_range=r.get("estimated_price_range"),
                performance_improvement=r.get("performance_improvement"),
                compatibility_notes=r.get("compatibility_notes")
            ))
        except Exception:
            continue
    recs.sort(key=lambda x: x.priority)

    return BuildRecommendationResponse(
        success=True,
        build_name=parsed.get("build_name"),
        overall_score=float(parsed.get("overall_score", 0.0)),
        bottleneck_analysis=bottleneck,
        compatibility_issues=compat_issues,
        recommendations=recs,
        total_estimated_cost=parsed.get("total_estimated_cost"),
        estimated_performance_level=parsed.get("estimated_performance_level"),
        best_for_games=list(parsed.get("best_for_games", []) or [])
    )


@router.post("/recommend-build", response_model=BuildRecommendationResponse, summary="Build analysis & upgrade recommendations (OpenAI)")
async def recommend_build(specs: HardwareSpecs):
    # Validate build using deterministic compatibility engine before processing
    is_valid, engine_issues = compatibility_engine.validate_build(specs)
    
    # Convert engine issues to API format
    compat_issues: List[CompatibilityIssue] = []
    for engine_issue in engine_issues:
        compat_issues.append(engine_issue)
    
    # Generate recommendations
    result = await _generate_recommendations(specs)
    
    if not result.success and result.error:
        raise HTTPException(status_code=400, detail=result.error)
    
    # Override compatibility issues with deterministic engine results
    result.compatibility_issues = compat_issues
    
    # If there are critical compatibility issues, mark as not fully compatible
    has_critical = any(issue.severity == "critical" for issue in compat_issues)
    if has_critical:
        result.bottleneck_analysis = BottleneckAnalysis(
            has_bottleneck=True,
            bottleneck_component="Compatibility",
            bottleneck_percentage=0.0,
            description="Critical compatibility issues detected by deterministic engine. Build may not POST or work correctly.",
            affected_tasks=["System Boot", "Hardware Operation"],
            recommended_fix="Resolve critical compatibility issues before proceeding"
        )
    
    return result
