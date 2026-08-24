import json
import os
import re
import time
from typing import Optional, List, Dict, Any, Tuple

try:
    from openai import OpenAI
    HAS_OPENAI = True
except ImportError:
    HAS_OPENAI = False
    OpenAI = None  # type: ignore

from dotenv import load_dotenv

from app.models.schemas import (
    ComponentInfo,
    CurrentBuild,
    UserInfo,
    HardwareSpecs,
    CPUSpec,
    GPUSpec,
    MotherboardSpec,
    RAMSpec,
    StorageSpec,
    PSUSpec,
    CoolerSpec,
    CaseSpec,
    AnalysisResponse,
    CompatibilityReport,
    CompatibilityIssue,
    WattsCalculatorResult,
    WattsBreakdown,
    BottleneckAnalysis,
    UpgradePathResult,
    UpgradeStep,
    BuildRecommendationResult,
    TieredBuild,
)
from app.services.mock_engine import (
    SAMPLE_BUILDS,
    SAMPLE_UPGRADES,
    build_hardware_specs_from_sample,
    _hash_pick,
    _is_quota_error,
)
from app.services.compatibility_engine import (
    compatibility_engine,
    ComponentScore,
)


load_dotenv()


ANALYSIS_SYSTEM_PROMPT = """PC HARDWARE ANALYZER — EXPLAINER MODE (GPT-4o-mini). RETURN JSON IMMEDIATELY. DIRECT, ACCURATE, NO FLUFF.

CRITICAL ROLE: YOU ARE NOT THE FINAL AUTHORITY ON HARDWARE COMPATIBILITY.
- The caller's DETERMINISTIC COMPATIBILITY ENGINE validates ALL socket/RAM/PSU/form factor compatibility AFTER you respond.
- Your job is to SUGGEST reasonable mainstream components, DESCRIBE tradeoffs, EXPLAIN recommendations, and WRITE user-friendly text.
- NEVER invent compatibility. NEVER invent hardware model names. NEVER assume socket compatibility.
- If you accidentally produce an incompatible pair, the caller will OVERRIDE your compatibility fields with deterministic checks.
- The deterministic engine ALWAYS overrides your compatibility decisions.

RULES — FAST + ACCURATE:
- temperature=0.0 mode. Direct, no extra words.
- 1-2 RECOMMENDATIONS ONLY — don't overdo it. Best options only.
- JSON IMMEDIATELY — no intro, no warnings, no disclaimer, no markdown fences.
- Suggest balanced pairings. Be value-aware. Avoid massive overkill for simple gaming.
- For gaming builds: default 32GB RAM is recommended for modern mid/high; 64GB only for heavy productivity.
- Distinguish: compatible | recommended | best value | high-end | overkill.
- Entry/Mid/High: all MUST include the user's searched component in the correct slot.
- Realistic current-gen parts only. NO PRICES / NO PHP / NO CURRENCY SYMBOLS in build tier names or any hardware names.
- Clearly label "PLATFORM UPGRADE" when CPU+mobo+RAM must all be replaced together (e.g. AM4 → AM5).
- Do NOT recommend unnecessary motherboard replacements if the current board supports the new CPU with a BIOS update.
- NEVER recommend the EXACT SAME component as an "upgrade" to the current component.
- Separate: (a) Hardware Compatibility vs (b) Performance Balance vs (c) Recommended Pairing — these are different.
- Hardware Compatibility = physical compatibility (socket, RAM type, PSU wattage) - DETERMINISTIC ENGINE DECIDES THIS.
- Performance Balance = CPU/GPU tier matching - YOU ANALYZE THIS.
- Recommended Pairing = value and performance considerations - YOU SUGGEST THIS.
- PSU: calculate from CPU+GPU actual TDP, ~20% headroom. Don't auto 850W unless justified.
- Storage: PCIe 4.0 default; PCIe 5.0 only if justified.
- RETURN ALL 5 REQUIRED TOP-LEVEL KEYS even if empty/null.
- ALWAYS RESPOND IN NATURAL, PROFESSIONAL ENGLISH. Never respond in Tagalog, Filipino, or Taglish."""


ANALYSIS_USER_PROMPT_TEMPLATE = """
INPUTS:
- IDENTIFIED COMPONENT: {component_info_json}
- CURRENT BUILD (if any): {current_build_json}
- USER PREFS: {user_info_json}

RUN ALL 5 ANALYSES → RETURN JSON IMMEDIATELY. NO INTRO. NO COMMENTARY. REMEMBER: YOU ARE THE EXPLAINER. THE DETERMINISTIC ENGINE WILL OVERRIDE YOUR COMPATIBILITY DECISIONS.

=== 1. COMPATIBILITY → "compatibility" ===
{{ is_compatible: bool, overall_score: 0-100, issues: [{{ severity:"critical|warning|info", components_involved:["A","B"], issue:"...", resolution:"..." }}], summary:"1-2 sentences" }}
Describe obvious issues you CAN see. Be conservative. Don't claim you validated BIOS support; flag where applicable.
NOTE: The deterministic engine will override your compatibility check with actual socket/RAM/PSU validation.

=== 2. WATTS → "watts_calculator" ===
{{ total_system_watts: int, breakdown: [{{ component:"CPU|GPU|...", estimated_watts:int, notes:"" }}], recommended_psu_watts:int (round up: 550/650/750/850/1000/1200), psu_efficiency_tip:"Bronze/Gold/Platinum based on tier", notes:"" }}
Calculate from actual TDP values with ~20% headroom. Don't auto 850W unless justified by components.

=== 3. BOTTLENECK → "bottleneck" ===
Separate hardware compatibility vs performance balance. A mismatched CPU/GPU tier is a BALANCE issue, NOT a physical incompatibility.
{{ has_bottleneck: bool, bottleneck_component:"CPU|GPU|RAM|Storage|PSU|Mobo|null", bottleneck_percentage:0.0-100.0, description:"brief 1-2 sentences why (use terms like POOR / GOOD / CPU-limited / GPU-limited)", affected_tasks:["1080p Gaming","Video Edit"], recommended_fix:"specific upgrade or null" }}
Use tier-based analysis: entry/mid/high/extreme. A low-end CPU + high-end GPU = POOR balance (NOT incompatible).

=== 4. UPGRADE PATH → "upgrade_path" (NULL if NO current_build) ===
1-3 STEPS ONLY. DON'T OVERDO IT. Priority order: GPU first → CPU if real bottleneck → RAM if below 32GB → Storage if capacity insufficient → PSU if insufficient for target GPU → Motherboard only when real technical reason (not just "it's high-end").
If a component upgrade is the SAME model as current, DO NOT include it — skip to the next step.
If a build needs CPU+Mobo+RAM replaced together, label that step as a PLATFORM UPGRADE.
{{ use_case:"Gaming etc.", budget_php:"PHP range", current_performance_score:0-100, projected_performance_score:0-100, steps:[{{ step_order:1, title:"short", description:"1-2 sentences why", component_category:"CPU|GPU|RAM|Storage|PSU|Cooler|Mobo|Case", old_component:"name or null", new_component:"EXACT model (NOT same as old)", price_range_php:"PHP 8k - PHP 10k", performance_gain:"+30% FPS", priority:"critical|high|medium|low" }}], total_estimated_cost_php:"PHP range", summary:"1-2 sentences" }}

=== 5. BUILD TIERS → "build_recommendations" ===
ALL 3 TIERS → EACH MUST INCLUDE the searched component in its CORRECT CATEGORY slot (adjust other parts to fit tier, but NOT the searched component).
NO PRICES / NO PHP / NO CURRENCY in tier_name or anywhere.
{{ selected_tier:"entry|mid|high", overall_score:0-100, best_for_games:["Valorant","Cyberpunk 2077"], summary:"short", entry:{{ tier:"entry", tier_name:"Entry-Level Budget Build", description:"for who", target_performance:"1080p Med-High 60+ FPS", hardware:{{ FULL HardwareSpecs WITH searched component }}, estimated_total_price_php:"PHP range", best_for:["esports","light create"], pros:["cheap","esports good"], cons:["limited upgrade","no 4K"], total_system_watts:int, recommended_psu_watts:int, psu_efficiency_tip:"Bronze/Gold", has_bottleneck:bool, bottleneck_component:"CPU|GPU|null", bottleneck_percentage:0.0|0, bottleneck_description:"1 sentence", compatibility_score:0-100, is_compatible:bool }}, mid:{{ ...same schema... }}, high:{{ ...same schema... }} }}

=== WRAPPER CONTAINER ===
{{ compatibility:{{...}}, watts_calculator:{{...}}, bottleneck:{{...}}, upgrade_path:{{...}}|null, build_recommendations:{{...}}, analysis_summary:"short 1-2 sentence exec summary" }}

RULES (MANDATORY):
- VALID JSON ONLY. No markdown fences. No extra words. No commentary.
- ALL 6 TOP-LEVEL KEYS required (use []|null if empty)
- TIERED BUILDS: NEVER replace the searched GPU/CPU/... — always include in the correct slot. OTHER parts can be adjusted per tier.
- 1-3 steps only in upgrade path. Best options only.
- NO PRICES, NO PHP, NO CURRENCY SYMBOLS in tier_name field.
- REMEMBER: Deterministic engine overrides your compatibility decisions.
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


class OpenAIService:
    """OpenAI GPT-4o Mini service for build analysis, bottleneck, upgrades, tiered recommendations."""

    def __init__(self, api_key: Optional[str] = None, model: Optional[str] = None,
                 temperature: Optional[float] = None, max_tokens: Optional[int] = None,
                 org_id: Optional[str] = None,
                 mock_mode: bool = False, auto_fallback: bool = True):
        self.api_key = api_key or os.getenv("OPENAI_API_KEY", "")
        self.model_name = model or os.getenv("OPENAI_MODEL", "gpt-4o-mini")
        self.temperature = temperature if temperature is not None else 0.0
        self.max_tokens = max_tokens if max_tokens is not None else 1024
        self.org_id = org_id or os.getenv("OPENAI_ORG_ID") or None
        self.mock_mode = mock_mode if mock_mode is not None else (os.getenv("MOCK_MODE", "false").lower() == "true")
        self.auto_fallback = auto_fallback if auto_fallback is not None else (os.getenv("AUTO_FALLBACK", "true").lower() == "true")

        self._client: Optional[Any] = None
        self._configured = False

        if HAS_OPENAI and self.api_key and not self.mock_mode:
            try:
                self._client = OpenAI(api_key=self.api_key, organization=self.org_id)
                self._configured = True
            except Exception:
                self._configured = False

    @property
    def is_ready(self) -> bool:
        if self.mock_mode:
            return True
        return self._configured and self._client is not None

    # ------------------------------------------------------------------
    # Internal: Real API call
    # ------------------------------------------------------------------
    def _call_openai_analysis(self, user_message: str) -> str:
        if not self._configured or self._client is None:
            raise RuntimeError("OpenAI service is not configured. Check OPENAI_API_KEY.")
        chat = self._client.chat.completions.create(
            model=self.model_name,
            temperature=0.0,
            max_tokens=1024,
            messages=[
                {"role": "system", "content": ANALYSIS_SYSTEM_PROMPT},
                {"role": "user", "content": user_message},
            ],
            response_format={"type": "json_object"},
        )
        content = chat.choices[0].message.content
        if not content:
            raise RuntimeError("Empty response from OpenAI API")
        # Validate language - reject Tagalog/Taglish responses
        _validate_language(content)
        return content

    # ------------------------------------------------------------------
    # Mock / Fallback generators for each analysis step
    # ------------------------------------------------------------------
    @staticmethod
    def _pick_sample_based_on_hw(specs: Optional[HardwareSpecs], query_hint: str) -> Tuple[Dict[str, Any], int]:
        hint = ""
        if specs:
            if specs.cpu and specs.cpu.name:
                hint += specs.cpu.name + " "
            if specs.gpu and specs.gpu.name:
                hint += specs.gpu.name
        if not hint.strip():
            hint = query_hint or "default"
        idx = _hash_pick(hint, len(SAMPLE_BUILDS))
        return SAMPLE_BUILDS[idx], idx

    # ---------------- Mock: Compatibility ----------------
    @staticmethod
    def _mock_compatibility(specs: Optional[HardwareSpecs]) -> CompatibilityReport:
        # Use deterministic compatibility engine
        is_valid, engine_issues = compatibility_engine.validate_build(specs)
        
        # Convert engine issues to CompatibilityIssue format
        issues: List[CompatibilityIssue] = []
        for engine_issue in engine_issues:
            issues.append(engine_issue)
        
        # Add info message if no issues
        if not issues:
            issues.append(CompatibilityIssue(
                severity="info",
                components_involved=["All"],
                issue="No critical compatibility issues detected. CPU socket, RAM gen, PSU wattage, and form factors checked by deterministic engine.",
                resolution="No action required."
            ))
        
        has_critical = any(i.severity == "critical" for i in issues)
        score = 100
        for i in issues:
            if i.severity == "critical":
                score -= 45
            elif i.severity == "warning":
                score -= 12
            else:
                score -= 1
        score = max(0, score)
        
        if has_critical:
            summary = "Critical incompatibilities detected by deterministic engine. Build will not POST or work correctly without fixes."
        elif score >= 85:
            summary = "Excellent compatibility. No meaningful issues; build should work perfectly out of the box."
        elif score >= 65:
            summary = "Mostly compatible. Minor warnings noted — resolve before purchasing upgrades."
        else:
            summary = "Several issues present. Review warnings before proceeding."
        
        return CompatibilityReport(
            is_compatible=not has_critical,
            overall_score=float(score),
            issues=issues,
            summary=summary,
        )

    # ---------------- Mock: Watts Calc ----------------
    @staticmethod
    def _mock_watts(specs: Optional[HardwareSpecs]) -> WattsCalculatorResult:
        breakdown: List[WattsBreakdown] = []
        total = 0
        def add_watts(name: str, w: int, note: str = ""):
            nonlocal total
            total += w
            breakdown.append(WattsBreakdown(component=name, estimated_watts=w, notes=note or None))
        if specs and specs.cpu:
            w = specs.cpu.tdp_watts or (120 if "ryzen 7" in (specs.cpu.name or "").lower() or "i7" in (specs.cpu.name or "").lower() else 65)
            add_watts("CPU", w)
        else:
            add_watts("CPU", 65, "default estimate")
        if specs and specs.gpu:
            w = specs.gpu.tdp_watts or (285 if "4070" in (specs.gpu.name or "").lower() else 170)
            add_watts("GPU", w)
        else:
            add_watts("GPU", 100, "iGPU / placeholder estimate")
        if specs and specs.motherboard:
            add_watts("Motherboard", 25, "chipset + VRM")
        else:
            add_watts("Motherboard", 25)
        if specs and specs.ram:
            n = specs.ram.module_count or 2
            add_watts("RAM", n * 4, f"{n} sticks @ ~4W each")
        else:
            add_watts("RAM", 8)
        add_watts("Storage", 8)
        add_watts("Case Fans / LED", 15)
        standard_sizes = [450, 550, 650, 750, 850, 1000, 1200, 1600]
        needed = int(total * 1.35)
        rec = 550
        for s in standard_sizes:
            if s >= needed:
                rec = s
                break
        if rec >= 1000:
            eff_tip = "Recommend 80+ Platinum or Titanium for high-wattage 24/7 operation."
        elif rec >= 650:
            eff_tip = "Recommend 80+ Gold for reliable mid/high-end builds."
        else:
            eff_tip = "80+ Bronze is acceptable for this wattage budget."
        return WattsCalculatorResult(
            total_system_watts=total,
            breakdown=breakdown,
            recommended_psu_watts=rec,
            psu_efficiency_tip=eff_tip,
            notes=None,
        )

    # ---------------- Mock: Bottleneck ----------------
    @staticmethod
    def _mock_bottleneck(specs: Optional[HardwareSpecs]) -> BottleneckAnalysis:
        # Use deterministic engine for performance balance analysis
        balance_status, balance_description = compatibility_engine.analyze_performance_balance(specs)
        
        cpu_str = (specs.cpu.name if specs and specs.cpu else "").lower()
        gpu_str = (specs.gpu.name if specs and specs.gpu else "").lower()
        
        if not cpu_str and not gpu_str:
            return BottleneckAnalysis(
                has_bottleneck=False,
                bottleneck_component=None,
                bottleneck_percentage=None,
                description="Not enough info to detect bottleneck without CPU/GPU info.",
                affected_tasks=[],
                recommended_fix=None,
            )
        
        # Map balance status to bottleneck analysis
        if balance_status == "Severe":
            # Determine which component is the bottleneck
            from app.services.component_database import find_cpu_by_name, find_gpu_by_name
            cpu = find_cpu_by_name(specs.cpu.name) if specs.cpu else None
            gpu = find_gpu_by_name(specs.gpu.name) if specs.gpu else None
            
            if cpu and gpu:
                tier_order = {"entry": 1, "mid": 2, "high": 3, "extreme": 4}
                cpu_tier = tier_order.get(cpu.performance_tier, 1)
                gpu_tier = tier_order.get(gpu.performance_tier, 1)
                
                if cpu_tier < gpu_tier:
                    bottleneck_component = "CPU"
                    bottleneck_percentage = 40.0
                    description = f"Severe CPU bottleneck - {cpu.performance_tier}-tier CPU cannot keep up with {gpu.performance_tier}-tier GPU"
                    affected_tasks = ["1080p Gaming", "CPU-intensive games", "Productivity workloads"]
                    recommended_fix = f"Upgrade CPU to {gpu.performance_tier}-tier or higher for better balance"
                else:
                    bottleneck_component = "GPU"
                    bottleneck_percentage = 40.0
                    description = f"Severe GPU bottleneck - {gpu.performance_tier}-tier GPU cannot keep up with {cpu.performance_tier}-tier CPU"
                    affected_tasks = ["High-resolution gaming", "GPU-intensive tasks"]
                    recommended_fix = f"Upgrade GPU to {cpu.performance_tier}-tier or higher for better balance"
            else:
                bottleneck_component = "Unknown"
                bottleneck_percentage = 40.0
                description = balance_description
                affected_tasks = ["Gaming"]
                recommended_fix = "Review component balance"
        elif balance_status == "Poor":
            # Determine which component is the bottleneck
            from app.services.component_database import find_cpu_by_name, find_gpu_by_name
            cpu = find_cpu_by_name(specs.cpu.name) if specs.cpu else None
            gpu = find_gpu_by_name(specs.gpu.name) if specs.gpu else None
            
            if cpu and gpu:
                tier_order = {"entry": 1, "mid": 2, "high": 3, "extreme": 4}
                cpu_tier = tier_order.get(cpu.performance_tier, 1)
                gpu_tier = tier_order.get(gpu.performance_tier, 1)
                
                if cpu_tier < gpu_tier:
                    bottleneck_component = "CPU"
                    bottleneck_percentage = 25.0
                    description = f"CPU limitation at 1080p in CPU-bound scenarios - {cpu.performance_tier}-tier CPU with {gpu.performance_tier}-tier GPU"
                    affected_tasks = ["1080p Gaming", "Open-world games", "RTS games"]
                    recommended_fix = f"Consider CPU upgrade to {gpu.performance_tier}-tier for better 1080p performance"
                else:
                    bottleneck_component = "GPU"
                    bottleneck_percentage = 25.0
                    description = f"GPU limitation at higher resolutions - {gpu.performance_tier}-tier GPU with {cpu.performance_tier}-tier CPU"
                    affected_tasks = ["1440p+ Gaming", "Ray tracing", "High settings"]
                    recommended_fix = f"Consider GPU upgrade to {cpu.performance_tier}-tier for better high-res performance"
            else:
                bottleneck_component = "Unknown"
                bottleneck_percentage = 25.0
                description = balance_description
                affected_tasks = ["Gaming"]
                recommended_fix = "Review component balance"
        elif balance_status == "Good":
            return BottleneckAnalysis(
                has_bottleneck=False,
                bottleneck_component=None,
                bottleneck_percentage=10.0,
                description=balance_description,
                affected_tasks=[],
                recommended_fix=None,
            )
        else:  # Excellent
            return BottleneckAnalysis(
                has_bottleneck=False,
                bottleneck_component=None,
                bottleneck_percentage=0.0,
                description=balance_description,
                affected_tasks=["All workloads are well balanced"],
                recommended_fix=None,
            )
        
        return BottleneckAnalysis(
            has_bottleneck=True,
            bottleneck_component=bottleneck_component,
            bottleneck_percentage=bottleneck_percentage,
            description=description,
            affected_tasks=affected_tasks,
            recommended_fix=recommended_fix,
        )

    @staticmethod
    def _mock_upgrade_path(specs: Optional[HardwareSpecs], user: Optional[UserInfo]) -> Optional[UpgradePathResult]:
        if specs is None or not any([specs.cpu, specs.gpu]):
            return None
        
        # Use deterministic engine to classify build tier
        build_tier = compatibility_engine.classify_build_tier(specs)
        
        # Get performance balance analysis
        balance_status, _ = compatibility_engine.analyze_performance_balance(specs)
        
        # Determine base scores based on tier
        tier_scores = {
            "entry": (35, 70),
            "mid": (55, 85),
            "high": (75, 92),
            "extreme": (85, 96)
        }
        cur, proj = tier_scores.get(build_tier, (50, 80))
        
        # Generate upgrade steps using deterministic logic
        steps: List[UpgradeStep] = []
        step_order = 1
        total_low = 0
        total_high = 0
        
        cpu_name = specs.cpu.name if specs.cpu else ""
        gpu_name = specs.gpu.name if specs.gpu else ""
        motherboard_name = specs.motherboard.name if specs.motherboard else ""
        
        # Priority 1: GPU upgrade if bottleneck exists
        if balance_status in ["Poor", "Severe"]:
            from app.services.component_database import find_gpu_by_name, get_gpu_upgrade_candidates
            current_gpu = find_gpu_by_name(gpu_name)
            if current_gpu:
                upgrade_candidates = get_gpu_upgrade_candidates(gpu_name)
                if upgrade_candidates:
                    # Pick the best value upgrade (mid-tier if current is entry)
                    upgrade_gpu = upgrade_candidates[0] if upgrade_candidates else None
                    if upgrade_gpu:
                        upgrade_gpu_db = find_gpu_by_name(upgrade_gpu)
                        if upgrade_gpu_db:
                            price_low = upgrade_gpu_db.recommended_psu_watts * 2
                            price_high = upgrade_gpu_db.recommended_psu_watts * 3
                            steps.append(UpgradeStep(
                                step_order=step_order,
                                title=f"Upgrade GPU to {upgrade_gpu}",
                                description=f"Replace {gpu_name} with {upgrade_gpu} for significant performance improvement",
                                component_category="GPU",
                                old_component=gpu_name,
                                new_component=upgrade_gpu,
                                price_range_php=f"PHP {price_low:,} - PHP {price_high:,}",
                                performance_gain="High performance gain",
                                priority="critical"
                            ))
                            total_low += price_low
                            total_high += price_high
                            step_order += 1
        
        # Priority 2: CPU upgrade if bottleneck exists
        if balance_status in ["Poor", "Severe"] and motherboard_name:
            from app.services.component_database import find_cpu_by_name, get_cpu_upgrade_candidates, find_motherboard_by_name
            current_cpu = find_cpu_by_name(cpu_name)
            mobo = find_motherboard_by_name(motherboard_name)
            if current_cpu and mobo:
                upgrade_candidates = get_cpu_upgrade_candidates(cpu_name, mobo.socket)
                if upgrade_candidates:
                    upgrade_cpu = upgrade_candidates[0]
                    upgrade_cpu_db = find_cpu_by_name(upgrade_cpu)
                    if upgrade_cpu_db:
                        price_low = 12000
                        price_high = 18000
                        steps.append(UpgradeStep(
                            step_order=step_order,
                            title=f"Upgrade CPU to {upgrade_cpu}",
                            description=f"Replace {cpu_name} with {upgrade_cpu} for better CPU performance",
                            component_category="CPU",
                            old_component=cpu_name,
                            new_component=upgrade_cpu,
                            price_range_php=f"PHP {price_low:,} - PHP {price_high:,}",
                            performance_gain="Medium performance gain",
                            priority="high"
                        ))
                        total_low += price_low
                        total_high += price_high
                        step_order += 1
        
        # Priority 3: RAM upgrade if below 32GB
        if specs.ram and specs.ram.total_capacity_gb and specs.ram.total_capacity_gb < 32:
            ram_price_low = 3500
            ram_price_high = 5500
            steps.append(UpgradeStep(
                step_order=step_order,
                title="Upgrade RAM to 32GB",
                description=f"Upgrade from {specs.ram.total_capacity_gb}GB to 32GB for better multitasking and modern games",
                component_category="RAM",
                old_component=f"{specs.ram.total_capacity_gb}GB RAM",
                new_component="32GB RAM kit",
                price_range_php=f"PHP {ram_price_low:,} - PHP {ram_price_high:,}",
                performance_gain="Low performance gain, better multitasking",
                priority="medium"
            ))
            total_low += ram_price_low
            total_high += ram_price_high
            step_order += 1
        
        # Priority 4: Storage upgrade if below 1TB
        if specs.storage and specs.storage.capacity_gb and specs.storage.capacity_gb < 1000:
            storage_price_low = 3000
            storage_price_high = 4500
            steps.append(UpgradeStep(
                step_order=step_order,
                title="Upgrade Storage to 1TB+ NVMe",
                description=f"Upgrade from {specs.storage.capacity_gb}GB to 1TB+ NVMe SSD for more game storage",
                component_category="Storage",
                old_component=f"{specs.storage.capacity_gb}GB Storage",
                new_component="1TB NVMe SSD",
                price_range_php=f"PHP {storage_price_low:,} - PHP {storage_price_high:,}",
                performance_gain="No FPS gain, more storage capacity",
                priority="low"
            ))
            total_low += storage_price_low
            total_high += storage_price_high
            step_order += 1
        
        steps.sort(key=lambda x: x.step_order)
        use_case = user.use_case if user and user.use_case else "Gaming"
        budget = None
        if user and user.budget_php:
            budget = f"PHP {user.budget_php:,.0f}"
        else:
            budget = "PHP 15,000 - PHP 50,000"
        
        tier_display = build_tier.replace("entry", "entry-level").replace("mid", "balanced mid-range").replace("high", "high-end").replace("extreme", "enthusiast")
        summary = (
            f"Detected a {tier_display} build. "
            f"Current performance score ~{cur}/100; after {len(steps)} phased upgrades, projected ~{proj}/100. "
            f"Prioritized by impact-per-peso using deterministic compatibility analysis."
        )
        total_cost = f"PHP {total_low:,} - PHP {total_high:,}" if total_low and total_high else budget
        
        return UpgradePathResult(
            use_case=use_case,
            budget_php=budget,
            current_performance_score=float(cur),
            projected_performance_score=float(proj),
            steps=steps,
            total_estimated_cost_php=total_cost,
            summary=summary,
        )

    # ---------------- Mock: Build Tiers ----------------
    @staticmethod
    def _mock_build_recommendations(specs: Optional[HardwareSpecs], user: Optional[UserInfo]) -> BuildRecommendationResult:
        # Use deterministic engine to classify current build tier
        current_tier = compatibility_engine.classify_build_tier(specs) if specs else "entry"
        def entry() -> TieredBuild:
            hw = HardwareSpecs()
            hw.cpu = CPUSpec(name="AMD Ryzen 5 5600 (6C/12T)", brand="AMD", cores=6, threads=12, base_clock="3.5 GHz", boost_clock="4.4 GHz", socket="AM4", tdp_watts=65)
            hw.gpu = GPUSpec(name="SAPPHIRE PULSE RX 6600 8GB", brand="SAPPHIRE", vram_gb=8.0, memory_type="GDDR6", interface="PCIe 4.0 x8", tdp_watts=132, length_mm=230)
            hw.motherboard = MotherboardSpec(name="MSI A520M-A PRO", brand="MSI", socket="AM4", chipset="A520", form_factor="Micro-ATX", memory_slots=2, max_ram_gb=64, ram_type="DDR4")
            hw.ram = RAMSpec(name="G.Skill Aegis 16GB (2x8GB) DDR4 3200 CL16", brand="G.Skill", total_capacity_gb=16, module_count=2, ram_type="DDR4", speed_mhz=3200, cas_latency=16)
            hw.storage = StorageSpec(name="Kingston NV3 1TB PCIe 4.0 NVMe", brand="Kingston", type="NVMe Gen4 SSD", capacity_gb=1000, interface="M.2 PCIe 4.0 x4", form_factor="M.2 2280")
            hw.psu = PSUSpec(name="Corsair CV550 550W 80+ Bronze", brand="Corsair", wattage=550, efficiency_rating="80 PLUS Bronze", modular_type="Non-Modular")
            hw.cooler = CoolerSpec(name="AMD Wraith Stealth (Stock)", brand="AMD", type="Stock Box Cooler", tdp_rating_watts=65, height_mm=35)
            hw.case = CaseSpec(name="Rakk Anyag", brand="Rakk", form_factor_support=["Micro-ATX", "Mini-ITX"], max_gpu_length_mm=280, max_cooler_height_mm=150)
            return TieredBuild(
                tier="entry",
                tier_name="Entry-Level (PHP 22,000 - PHP 32,000 Budget Build)",
                description="For esports and light AAA gaming on tight budget; office work / online classes friendly.",
                target_performance="1080p Medium-High 60+ FPS in most games",
                hardware=hw,
                estimated_total_price_php="PHP 22,500 - PHP 31,000",
                best_for=["Valorant", "League of Legends", "DOTA 2", "GTA V", "Minecraft"],
                pros=["Best value for budget gamers","Low power draw","Philippine market parts readily available"],
                cons=["No ray tracing capability","Limited future CPU upgrade (AM4 end-of-life)","Case cooling is basic"],
                total_system_watts=310,
                recommended_psu_watts=550,
                psu_efficiency_tip="80+ Bronze is acceptable for this budget/wattage tier.",
                has_bottleneck=False,
                bottleneck_component=None,
                bottleneck_percentage=3.0,
                bottleneck_description="Balanced budget pairing. Ryzen 5 5600 and RX 6600 are well-matched at 1080p with no meaningful bottleneck.",
                compatibility_score=96.0,
                is_compatible=True,
            )

        def mid() -> TieredBuild:
            hw = HardwareSpecs()
            hw.cpu = CPUSpec(name="AMD Ryzen 5 7600 (6C/12T AM5)", brand="AMD", cores=6, threads=12, base_clock="3.8 GHz", boost_clock="5.1 GHz", socket="AM5", tdp_watts=65)
            hw.gpu = GPUSpec(name="AMD Radeon RX 7800 XT 16GB", brand="AMD", vram_gb=16.0, memory_type="GDDR6", interface="PCIe 4.0 x16", tdp_watts=263, length_mm=287)
            hw.motherboard = MotherboardSpec(name="MSI MAG B650M MORTAR WIFI", brand="MSI", socket="AM5", chipset="B650", form_factor="Micro-ATX", memory_slots=4, max_ram_gb=128, ram_type="DDR5")
            hw.ram = RAMSpec(name="G.Skill Trident Z5 32GB (2x16GB) DDR5 6000 CL30 EXPO", brand="G.Skill", total_capacity_gb=32, module_count=2, ram_type="DDR5", speed_mhz=6000, cas_latency=30)
            hw.storage = StorageSpec(name="Samsung 990 PRO 2TB NVMe PCIe 4.0", brand="Samsung", type="NVMe Gen4 SSD", capacity_gb=2000, interface="M.2 PCIe 4.0 x4", form_factor="M.2 2280")
            hw.psu = PSUSpec(name="Corsair RM750e (2024) 750W 80+ Gold Fully Modular", brand="Corsair", wattage=750, efficiency_rating="80 PLUS Gold", modular_type="Fully Modular")
            hw.cooler = CoolerSpec(name="DeepCool AK500 (Single-Tower Air)", brand="DeepCool", type="Single-Tower Air", tdp_rating_watts=240, height_mm=160)
            hw.case = CaseSpec(name="Lian Li Lancool 216", brand="Lian Li", form_factor_support=["ATX", "Micro-ATX", "Mini-ITX"], max_gpu_length_mm=384, max_cooler_height_mm=180)
            return TieredBuild(
                tier="mid",
                tier_name="Mid-Range (PHP 55,000 - PHP 78,000 Balanced Build)",
                description="Sweet spot 1440p gaming build with upgradeable AM5 platform.",
                target_performance="1080p Ultra 120+ FPS / 1440p High 70-100+ FPS in AAA titles",
                hardware=hw,
                estimated_total_price_php="PHP 56,000 - PHP 77,000",
                best_for=["Cyberpunk 2077 (FSR 3)", "Baldur's Gate 3", "Elden Ring", "Counter-Strike 2", "Starfield"],
                pros=["Excellent 1440p performance","AM5 upgradable platform (future Ryzen 8/9000)","16GB VRAM future-proofs for 2025-2027 games"],
                cons=["Runs warm if poorly cabled","Still 2TB max before needing 2nd drive"],
                total_system_watts=485,
                recommended_psu_watts=750,
                psu_efficiency_tip="Recommend 80+ Gold for reliable mid/high-end builds. 750W leaves headroom for future GPU upgrade.",
                has_bottleneck=False,
                bottleneck_component=None,
                bottleneck_percentage=2.0,
                bottleneck_description="Excellent balance. Ryzen 5 7600 and RX 7800 XT 16GB pair beautifully at 1440p; 1080p shows negligible CPU-bound scenario only in esports.",
                compatibility_score=97.0,
                is_compatible=True,
            )

        def high() -> TieredBuild:
            hw = HardwareSpecs()
            hw.cpu = CPUSpec(name="AMD Ryzen 7 7800X3D (8C/16T AM5)", brand="AMD", cores=8, threads=16, base_clock="4.2 GHz", boost_clock="5.0 GHz", socket="AM5", tdp_watts=120)
            hw.gpu = GPUSpec(name="NVIDIA GeForce RTX 4080 SUPER 16GB", brand="NVIDIA", vram_gb=16.0, memory_type="GDDR6X", interface="PCIe 4.0 x16", tdp_watts=320, length_mm=304)
            hw.motherboard = MotherboardSpec(name="ASUS ROG STRIX X670E-A GAMING WIFI", brand="ASUS", socket="AM5", chipset="X670E", form_factor="ATX", memory_slots=4, max_ram_gb=192, ram_type="DDR5")
            hw.ram = RAMSpec(name="G.Skill Trident Z5 RGB 64GB (2x32GB) DDR5 6400 CL32 EXPO", brand="G.Skill", total_capacity_gb=64, module_count=2, ram_type="DDR5", speed_mhz=6400, cas_latency=32)
            hw.storage = StorageSpec(name="Samsung 990 PRO 4TB NVMe PCIe 4.0", brand="Samsung", type="NVMe Gen4 SSD", capacity_gb=4000, interface="M.2 PCIe 4.0 x4", form_factor="M.2 2280")
            hw.secondary_storage = StorageSpec(name="Crucial T700 2TB Gen5 NVMe", brand="Crucial", type="NVMe Gen5 SSD", capacity_gb=2000, interface="M.2 PCIe 5.0 x4", form_factor="M.2 2280")
            hw.psu = PSUSpec(name="Seasonic PRIME TX-1000 1000W 80+ Titanium Fully Modular", brand="Seasonic", wattage=1000, efficiency_rating="80 PLUS Titanium", modular_type="Fully Modular")
            hw.cooler = CoolerSpec(name="NZXT Kraken 360 RGB (360mm AIO)", brand="NZXT", type="360mm AIO Liquid", tdp_rating_watts=350, height_mm=None)
            hw.case = CaseSpec(name="Lian Li O11 Dynamic EVO", brand="Lian Li", form_factor_support=["ATX","Micro-ATX","Mini-ITX","E-ATX"], max_gpu_length_mm=420, max_cooler_height_mm=167)
            return TieredBuild(
                tier="high",
                tier_name="High-End (PHP 130,000+ Enthusiast Build)",
                description="4K gaming, maximum ray tracing, and heavy content creation workstation.",
                target_performance="1440p Ultra 144+ FPS / 4K High 60-100+ FPS w/ DLSS / Ray Tracing",
                hardware=hw,
                estimated_total_price_php="PHP 132,000 - PHP 165,000",
                best_for=["Cyberpunk 2077 4K Path Tracing", "Alan Wake 2", "Black Myth: Wukong", "Blender Cycles (GPU)", "DaVinci Resolve 4K grading"],
                pros=["Extreme 4K gaming w/ DLSS 3","3D V-Cache CPU = best gaming CPU","PCIe 5 storage ready"],
                cons=["Very expensive","Large footprint + heavy","Requires good airflow room"],
                total_system_watts=690,
                recommended_psu_watts=1000,
                psu_efficiency_tip="Recommend 80+ Platinum or Titanium for high-wattage 24/7 operation. 1000W gives headroom for RTX 5090 upgrade later.",
                has_bottleneck=False,
                bottleneck_component=None,
                bottleneck_percentage=0.0,
                bottleneck_description="Top-tier enthusiast pairing. 7800X3D + RTX 4080 SUPER is perfectly balanced for 4K gaming; no component bottlenecks the other at any resolution.",
                compatibility_score=99.0,
                is_compatible=True,
            )

        e = entry()
        m = mid()
        h = high()
        selected = "mid"
        score = 82.0
        games = list(m.best_for)
        if user and user.budget_php:
            if user.budget_php < 40000:
                selected = "entry"
                score = 55.0
                games = list(e.best_for)
            elif user.budget_php > 100000:
                selected = "high"
                score = 95.0
                games = list(h.best_for)
        summary = (
            f"Recommended {selected.upper()} tier based on budget/profile. "
            f"Entry (PHP 22-31k) for esports/budget; Mid (PHP 56-77k) = best 1440p value; "
            f"High (PHP 132k+) = true 4K enthusiast class."
        )
        return BuildRecommendationResult(
            selected_tier=selected,
            entry=e,
            mid=m,
            high=h,
            overall_score=score,
            best_for_games=games,
            summary=summary,
        )

    # ------------------------------------------------------------------
    # Public: Unified analyze() — returns AnalysisResponse
    # ------------------------------------------------------------------
    def analyze(self,
                identified_component: Optional[ComponentInfo] = None,
                current_build: Optional[CurrentBuild] = None,
                user_info: Optional[UserInfo] = None,
                query_text: Optional[str] = None,
                include_recommendations: bool = True,
                include_upgrade_path: bool = True) -> AnalysisResponse:
        t0 = int(time.time() * 1000)
        specs = current_build.hardware if current_build else None
        error_msg: Optional[str] = None
        success = True
        use_mock = self.mock_mode
        raw_analysis: Dict[str, Any] = {}

        # Try real API if configured and not mocked
        if not use_mock and self._configured:
            try:
                comp_json = json.dumps(identified_component.model_dump() if identified_component else {}, indent=2)
                build_json = json.dumps(current_build.model_dump() if current_build else {}, indent=2)
                user_json = json.dumps(user_info.model_dump() if user_info else {}, indent=2)
                user_msg = ANALYSIS_USER_PROMPT_TEMPLATE.format(
                    component_info_json=comp_json,
                    current_build_json=build_json,
                    user_info_json=user_json,
                )
                resp_text = self._call_openai_analysis(user_msg)
                cleaned = _clean_json_response(resp_text)
                raw_analysis = json.loads(cleaned)
            except Exception as e:
                if self.auto_fallback or _is_quota_error(e):
                    use_mock = True
                else:
                    success = False
                    error_msg = f"OpenAI analysis failed: {type(e).__name__}: {e}"

        if use_mock or not success:
            try:
                comp_report = self._mock_compatibility(specs)
                watts = self._mock_watts(specs)
                bottleneck = self._mock_bottleneck(specs)
                upgrade = self._mock_upgrade_path(specs, user_info) if include_upgrade_path else None
                recs = self._mock_build_recommendations(specs, user_info) if include_recommendations else None

                sample, _ = self._pick_sample_based_on_hw(specs, query_text or "")
                summary = (
                    f"Compatibility: {comp_report.overall_score:.0f}/100 — {comp_report.summary} "
                    f"Bottleneck: " + (f"{bottleneck.bottleneck_component} @ {bottleneck.bottleneck_percentage:.0f}%" if bottleneck.has_bottleneck else "None detected. ")
                    + f"PSU recommendation: {watts.recommended_psu_watts}W. "
                    + (f"Selected build tier: {recs.selected_tier.upper()}." if recs else "")
                )
                return AnalysisResponse(
                    success=True,
                    query=query_text,
                    identified_component=identified_component,
                    current_build=current_build,
                    compatibility=comp_report,
                    watts_calculator=watts,
                    bottleneck=bottleneck,
                    upgrade_path=upgrade,
                    build_recommendations=recs,
                    overall_confidence=0.85,
                    analysis_summary=summary,
                    processing_time_ms=int(time.time() * 1000) - t0,
                    error_message=None,
                )
            except Exception as e:
                success = False
                error_msg = f"Fallback mock analysis failed: {type(e).__name__}: {e}"

        # Parse real API result
        comp: Optional[CompatibilityReport] = None
        watts: Optional[WattsCalculatorResult] = None
        bn: Optional[BottleneckAnalysis] = None
        up: Optional[UpgradePathResult] = None
        rec: Optional[BuildRecommendationResult] = None
        analysis_summary: Optional[str] = raw_analysis.get("analysis_summary")

        try:
            c = raw_analysis.get("compatibility")
            if c:
                comp = CompatibilityReport(**c)
        except Exception as e:
            error_msg = (error_msg or "") + f" [compatibility parse: {e}]"
        try:
            w = raw_analysis.get("watts_calculator")
            if w:
                watts = WattsCalculatorResult(**w)
        except Exception as e:
            error_msg = (error_msg or "") + f" [watts parse: {e}]"
        try:
            b = raw_analysis.get("bottleneck")
            if b:
                bn = BottleneckAnalysis(**b)
        except Exception as e:
            error_msg = (error_msg or "") + f" [bottleneck parse: {e}]"
        try:
            u = raw_analysis.get("upgrade_path")
            if u and include_upgrade_path:
                up = UpgradePathResult(**u)
        except Exception as e:
            error_msg = (error_msg or "") + f" [upgrade parse: {e}]"
        try:
            r = raw_analysis.get("build_recommendations")
            if r and include_recommendations:
                rec = BuildRecommendationResult(**r)
        except Exception as e:
            error_msg = (error_msg or "") + f" [recs parse: {e}]"

        elapsed = int(time.time() * 1000) - t0
        return AnalysisResponse(
            success=success and (comp is not None or watts is not None or bn is not None),
            query=query_text,
            identified_component=identified_component,
            current_build=current_build,
            compatibility=comp,
            watts_calculator=watts,
            bottleneck=bn,
            upgrade_path=up,
            build_recommendations=rec,
            overall_confidence=0.9 if success and not use_mock else 0.8,
            analysis_summary=analysis_summary,
            processing_time_ms=elapsed,
            error_message=error_msg,
        )
