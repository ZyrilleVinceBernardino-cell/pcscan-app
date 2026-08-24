from pydantic import BaseModel, Field
from typing import Optional, List, Literal


# =============================================================
# 1. HEALTH CHECK
# =============================================================
class HealthResponse(BaseModel):
    status: str = Field(..., description="Server status: 'ok' or 'error'")
    service: str = Field(default="pcscan-backend", description="Service name")
    version: str = Field(default="1.0.0", description="API version")
    timestamp: str = Field(..., description="ISO 8601 timestamp")
    gemini_enabled: bool = Field(default=False, description="Gemini API ready")
    openai_enabled: bool = Field(default=False, description="OpenAI API ready")


# =============================================================
# 2. USER-RELATED MODELS
# =============================================================
class UserInfo(BaseModel):
    user_id: Optional[str] = Field(default=None, description="Unique user identifier (UUID)")
    username: Optional[str] = Field(default=None, description="Display name ng user")
    email: Optional[str] = Field(default=None, description="Email address ng user")
    budget_php: Optional[float] = Field(default=None, description="Budget sa PHP para sa upgrades/build")
    use_case: Optional[str] = Field(default="Gaming", description="Primary use: Gaming, Content Creation, Office, etc.")
    target_resolution: Optional[str] = Field(default="1080p", description="Target resolution: 1080p, 1440p, 4K")
    target_fps: Optional[int] = Field(default=60, description="Target FPS")


# =============================================================
# 3. HARDWARE SPECIFICATION MODELS (per-component)
# =============================================================
class CPUSpec(BaseModel):
    name: str = Field(default="", description="Full CPU model name, e.g. 'AMD Ryzen 5 5600X'")
    brand: Optional[str] = Field(default=None, description="Intel o AMD")
    cores: Optional[int] = Field(default=None, description="Bilang ng CPU cores")
    threads: Optional[int] = Field(default=None, description="Bilang ng CPU threads")
    base_clock: Optional[str] = Field(default=None, description="Base clock, e.g. '3.7 GHz'")
    boost_clock: Optional[str] = Field(default=None, description="Boost clock, e.g. '4.6 GHz'")
    socket: Optional[str] = Field(default=None, description="Socket, e.g. 'AM4', 'LGA1700'")
    tdp_watts: Optional[int] = Field(default=None, description="Thermal Design Power in watts")


class GPUSpec(BaseModel):
    name: str = Field(default="", description="Full GPU model name, e.g. 'NVIDIA GeForce RTX 3060 12GB'")
    brand: Optional[str] = Field(default=None, description="NVIDIA o AMD o Intel")
    vram_gb: Optional[float] = Field(default=None, description="VRAM size in GB")
    memory_type: Optional[str] = Field(default=None, description="GDDR6, GDDR6X, etc.")
    interface: Optional[str] = Field(default=None, description="PCIe 4.0 x16, etc.")
    tdp_watts: Optional[int] = Field(default=None, description="GPU power draw in watts")
    length_mm: Optional[int] = Field(default=None, description="GPU length para sa case compatibility")


class MotherboardSpec(BaseModel):
    name: str = Field(default="", description="Motherboard model, e.g. 'MSI B550M PRO-VDH WiFi'")
    brand: Optional[str] = Field(default=None, description="ASUS, MSI, Gigabyte, etc.")
    socket: Optional[str] = Field(default=None, description="CPU socket support")
    chipset: Optional[str] = Field(default=None, description="B550, Z790, etc.")
    form_factor: Optional[str] = Field(default=None, description="ATX, Micro-ATX, Mini-ITX")
    memory_slots: Optional[int] = Field(default=None, description="Bilang ng RAM slots")
    max_ram_gb: Optional[int] = Field(default=None, description="Maximum supported RAM in GB")
    ram_type: Optional[str] = Field(default=None, description="DDR4 o DDR5")


class RAMSpec(BaseModel):
    name: str = Field(default="", description="RAM kit/module name, e.g. 'G.Skill Ripjaws V 16GB (2x8GB) DDR4 3200'")
    brand: Optional[str] = Field(default=None, description="G.Skill, Corsair, Kingston, etc.")
    total_capacity_gb: Optional[int] = Field(default=None, description="Total capacity in GB")
    module_count: Optional[int] = Field(default=None, description="Bilang ng sticks (1, 2, 4)")
    ram_type: Optional[str] = Field(default=None, description="DDR4, DDR5")
    speed_mhz: Optional[int] = Field(default=None, description="Speed in MHz, e.g. 3200, 6000")
    cas_latency: Optional[int] = Field(default=None, description="CAS latency, e.g. 16, 30")


class StorageSpec(BaseModel):
    name: str = Field(default="", description="Storage name, e.g. 'Samsung 980 Pro 1TB NVMe SSD'")
    brand: Optional[str] = Field(default=None, description="Samsung, WD, Kingston, etc.")
    type: Optional[str] = Field(default=None, description="NVMe SSD, SATA SSD, HDD")
    capacity_gb: Optional[int] = Field(default=None, description="Capacity in GB (1TB = 1000)")
    interface: Optional[str] = Field(default=None, description="M.2 PCIe 4.0, SATA III, etc.")
    form_factor: Optional[str] = Field(default=None, description="M.2 2280, 2.5-inch, 3.5-inch")


class PSUSpec(BaseModel):
    name: str = Field(default="", description="PSU name, e.g. 'Corsair RM750x 750W 80+ Gold Fully Modular'")
    brand: Optional[str] = Field(default=None, description="Corsair, Seasonic, EVGA, etc.")
    wattage: Optional[int] = Field(default=None, description="Wattage rating in watts")
    efficiency_rating: Optional[str] = Field(default=None, description="80+ Gold, 80+ Platinum, etc.")
    modular_type: Optional[str] = Field(default=None, description="Non-Modular, Semi-Modular, Fully-Modular")


class CoolerSpec(BaseModel):
    name: str = Field(default="", description="Cooler name, e.g. 'Noctua NH-D15 chromax.black'")
    brand: Optional[str] = Field(default=None, description="Noctua, NZXT, Corsair, etc.")
    type: Optional[str] = Field(default=None, description="Air, AIO Liquid, Custom Loop")
    tdp_rating_watts: Optional[int] = Field(default=None, description="Max TDP supported")
    height_mm: Optional[int] = Field(default=None, description="Height para sa case clearance")


class CaseSpec(BaseModel):
    name: str = Field(default="", description="Case model, e.g. 'NZXT H510 Flow'")
    brand: Optional[str] = Field(default=None, description="NZXT, Lian Li, Corsair, etc.")
    form_factor_support: Optional[List[str]] = Field(default_factory=list, description="ATX, mATX, Mini-ITX, etc.")
    max_gpu_length_mm: Optional[int] = Field(default=None, description="Maximum GPU length supported")
    max_cooler_height_mm: Optional[int] = Field(default=None, description="Maximum CPU cooler height")


# =============================================================
# 4. HARDWARE BUNDLE - Current Build / Buong System Specs
# =============================================================
class HardwareSpecs(BaseModel):
    cpu: Optional[CPUSpec] = Field(default=None, description="CPU specification")
    gpu: Optional[GPUSpec] = Field(default=None, description="GPU specification")
    motherboard: Optional[MotherboardSpec] = Field(default=None, description="Motherboard specification")
    ram: Optional[RAMSpec] = Field(default=None, description="RAM specification")
    storage: Optional[StorageSpec] = Field(default=None, description="Primary storage specification")
    secondary_storage: Optional[StorageSpec] = Field(default=None, description="Secondary storage (optional)")
    psu: Optional[PSUSpec] = Field(default=None, description="Power Supply specification")
    cooler: Optional[CoolerSpec] = Field(default=None, description="CPU Cooler specification")
    case: Optional[CaseSpec] = Field(default=None, description="PC Case specification")


class CurrentBuild(BaseModel):
    build_name: Optional[str] = Field(default=None, description="Pangalan ng build (optional)")
    hardware: HardwareSpecs = Field(..., description="Kumpletong hardware specs ng current build")
    build_year: Optional[int] = Field(default=None, description="Taon ng pagkakabuo")
    notes: Optional[str] = Field(default=None, description="Additional notes")


# =============================================================
# 5. COMPONENT IDENTIFICATION MODELS (Gemini / OpenAI text output)
# =============================================================
class IdentifiedComponent(BaseModel):
    component_type: Literal["CPU", "GPU", "Motherboard", "RAM", "Storage", "PSU", "Cooler", "Case", "Unknown"] = Field(
        ..., description="Uri ng PC component"
    )
    name: str = Field(default="", description="Eksaktong model name, e.g. 'AMD Ryzen 5 5500'")
    brand: Optional[str] = Field(default=None, description="Brand name, e.g. 'NVIDIA', 'AMD', 'ASUS'")
    confidence: float = Field(
        default=0.0, ge=0.0, le=1.0, description="Confidence level 0.0 to 1.0"
    )
    raw_text: Optional[str] = Field(default=None, description="Original na teksto na nag-identify sa component")


class ComponentInfo(BaseModel):
    component_type: Literal["CPU", "GPU", "Motherboard", "RAM", "Storage", "PSU", "Cooler", "Case", "Unknown"] = Field(
        ..., description="Uri ng PC component"
    )
    brand: str = Field(default="", description="Brand name, e.g. 'NVIDIA', 'AMD', 'ASUS'")
    exact_model: str = Field(..., description="Eksaktong modelo, e.g. 'GeForce RTX 3060 12GB'")
    full_name: str = Field(default="", description="Buong pangalan = brand + exact_model")
    confidence_score: float = Field(
        ..., ge=0.0, le=1.0, description="Confidence level 0.0 to 1.0 (from Gemini)"
    )
    raw_ocr_text: Optional[str] = Field(default=None, description="Original na nakuhang teksto mula sa OCR")
    specs: Optional[dict] = Field(default=None, description="Additional key-value specs mula sa image/OCR")


class OCRScanResult(BaseModel):
    success: bool
    raw_ocr_text: str = Field(default="", description="Buong raw text na nakuha sa litrato")
    identified_components: List[ComponentInfo] = Field(default_factory=list, description="Mga component na natukoy")
    primary_component: Optional[ComponentInfo] = Field(default=None, description="Pangunahing component na pinaka-clear")
    extracted_hardware_specs: Optional[HardwareSpecs] = Field(default=None, description="Parsed specs (kung kumpleto)")
    overall_confidence: float = Field(default=0.0, ge=0.0, le=1.0, description="Average confidence")
    processing_time_ms: int = Field(default=0)
    error_message: Optional[str] = Field(default=None)


# =============================================================
# 6. REQUEST MODELS (Papasok sa API)
# =============================================================
class AnalyzeTextRequest(BaseModel):
    text: str = Field(..., min_length=1, description="Teksto na nais i-analyze (component name, specs, etc.)")
    user_info: Optional[UserInfo] = Field(default=None, description="User preferences at budget")
    current_build: Optional[CurrentBuild] = Field(default=None, description="Kasalukuyang build (if meron)")
    include_recommendations: bool = Field(default=True, description="Isama ang build recommendations")
    include_upgrade_path: bool = Field(default=True, description="Isama ang upgrade path")


class ScanAndAnalyzeRequest(BaseModel):
    """Note: image file ay UploadFile (multipart/form-data), hindi nandito."""
    user_info: Optional[UserInfo] = Field(default=None, description="User preferences at budget")
    current_build: Optional[CurrentBuild] = Field(default=None, description="Kasalukuyang build (if meron)")
    include_recommendations: bool = Field(default=True, description="Isama ang build recommendations")
    include_upgrade_path: bool = Field(default=True, description="Isama ang upgrade path")


# =============================================================
# 7. ANALYSIS RESULT MODELS (OpenAI output)
# =============================================================
class CompatibilityIssue(BaseModel):
    severity: Literal["critical", "warning", "info"]
    components_involved: List[str] = Field(..., description="Mga involved component, e.g. ['CPU', 'Motherboard']")
    issue: str = Field(..., description="Paglalarawan ng compatibility problem")
    resolution: Optional[str] = Field(default=None, description="Paano ito aayusin")


class CompatibilityReport(BaseModel):
    is_compatible: bool = Field(..., description="True if walang critical issues")
    overall_score: float = Field(..., ge=0.0, le=100.0, description="Compatibility score 0-100")
    issues: List[CompatibilityIssue] = Field(default_factory=list, description="Lahat ng nahanap na issue")
    summary: str = Field(..., description="Maikling summary ng compatibility check")


class WattsBreakdown(BaseModel):
    component: str = Field(..., description="Component name, e.g. 'CPU'")
    estimated_watts: int = Field(..., description="Estimated power draw")
    notes: Optional[str] = Field(default=None)


class WattsCalculatorResult(BaseModel):
    total_system_watts: int = Field(..., description="Total estimated draw of ALL components")
    breakdown: List[WattsBreakdown] = Field(default_factory=list, description="Per-component breakdown")
    recommended_psu_watts: int = Field(..., description="Recommended PSU wattage (with headroom)")
    psu_efficiency_tip: str = Field(default="", description="Tip tungkol sa 80+ rating")
    notes: Optional[str] = Field(default=None)


class BottleneckAnalysis(BaseModel):
    has_bottleneck: bool = Field(..., description="True if may makabuluhang bottleneck")
    bottleneck_component: Optional[str] = Field(default=None, description="CPU, GPU, RAM, etc.")
    bottleneck_percentage: Optional[float] = Field(default=None, ge=0.0, le=100.0, description="Gaano kalaki ang bottleneck %")
    description: str = Field(default="", description="Paglalarawan ng bottleneck")
    affected_tasks: List[str] = Field(default_factory=list, description="Mga tasks na naaapektuhan: Gaming, Rendering, etc.")
    recommended_fix: Optional[str] = Field(default=None, description="Paano ayusin")


class UpgradeStep(BaseModel):
    step_order: int = Field(..., description="Order of upgrade: 1 = una, 2 = pangalawa, ...")
    title: str = Field(..., description="Short title, e.g. 'Palitan ang GPU'")
    description: str = Field(..., description="Detalyadong paliwanag")
    component_category: str = Field(..., description="CPU, GPU, RAM, etc.")
    old_component: Optional[str] = Field(default=None, description="Current component na papalitan")
    new_component: str = Field(..., description="Recommended na bagong component")
    price_range_php: Optional[str] = Field(default=None, description="Tinatayang halaga sa PHP")
    performance_gain: Optional[str] = Field(default=None, description="Estimated improvement, e.g. '+30% FPS'")
    priority: Literal["critical", "high", "medium", "low"] = Field(default="medium")


class UpgradePathResult(BaseModel):
    use_case: Optional[str] = Field(default=None, description="Target use case")
    budget_php: Optional[str] = Field(default=None, description="Budget na ginamit")
    current_performance_score: float = Field(default=0.0, ge=0.0, le=100.0, description="Score bago mag-upgrade")
    projected_performance_score: float = Field(default=0.0, ge=0.0, le=100.0, description="Score pagkatapos ng LAHAT ng upgrades")
    steps: List[UpgradeStep] = Field(default_factory=list, description="Sunod-sunod na hakbang")
    total_estimated_cost_php: Optional[str] = Field(default=None, description="Total gastos")
    summary: str = Field(default="", description="Maikling summary ng upgrade path")


# =============================================================
# 8. BUILD RECOMMENDATION TIERS (Entry / Mid / High-End)
# =============================================================
class TieredBuild(BaseModel):
    tier: Literal["entry", "mid", "high"]
    tier_name: str = Field(..., description="Display name, e.g. 'Entry-Level (Budget Build)'")
    description: str = Field(..., description="Para kanino ang tier na ito")
    target_performance: str = Field(..., description="Ano ang kaya ng build na ito, e.g. '1080p High 60+ FPS'")
    hardware: HardwareSpecs = Field(..., description="Kumpletong specs para sa tier na ito")
    estimated_total_price_php: Optional[str] = Field(default=None, description="Tinatayang kabuuang halaga")
    best_for: List[str] = Field(default_factory=list, description="Best games/tasks para rito")
    pros: List[str] = Field(default_factory=list)
    cons: List[str] = Field(default_factory=list)
    total_system_watts: Optional[int] = Field(default=None, description="Total estimated power draw of all components in watts")
    recommended_psu_watts: Optional[int] = Field(default=None, description="Recommended PSU wattage with 30-40% headroom (standard ATX size)")
    psu_efficiency_tip: Optional[str] = Field(default=None, description="80+ rating recommendation: Bronze/Gold/Platinum")
    has_bottleneck: Optional[bool] = Field(default=None, description="True if may makabuluhang bottleneck sa tier build na ito")
    bottleneck_component: Optional[str] = Field(default=None, description="CPU / GPU / RAM / Storage / PSU / Motherboard or null")
    bottleneck_percentage: Optional[float] = Field(default=None, ge=0.0, le=100.0, description="Gaano kalaki ang bottleneck % (0-100)")
    bottleneck_description: Optional[str] = Field(default=None, description="Paglalarawan ng bottleneck at paano ito naaapektuhan ang performance")
    compatibility_score: Optional[float] = Field(default=None, ge=0.0, le=100.0, description="Overall compatibility score 0-100 for this specific tier build")
    is_compatible: Optional[bool] = Field(default=None, description="True if walang critical compatibility issues")


class BuildRecommendationResult(BaseModel):
    selected_tier: Optional[str] = Field(default=None, description="Recommended tier para sa user (based on budget)")
    entry: Optional[TieredBuild] = Field(default=None, description="Entry-level build (P20k-P40k typical)")
    mid: Optional[TieredBuild] = Field(default=None, description="Mid-range build (P50k-P80k typical)")
    high: Optional[TieredBuild] = Field(default=None, description="High-end build (P100k+)")
    overall_score: float = Field(default=0.0, ge=0.0, le=100.0, description="Average performance score ng selected tier")
    best_for_games: List[str] = Field(default_factory=list, description="Mga laro na pinaka-pwedeng laruin")
    summary: str = Field(default="", description="Maikling summary ng recommendations")


# =============================================================
# 9. UNIFIED RESPONSE MODELS (Output ng API endpoints)
# =============================================================
class AnalysisResponse(BaseModel):
    """Full analysis output ng OpenAI (gamit ng /api/analyze-text at /api/scan-and-analyze)"""
    success: bool
    query: Optional[str] = Field(default=None, description="Original query text (if from analyze-text)")
    identified_component: Optional[ComponentInfo] = Field(default=None, description="Natukoy na component (merged with Gemini result)")
    current_build: Optional[CurrentBuild] = Field(default=None, description="Kasalukuyang build (if provided)")
    compatibility: Optional[CompatibilityReport] = Field(default=None, description="Compatibility Check result")
    watts_calculator: Optional[WattsCalculatorResult] = Field(default=None, description="Watts Calculator result")
    bottleneck: Optional[BottleneckAnalysis] = Field(default=None, description="Bottleneck Check result")
    upgrade_path: Optional[UpgradePathResult] = Field(default=None, description="Upgrade Path result")
    build_recommendations: Optional[BuildRecommendationResult] = Field(default=None, description="Entry/Mid/High-End builds")
    overall_confidence: float = Field(default=0.0, ge=0.0, le=1.0)
    analysis_summary: Optional[str] = Field(default=None, description="Maikling AI-generated summary ng buong analysis")
    processing_time_ms: int = Field(default=0)
    error_message: Optional[str] = Field(default=None)


class ScanAndAnalyzeResponse(BaseModel):
    """Full response ng /api/scan-and-analyze (image + OCR + AI analysis)"""
    success: bool
    ocr_result: Optional[OCRScanResult] = Field(default=None, description="Gemini OCR + identification result")
    analysis: Optional[AnalysisResponse] = Field(default=None, description="OpenAI analysis + recommendations")
    total_processing_time_ms: int = Field(default=0)
    source: Literal["real", "mock"] = Field(default="real", description="Kung saan nanggaling ang data")
    error_message: Optional[str] = Field(default=None)


class AnalyzeTextResponse(BaseModel):
    """Full response ng /api/analyze-text (text query + AI analysis)"""
    success: bool
    query: str
    identified_components: List[IdentifiedComponent] = Field(default_factory=list, description="Mga component na natukoy mula sa search query")
    hardware_specs: Optional[HardwareSpecs] = Field(default=None, description="Eksaktong specs ng natukoy na component (galing sa OpenAI)")
    identification_confidence: float = Field(default=0.0, ge=0.0, le=1.0, description="Confidence ng identification step (0.0 - 1.0)")
    identification_summary: Optional[str] = Field(default=None, description="Maikling summary ng identification")
    analysis: Optional[AnalysisResponse] = Field(default=None, description="OpenAI 5-analysis: compatibility, watts, bottleneck, upgrade path, build recos")
    total_processing_time_ms: int = Field(default=0)
    source: Literal["real", "mock"] = Field(default="real", description="Kung saan nanggaling ang data")
    error_message: Optional[str] = Field(default=None)


# =============================================================
# 10. EXACT COMPONENT SEARCH MODELS (/api/search)
# =============================================================
class SearchRequest(BaseModel):
    query: str = Field(..., min_length=1, description="User search query, e.g. 'Ryzen 5 5600'")


class SearchFoundComponent(BaseModel):
    id: str = Field(..., description="Component unique ID (Firestore doc id or local db key)")
    category: Literal["CPU", "GPU", "Motherboard", "RAM", "Storage", "PSU", "Cooler", "Case", "Unknown"]
    brand: str
    model: str
    canonical_name: str = Field(..., description="Exact brand+model, e.g. 'AMD Ryzen 5 5600'")
    image_url: Optional[str] = Field(default=None)
    specifications: dict = Field(default_factory=dict, description="Verified basic specs FROM FIRESTORE / DB, NOT invented by OpenAI")


class SearchResponse(BaseModel):
    found: bool
    exact_match: bool
    query: Optional[str] = Field(default=None)
    message: Optional[str] = Field(default=None, description="Reason kung bakit hindi found, or informational text")
    component: Optional[SearchFoundComponent] = Field(default=None, description="Only present when found=true")
    ai_confidence: Optional[float] = Field(default=None, ge=0.0, le=1.0, description="OpenAI identification confidence 0.0-1.0")
    processing_time_ms: int = Field(default=0)
    is_valid_pc_part: Optional[bool] = Field(default=None, description="True ONLY if this is a DESKTOP PC part; False for laptop/mobile, non-pc-part, or unknown")
    rejection_reason: Optional[str] = Field(default=None, description="One of: laptop_part | not_pc_part | wrong_model | unknown_model | empty")
    form_factor: Optional[str] = Field(default=None, description="desktop | laptop | unknown | na")


# =============================================================
# 12. BUILD RECOMMENDATION (FAST DETERMINISTIC TIERED BUILDS)
# =============================================================
class BuildRecoRequest(BaseModel):
    component_name: str = Field(..., min_length=1, description="Anchor component name to build around, e.g. 'Ryzen 5 5600'")
    component_category: Optional[str] = Field(default=None, description="Optional: CPU / GPU / Motherboard / RAM / Storage / PSU. If omitted, auto-detect.")
    user_info: Optional[UserInfo] = Field(default=None, description="User preferences, budget, use case")


class BuildRecoTierBundle(BaseModel):
    cpu: str = Field(..., description="Full canonical CPU brand+model (anchor, NEVER replaced across tiers)")
    motherboard: str = Field(..., description="Full motherboard name, e.g. 'Generic B550M'")
    ram: str = Field(..., description="Full RAM kit name, e.g. 'Generic 16GB DDR4 3200 (2x8GB)'")
    gpu_nvidia: str = Field(..., description="Full canonical NVIDIA GPU name, e.g. 'NVIDIA RTX 4060'")
    gpu_amd: str = Field(..., description="Full canonical AMD GPU name, e.g. 'AMD RX 7600'")
    storage: str = Field(..., description="Full storage name, e.g. 'Generic 1TB NVMe SSD (PCIe 4.0)'")
    psu: str = Field(..., description="Full PSU name, e.g. 'Generic 650W 80+ Gold'")
    estimated_wattage: int = Field(..., description="Estimated total system power draw in watts (integer only, no suffix)")
    bottleneck_pct: int = Field(..., ge=0, le=100, description="Estimated bottleneck percentage 0-100 (integer)")


class BuildRecoResponse(BaseModel):
    success: bool = Field(default=True, description="True if build recommendation succeeded")
    anchor_component: str = Field(..., description="Canonical anchor component name used for builds")
    anchor_category: str = Field(..., description="Anchor category: CPU / GPU / Motherboard / RAM / Storage / PSU")
    entry: BuildRecoTierBundle = Field(..., description="Entry-level tier build bundle")
    mid: BuildRecoTierBundle = Field(..., description="Mid-range tier build bundle")
    high: BuildRecoTierBundle = Field(..., description="High-end tier build bundle")
    processing_time_ms: int = Field(default=0, description="Total processing time in milliseconds")
    error_message: Optional[str] = Field(default=None, description="Error message if success=False")
