from fastapi import APIRouter, HTTPException
import json
import time
from typing import Optional, List

from app.config import settings
from app.models.schemas import (
    UpgradePathRequest,
    UpgradePathResponse,
    UpgradeStep,
    TargetedUpgradeRequest,
    TargetedUpgradeResponse,
)
from app.services.mock_engine import (
    mock_upgrade_path_response,
    mock_targeted_upgrade_response,
    should_use_mock,
)
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


# ============================================================================
# NEW: TARGETED UPGRADE ADVISOR (3-step spec)
# ============================================================================

TARGETED_UPGRADE_SYS_PROMPT = """You are a PC build upgrade advisor for PcScan. The user has an
existing build and wants to upgrade it, starting with a specific
component they searched/scanned/uploaded ("TARGET_COMPONENT").

INPUT:
- TARGET_COMPONENT: the component the user wants to upgrade (id,
  category, tier, full specs) — this is the component they searched,
  scanned, or uploaded. This is the PRIMARY upgrade focus.
- CURRENT_BUILD: the user's full existing build, one entry per category
  they provided (cpu, gpu, ram, storage, motherboard, psu, case, cooler —
  some fields may be missing/unknown if the user didn't list them).
  Each entry has: category, name, tier, and specs (socket/chipset,
  memory_type, tdp, wattage_rating, etc. as applicable).
- COMPONENT_CATALOG: available components per category from our database.

TASK — follow this exact order:

STEP 1 — Upgrade the target component first.
  - Determine TARGET_COMPONENT's current tier: "entry", "midrange",
    "high-end", or "flagship/super high-end".
  - Recommend the next tier up ONLY, following this mapping:
      entry        -> recommend midrange upgrade
      midrange     -> recommend high-end upgrade
      high-end     -> recommend another high-end option (a meaningfully
                       better high-end part, not a downgrade or lateral
                       move) — NOT flagship/halo-tier unless the user's
                       other components can support it
      flagship / super high-end -> DO NOT recommend an upgrade for this
                       component. State explicitly that it's already at
                       the top tier and no upgrade is needed.
  - The recommended replacement must be a specific, real model from
    COMPONENT_CATALOG — never a generic/placeholder name (see rule
    below).

STEP 2 — Check compatibility of the upgraded target against the REST of
  the current build (the parts NOT being upgraded in this step):
  - Socket/chipset match (CPU <-> motherboard).
  - Memory type match (RAM <-> motherboard), if RAM is not itself being
    upgraded.
  - Physical fit (GPU length vs case), PCIe version, PSU wattage vs new
    total draw.
  - If the upgraded target is NOT compatible with an existing part
    (e.g. new CPU needs a different socket than the current
    motherboard), that existing part MUST also be included in the
    upgrade recommendations — it's not optional, it's required for the
    upgrade to function.

STEP 3 — After the target is upgraded and required-compatibility parts
  are handled, evaluate the REMAINING components in CURRENT_BUILD (the
  ones not yet touched) and recommend further upgrades where they would
  meaningfully improve the build:
  - Only recommend upgrading a component if it would bottleneck the
    newly-upgraded target, is meaningfully below the target's new tier,
    or is otherwise a clear weak point (e.g. RAM capacity too low,
    storage too small/slow, PSU insufficient for new total draw).
  - Do NOT recommend upgrading a component that is already adequate for
    the new tier just to "complete a matching set" — only recommend
    real, functional improvements.

HARD CONSTRAINTS (apply throughout):
1. COMPATIBILITY: every recommended part must be 100% compatible with
   every other part in the final recommended build (upgraded + kept
   components combined) — socket, chipset, memory type, PCIe, physical
   fit, PSU wattage. Zero exceptions.
2. BOTTLENECK CAP: CPU-GPU bottleneck in the final recommended build
   must not exceed 20% in either direction. Compute this using the
   FINAL component pairing (after all recommended upgrades + kept
   parts), not just the target component in isolation.
3. NO GENERIC / PLACEHOLDER COMPONENTS: every recommended replacement
   must be a specific, real model from COMPONENT_CATALOG. Never output
   a brand-only or category-only placeholder value.
4. WATTAGE / PSU: if the final build's estimated total draw (after
   upgrades) exceeds what the CURRENT_BUILD's existing PSU can safely
   supply (using 1.3-1.5x headroom over total draw), recommend a PSU
   upgrade. If the existing PSU already has enough headroom, explicitly
   say the current PSU is sufficient — do not recommend a PSU upgrade
   just because other parts changed.
5. MINIMAL DISRUPTION: only recommend changing a component if there is
   a real functional reason (compatibility requirement, bottleneck,
   insufficient capacity/wattage, or meaningful tier improvement).
   Components that are still adequate and compatible should be kept
   and explicitly listed as "no change needed."

OUTPUT FORMAT (strict JSON):
{
  "target_component": {
    "current": {...},
    "current_tier": "entry" | "midrange" | "high-end" | "flagship",
    "upgrade_needed": true | false,
    "recommended_replacement": {...} | null,
    "reason": "<short explanation, e.g. 'already flagship tier, no
      upgrade needed' or 'entry -> midrange upgrade'>"
  },
  "required_compatibility_changes": [
    {
      "category": "<e.g. motherboard>",
      "current": {...},
      "recommended_replacement": {...},
      "reason": "<why this is required, e.g. socket mismatch with new CPU>"
    }
  ],
  "optional_recommended_upgrades": [
    {
      "category": "<e.g. psu>",
      "current": {...},
      "recommended_replacement": {...},
      "reason": "<why this improves the build>"
    }
  ],
  "unchanged_components": [
    { "category": "<e.g. storage>", "current": {...}, "reason": "still adequate" }
  ],
  "final_build_check": {
    "compatibility_ok": true,
    "bottleneck_percent": <number>,
    "bottleneck_warning": true | false,
    "estimated_total_draw_watts": <number>,
    "current_psu_sufficient": true | false,
    "recommended_psu_watts": <number> | null
  }
}

Before finalizing, re-verify the FULL final build (target upgrade +
required changes + optional upgrades + unchanged parts together) against
constraints 1 and 2. If it fails, revise and re-check again."""


def _validate_targeted_response_shape(obj: dict) -> Optional[str]:
    """Return an error string if the shape is invalid, None if OK."""
    required_keys = {
        "target_component",
        "required_compatibility_changes",
        "optional_recommended_upgrades",
        "unchanged_components",
        "final_build_check",
    }
    if not isinstance(obj, dict):
        return "response body is not a JSON object"
    missing = required_keys - set(obj.keys())
    if missing:
        return f"missing top-level keys: {sorted(missing)}"
    final_check = obj.get("final_build_check") or {}
    fkeys = {
        "compatibility_ok",
        "bottleneck_percent",
        "bottleneck_warning",
        "estimated_total_draw_watts",
        "current_psu_sufficient",
        "recommended_psu_watts",
    }
    if not isinstance(final_check, dict):
        return "final_build_check is not an object"
    fmissing = fkeys - set(final_check.keys())
    if fmissing:
        return f"missing final_build_check keys: {sorted(fmissing)}"
    tcomp = obj.get("target_component") or {}
    if not isinstance(tcomp, dict):
        return "target_component is not an object"
    tmissing = {"current", "current_tier", "upgrade_needed", "recommended_replacement", "reason"} - set(tcomp.keys())
    if tmissing:
        return f"missing target_component keys: {sorted(tmissing)}"
    if final_check.get("bottleneck_warning") is True and not obj.get("error_message"):
        # Still valid but flag for caller — not a shape error
        pass
    return None


@router.post(
    "/upgrade/advisor",
    response_model=TargetedUpgradeResponse,
    summary="New: Targeted upgrade advisor with strict 5-section JSON (spec-compliant)",
)
async def targeted_upgrade_advisor(request: TargetedUpgradeRequest) -> TargetedUpgradeResponse:
    """
    Implements the 3-step TARGETED UPGRADE ADVISOR spec:

    1. Upgrade TARGET_COMPONENT first (entry -> midrange -> high-end -> another high-end,
       NEVER flagship unless user components support it; flagship = no upgrade).
    2. REQUIRED compatibility changes (socket/memory/PCIe/PSU wattage).
    3. OPTIONAL weak-point upgrades (bottleneck fixes, capacity/speed improvements only).

    Hard constraints enforced by `compatibility_engine.final_build_check`:
      - 100% compatibility (all pairs)
      - <=20% bottleneck cap; if >20% after auto-revision → bottleneck_warning=true
      - NO generic names; every recommendation is a specific real model
      - PSU 1.3-1.5x headroom over total draw
      - Minimal disruption: components kept unless functionally needed
    """
    t0 = time.time()

    # ------------------- DETERMINISTIC ENGINE (primary) ------------------
    try:
        result = compatibility_engine.generate_targeted_upgrade(request)
    except Exception as e:
        if not settings.MOCK_MODE:
            raise HTTPException(
                status_code=500,
                detail=f"Deterministic upgrade engine failed: {type(e).__name__}: {str(e)}",
            )
        result = mock_targeted_upgrade_response(request)

    # ------------------- MOCK MODE FALLBACK -----------------------------
    if settings.MOCK_MODE and (not result or getattr(result, "source", "") == ""):
        time.sleep(0.5)
        result = mock_targeted_upgrade_response(request)
        return result

    # ------------------- OPTIONAL LLM REFINEMENT ------------------------
    # Only call LLM if user requested AND deterministic result flagged an issue
    # (bottleneck_warning after auto-revision) OR missing data.
    # We still return deterministic result FIRST; the LLM result is NOT yet wired
    # into response because deterministic engine is the spec's canonical source.
    # TODO: optional LLM refine loop that re-validates shape + bottleneck before
    # returning.
    _ = request.use_llm_if_needed  # reserved for future refinement

    # Shape / constraint validation is performed inside generate_targeted_upgrade
    # (final_build_check is populated there).  We run an external check again for
    # defense-in-depth.
    try:
        body_dict = result.model_dump(mode="json")
    except Exception:
        body_dict = {}
    err = _validate_targeted_response_shape(body_dict)
    if err:
        # Should never reach — deterministic engine always produces the shape.
        # If it does, fall back to mock so caller never gets 500.
        mock_result = mock_targeted_upgrade_response(request)
        mock_result.error_message = f"deterministic engine shape validation failed: {err}; using mock fallback"
        return mock_result

    # Final: tag elapsed time
    try:
        result.processing_time_ms = int((time.time() - t0) * 1000)
    except Exception:
        pass
    return result
