from fastapi import APIRouter, HTTPException
import json
import time
from typing import Optional, List

from app.config import settings
from app.models.schemas import (
    UpgradePathRequest,
    UpgradePathResponse,
    UpgradeStep
)
from app.services.mock_engine import mock_upgrade_path_response, should_use_mock
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


UPGRADE_PATH_PROMPT = """PC UPGRADE PATH PLANNER. DIRECT, TUMPAK. RETURN JSON AGAD. 1-3 STEPS LANG. HINDI DAMIHAN.

GIVEN: Current specs, budget, use case, target perf.
CREATE PHased upgrade path:
- 1-3 STEPS ONLY (NOT 4-6) — most impactful per peso first
- Budget-aware, specific EXACT models
- ALL compatible at each phase
- Priority-tagged: critical/high/medium/low

RETURN EXACT JSON (no fences, no extra text, no intro):
{
  "use_case": "echoed, normalized",
  "budget": "echoed",
  "current_score": 0-100,
  "projected_score": 0-100,
  "upgrade_steps": [
    {
      "step_order": 1,
      "title": "short upgrade title",
      "description": "1-2 sentences why",
      "component_category": "CPU/GPU/RAM/Storage/PSU/Cooler/Motherboard/Case",
      "old_component": "old name or null",
      "new_component": "EXACT model (PH market)",
      "price_range": "PHP X,Y - PHP Z,W",
      "performance_gain": "+25% FPS uplift",
      "priority": "critical/high/medium/low"
    }
  ],
  "total_estimated_cost": "PHP total or N/A",
  "summary": "2-3 sentence exec summary"
}

RULES:
- 1-3 STEPS LANG — huwag lumampas
- EXACT PH-market models, realistic PHP 2024-2025 pricing
- GPU upgrade → CHECK PSU MUNA; include PSU step kasabay if needed
- Order: highest impact, cheapest wins first
- JSON LANG. No intro words."""


@router.post("/upgrade-path", response_model=UpgradePathResponse, summary="Generate step-by-step upgrade path plan")
async def upgrade_path(request: UpgradePathRequest):
    # Validate current build using deterministic compatibility engine
    is_valid, engine_issues = compatibility_engine.validate_build(request.current_specs)
    
    if settings.MOCK_MODE:
        time.sleep(0.55)
        result = mock_upgrade_path_response(request)
        
        # Filter upgrade steps to remove invalid upgrades
        if result.success and result.upgrade_steps:
            filtered_steps = []
            for step in result.upgrade_steps:
                # Validate GPU upgrades
                if step.component_category == "GPU" and step.old_component:
                    is_valid_upgrade, reason = compatibility_engine.check_gpu_upgrade_validity(
                        step.old_component, step.new_component
                    )
                    if not is_valid_upgrade:
                        continue  # Skip invalid upgrade
                
                # Validate CPU upgrades
                if step.component_category == "CPU" and step.old_component:
                    motherboard_name = request.current_specs.motherboard.name if request.current_specs.motherboard else ""
                    is_valid_upgrade, reason = compatibility_engine.check_cpu_upgrade_validity(
                        step.old_component, step.new_component, motherboard_name
                    )
                    if not is_valid_upgrade:
                        continue  # Skip invalid upgrade
                
                filtered_steps.append(step)
            
            result.upgrade_steps = filtered_steps
        
        return result

    try:
        client = _get_openai_client()
        payload = {
            "current_specs": request.current_specs.model_dump(exclude_none=True),
            "budget": request.budget or "Not specified",
            "use_case": request.use_case or "Gaming",
            "target_resolution": request.target_resolution or "1080p",
            "target_fps": request.target_fps or 60
        }
        response = client.chat.completions.create(
            model=settings.OPENAI_MODEL,
            temperature=0.0,
            max_tokens=1024,
            messages=[
                {"role": "system", "content": UPGRADE_PATH_PROMPT},
                {"role": "user", "content": "Create upgrade path for:\n" + json.dumps(payload, indent=2, default=str)}
            ],
            response_format={"type": "json_object"}
        )
        result_text = response.choices[0].message.content or ""
    except Exception as e:
        if should_use_mock(e, settings):
            return mock_upgrade_path_response(request)
        return UpgradePathResponse(
            success=False,
            use_case=request.use_case,
            budget=request.budget,
            current_score=0.0,
            projected_score=0.0,
            error=f"OpenAI API Error: {str(e)}"
        )

    parsed = _safe_parse_json(result_text)
    if not parsed:
        if should_use_mock(None, settings):
            return mock_upgrade_path_response(request)
        return UpgradePathResponse(
            success=False,
            use_case=request.use_case,
            budget=request.budget,
            current_score=0.0,
            projected_score=0.0,
            error="Failed to parse AI response JSON"
        )

    steps: List[UpgradeStep] = []
    for step in parsed.get("upgrade_steps", []) or []:
        try:
            # Validate GPU upgrades
            if step.get("component_category") == "GPU" and step.get("old_component"):
                is_valid_upgrade, reason = compatibility_engine.check_gpu_upgrade_validity(
                    step.get("old_component"), step.get("new_component", "")
                )
                if not is_valid_upgrade:
                    continue  # Skip invalid upgrade
            
            # Validate CPU upgrades
            if step.get("component_category") == "CPU" and step.get("old_component"):
                motherboard_name = request.current_specs.motherboard.name if request.current_specs.motherboard else ""
                is_valid_upgrade, reason = compatibility_engine.check_cpu_upgrade_validity(
                    step.get("old_component"), step.get("new_component", ""), motherboard_name
                )
                if not is_valid_upgrade:
                    continue  # Skip invalid upgrade
            
            steps.append(UpgradeStep(
                step_order=int(step.get("step_order", len(steps) + 1)),
                title=step.get("title", ""),
                description=step.get("description", ""),
                component_category=step.get("component_category", ""),
                old_component=step.get("old_component"),
                new_component=step.get("new_component", ""),
                price_range=step.get("price_range"),
                performance_gain=step.get("performance_gain"),
                priority=step.get("priority", "medium")
            ))
        except Exception:
            continue
    steps.sort(key=lambda x: x.step_order)

    return UpgradePathResponse(
        success=True,
        use_case=parsed.get("use_case", request.use_case),
        budget=parsed.get("budget", request.budget),
        current_score=float(parsed.get("current_score", 0.0)),
        projected_score=float(parsed.get("projected_score", 0.0)),
        upgrade_steps=steps,
        total_estimated_cost=parsed.get("total_estimated_cost"),
        summary=parsed.get("summary")
    )
