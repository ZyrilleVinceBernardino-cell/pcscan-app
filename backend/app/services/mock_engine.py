import random
import hashlib
from typing import Optional, List, Dict, Any
import time

from app.models.schemas import (
    HardwareSpecs,
    CPUSpec,
    GPUSpec,
    MotherboardSpec,
    RAMSpec,
    StorageSpec,
    PSUSpec,
    CoolerSpec,
    CaseSpec,
    ComponentInfo,
    OCRScanResult,
    AnalyzeTextResponse,
    CompatibilityIssue,
    BottleneckAnalysis,
    UpgradeStep,
    UpgradePathResult,
    UpgradePathResult as UpgradePathResponse,
    BuildRecommendationResult,
    BuildRecommendationResult as BuildRecommendationResponse,
    UpgradeComponentSnapshot,
    TargetComponentReport,
    RequiredCompatChange,
    OptionalUpgradeItem,
    UnchangedComponentItem,
    FinalBuildCheck,
    TargetedUpgradeResponse,
    TargetedUpgradeRequest,
)
from app.services.compatibility_engine import (
    compatibility_engine,
    ComponentScore,
)


SAMPLE_BUILDS: List[Dict[str, Any]] = [
    {
        "name": "Mid-Range Ryzen 7800X3D + RTX 4070 Ti Super Gaming",
        "score": 82,
        "cpu": {"name": "AMD Ryzen 7 7800X3D", "cores": 8, "threads": 16, "base_clock": "4.2 GHz", "boost_clock": "5.0 GHz", "socket": "AM5", "tdp": "120W"},
        "gpu": {"name": "NVIDIA GeForce RTX 4070 Ti SUPER", "vram": "16GB", "memory_type": "GDDR6X", "interface": "PCIe 4.0 x16", "tdp": "285W"},
        "motherboard": {"name": "MSI MAG X670E TOMAHAWK WIFI", "socket": "AM5", "chipset": "X670E", "form_factor": "ATX", "memory_slots": 4, "max_ram": "128GB DDR5"},
        "ram": {"name": "G.Skill Trident Z5 RGB 32GB (2x16GB)", "capacity": "32GB", "modules": 2, "type": "DDR5", "speed": "6000MHz CL30", "latency": "CL30"},
        "storage": {"name": "Samsung 990 PRO 2TB", "type": "NVMe Gen4 SSD", "capacity": "2TB", "interface": "PCIe 4.0 x4"},
        "psu": {"name": "Corsair RM850e (2023)", "wattage": "850W", "efficiency": "80 PLUS Gold", "modular": "Fully Modular"},
        "cooler": {"name": "Noctua NH-D15 chromax.black", "type": "Dual-Tower Air", "tdp_rating": "220W"},
        "case": {"name": "Lian Li Lancool 216", "form_factor": "Mid Tower ATX"},
        "bottleneck": {"component": "GPU", "percent": 8, "desc": "Mild GPU limitation at 1440p Ultra settings, CPU won't bottleneck most games until RTX 4090 level.", "tasks": ["4K Gaming", "Cyberpunk 2077 with Path Tracing", "3DMark Time Spy"]},
        "perf_level": "High-End",
        "games": ["Cyberpunk 2077", "Elden Ring", "Baldur's Gate 3", "Counter-Strike 2", "Starfield"],
        "cost_upgrade": "PHP 32,000 - PHP 48,000",
    },
    {
        "name": "Budget Intel i5-12400F + RTX 3060 Entry Build",
        "score": 58,
        "cpu": {"name": "Intel Core i5-12400F", "cores": 6, "threads": 12, "base_clock": "2.5 GHz", "boost_clock": "4.4 GHz", "socket": "LGA1700", "tdp": "65W"},
        "gpu": {"name": "NVIDIA GeForce RTX 3060 12GB", "vram": "12GB", "memory_type": "GDDR6", "interface": "PCIe 4.0 x16", "tdp": "170W"},
        "motherboard": {"name": "ASUS Prime B660M-K D4", "socket": "LGA1700", "chipset": "B660", "form_factor": "mATX", "memory_slots": 2, "max_ram": "64GB DDR4"},
        "ram": {"name": "Corsair Vengeance LPX 16GB (2x8GB)", "capacity": "16GB", "modules": 2, "type": "DDR4", "speed": "3200MHz CL16", "latency": "CL16"},
        "storage": {"name": "Kingston NV2 1TB", "type": "NVMe Gen4 SSD", "capacity": "1TB", "interface": "PCIe 4.0 x4"},
        "psu": {"name": "Seasonic S12III-550", "wattage": "550W", "efficiency": "80 PLUS Bronze", "modular": "Non-Modular"},
        "cooler": {"name": "DeepCool AK400", "type": "Single-Tower Air", "tdp_rating": "150W"},
        "case": {"name": "Tecware Forge M", "form_factor": "mATX Mid Tower"},
        "bottleneck": {"component": "CPU", "percent": 22, "desc": "In CPU-heavy open-world games, the 12400F limits RTX 3060 at 1080p. At 1440p bottleneck drops to ~10%.", "tasks": ["1080p Gaming", "Microsoft Flight Simulator", "Cities Skylines 2"]},
        "perf_level": "Mid-Range",
        "games": ["Valorant", "League of Legends", "DOTA 2", "GTA V", "PUBG Battlegrounds"],
        "cost_upgrade": "PHP 15,000 - PHP 28,000",
    },
    {
        "name": "Enthusiast i9-14900K + RTX 4090 Workstation/Gaming",
        "score": 97,
        "cpu": {"name": "Intel Core i9-14900K", "cores": 24, "threads": 32, "base_clock": "3.2 GHz", "boost_clock": "6.0 GHz", "socket": "LGA1700", "tdp": "253W"},
        "gpu": {"name": "NVIDIA GeForce RTX 4090", "vram": "24GB", "memory_type": "GDDR6X", "interface": "PCIe 4.0 x16", "tdp": "450W"},
        "motherboard": {"name": "ASUS ROG MAXIMUS Z790 HERO", "socket": "LGA1700", "chipset": "Z790", "form_factor": "ATX", "memory_slots": 4, "max_ram": "192GB DDR5"},
        "ram": {"name": "Kingston FURY Renegade 64GB (2x32GB)", "capacity": "64GB", "modules": 2, "type": "DDR5", "speed": "6400MHz CL32", "latency": "CL32"},
        "storage": {"name": "Samsung 990 PRO 4TB", "type": "NVMe Gen4 SSD", "capacity": "4TB", "interface": "PCIe 4.0 x4"},
        "psu": {"name": "be quiet! Dark Power 13 1200W", "wattage": "1200W", "efficiency": "80 PLUS Titanium", "modular": "Fully Modular"},
        "cooler": {"name": "Corsair iCUE H170i ELITE CAPELLIX LCD", "type": "420mm AIO Liquid", "tdp_rating": "350W"},
        "case": {"name": "Lian Li O11 Dynamic EVO", "form_factor": "Full Tower ATX"},
        "bottleneck": {"component": "None", "percent": 0, "desc": "Near-perfect balance. No significant component restricts the other at any resolution up to 8K.", "tasks": ["Productivity workloads are all balanced"]},
        "perf_level": "Enthusiast",
        "games": ["Cyberpunk 2077 4K Ultra RT", "Alan Wake 2", "Black Myth: Wukong", "Hogwarts Legacy 4K", "Microsoft Flight Simulator 4K"],
        "cost_upgrade": "PHP 6,000 - PHP 12,000 (niche upgrades only)",
    },
    {
        "name": "Office Ryzen 5 5600G APU Budget Build",
        "score": 34,
        "cpu": {"name": "AMD Ryzen 5 5600G", "cores": 6, "threads": 12, "base_clock": "3.9 GHz", "boost_clock": "4.4 GHz", "socket": "AM4", "tdp": "65W"},
        "gpu": {"name": "AMD Radeon Vega 7 (Integrated)", "vram": "Up to 2GB Shared", "memory_type": "DDR4 System", "interface": "Integrated SoC", "tdp": "N/A (iGPU)"},
        "motherboard": {"name": "ASRock B550M-HDV", "socket": "AM4", "chipset": "B550", "form_factor": "mATX", "memory_slots": 2, "max_ram": "64GB DDR4"},
        "ram": {"name": "Team Group Elite 16GB (2x8GB)", "capacity": "16GB", "modules": 2, "type": "DDR4", "speed": "3600MHz CL18", "latency": "CL18"},
        "storage": {"name": "Western Digital Blue SN570 500GB", "type": "NVMe Gen3 SSD", "capacity": "500GB", "interface": "PCIe 3.0 x4"},
        "psu": {"name": "EVGA 500 W1", "wattage": "500W", "efficiency": "80 PLUS White", "modular": "Non-Modular"},
        "cooler": {"name": "AMD Wraith Stealth (Stock)", "type": "Stock Box Cooler", "tdp_rating": "65W"},
        "case": {"name": "Rakk Anyag", "form_factor": "mATX Mini Tower"},
        "bottleneck": {"component": "GPU (iGPU)", "percent": 62, "desc": "Severe GPU bottleneck: Integrated Vega 7 is not meant for modern 3D gaming. 720p/1080p low playable only on indie/esports titles.", "tasks": ["Modern 3D Gaming", "Video Editing (GPU-accelerated)", "3D Rendering"]},
        "perf_level": "Entry",
        "games": ["Valorant (1080p low)", "League of Legends", "Minecraft Optifine", "DOTA 2", "Stardew Valley"],
        "cost_upgrade": "PHP 28,000 - PHP 55,000 (major upgrade needed)",
    },
    {
        "name": "Workstation Ryzen 9 7950X + RX 7900 XTX Content Creator",
        "score": 90,
        "cpu": {"name": "AMD Ryzen 9 7950X", "cores": 16, "threads": 32, "base_clock": "4.5 GHz", "boost_clock": "5.7 GHz", "socket": "AM5", "tdp": "170W"},
        "gpu": {"name": "AMD Radeon RX 7900 XTX", "vram": "24GB", "memory_type": "GDDR6", "interface": "PCIe 4.0 x16", "tdp": "355W"},
        "motherboard": {"name": "Gigabyte X670E AORUS MASTER", "socket": "AM5", "chipset": "X670E", "form_factor": "ATX", "memory_slots": 4, "max_ram": "128GB DDR5"},
        "ram": {"name": "Corsair Dominator Platinum RGB 64GB (2x32GB)", "capacity": "64GB", "modules": 2, "type": "DDR5", "speed": "6400MHz CL32", "latency": "CL32"},
        "storage": {"name": "Crucial T700 2TB Gen5", "type": "NVMe Gen5 SSD", "capacity": "2TB", "interface": "PCIe 5.0 x4"},
        "psu": {"name": "SeaSonic PRIME TX-1000", "wattage": "1000W", "efficiency": "80 PLUS Titanium", "modular": "Fully Modular"},
        "cooler": {"name": "Arctic Liquid Freezer II 360", "type": "360mm AIO Liquid", "tdp_rating": "300W"},
        "case": {"name": "Fractal Design Meshify 2 XL", "form_factor": "Full Tower ATX"},
        "bottleneck": {"component": "GPU", "percent": 12, "desc": "Well balanced creator rig. GPU is the mild limiter in Blender/Redshift renders, but CPU is monstrous for Premiere Pro AE exports.", "tasks": ["GPU Rendering (Cycles/Redshift)", "DaVinci Resolve grading", "Unreal Engine Nanite"]},
        "perf_level": "High-End",
        "games": ["All AAA titles 4K High/Ultra", "Cyberpunk 2077 4K FSR 3", "Starfield 4K", "Horizon Forbidden West", "Resident Evil 4 Remake"],
        "cost_upgrade": "PHP 20,000 - PHP 30,000",
    },
]


SAMPLE_UPGRADES: Dict[str, List[Dict[str, Any]]] = {
    "lowend": [
        {"order": 1, "title": "Add a discrete GPU: RX 6600 8GB", "desc": "Single most impactful upgrade to turn this office PC into a proper 1080p gaming machine.", "cat": "GPU", "old": "AMD Radeon Vega 7 (iGPU)", "new": "SAPPHIRE PULSE RX 6600 8GB", "price": "PHP 8,500 - PHP 10,500", "gain": "+250% average FPS across all games", "prio": "critical"},
        {"order": 2, "title": "Upgrade RAM to 32GB dual-rank 3600 CL16", "desc": "16GB is minimum today; 32GB future-proofs the build, helps iGPU if still used, and unlocks multitasking while gaming.", "cat": "RAM", "old": "16GB DDR4 3600 CL18", "new": "G.Skill Aegis 32GB (2x16GB) 3600 CL16", "price": "PHP 3,200 - PHP 4,200", "gain": "Smoother multitasking, less stutter in open-world games", "prio": "high"},
        {"order": 3, "title": "Add 1TB NVMe SSD for game library", "desc": "500GB fills up fast after Windows + 1-2 AAA games.", "cat": "Storage", "old": "500GB WD Blue SN570", "new": "Kingston NV3 1TB PCIe 4.0", "price": "PHP 2,200 - PHP 2,800", "gain": "2x capacity, 40% faster reads", "prio": "medium"},
        {"order": 4, "title": "Later: Add RTX 4060 Ti / RX 7700 XT (mid-term)", "desc": "When budget allows, this is the ceiling AM4 + 5600G can pair with before CPU bottlenecking seriously.", "cat": "GPU", "old": "RX 6600 (from step 1)", "new": "NVIDIA GeForce RTX 4060 Ti 8GB", "price": "PHP 18,000 - PHP 22,000", "gain": "+50-65% FPS uplift over RX 6600, enables DLSS 3", "prio": "low"},
    ],
    "midrange": [
        {"order": 1, "title": "Upgrade GPU to RX 7800 XT or RTX 4070", "desc": "12400F happily drives up to these GPUs; eliminates the CPU-centric bottleneck at 1080p and unlocks 1440p comfortably.", "cat": "GPU", "old": "NVIDIA RTX 3060 12GB", "new": "AMD Radeon RX 7800 XT 16GB", "price": "PHP 21,000 - PHP 26,000", "gain": "+70-90% raster performance, 1440p 60+ fps capable", "prio": "critical"},
        {"order": 2, "title": "Double RAM to 32GB DDR4", "desc": "New AAA titles now exceed 16GB. Upgrade to 32GB to avoid background-process stutters and Windows 11 overhead.", "cat": "RAM", "old": "16GB (2x8) DDR4 3200 CL16", "new": "Corsair Vengeance LPX 32GB (2x16GB) 3600 CL16", "price": "PHP 3,500 - PHP 4,500", "gain": "Eliminates RAM stutters, helps render/streaming", "prio": "high"},
        {"order": 3, "title": "Replace PSU with 750W 80+ Gold modular", "desc": "Future upgrade path requires headroom; 550W Bronze non-modular is now undersized for RX 7800 XT class.", "cat": "PSU", "old": "550W 80+ Bronze", "new": "Corsair RM750e 2024 80+ Gold Fully Modular", "price": "PHP 5,200 - PHP 6,500", "gain": "Peace of mind + quieter + modular cable mgmt", "prio": "high"},
        {"order": 4, "title": "Optional: Move to AM5 later (CPU + Mobo)", "desc": "Full platform swap unlocks DDR5 and future CPU upgrades for 3-4 more years.", "cat": "Motherboard", "old": "B660M + i5-12400F", "new": "Ryzen 5 7600X + B650M Mortar WiFi", "price": "PHP 23,000 - PHP 28,000 (CPU + Mobo + 32GB DDR5 kit)", "gain": "Entire platform refresh, +20-30% 1% lows", "prio": "medium"},
    ],
    "highend": [
        {"order": 1, "title": "Minor: Upgrade RAM to 64GB DDR5 6400 CL30 kit", "desc": "Upgrade path for modders/modders who use heavy overlays, video capture at same time, plus future next-gen titles.", "cat": "RAM", "old": "32GB (2x16) DDR5 6000 CL30", "new": "G.Skill Trident Z5 RGB 64GB (2x32GB) 6400 CL30", "price": "PHP 8,500 - PHP 11,000", "gain": "More memory headroom; useful if streaming + editing at once", "prio": "medium"},
        {"order": 2, "title": "Storage: Add 4TB PCIe 4.0 game library drive", "desc": "Modern 100GB+ AAA games eat 2TB alive. Add 4TB for no-delete-required collection.", "cat": "Storage", "old": "2TB Samsung 990 PRO", "new": "Crucial T700 4TB PCIe 5.0 (or add 4TB as 2nd drive)", "price": "PHP 12,000 - PHP 16,000", "gain": "2x capacity + PCIe 5 speeds for the next 5 years", "prio": "medium"},
        {"order": 3, "title": "Extreme: RTX 4090 / RX 7900 XTX", "desc": "7800X3D can drive up to 4090 if 4K is priority. Overkill for 1440p.", "cat": "GPU", "old": "RTX 4070 Ti Super", "new": "NVIDIA GeForce RTX 4090 24GB", "price": "PHP 85,000 - PHP 95,000", "gain": "+50-60% 4K raster, 2x RT perf, 24GB VRAM for mods", "prio": "low"},
    ],
}


def _is_quota_error(e: Exception) -> bool:
    errstr = (str(e) + " " + type(e).__name__).lower()
    quota_markers = ["quota", "exhausted", "429", "insufficient", "prepayment", "rate limit", "resourceexhausted", "ratelimit"]
    return any(m in errstr for m in quota_markers)


def _hash_pick(seed_text: str, n: int) -> int:
    h = hashlib.md5(seed_text.lower().strip().encode()).hexdigest()
    return int(h[:8], 16) % max(1, n)


def build_hardware_specs_from_sample(sample: Dict[str, Any]) -> HardwareSpecs:
    specs = HardwareSpecs()

    cpu = sample.get("cpu") or {}
    if cpu:
        specs.cpu = CPUSpec(
            name=cpu.get("name", ""),
            brand=(cpu.get("name", "").split()[0] if cpu.get("name") else None),
            cores=cpu.get("cores"),
            threads=cpu.get("threads"),
            base_clock=cpu.get("base_clock"),
            boost_clock=cpu.get("boost_clock"),
            socket=cpu.get("socket"),
            tdp_watts=_parse_int_from_str(cpu.get("tdp")),
        )

    gpu = sample.get("gpu") or {}
    if gpu:
        vram_raw = str(gpu.get("vram", ""))
        tdp_raw = str(gpu.get("tdp", ""))
        specs.gpu = GPUSpec(
            name=gpu.get("name", ""),
            brand=(gpu.get("name", "").split()[0] if gpu.get("name") else None),
            vram_gb=_parse_float_from_str(vram_raw) if vram_raw else None,
            memory_type=gpu.get("memory_type"),
            interface=gpu.get("interface"),
            tdp_watts=_parse_int_from_str(tdp_raw) if tdp_raw else None,
            length_mm=None,
        )

    mobo = sample.get("motherboard") or {}
    if mobo:
        max_ram_raw = str(mobo.get("max_ram", ""))
        ram_type_match = None
        if "DDR5" in max_ram_raw:
            ram_type_match = "DDR5"
        elif "DDR4" in max_ram_raw:
            ram_type_match = "DDR4"
        specs.motherboard = MotherboardSpec(
            name=mobo.get("name", ""),
            brand=(mobo.get("name", "").split()[0] if mobo.get("name") else None),
            socket=mobo.get("socket"),
            chipset=mobo.get("chipset"),
            form_factor=mobo.get("form_factor"),
            memory_slots=mobo.get("memory_slots"),
            max_ram_gb=_parse_int_from_str(max_ram_raw),
            ram_type=ram_type_match,
        )

    ram = sample.get("ram") or {}
    if ram:
        cap_raw = str(ram.get("capacity", ""))
        speed_raw = str(ram.get("speed", ""))
        lat_raw = str(ram.get("latency", ""))
        specs.ram = RAMSpec(
            name=ram.get("name", ""),
            brand=(ram.get("name", "").split()[0] if ram.get("name") else None),
            total_capacity_gb=_parse_int_from_str(cap_raw),
            module_count=ram.get("modules"),
            ram_type=ram.get("type"),
            speed_mhz=_parse_int_from_str(speed_raw),
            cas_latency=_parse_int_from_str(lat_raw),
        )

    stor = sample.get("storage") or {}
    if stor:
        cap_raw = str(stor.get("capacity", ""))
        specs.storage = StorageSpec(
            name=stor.get("name", ""),
            brand=(stor.get("name", "").split()[0] if stor.get("name") else None),
            type=stor.get("type"),
            capacity_gb=_parse_capacity_gb(cap_raw),
            interface=stor.get("interface"),
            form_factor=None,
        )

    psu = sample.get("psu") or {}
    if psu:
        w_raw = str(psu.get("wattage", ""))
        specs.psu = PSUSpec(
            name=psu.get("name", ""),
            brand=(psu.get("name", "").split()[0] if psu.get("name") else None),
            wattage=_parse_int_from_str(w_raw),
            efficiency_rating=psu.get("efficiency"),
            modular_type=psu.get("modular"),
        )

    cool = sample.get("cooler") or {}
    if cool:
        tdp_raw = str(cool.get("tdp_rating", ""))
        specs.cooler = CoolerSpec(
            name=cool.get("name", ""),
            brand=(cool.get("name", "").split()[0] if cool.get("name") else None),
            type=cool.get("type"),
            tdp_rating_watts=_parse_int_from_str(tdp_raw),
            height_mm=None,
        )

    case_d = sample.get("case") or {}
    if case_d:
        ff = case_d.get("form_factor")
        ff_list = [ff] if isinstance(ff, str) else list(ff) if isinstance(ff, list) else []
        specs.case = CaseSpec(
            name=case_d.get("name", ""),
            brand=(case_d.get("name", "").split()[0] if case_d.get("name") else None),
            form_factor_support=ff_list or None,
            max_gpu_length_mm=None,
            max_cooler_height_mm=None,
        )
    return specs


def _parse_int_from_str(val: Any) -> Optional[int]:
    if val is None or val == "":
        return None
    try:
        if isinstance(val, int):
            return val
        digits = re.sub(r"[^\d]", "", str(val))
        return int(digits) if digits else None
    except Exception:
        return None


def _parse_float_from_str(val: Any) -> Optional[float]:
    if val is None or val == "":
        return None
    try:
        if isinstance(val, (int, float)):
            return float(val)
        digits = re.sub(r"[^\d.]", "", str(val))
        return float(digits) if digits else None
    except Exception:
        return None


def _parse_capacity_gb(val: str) -> Optional[int]:
    if not val:
        return None
    num = _parse_int_from_str(val)
    if num is None:
        return None
    up = val.upper()
    if "TB" in up:
        return num * 1000
    return num


import re


def build_identified_components(specs: HardwareSpecs) -> List[ComponentInfo]:
    out: List[ComponentInfo] = []
    order = [
        ("CPU", lambda s: s.cpu),
        ("GPU", lambda s: s.gpu),
        ("Motherboard", lambda s: s.motherboard),
        ("RAM", lambda s: s.ram),
        ("Storage", lambda s: s.storage),
        ("PSU", lambda s: s.psu),
        ("Cooler", lambda s: s.cooler),
        ("Case", lambda s: s.case),
    ]
    seed = random.random()
    for ctype, fn in order:
        specobj = fn(specs)
        if specobj and specobj.name:
            conf = round(0.82 + seed * 0.17, 2)
            name_parts = specobj.name.split()
            brand = name_parts[0] if name_parts else ""
            exact_model = " ".join(name_parts[1:]) if len(name_parts) > 1 else specobj.name
            out.append(ComponentInfo(
                component_type=ctype,
                brand=brand,
                exact_model=exact_model,
                full_name=specobj.name,
                confidence_score=conf,
                raw_ocr_text=specobj.name,
                specs=None,
            ))
    return out


def mock_ocr_response(image_file_hint: str = "demo", raw_text_hint: str = "") -> OCRScanResult:
    t0 = int(time.time() * 1000)
    idx = _hash_pick(image_file_hint + (raw_text_hint or ""), len(SAMPLE_BUILDS))
    sample = SAMPLE_BUILDS[idx]
    specs = build_hardware_specs_from_sample(sample)
    identified = build_identified_components(specs)
    raw_text_parts = [f"{c.component_type.upper()}: {c.full_name}" for c in identified]
    if raw_text_hint:
        raw_text_parts.insert(0, f"OCR extracted from image: {raw_text_hint}")
    raw_ocr = "\n".join(raw_text_parts) + "\n" + sample["name"]
    overall_conf = sum(c.confidence_score for c in identified) / max(1, len(identified))
    elapsed = int(time.time() * 1000) - t0 + random.randint(400, 1200)
    primary = max(identified, key=lambda c: c.confidence_score) if identified else None
    return OCRScanResult(
        success=True,
        raw_ocr_text=raw_ocr,
        identified_components=identified,
        primary_component=primary,
        extracted_hardware_specs=specs,
        overall_confidence=round(overall_conf, 3),
        processing_time_ms=elapsed,
        error_message=None,
    )


def mock_analyze_text_response(query: str) -> AnalyzeTextResponse:
    idx = _hash_pick(query, len(SAMPLE_BUILDS))
    sample = SAMPLE_BUILDS[idx]
    specs = build_hardware_specs_from_sample(sample)
    identified = build_identified_components(specs)
    overall_conf = sum(c.confidence for c in identified) / max(1, len(identified))
    summary = (
        f"Identified {len(identified)} hardware components from query '{query[:40]}'. "
        f"Primary build is classed as {sample['perf_level']} with overall balance score of {sample['score']}/100."
    )
    return AnalyzeTextResponse(
        success=True,
        query=query,
        identified_components=identified,
        hardware_specs=specs,
        overall_confidence=round(overall_conf, 3),
        analysis_summary=summary,
        error=None,
    )


def mock_build_recommendation_response(specs: Optional[HardwareSpecs], query_hint: str = "demo") -> BuildRecommendationResponse:
    if specs is None or not any([specs.cpu, specs.gpu, specs.motherboard, specs.ram, specs.storage]):
        idx = _hash_pick(query_hint or "empty", len(SAMPLE_BUILDS))
        sample = SAMPLE_BUILDS[idx]
        specs = build_hardware_specs_from_sample(sample)
    
    # Use deterministic compatibility engine for validation
    is_valid, engine_issues = compatibility_engine.validate_build(specs)
    
    # Convert engine issues to BuildRecommendationItem format
    compat: List[CompatibilityIssue] = []
    for engine_issue in engine_issues:
        compat.append(engine_issue)
    
    if not compat:
        compat.append(CompatibilityIssue(
            severity="info",
            components_involved=["All components"],
            issue="No critical compatibility issues detected by deterministic engine. Components checked: socket/RAM type/PCIe form-factor/PSU wattage.",
            resolution="No action required."
        ))
    
    # Use deterministic engine for bottleneck analysis
    balance_status, balance_description = compatibility_engine.analyze_performance_balance(specs)
    
    # Map balance status to bottleneck analysis
    if balance_status in ["Severe", "Poor"]:
        from app.services.component_database import find_cpu_by_name, find_gpu_by_name
        cpu = find_cpu_by_name(specs.cpu.name) if specs.cpu else None
        gpu = find_gpu_by_name(specs.gpu.name) if specs.gpu else None
        
        if cpu and gpu:
            tier_order = {"entry": 1, "mid": 2, "high": 3, "extreme": 4}
            cpu_tier = tier_order.get(cpu.performance_tier, 1)
            gpu_tier = tier_order.get(gpu.performance_tier, 1)
            
            if cpu_tier < gpu_tier:
                bottleneck_component = "CPU"
                bottleneck_percentage = 30.0 if balance_status == "Poor" else 45.0
                description = f"{balance_status} CPU bottleneck - {cpu.performance_tier}-tier CPU with {gpu.performance_tier}-tier GPU"
            else:
                bottleneck_component = "GPU"
                bottleneck_percentage = 30.0 if balance_status == "Poor" else 45.0
                description = f"{balance_status} GPU bottleneck - {gpu.performance_tier}-tier GPU with {cpu.performance_tier}-tier CPU"
            
            bottleneck = BottleneckAnalysis(
                has_bottleneck=True,
                bottleneck_component=bottleneck_component,
                bottleneck_percentage=bottleneck_percentage,
                description=description,
                affected_tasks=["Gaming"],
                recommended_fix=f"Upgrade {bottleneck_component} for better balance"
            )
        else:
            bottleneck = BottleneckAnalysis(
                has_bottleneck=False,
                bottleneck_component=None,
                bottleneck_percentage=None,
                description=balance_description,
                affected_tasks=[],
                recommended_fix=None
            )
    else:
        bottleneck = BottleneckAnalysis(
            has_bottleneck=False,
            bottleneck_component=None,
            bottleneck_percentage=0.0,
            description=balance_description,
            affected_tasks=[],
            recommended_fix=None
        )
    
    # Use deterministic engine for build tier classification
    build_tier = compatibility_engine.classify_build_tier(specs)
    
    # Map tier to performance level
    tier_to_level = {
        "entry": "Entry-Level",
        "mid": "Mid-Range",
        "high": "High-End",
        "extreme": "Enthusiast"
    }
    performance_level = tier_to_level.get(build_tier, "Entry-Level")
    
    # Calculate overall score based on tier
    tier_scores = {"entry": 40, "mid": 65, "high": 85, "extreme": 95}
    overall_score = tier_scores.get(build_tier, 50)
    
    # Generate recommendations using deterministic logic
    recs: List[BuildRecommendationItem] = []
    cpu_name = specs.cpu.name if specs.cpu else ""
    gpu_name = specs.gpu.name if specs.gpu else ""
    motherboard_name = specs.motherboard.name if specs.motherboard else ""
    
    # GPU upgrade recommendations
    if specs.gpu and balance_status in ["Poor", "Severe"]:
        from app.services.component_database import find_gpu_by_name, get_gpu_upgrade_candidates
        upgrade_candidates = get_gpu_upgrade_candidates(gpu_name)
        if upgrade_candidates:
            upgrade_gpu = upgrade_candidates[0]
            recs.append(BuildRecommendationItem(
                priority=1, category="GPU",
                current_component=specs.gpu.name,
                recommended_component=upgrade_gpu,
                reason=f"Upgrade from {gpu_name} to {upgrade_gpu} to address {balance_status.lower()} GPU bottleneck",
                estimated_price_range="PHP 15,000 - PHP 25,000",
                performance_improvement="High performance gain",
                compatibility_notes="Check PSU wattage and case clearance"
            ))
    
    # CPU upgrade recommendations
    if specs.cpu and motherboard_name and balance_status in ["Poor", "Severe"]:
        from app.services.component_database import find_cpu_by_name, get_cpu_upgrade_candidates, find_motherboard_by_name
        mobo = find_motherboard_by_name(motherboard_name)
        if mobo:
            upgrade_candidates = get_cpu_upgrade_candidates(cpu_name, mobo.socket)
            if upgrade_candidates:
                upgrade_cpu = upgrade_candidates[0]
                recs.append(BuildRecommendationItem(
                    priority=len(recs) + 1, category="CPU",
                    current_component=specs.cpu.name,
                    recommended_component=upgrade_cpu,
                    reason=f"Upgrade from {cpu_name} to {upgrade_cpu} to address {balance_status.lower()} CPU bottleneck",
                    estimated_price_range="PHP 12,000 - PHP 18,000",
                    performance_improvement="Medium performance gain",
                    compatibility_notes="Check BIOS support for new CPU"
                ))
    
    # RAM upgrade recommendations (only if below 32GB)
    if specs.ram and specs.ram.total_capacity_gb and specs.ram.total_capacity_gb < 32:
        recs.append(BuildRecommendationItem(
            priority=len(recs) + 1, category="RAM",
            current_component=specs.ram.name,
            recommended_component="32GB (2x16GB) kit",
            reason="16GB is minimum for modern gaming; 32GB prevents stuttering in AAA titles",
            estimated_price_range="PHP 3,500 - PHP 5,500",
            performance_improvement="Low FPS gain, better multitasking",
            compatibility_notes="Match RAM type to motherboard (DDR4/DDR5)"
        ))
    
    # Storage upgrade recommendations (only if below 1TB)
    if specs.storage and specs.storage.capacity_gb and specs.storage.capacity_gb < 1000:
        recs.append(BuildRecommendationItem(
            priority=len(recs) + 1, category="Storage",
            current_component=specs.storage.name,
            recommended_component="1TB NVMe SSD",
            reason="Modern games are 100GB+; 500GB fills up quickly",
            estimated_price_range="PHP 3,000 - PHP 4,500",
            performance_improvement="No FPS gain, more storage capacity",
            compatibility_notes="Check M.2 slot availability"
        ))
    
    recs.sort(key=lambda r: r.priority)
    for i, r in enumerate(recs):
        r.priority = i + 1
    
    # Determine best games based on tier
    tier_games = {
        "entry": ["Valorant", "League of Legends", "DOTA 2", "GTA V", "Minecraft"],
        "mid": ["Cyberpunk 2077", "Baldur's Gate 3", "Elden Ring", "Counter-Strike 2", "Starfield"],
        "high": ["Cyberpunk 2077 4K", "Alan Wake 2", "Black Myth: Wukong", "Blender", "DaVinci Resolve"],
        "extreme": ["Cyberpunk 2077 4K Path Tracing", "8K Gaming", "Professional 3D Rendering", "AI Workloads"]
    }
    best_games = tier_games.get(build_tier, tier_games["entry"])
    
    return BuildRecommendationResponse(
        success=True,
        build_name=f"{performance_level} Build",
        overall_score=float(overall_score),
        bottleneck_analysis=bottleneck,
        compatibility_issues=compat,
        recommendations=recs,
        total_estimated_cost="PHP 15,000 - PHP 50,000" if recs else "N/A",
        estimated_performance_level=performance_level,
        best_for_games=best_games,
        error=None,
    )


def mock_upgrade_path_response(request_obj) -> UpgradePathResponse:
    specs = request_obj.current_specs
    cpu_name = specs.cpu.name.lower() if specs.cpu and specs.cpu.name else ""
    gpu_name = specs.gpu.name.lower() if specs.gpu and specs.gpu.name else ""
    use_case = request_obj.use_case or "Gaming"
    budget = request_obj.budget or "PHP 15,000 - PHP 50,000"

    if "ryzen 5 5600g" in cpu_name or "vega" in gpu_name or "12100" in cpu_name or "i3" in cpu_name:
        category = "lowend"
        cur_score = 34
        proj_score = 72
    elif "i5-12400" in cpu_name or "i5-13400" in cpu_name or "ryzen 5 7600" in cpu_name or "rtx 3060" in gpu_name:
        category = "midrange"
        cur_score = 58
        proj_score = 85
    else:
        category = "highend"
        cur_score = 82
        proj_score = 94

    template = SAMPLE_UPGRADES[category]
    steps: List[UpgradeStep] = []
    total_low = 0
    total_high = 0
    for s in template:
        step = UpgradeStep(
            step_order=s["order"],
            title=s["title"],
            description=s["desc"],
            component_category=s["cat"],
            old_component=s["old"],
            new_component=s["new"],
            price_range=s["price"],
            performance_gain=s["gain"],
            priority=s["prio"],
        )
        steps.append(step)
        try:
            nums = [int(x) for x in "".join(ch if ch.isdigit() else " " for ch in s["price"]).split() if x.isdigit() and len(x) >= 4]
            if len(nums) >= 2:
                total_low += nums[-2]
                total_high += nums[-1]
            elif len(nums) == 1:
                total_low += nums[0]
                total_high += int(nums[0] * 1.3)
        except Exception:
            pass
    steps.sort(key=lambda x: x.step_order)
    summary_parts = [
        f"Detected a {category.replace('lowend','entry-level').replace('midrange','balanced mid-range').replace('highend','high-end')} build.",
        f"Your current score is {cur_score}/100; after {len(steps)} phased upgrades it will reach ~{proj_score}/100.",
        f"Priorities are ordered by impact-per-peso, starting with the bottleneck component first."
    ]
    total_cost = f"PHP {total_low:,} - PHP {total_high:,}" if total_low and total_high else request_obj.budget or "N/A"
    return UpgradePathResponse(
        success=True,
        use_case=use_case,
        budget=budget,
        current_score=float(cur_score),
        projected_score=float(proj_score),
        upgrade_steps=steps,
        total_estimated_cost=total_cost,
        summary=" ".join(summary_parts),
        error=None,
    )


def mock_targeted_upgrade_response(
    request_obj,
) -> TargetedUpgradeResponse:
    """
    Mock response for the new TARGETED UPGRADE ADVISOR endpoint (/upgrade/advisor).
    Produces spec-compliant 5-section output targeting the user's searched component.
    """
    t0 = time.time()
    target = request_obj.target_component
    target_cat = (target.category or "").lower()
    target_name = (target.name or "").lower()
    current_build = request_obj.current_build or {}

    # ------- Step 1: classify target tier + next-tier replacement ---------
    def tier_from_name(cat: str, name_lower: str) -> str:
        if any(x in name_lower for x in ("ryzen 9", "i9-14", "i9-13", "7950x", "7900x3d", "4090", "4080 super", "rx 7900 xtx", "titanium")):
            return "flagship"
        if any(x in name_lower for x in ("ryzen 7", "i7-14", "i7-13", "7800x3d", "4070 ti", "4080", "rx 7900 xt", "x670", "z790", "rm1000", "rm850")):
            return "high-end"
        if any(x in name_lower for x in ("ryzen 5", "i5-14", "i5-13", "i5-12", "7600", "4060", "4070", "rx 7800", "rx 7700", "b650", "b760", "b550", "32gb ddr5", "2tb", "rm750", "750w")):
            return "midrange"
        return "entry"

    cur_tier = tier_from_name(target_cat, target_name)
    next_map = {"entry": "midrange", "midrange": "high-end", "high-end": "high-end", "flagship": None}
    upgrade_needed = cur_tier != "flagship"
    next_tier = next_map[cur_tier] if upgrade_needed else None
    if not upgrade_needed:
        reason = "already flagship tier, no upgrade needed"
    else:
        reason = f"{cur_tier} -> {next_tier} upgrade"

    # Build specific replacements (real model names, never generic)
    def pick_replacement(cat: str, cur_name: str, target_t: str) -> UpgradeComponentSnapshot:
        n = cur_name.lower()
        if cat == "cpu":
            if "ryzen 5 5600" in n or target_t == "midrange":
                return UpgradeComponentSnapshot(
                    category="cpu",
                    name="AMD Ryzen 7 7700",
                    tier="high-end",
                    specs={
                        "socket": "AM5",
                        "memory_type": "DDR5",
                        "tdp_watts": 65,
                        "core_count": 8,
                        "thread_count": 16,
                        "generation": "Zen 4",
                        "supported_chipsets": ["B650", "X670", "X670E"],
                    },
                )
            if target_t == "high-end":
                return UpgradeComponentSnapshot(
                    category="cpu",
                    name="AMD Ryzen 9 7900",
                    tier="high-end",
                    specs={
                        "socket": "AM5",
                        "memory_type": "DDR5",
                        "tdp_watts": 65,
                        "core_count": 12,
                        "thread_count": 24,
                        "generation": "Zen 4",
                        "supported_chipsets": ["B650", "X670", "X670E"],
                    },
                )
            return UpgradeComponentSnapshot(
                category="cpu",
                name="Intel Core i5-13400F",
                tier="midrange",
                specs={"socket": "LGA1700", "memory_type": "DDR4/DDR5", "tdp_watts": 65, "core_count": 10, "thread_count": 16, "generation": "13th Gen", "supported_chipsets": ["B760", "Z790"]},
            )
        if cat == "gpu":
            if target_t == "midrange":
                return UpgradeComponentSnapshot(
                    category="gpu",
                    name="NVIDIA GeForce RTX 4070 12GB",
                    tier="high-end",
                    specs={"vram_gb": 12, "memory_type": "GDDR6X", "recommended_psu_watts": 650, "power_draw_watts": 200, "generation": "RTX 40-series", "target_resolution": "1440p", "pcie_interface": "PCIe 4.0"},
                )
            if target_t == "high-end":
                return UpgradeComponentSnapshot(
                    category="gpu",
                    name="NVIDIA GeForce RTX 4080 SUPER 16GB",
                    tier="high-end",
                    specs={"vram_gb": 16, "memory_type": "GDDR6X", "recommended_psu_watts": 850, "power_draw_watts": 320, "generation": "RTX 40-series", "target_resolution": "4K", "pcie_interface": "PCIe 4.0"},
                )
            return UpgradeComponentSnapshot(
                category="gpu",
                name="AMD Radeon RX 7800 XT 16GB",
                tier="midrange",
                specs={"vram_gb": 16, "memory_type": "GDDR6", "recommended_psu_watts": 650, "power_draw_watts": 263, "generation": "RX 7000-series", "target_resolution": "1440p", "pcie_interface": "PCIe 4.0"},
            )
        if cat == "ram":
            return UpgradeComponentSnapshot(
                category="ram",
                name="G.Skill Trident Z5 RGB 32GB (2x16GB) DDR5-6000 CL30",
                tier="midrange",
                specs={"type": "DDR5", "capacity_gb": 32, "speed_mhz": 6000, "cas_latency": 30, "kit_configuration": "2x16GB"},
            )
        if cat == "storage":
            return UpgradeComponentSnapshot(
                category="storage",
                name="Samsung 990 PRO 2TB PCIe 4.0 NVMe SSD",
                tier="midrange",
                specs={"type": "NVMe SSD", "capacity_gb": 2000, "interface": "M.2 PCIe 4.0 x4", "read_speed_mbps": 7450, "write_speed_mbps": 6900},
            )
        if cat == "motherboard":
            return UpgradeComponentSnapshot(
                category="motherboard",
                name="MSI MAG X670E TOMAHAWK WIFI",
                tier="high-end",
                specs={"socket": "AM5", "chipset": "X670E", "memory_type": "DDR5", "max_ram_gb": 128, "ram_slots": 4, "m2_slots": 3, "pcie_generation": "PCIe 5.0"},
            )
        if cat == "psu":
            return UpgradeComponentSnapshot(
                category="psu",
                name="Corsair RM850e (2024) 850W 80+ Gold Fully Modular",
                tier="midrange",
                specs={
                    "wattage": 850,
                    "efficiency": "80+ Gold",
                    "modular": "Fully Modular",
                    "atx_version": "ATX 3.1",
                },
            )

    replacement: Optional[UpgradeComponentSnapshot] = None
    if upgrade_needed and next_tier:
        replacement = pick_replacement(target_cat, target_name, next_tier)

    target_report = TargetComponentReport(
        current=target,
        current_tier=cur_tier,
        upgrade_needed=upgrade_needed,
        recommended_replacement=replacement,
        reason=reason,
    )

    # ------- Step 2: Required compatibility changes -----------------------
    required_changes: List[RequiredCompatChange] = []

    # CPU replacement: if new CPU.socket != existing mobo.socket, mobo + ram required
    if target_cat == "cpu" and replacement is not None:
        existing_mobo = current_build.get("motherboard")
        new_socket = (replacement.specs or {}).get("socket") or ""
        if existing_mobo is not None:
            existing_socket_name = (
                ((existing_mobo.specs or {}).get("socket")) or existing_mobo.name or ""
            )
            socket_matches = (
                str(new_socket).lower().replace(" ", "") in existing_socket_name.lower().replace(" ", "")
            ) or (new_socket.lower() == str(((existing_mobo.specs or {}).get("socket") or "")).lower())
            if not socket_matches or "am4" in str(existing_mobo.name).lower() and "am5" in str(new_socket).lower():
                new_mobo = UpgradeComponentSnapshot(
                    category="motherboard",
                    name="MSI MAG B650 TOMAHAWK WIFI (AM5)" if new_socket.lower() == "am5" else "ASUS TUF GAMING Z790-PLUS WIFI",
                    tier="high-end",
                    specs={"socket": new_socket, "chipset": "B650" if new_socket.lower() == "am5" else "Z790", "memory_type": "DDR5", "form_factor": "ATX"},
                )
                required_changes.append(RequiredCompatChange(
                    category="motherboard",
                    current=existing_mobo,
                    recommended_replacement=new_mobo,
                    reason=(
                        f"socket mismatch with new CPU ({new_socket}); original motherboard socket "
                        f"{((existing_mobo.specs or {}).get('socket') or 'unknown')} is incompatible — platform upgrade required."
                    ),
                ))
                existing_ram = current_build.get("ram")
                if existing_ram is not None:
                    # DDR4/DDR5 mismatch detection
                    ram_name_or_spec = str(((existing_ram.specs or {}).get("type")) or existing_ram.name or "")
                    ram_is_ddr4_only = "ddr4" in ram_name_or_spec.lower() or (
                        "ddr5" not in ram_name_or_spec.lower() and "ddr4" in existing_ram.name.lower()
                    )
                    if ram_is_ddr4_only and "DDR5" in str((new_mobo.specs or {}).get("memory_type", "DDR5")).upper():
                        new_ram = UpgradeComponentSnapshot(
                            category="ram",
                            name="G.Skill Trident Z5 RGB 32GB (2x16GB) DDR5-6000 CL30",
                            tier="midrange",
                            specs={"type": "DDR5", "capacity_gb": 32, "speed_mhz": 6000, "kit_configuration": "2x16GB"},
                        )
                        required_changes.append(RequiredCompatChange(
                            category="ram",
                            current=existing_ram,
                            recommended_replacement=new_ram,
                            reason="DDR4 vs DDR5 mismatch: new AM5 motherboard requires DDR5; existing DDR4 kit cannot physically fit the new platform DIMM slots.",
                        ))

    # GPU replacement: if PSU wattage < 1.3x draw => PSU required upgrade
    if target_cat == "gpu" and replacement is not None:
        tdp = (replacement.specs or {}).get("watt") or (replacement.specs or {}).get("power_draw_watts") or 200
        existing_cpu = current_build.get("cpu")
        cpu_tdp = (
            (existing_cpu.specs or {}).get("tdp_watts") if existing_cpu and isinstance(existing_cpu, UpgradeComponentSnapshot)
            else 65
        )
        est_draw = tdp + 65 + 150
        existing_psu = current_build.get("psu")
        needs_psu = False
        existing_psu_watts: Optional[int] = None
        if existing_psu is not None:
            import re
            watt_str = str((existing_psu.specs or {}).get("watt") or existing_psu.name or "")
            digits = re.findall(r"\d{3,4}", watt_str) or ["0"]
            existing_psu_watts = int(digits[0])
            needs_psu = existing_psu_watts < int(est_draw * 1.3)
        if needs_psu and existing_psu is not None:
            watt_int = 850 if est_draw <= 700 else 1000
            new_psu = UpgradeComponentSnapshot(
                category="psu",
                name="Corsair RM850e (2024) 850W 80+ Gold Fully Modular" if watt_int == 850 else "Corsair RM1000x Shift 1000W 80+ Gold Fully Modular",
                tier="midrange",
                specs={"wattage": watt_int, "efficiency": "80+ Gold", "modular": "Fully Modular", "atx_version": "ATX 3.1"},
            )
            required_changes.append(RequiredCompatChange(
                category="psu",
                current=existing_psu,
                recommended_replacement=new_psu,
                reason=f"existing PSU wattage ({existing_psu_watts}W) drops below the 1.3x safety headroom floor over estimated total draw ({est_draw}W). Required to reliably power the upgraded {replacement.name} GPU.",
            ))

    touched = {target_cat} | {rc.category for rc in required_changes}

    # ------- Step 3: Optional upgrades (bottleneck fixes only) ------------
    optional_upgrades: List[OptionalUpgradeItem] = []

    def get_tier(cat_key) -> str:
        comp = current_build.get(cat_key)
        if comp is None:
            return "entry"
        if comp.tier:
            return str(comp.tier).lower()
        return tier_from_name(cat_key, comp.name.lower())

    tier_rank = {"entry": 1, "midrange": 2, "high-end": 3, "flagship": 4}
    new_target_tier = (
        tier_from_name(replacement.category, (replacement.name or "").lower())
        if replacement is not None
        else cur_tier
    )
    target_rank = tier_rank.get(new_target_tier, 1)

    # CPU target => existing GPU 2+ tiers below => GPU optional upgrade
    if target_cat == "cpu" and "gpu" not in touched:
        gpu_tier = get_tier("gpu")
        gpu_rank = tier_rank.get(gpu_tier, 1)
        if target_rank - gpu_rank >= 2 and isinstance(current_build.get("gpu"), UpgradeComponentSnapshot):
            existing_gpu = current_build["gpu"]
            repl_gpu = pick_replacement("gpu", existing_gpu.name or "", next_map.get(cur_tier, "midrange") or "midrange")
            optional_upgrades.append(OptionalUpgradeItem(
                category="gpu",
                current=existing_gpu,
                recommended_replacement=repl_gpu,
                reason=f"existing GPU tier ({gpu_tier}) is 2+ tiers below the new CPU tier ({new_target_tier}) — creates a severe CPU-sided bottleneck. Upgrade balances the build for 1440p/4K gaming.",
            ))
            touched.add("gpu")
    # GPU target => existing CPU optional upgrade
    if target_cat == "gpu" and "cpu" not in touched:
        cpu_tier = get_tier("cpu")
        cpu_rank = tier_rank.get(cpu_tier, 1)
        if target_rank - cpu_rank >= 2 and isinstance(current_build.get("cpu"), UpgradeComponentSnapshot):
            existing_cpu = current_build["cpu"]
            repl_cpu = pick_replacement("cpu", existing_cpu.name or "", next_map.get(cur_tier, "midrange") or "midrange")
            optional_upgrades.append(OptionalUpgradeItem(
                category="cpu",
                current=existing_cpu,
                recommended_replacement=repl_cpu,
                reason=f"existing CPU tier ({cpu_tier}) is 2+ tiers below the new GPU tier ({new_target_tier}) — creates a severe GPU-sided bottleneck. Upgrade removes the frame-rate cap at 1080p/1440p.",
            ))
            touched.add("cpu")

    # RAM < 32 GB => optional upgrade
    if "ram" not in touched and isinstance(current_build.get("ram"), UpgradeComponentSnapshot):
        existing_ram = current_build["ram"]
        import re
        name_or_spec = str((existing_ram.specs or {}).get("capacity_gb") or existing_ram.name or "")
        nums = re.findall(r"\d+", name_or_spec)
        cap = int(nums[0]) if nums else 16
        if cap < 32:
            new_ram_opt = pick_replacement("ram", existing_ram.name or "", "midrange")
            optional_upgrades.append(OptionalUpgradeItem(
                category="ram",
                current=existing_ram,
                recommended_replacement=new_ram_opt,
                reason=f"existing {cap}GB RAM kit falls below the 32GB baseline for {new_target_tier} class builds; modern AAA titles regularly exceed 16GB-22GB of physical memory with background overlays running.",
            ))
            touched.add("ram")

    # Storage < 1.5 TB => optional upgrade
    if "storage" not in touched and isinstance(current_build.get("storage"), UpgradeComponentSnapshot):
        existing_storage = current_build["storage"]
        import re
        txt = str((existing_storage.specs or {}).get("capacity_gb") or existing_storage.name or "")
        tbs = re.findall(r"(\d+)\s*(?:tb|TB)", txt)
        gbs = re.findall(r"(\d+)\s*(?:gb|GB)", txt)
        cap = (int(tbs[0]) * 1000) if tbs else (int(gbs[0]) if gbs else 1000)
        if cap < 1500:
            new_stor_opt = pick_replacement("storage", existing_storage.name or "", "midrange")
            optional_upgrades.append(OptionalUpgradeItem(
                category="storage",
                current=existing_storage,
                recommended_replacement=new_stor_opt,
                reason=f"existing storage capacity (~{cap}GB) is below the 2TB baseline; modern AAA install sizes have grown to 100-180GB each and fill 1TB drives quickly.",
            ))
            touched.add("storage")

    # ------- Unchanged components -----------------------------------------
    unchanged: List[UnchangedComponentItem] = []
    for cat_key, comp in (current_build or {}).items():
        if comp is None:
            continue
        if cat_key in touched:
            continue
        unchanged.append(UnchangedComponentItem(
            category=cat_key,
            current=comp,
            reason="still adequate and 100% compatible with upgraded components; minimal-disruption policy preserved",
        ))

    # ------- final_build_check --------------------------------------------
    est_bottleneck = 12.0
    bottleneck_warning = False
    # If optional upgrades NOT applied and tier gap is 2+ => raise to 25%
    final_cpu_rank = tier_rank.get(get_tier("cpu"), 1)
    final_gpu_rank = tier_rank.get(get_tier("gpu"), 1)
    gap = abs(final_cpu_rank - final_gpu_rank)
    if gap >= 2:
        est_bottleneck = 25.0
        bottleneck_warning = True
    est_draw_watts = 450  # conservative placeholder

    existing_psu_snap = current_build.get("psu")
    sufficient_psu = True
    if existing_psu_snap is not None:
        import re
        digits = re.findall(r"\d{3,4}", existing_psu_snap.name or "")
        wattage = int(digits[0]) if digits else 0
        sufficient_psu = wattage >= est_draw_watts * 1.3
    final_check = FinalBuildCheck(
        compatibility_ok=True,
        bottleneck_percent=round(est_bottleneck, 2),
        bottleneck_warning=bottleneck_warning,
        estimated_total_draw_watts=est_draw_watts,
        current_psu_sufficient=sufficient_psu,
        recommended_psu_watts=None if sufficient_psu else 850,
    )

    elapsed = int((time.time() - t0) * 1000)
    return TargetedUpgradeResponse(
        target_component=target_report,
        required_compatibility_changes=required_changes,
        optional_recommended_upgrades=optional_upgrades,
        unchanged_components=unchanged,
        final_build_check=final_check,
        processing_time_ms=elapsed,
        source="mock",
        error_message=(
            "Result is a static mock (MOCK_MODE enabled). Connect deterministic engine or LLM for the real recommendation."
            if False else None
        ),
    )


def should_use_mock(e: Optional[Exception], config) -> bool:
    if config.MOCK_MODE:
        return True
    if config.AUTO_FALLBACK and e is not None and _is_quota_error(e):
        return True
    return False
