from pydantic import BaseModel, Field
from typing import Optional, List


class HealthResponse(BaseModel):
    status: str
    service: str
    version: str
    timestamp: str


class CPUSpec(BaseModel):
    name: str = Field(default="", description="CPU model name")
    cores: Optional[int] = Field(default=None, description="Number of cores")
    threads: Optional[int] = Field(default=None, description="Number of threads")
    base_clock: Optional[str] = Field(default=None, description="Base clock speed")
    boost_clock: Optional[str] = Field(default=None, description="Boost clock speed")
    socket: Optional[str] = Field(default=None, description="CPU socket type")
    tdp: Optional[str] = Field(default=None, description="Thermal Design Power")


class GPUSpec(BaseModel):
    name: str = Field(default="", description="GPU model name")
    vram: Optional[str] = Field(default=None, description="VRAM size")
    memory_type: Optional[str] = Field(default=None, description="Memory type (GDDR6, etc.)")
    interface: Optional[str] = Field(default=None, description="PCIe interface")
    tdp: Optional[str] = Field(default=None, description="GPU power consumption")


class MotherboardSpec(BaseModel):
    name: str = Field(default="", description="Motherboard model")
    socket: Optional[str] = Field(default=None, description="CPU socket")
    chipset: Optional[str] = Field(default=None, description="Chipset")
    form_factor: Optional[str] = Field(default=None, description="Form factor (ATX, mATX, etc.)")
    memory_slots: Optional[int] = Field(default=None, description="RAM slots count")
    max_ram: Optional[str] = Field(default=None, description="Max RAM supported")


class RAMSpec(BaseModel):
    name: str = Field(default="", description="RAM model name")
    capacity: Optional[str] = Field(default=None, description="Total capacity")
    modules: Optional[int] = Field(default=None, description="Number of modules")
    type: Optional[str] = Field(default=None, description="DDR type (DDR4, DDR5)")
    speed: Optional[str] = Field(default=None, description="Speed (MHz)")
    latency: Optional[str] = Field(default=None, description="CAS latency")


class StorageSpec(BaseModel):
    name: str = Field(default="", description="Storage name")
    type: Optional[str] = Field(default=None, description="Type (NVMe SSD, SATA SSD, HDD)")
    capacity: Optional[str] = Field(default=None, description="Storage capacity")
    interface: Optional[str] = Field(default=None, description="Interface (PCIe, SATA)")


class PSUSpec(BaseModel):
    name: str = Field(default="", description="PSU model name")
    wattage: Optional[str] = Field(default=None, description="Power output (W)")
    efficiency: Optional[str] = Field(default=None, description="Efficiency rating (80+ Gold, etc.)")
    modular: Optional[str] = Field(default=None, description="Modular type")


class CoolerSpec(BaseModel):
    name: str = Field(default="", description="Cooler name")
    type: Optional[str] = Field(default=None, description="Type (Air, AIO, Liquid)")
    tdp_rating: Optional[str] = Field(default=None, description="TDP rating")


class CaseSpec(BaseModel):
    name: str = Field(default="", description="Case model name")
    form_factor: Optional[str] = Field(default=None, description="Supported form factors")


class HardwareSpecs(BaseModel):
    cpu: Optional[CPUSpec] = Field(default=None)
    gpu: Optional[GPUSpec] = Field(default=None)
    motherboard: Optional[MotherboardSpec] = Field(default=None)
    ram: Optional[RAMSpec] = Field(default=None)
    storage: Optional[StorageSpec] = Field(default=None)
    psu: Optional[PSUSpec] = Field(default=None)
    cooler: Optional[CoolerSpec] = Field(default=None)
    case: Optional[CaseSpec] = Field(default=None)


class IdentifiedComponent(BaseModel):
    component_type: str
    name: str
    confidence: float = Field(ge=0.0, le=1.0)
    raw_text: Optional[str] = None


class OCRScanResponse(BaseModel):
    success: bool
    raw_ocr_text: str
    identified_components: List[IdentifiedComponent] = Field(default_factory=list)
    hardware_specs: Optional[HardwareSpecs] = None
    overall_confidence: float = Field(default=0.0, ge=0.0, le=1.0)
    processing_time_ms: int = Field(default=0)
    error: Optional[str] = None


class AnalyzeTextRequest(BaseModel):
    text: str = Field(..., min_length=1, description="Search text or hardware description")
    include_recommendations: bool = Field(default=True, description="Include build recommendations")


class AnalyzeTextResponse(BaseModel):
    success: bool
    query: str
    identified_components: List[IdentifiedComponent] = Field(default_factory=list)
    hardware_specs: Optional[HardwareSpecs] = None
    overall_confidence: float = Field(default=0.0, ge=0.0, le=1.0)
    analysis_summary: Optional[str] = None
    error: Optional[str] = None


class BuildRecommendationItem(BaseModel):
    priority: int
    category: str
    current_component: Optional[str] = None
    recommended_component: str
    reason: str
    estimated_price_range: Optional[str] = None
    performance_improvement: Optional[str] = None
    compatibility_notes: Optional[str] = None


class BottleneckAnalysis(BaseModel):
    has_bottleneck: bool
    bottleneck_component: Optional[str] = None
    bottleneck_percentage: Optional[float] = Field(default=None, ge=0.0, le=100.0)
    description: Optional[str] = None
    affected_tasks: Optional[List[str]] = Field(default_factory=list)


class CompatibilityIssue(BaseModel):
    severity: str
    components_involved: List[str]
    issue: str
    resolution: Optional[str] = None


class BuildRecommendationResponse(BaseModel):
    success: bool
    build_name: Optional[str] = None
    overall_score: float = Field(default=0.0, ge=0.0, le=100.0)
    bottleneck_analysis: Optional[BottleneckAnalysis] = None
    compatibility_issues: List[CompatibilityIssue] = Field(default_factory=list)
    recommendations: List[BuildRecommendationItem] = Field(default_factory=list)
    total_estimated_cost: Optional[str] = None
    estimated_performance_level: Optional[str] = None
    best_for_games: List[str] = Field(default_factory=list)
    error: Optional[str] = None


class UpgradePathRequest(BaseModel):
    current_specs: HardwareSpecs
    budget: Optional[str] = Field(default=None, description="Budget range, e.g. '10000-20000 PHP'")
    use_case: Optional[str] = Field(default="Gaming", description="Primary use case")
    target_resolution: Optional[str] = Field(default="1080p", description="Target resolution")
    target_fps: Optional[int] = Field(default=60, description="Target FPS")


class UpgradeStep(BaseModel):
    step_order: int
    title: str
    description: str
    component_category: str
    old_component: Optional[str] = None
    new_component: str
    price_range: Optional[str] = None
    performance_gain: Optional[str] = None
    priority: str = Field(default="medium", description="low/medium/high/critical")


class UpgradePathResponse(BaseModel):
    success: bool
    use_case: Optional[str] = None
    budget: Optional[str] = None
    current_score: float = Field(default=0.0, ge=0.0, le=100.0)
    projected_score: float = Field(default=0.0, ge=0.0, le=100.0)
    upgrade_steps: List[UpgradeStep] = Field(default_factory=list)
    total_estimated_cost: Optional[str] = None
    summary: Optional[str] = None
    error: Optional[str] = None


class ScanAndAnalyzeResponse(BaseModel):
    success: bool
    ocr_result: Optional[OCRScanResponse] = None
    analysis: Optional[AnalyzeTextResponse] = None
    recommendations: Optional[BuildRecommendationResponse] = None
    total_processing_time_ms: int = Field(default=0)
    error: Optional[str] = None
