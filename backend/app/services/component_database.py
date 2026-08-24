"""
Structured Component Database for PC Compatibility Engine
This database contains hardware specifications for deterministic compatibility checking.
"""

from typing import Dict, List, Optional, Any
from dataclasses import dataclass


@dataclass
class CPUDatabaseEntry:
    """CPU database entry with all required compatibility fields."""
    brand: str  # Intel, AMD
    model: str  # e.g., "Core i3-12100F", "Ryzen 5 5500"
    generation: str  # e.g., "12th Gen", "Zen 3"
    socket: str  # e.g., "LGA1700", "AM4", "AM5", "LGA1851"
    memory_type: str  # DDR4, DDR5
    supported_chipsets: List[str]  # e.g., ["B660", "Z690", "H670"]
    tdp_watts: int
    core_count: int
    thread_count: int
    performance_tier: str  # entry, mid, high, extreme


@dataclass
class MotherboardDatabaseEntry:
    """Motherboard database entry with all required compatibility fields."""
    brand: str
    model: str
    socket: str
    chipset: str
    memory_type: str  # DDR4, DDR5
    max_ram_gb: int
    ram_slots: int
    m2_slots: int
    pcie_generation: str  # PCIe 3.0, 4.0, 5.0
    bios_requirements: Optional[str] = None  # Notes on BIOS requirements


@dataclass
class GPUDatabaseEntry:
    """GPU database entry with all required compatibility fields."""
    brand: str  # NVIDIA, AMD
    model: str
    generation: str  # e.g., "RTX 40-series", "RX 7000-series"
    vram_gb: float
    memory_type: str  # GDDR6, GDDR6X
    recommended_psu_watts: int
    performance_tier: str  # entry, mid, high, extreme
    target_resolution: str  # 1080p, 1440p, 4K
    power_draw_watts: int
    pcie_interface: str  # PCIe 3.0, 4.0


@dataclass
class RAMDatabaseEntry:
    """RAM database entry with all required compatibility fields."""
    type: str  # DDR4, DDR5
    capacity_gb: int
    speed_mhz: int
    kit_configuration: str  # e.g., "2x8GB", "2x16GB"
    cas_latency: int


@dataclass
class StorageDatabaseEntry:
    """Storage database entry with all required compatibility fields."""
    type: str  # NVMe SSD, SATA SSD, HDD
    interface: str  # M.2 PCIe 3.0, M.2 PCIe 4.0, M.2 PCIe 5.0, SATA III
    capacity_gb: int
    pcie_generation: Optional[str] = None  # 3.0, 4.0, 5.0
    read_speed_mbps: Optional[int] = None
    write_speed_mbps: Optional[int] = None


@dataclass
class PSUDatabaseEntry:
    """PSU database entry with all required compatibility fields."""
    wattage: int
    efficiency: str  # 80+ Bronze, Gold, Platinum, Titanium
    atx_version: str  # ATX 2.52, ATX 3.0, ATX 3.1
    gpu_connector: str  # 8-pin, 12VHPWR, native
    quality_tier: str  # entry, mid, high


# ============================================================================
# CPU DATABASE
# ============================================================================
CPU_DATABASE: Dict[str, CPUDatabaseEntry] = {
    # Intel 12th Gen (LGA1700)
    "i3-12100F": CPUDatabaseEntry(
        brand="Intel", model="Core i3-12100F", generation="12th Gen",
        socket="LGA1700", memory_type="DDR4/DDR5",
        supported_chipsets=["B660", "B760", "Z690", "Z790", "H610", "H670", "H770"],
        tdp_watts=65, core_count=4, thread_count=8, performance_tier="entry"
    ),
    "i5-12400F": CPUDatabaseEntry(
        brand="Intel", model="Core i5-12400F", generation="12th Gen",
        socket="LGA1700", memory_type="DDR4/DDR5",
        supported_chipsets=["B660", "B760", "Z690", "Z790", "H610", "H670", "H770"],
        tdp_watts=65, core_count=6, thread_count=12, performance_tier="mid"
    ),
    "i5-12600K": CPUDatabaseEntry(
        brand="Intel", model="Core i5-12600K", generation="12th Gen",
        socket="LGA1700", memory_type="DDR4/DDR5",
        supported_chipsets=["Z690", "Z790"],
        tdp_watts=125, core_count=6, thread_count=12, performance_tier="mid"
    ),
    
    # Intel 13th Gen (LGA1700)
    "i5-13400F": CPUDatabaseEntry(
        brand="Intel", model="Core i5-13400F", generation="13th Gen",
        socket="LGA1700", memory_type="DDR4/DDR5",
        supported_chipsets=["B660", "B760", "Z690", "Z790", "H610", "H670", "H770"],
        tdp_watts=65, core_count=10, thread_count=16, performance_tier="mid"
    ),
    "i7-13700K": CPUDatabaseEntry(
        brand="Intel", model="Core i7-13700K", generation="13th Gen",
        socket="LGA1700", memory_type="DDR4/DDR5",
        supported_chipsets=["Z690", "Z790"],
        tdp_watts=125, core_count=16, thread_count=24, performance_tier="high"
    ),
    "i9-13900K": CPUDatabaseEntry(
        brand="Intel", model="Core i9-13900K", generation="13th Gen",
        socket="LGA1700", memory_type="DDR4/DDR5",
        supported_chipsets=["Z690", "Z790"],
        tdp_watts=253, core_count=24, thread_count=32, performance_tier="extreme"
    ),
    
    # Intel 14th Gen (LGA1700)
    "i5-14400F": CPUDatabaseEntry(
        brand="Intel", model="Core i5-14400F", generation="14th Gen",
        socket="LGA1700", memory_type="DDR4/DDR5",
        supported_chipsets=["B660", "B760", "Z690", "Z790", "H610", "H670", "H770"],
        tdp_watts=65, core_count=10, thread_count=16, performance_tier="mid"
    ),
    "i7-14700K": CPUDatabaseEntry(
        brand="Intel", model="Core i7-14700K", generation="14th Gen",
        socket="LGA1700", memory_type="DDR4/DDR5",
        supported_chipsets=["Z690", "Z790"],
        tdp_watts=125, core_count=20, thread_count=28, performance_tier="high"
    ),
    "i9-14900K": CPUDatabaseEntry(
        brand="Intel", model="Core i9-14900K", generation="14th Gen",
        socket="LGA1700", memory_type="DDR4/DDR5",
        supported_chipsets=["Z690", "Z790"],
        tdp_watts=253, core_count=24, thread_count=32, performance_tier="extreme"
    ),
    
    # AMD AM4 (Zen 2/3/3+)
    "Ryzen 5 5500": CPUDatabaseEntry(
        brand="AMD", model="Ryzen 5 5500", generation="Zen 3",
        socket="AM4", memory_type="DDR4",
        supported_chipsets=["B450", "B550", "X470", "X570", "A520"],
        tdp_watts=65, core_count=6, thread_count=12, performance_tier="entry"
    ),
    "Ryzen 5 5600": CPUDatabaseEntry(
        brand="AMD", model="Ryzen 5 5600", generation="Zen 3",
        socket="AM4", memory_type="DDR4",
        supported_chipsets=["B450", "B550", "X470", "X570", "A520"],
        tdp_watts=65, core_count=6, thread_count=12, performance_tier="entry"
    ),
    "Ryzen 5 5600G": CPUDatabaseEntry(
        brand="AMD", model="Ryzen 5 5600G", generation="Zen 3",
        socket="AM4", memory_type="DDR4",
        supported_chipsets=["B450", "B550", "X470", "X570", "A520"],
        tdp_watts=65, core_count=6, thread_count=12, performance_tier="entry"
    ),
    "Ryzen 5 5600X": CPUDatabaseEntry(
        brand="AMD", model="Ryzen 5 5600X", generation="Zen 3",
        socket="AM4", memory_type="DDR4",
        supported_chipsets=["B450", "B550", "X470", "X570", "A520"],
        tdp_watts=65, core_count=6, thread_count=12, performance_tier="mid"
    ),
    "Ryzen 7 5700X": CPUDatabaseEntry(
        brand="AMD", model="Ryzen 7 5700X", generation="Zen 3",
        socket="AM4", memory_type="DDR4",
        supported_chipsets=["B450", "B550", "X470", "X570", "A520"],
        tdp_watts=65, core_count=8, thread_count=16, performance_tier="mid"
    ),
    "Ryzen 7 5700X3D": CPUDatabaseEntry(
        brand="AMD", model="Ryzen 7 5700X3D", generation="Zen 3+",
        socket="AM4", memory_type="DDR4",
        supported_chipsets=["B450", "B550", "X470", "X570", "A520"],
        tdp_watts=105, core_count=8, thread_count=16, performance_tier="high"
    ),
    "Ryzen 7 5800X3D": CPUDatabaseEntry(
        brand="AMD", model="Ryzen 7 5800X3D", generation="Zen 3+",
        socket="AM4", memory_type="DDR4",
        supported_chipsets=["B450", "B550", "X470", "X570", "A520"],
        tdp_watts=105, core_count=8, thread_count=16, performance_tier="high"
    ),
    "Ryzen 9 5900X": CPUDatabaseEntry(
        brand="AMD", model="Ryzen 9 5900X", generation="Zen 3",
        socket="AM4", memory_type="DDR4",
        supported_chipsets=["B450", "B550", "X470", "X570", "A520"],
        tdp_watts=105, core_count=12, thread_count=24, performance_tier="high"
    ),
    
    # AMD AM5 (Zen 4/4+)
    "Ryzen 5 7600": CPUDatabaseEntry(
        brand="AMD", model="Ryzen 5 7600", generation="Zen 4",
        socket="AM5", memory_type="DDR5",
        supported_chipsets=["B650", "B650E", "X670", "X670E", "A620"],
        tdp_watts=65, core_count=6, thread_count=12, performance_tier="mid"
    ),
    "Ryzen 7 7700X": CPUDatabaseEntry(
        brand="AMD", model="Ryzen 7 7700X", generation="Zen 4",
        socket="AM5", memory_type="DDR5",
        supported_chipsets=["B650", "B650E", "X670", "X670E", "A620"],
        tdp_watts=105, core_count=8, thread_count=16, performance_tier="high"
    ),
    "Ryzen 7 7800X3D": CPUDatabaseEntry(
        brand="AMD", model="Ryzen 7 7800X3D", generation="Zen 4+",
        socket="AM5", memory_type="DDR5",
        supported_chipsets=["B650", "B650E", "X670", "X670E", "A620"],
        tdp_watts=120, core_count=8, thread_count=16, performance_tier="high"
    ),
    "Ryzen 9 7900X": CPUDatabaseEntry(
        brand="AMD", model="Ryzen 9 7900X", generation="Zen 4",
        socket="AM5", memory_type="DDR5",
        supported_chipsets=["B650", "B650E", "X670", "X670E", "A620"],
        tdp_watts=170, core_count=12, thread_count=24, performance_tier="extreme"
    ),
    "Ryzen 9 7950X": CPUDatabaseEntry(
        brand="AMD", model="Ryzen 9 7950X", generation="Zen 4",
        socket="AM5", memory_type="DDR5",
        supported_chipsets=["B650", "B650E", "X670", "X670E", "A620"],
        tdp_watts=170, core_count=16, thread_count=32, performance_tier="extreme"
    ),
}


# ============================================================================
# MOTHERBOARD DATABASE
# ============================================================================
MOTHERBOARD_DATABASE: Dict[str, MotherboardDatabaseEntry] = {
    # Intel LGA1700 Motherboards
    "B660M": MotherboardDatabaseEntry(
        brand="Generic", model="B660M", socket="LGA1700", chipset="B660",
        memory_type="DDR4", max_ram_gb=128, ram_slots=4, m2_slots=2,
        pcie_generation="4.0"
    ),
    "B660M DDR5": MotherboardDatabaseEntry(
        brand="Generic", model="B660M DDR5", socket="LGA1700", chipset="B660",
        memory_type="DDR5", max_ram_gb=128, ram_slots=4, m2_slots=2,
        pcie_generation="4.0"
    ),
    "Z790": MotherboardDatabaseEntry(
        brand="Generic", model="Z790", socket="LGA1700", chipset="Z790",
        memory_type="DDR5", max_ram_gb=192, ram_slots=4, m2_slots=4,
        pcie_generation="5.0"
    ),
    "H610M": MotherboardDatabaseEntry(
        brand="Generic", model="H610M", socket="LGA1700", chipset="H610",
        memory_type="DDR4", max_ram_gb=64, ram_slots=2, m2_slots=1,
        pcie_generation="3.0"
    ),
    
    # AMD AM4 Motherboards
    "B450M": MotherboardDatabaseEntry(
        brand="Generic", model="B450M", socket="AM4", chipset="B450",
        memory_type="DDR4", max_ram_gb=64, ram_slots=2, m2_slots=1,
        pcie_generation="3.0",
        bios_requirements="May need BIOS update for Ryzen 5000 series CPUs"
    ),
    "B550M": MotherboardDatabaseEntry(
        brand="Generic", model="B550M", socket="AM4", chipset="B550",
        memory_type="DDR4", max_ram_gb=128, ram_slots=4, m2_slots=2,
        pcie_generation="4.0"
    ),
    "X570": MotherboardDatabaseEntry(
        brand="Generic", model="X570", socket="AM4", chipset="X570",
        memory_type="DDR4", max_ram_gb=128, ram_slots=4, m2_slots=2,
        pcie_generation="4.0"
    ),
    "A520M": MotherboardDatabaseEntry(
        brand="Generic", model="A520M", socket="AM4", chipset="A520",
        memory_type="DDR4", max_ram_gb=64, ram_slots=2, m2_slots=1,
        pcie_generation="3.0"
    ),
    
    # AMD AM5 Motherboards
    "B650M": MotherboardDatabaseEntry(
        brand="Generic", model="B650M", socket="AM5", chipset="B650",
        memory_type="DDR5", max_ram_gb=128, ram_slots=4, m2_slots=2,
        pcie_generation="4.0"
    ),
    "X670E": MotherboardDatabaseEntry(
        brand="Generic", model="X670E", socket="AM5", chipset="X670E",
        memory_type="DDR5", max_ram_gb=128, ram_slots=4, m2_slots=4,
        pcie_generation="5.0"
    ),
    "A620": MotherboardDatabaseEntry(
        brand="Generic", model="A620", socket="AM5", chipset="A620",
        memory_type="DDR5", max_ram_gb=96, ram_slots=2, m2_slots=1,
        pcie_generation="3.0"
    ),
}


# ============================================================================
# GPU DATABASE
# ============================================================================
GPU_DATABASE: Dict[str, GPUDatabaseEntry] = {
    # NVIDIA Entry-Level
    "GTX 1650": GPUDatabaseEntry(
        brand="NVIDIA", model="GTX 1650", generation="GTX 16-series",
        vram_gb=4, memory_type="GDDR5", recommended_psu_watts=300,
        performance_tier="entry", target_resolution="1080p",
        power_draw_watts=75, pcie_interface="PCIe 3.0"
    ),
    "GTX 1660 Super": GPUDatabaseEntry(
        brand="NVIDIA", model="GTX 1660 Super", generation="GTX 16-series",
        vram_gb=6, memory_type="GDDR6", recommended_psu_watts=450,
        performance_tier="entry", target_resolution="1080p",
        power_draw_watts=125, pcie_interface="PCIe 3.0"
    ),
    "RTX 3050": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 3050", generation="RTX 30-series",
        vram_gb=8, memory_type="GDDR6", recommended_psu_watts=450,
        performance_tier="entry", target_resolution="1080p",
        power_draw_watts=130, pcie_interface="PCIe 4.0"
    ),
    "RTX 4050": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 4050", generation="RTX 40-series",
        vram_gb=6, memory_type="GDDR6", recommended_psu_watts=450,
        performance_tier="entry", target_resolution="1080p",
        power_draw_watts=115, pcie_interface="PCIe 4.0"
    ),
    
    # NVIDIA Mid-Range
    "RTX 2060": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 2060", generation="RTX 20-series",
        vram_gb=6, memory_type="GDDR6", recommended_psu_watts=500,
        performance_tier="entry", target_resolution="1080p",
        power_draw_watts=160, pcie_interface="PCIe 3.0"
    ),
    "RTX 3060": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 3060", generation="RTX 30-series",
        vram_gb=12, memory_type="GDDR6", recommended_psu_watts=550,
        performance_tier="mid", target_resolution="1080p/1440p",
        power_draw_watts=170, pcie_interface="PCIe 4.0"
    ),
    "RTX 3060 Ti": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 3060 Ti", generation="RTX 30-series",
        vram_gb=8, memory_type="GDDR6X", recommended_psu_watts=600,
        performance_tier="mid", target_resolution="1440p",
        power_draw_watts=200, pcie_interface="PCIe 4.0"
    ),
    "RTX 4060": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 4060", generation="RTX 40-series",
        vram_gb=8, memory_type="GDDR6", recommended_psu_watts=550,
        performance_tier="mid", target_resolution="1080p/1440p",
        power_draw_watts=115, pcie_interface="PCIe 4.0"
    ),
    "RTX 4060 Ti": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 4060 Ti", generation="RTX 40-series",
        vram_gb=8, memory_type="GDDR6", recommended_psu_watts=600,
        performance_tier="mid", target_resolution="1440p",
        power_draw_watts=160, pcie_interface="PCIe 4.0"
    ),
    "RTX 4060 Ti 16GB": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 4060 Ti 16GB", generation="RTX 40-series",
        vram_gb=16, memory_type="GDDR6", recommended_psu_watts=600,
        performance_tier="mid", target_resolution="1440p",
        power_draw_watts=165, pcie_interface="PCIe 4.0"
    ),
    "RTX 5060": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 5060", generation="RTX 50-series",
        vram_gb=8, memory_type="GDDR7", recommended_psu_watts=550,
        performance_tier="mid", target_resolution="1080p/1440p",
        power_draw_watts=130, pcie_interface="PCIe 4.0"
    ),
    "RTX 5060 Ti": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 5060 Ti", generation="RTX 50-series",
        vram_gb=12, memory_type="GDDR7", recommended_psu_watts=600,
        performance_tier="mid", target_resolution="1440p",
        power_draw_watts=180, pcie_interface="PCIe 4.0"
    ),
    "RTX 5070": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 5070", generation="RTX 50-series",
        vram_gb=12, memory_type="GDDR7", recommended_psu_watts=650,
        performance_tier="high", target_resolution="1440p",
        power_draw_watts=220, pcie_interface="PCIe 4.0"
    ),
    
    # NVIDIA High-End
    "RTX 3070": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 3070", generation="RTX 30-series",
        vram_gb=8, memory_type="GDDR6", recommended_psu_watts=650,
        performance_tier="high", target_resolution="1440p",
        power_draw_watts=220, pcie_interface="PCIe 4.0"
    ),
    "RTX 3070 Ti": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 3070 Ti", generation="RTX 30-series",
        vram_gb=8, memory_type="GDDR6X", recommended_psu_watts=700,
        performance_tier="high", target_resolution="1440p",
        power_draw_watts=290, pcie_interface="PCIe 4.0"
    ),
    "RTX 3080": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 3080", generation="RTX 30-series",
        vram_gb=10, memory_type="GDDR6X", recommended_psu_watts=750,
        performance_tier="high", target_resolution="1440p/4K",
        power_draw_watts=320, pcie_interface="PCIe 4.0"
    ),
    "RTX 4070": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 4070", generation="RTX 40-series",
        vram_gb=12, memory_type="GDDR6X", recommended_psu_watts=650,
        performance_tier="high", target_resolution="1440p",
        power_draw_watts=200, pcie_interface="PCIe 4.0"
    ),
    "RTX 4070 Ti": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 4070 Ti", generation="RTX 40-series",
        vram_gb=12, memory_type="GDDR6X", recommended_psu_watts=700,
        performance_tier="high", target_resolution="1440p/4K",
        power_draw_watts=285, pcie_interface="PCIe 4.0"
    ),
    "RTX 4070 Ti Super": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 4070 Ti Super", generation="RTX 40-series",
        vram_gb=16, memory_type="GDDR6X", recommended_psu_watts=750,
        performance_tier="high", target_resolution="1440p/4K",
        power_draw_watts=285, pcie_interface="PCIe 4.0"
    ),
    "RTX 4080": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 4080", generation="RTX 40-series",
        vram_gb=16, memory_type="GDDR6X", recommended_psu_watts=750,
        performance_tier="high", target_resolution="4K",
        power_draw_watts=320, pcie_interface="PCIe 4.0"
    ),
    "RTX 4080 Super": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 4080 Super", generation="RTX 40-series",
        vram_gb=16, memory_type="GDDR6X", recommended_psu_watts=750,
        performance_tier="high", target_resolution="4K",
        power_draw_watts=320, pcie_interface="PCIe 4.0"
    ),
    "RTX 5070 Ti": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 5070 Ti", generation="RTX 50-series",
        vram_gb=16, memory_type="GDDR7", recommended_psu_watts=750,
        performance_tier="high", target_resolution="1440p/4K",
        power_draw_watts=300, pcie_interface="PCIe 4.0"
    ),
    "RTX 5080": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 5080", generation="RTX 50-series",
        vram_gb=16, memory_type="GDDR7", recommended_psu_watts=850,
        performance_tier="high", target_resolution="4K",
        power_draw_watts=360, pcie_interface="PCIe 4.0"
    ),
    
    # NVIDIA Extreme
    "RTX 3090": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 3090", generation="RTX 30-series",
        vram_gb=24, memory_type="GDDR6X", recommended_psu_watts=850,
        performance_tier="extreme", target_resolution="4K/8K",
        power_draw_watts=350, pcie_interface="PCIe 4.0"
    ),
    "RTX 3090 Ti": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 3090 Ti", generation="RTX 30-series",
        vram_gb=24, memory_type="GDDR6X", recommended_psu_watts=1000,
        performance_tier="extreme", target_resolution="4K/8K",
        power_draw_watts=450, pcie_interface="PCIe 4.0"
    ),
    "RTX 4090": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 4090", generation="RTX 40-series",
        vram_gb=24, memory_type="GDDR6X", recommended_psu_watts=1000,
        performance_tier="extreme", target_resolution="4K/8K",
        power_draw_watts=450, pcie_interface="PCIe 4.0"
    ),
    "RTX 5090": GPUDatabaseEntry(
        brand="NVIDIA", model="RTX 5090", generation="RTX 50-series",
        vram_gb=32, memory_type="GDDR7", recommended_psu_watts=1200,
        performance_tier="extreme", target_resolution="4K/8K",
        power_draw_watts=575, pcie_interface="PCIe 4.0"
    ),
    
    # AMD Entry-Level
    "RX 6500 XT": GPUDatabaseEntry(
        brand="AMD", model="RX 6500 XT", generation="RX 6000-series",
        vram_gb=4, memory_type="GDDR6", recommended_psu_watts=400,
        performance_tier="entry", target_resolution="1080p",
        power_draw_watts=107, pcie_interface="PCIe 4.0"
    ),
    "RX 6600": GPUDatabaseEntry(
        brand="AMD", model="RX 6600", generation="RX 6000-series",
        vram_gb=8, memory_type="GDDR6", recommended_psu_watts=450,
        performance_tier="entry", target_resolution="1080p",
        power_draw_watts=132, pcie_interface="PCIe 4.0"
    ),
    "RX 7600": GPUDatabaseEntry(
        brand="AMD", model="RX 7600", generation="RX 7000-series",
        vram_gb=8, memory_type="GDDR6", recommended_psu_watts=450,
        performance_tier="entry", target_resolution="1080p",
        power_draw_watts=165, pcie_interface="PCIe 4.0"
    ),

    
    # AMD Mid-Range
    "RX 6700 XT": GPUDatabaseEntry(
        brand="AMD", model="RX 6700 XT", generation="RX 6000-series",
        vram_gb=12, memory_type="GDDR6", recommended_psu_watts=650,
        performance_tier="mid", target_resolution="1440p",
        power_draw_watts=230, pcie_interface="PCIe 4.0"
    ),
    "RX 6750 XT": GPUDatabaseEntry(
        brand="AMD", model="RX 6750 XT", generation="RX 6000-series",
        vram_gb=12, memory_type="GDDR6", recommended_psu_watts=650,
        performance_tier="mid", target_resolution="1440p",
        power_draw_watts=250, pcie_interface="PCIe 4.0"
    ),
    "RX 7700 XT": GPUDatabaseEntry(
        brand="AMD", model="RX 7700 XT", generation="RX 7000-series",
        vram_gb=12, memory_type="GDDR6", recommended_psu_watts=650,
        performance_tier="mid", target_resolution="1440p",
        power_draw_watts=245, pcie_interface="PCIe 4.0"
    ),
    "RX 7800 XT": GPUDatabaseEntry(
        brand="AMD", model="RX 7800 XT", generation="RX 7000-series",
        vram_gb=16, memory_type="GDDR6", recommended_psu_watts=750,
        performance_tier="high", target_resolution="1440p/4K",
        power_draw_watts=263, pcie_interface="PCIe 4.0"
    ),
    "RX 9060 XT": GPUDatabaseEntry(
        brand="AMD", model="RX 9060 XT", generation="RX 9000-series",
        vram_gb=16, memory_type="GDDR7", recommended_psu_watts=750,
        performance_tier="high", target_resolution="1440p/4K",
        power_draw_watts=304, pcie_interface="PCIe 4.0"
    ),
    
    # AMD High-End
    "RX 6800": GPUDatabaseEntry(
        brand="AMD", model="RX 6800", generation="RX 6000-series",
        vram_gb=16, memory_type="GDDR6", recommended_psu_watts=750,
        performance_tier="high", target_resolution="1440p/4K",
        power_draw_watts=250, pcie_interface="PCIe 4.0"
    ),
    "RX 6800 XT": GPUDatabaseEntry(
        brand="AMD", model="RX 6800 XT", generation="RX 6000-series",
        vram_gb=16, memory_type="GDDR6", recommended_psu_watts=750,
        performance_tier="high", target_resolution="4K",
        power_draw_watts=300, pcie_interface="PCIe 4.0"
    ),
    "RX 6900 XT": GPUDatabaseEntry(
        brand="AMD", model="RX 6900 XT", generation="RX 6000-series",
        vram_gb=16, memory_type="GDDR6", recommended_psu_watts=850,
        performance_tier="high", target_resolution="4K",
        power_draw_watts=300, pcie_interface="PCIe 4.0"
    ),
    "RX 7900 XT": GPUDatabaseEntry(
        brand="AMD", model="RX 7900 XT", generation="RX 7000-series",
        vram_gb=20, memory_type="GDDR6", recommended_psu_watts=750,
        performance_tier="high", target_resolution="4K",
        power_draw_watts=315, pcie_interface="PCIe 4.0"
    ),
    "RX 7900 XTX": GPUDatabaseEntry(
        brand="AMD", model="RX 7900 XTX", generation="RX 7000-series",
        vram_gb=24, memory_type="GDDR6", recommended_psu_watts=850,
        performance_tier="extreme", target_resolution="4K",
        power_draw_watts=355, pcie_interface="PCIe 4.0"
    ),
    "RX 9070 XT": GPUDatabaseEntry(
        brand="AMD", model="RX 9070 XT", generation="RX 9000-series",
        vram_gb=16, memory_type="GDDR7", recommended_psu_watts=850,
        performance_tier="extreme", target_resolution="4K",
        power_draw_watts=370, pcie_interface="PCIe 4.0"
    ),
}


# ============================================================================
# RAM DATABASE
# ============================================================================
RAM_DATABASE: List[RAMDatabaseEntry] = [
    # DDR4
    RAMDatabaseEntry(type="DDR4", capacity_gb=8, speed_mhz=3200, kit_configuration="1x8GB", cas_latency=16),
    RAMDatabaseEntry(type="DDR4", capacity_gb=16, speed_mhz=3200, kit_configuration="2x8GB", cas_latency=16),
    RAMDatabaseEntry(type="DDR4", capacity_gb=16, speed_mhz=3600, kit_configuration="2x8GB", cas_latency=18),
    RAMDatabaseEntry(type="DDR4", capacity_gb=32, speed_mhz=3200, kit_configuration="2x16GB", cas_latency=16),
    RAMDatabaseEntry(type="DDR4", capacity_gb=32, speed_mhz=3600, kit_configuration="2x16GB", cas_latency=18),
    RAMDatabaseEntry(type="DDR4", capacity_gb=64, speed_mhz=3200, kit_configuration="2x32GB", cas_latency=16),
    RAMDatabaseEntry(type="DDR4", capacity_gb=64, speed_mhz=3600, kit_configuration="2x32GB", cas_latency=18),
    
    # DDR5
    RAMDatabaseEntry(type="DDR5", capacity_gb=16, speed_mhz=4800, kit_configuration="2x8GB", cas_latency=40),
    RAMDatabaseEntry(type="DDR5", capacity_gb=16, speed_mhz=6000, kit_configuration="2x8GB", cas_latency=30),
    RAMDatabaseEntry(type="DDR5", capacity_gb=32, speed_mhz=5600, kit_configuration="2x16GB", cas_latency=36),
    RAMDatabaseEntry(type="DDR5", capacity_gb=32, speed_mhz=6000, kit_configuration="2x16GB", cas_latency=30),
    RAMDatabaseEntry(type="DDR5", capacity_gb=32, speed_mhz=6400, kit_configuration="2x16GB", cas_latency=32),
    RAMDatabaseEntry(type="DDR5", capacity_gb=64, speed_mhz=6000, kit_configuration="2x32GB", cas_latency=30),
    RAMDatabaseEntry(type="DDR5", capacity_gb=64, speed_mhz=6400, kit_configuration="2x32GB", cas_latency=32),
]


# ============================================================================
# STORAGE DATABASE
# ============================================================================
STORAGE_DATABASE: List[StorageDatabaseEntry] = [
    # PCIe 3.0 NVMe
    StorageDatabaseEntry(type="NVMe SSD", interface="M.2 PCIe 3.0", capacity_gb=500, pcie_generation="3.0", read_speed_mbps=3500, write_speed_mbps=3000),
    StorageDatabaseEntry(type="NVMe SSD", interface="M.2 PCIe 3.0", capacity_gb=1000, pcie_generation="3.0", read_speed_mbps=3500, write_speed_mbps=3000),
    StorageDatabaseEntry(type="NVMe SSD", interface="M.2 PCIe 3.0", capacity_gb=2000, pcie_generation="3.0", read_speed_mbps=3500, write_speed_mbps=3000),
    
    # PCIe 4.0 NVMe
    StorageDatabaseEntry(type="NVMe SSD", interface="M.2 PCIe 4.0", capacity_gb=500, pcie_generation="4.0", read_speed_mbps=7000, write_speed_mbps=5000),
    StorageDatabaseEntry(type="NVMe SSD", interface="M.2 PCIe 4.0", capacity_gb=1000, pcie_generation="4.0", read_speed_mbps=7000, write_speed_mbps=5000),
    StorageDatabaseEntry(type="NVMe SSD", interface="M.2 PCIe 4.0", capacity_gb=2000, pcie_generation="4.0", read_speed_mbps=7400, write_speed_mbps=6800),
    StorageDatabaseEntry(type="NVMe SSD", interface="M.2 PCIe 4.0", capacity_gb=4000, pcie_generation="4.0", read_speed_mbps=7400, write_speed_mbps=6800),
    
    # PCIe 5.0 NVMe
    StorageDatabaseEntry(type="NVMe SSD", interface="M.2 PCIe 5.0", capacity_gb=1000, pcie_generation="5.0", read_speed_mbps=10000, write_speed_mbps=10000),
    StorageDatabaseEntry(type="NVMe SSD", interface="M.2 PCIe 5.0", capacity_gb=2000, pcie_generation="5.0", read_speed_mbps=12000, write_speed_mbps=10000),
    StorageDatabaseEntry(type="NVMe SSD", interface="M.2 PCIe 5.0", capacity_gb=4000, pcie_generation="5.0", read_speed_mbps=14000, write_speed_mbps=12000),
    
    # SATA SSD
    StorageDatabaseEntry(type="SATA SSD", interface="SATA III", capacity_gb=500, read_speed_mbps=550, write_speed_mbps=520),
    StorageDatabaseEntry(type="SATA SSD", interface="SATA III", capacity_gb=1000, read_speed_mbps=550, write_speed_mbps=520),
    StorageDatabaseEntry(type="SATA SSD", interface="SATA III", capacity_gb=2000, read_speed_mbps=550, write_speed_mbps=520),
    
    # HDD
    StorageDatabaseEntry(type="HDD", interface="SATA III", capacity_gb=1000, read_speed_mbps=150, write_speed_mbps=150),
    StorageDatabaseEntry(type="HDD", interface="SATA III", capacity_gb=2000, read_speed_mbps=150, write_speed_mbps=150),
    StorageDatabaseEntry(type="HDD", interface="SATA III", capacity_gb=4000, read_speed_mbps=150, write_speed_mbps=150),
    StorageDatabaseEntry(type="HDD", interface="SATA III", capacity_gb=8000, read_speed_mbps=150, write_speed_mbps=150),
]


# ============================================================================
# PSU DATABASE
# ============================================================================
PSU_DATABASE: List[PSUDatabaseEntry] = [
    PSUDatabaseEntry(wattage=450, efficiency="80+ Bronze", atx_version="ATX 2.52", gpu_connector="8-pin", quality_tier="entry"),
    PSUDatabaseEntry(wattage=550, efficiency="80+ Bronze", atx_version="ATX 2.52", gpu_connector="8-pin", quality_tier="entry"),
    PSUDatabaseEntry(wattage=550, efficiency="80+ Gold", atx_version="ATX 2.52", gpu_connector="8-pin", quality_tier="mid"),
    PSUDatabaseEntry(wattage=650, efficiency="80+ Bronze", atx_version="ATX 2.52", gpu_connector="8-pin", quality_tier="entry"),
    PSUDatabaseEntry(wattage=650, efficiency="80+ Gold", atx_version="ATX 2.52", gpu_connector="8-pin", quality_tier="mid"),
    PSUDatabaseEntry(wattage=750, efficiency="80+ Gold", atx_version="ATX 2.52", gpu_connector="8-pin", quality_tier="mid"),
    PSUDatabaseEntry(wattage=750, efficiency="80+ Platinum", atx_version="ATX 2.52", gpu_connector="8-pin", quality_tier="high"),
    PSUDatabaseEntry(wattage=850, efficiency="80+ Gold", atx_version="ATX 2.52", gpu_connector="8-pin", quality_tier="mid"),
    PSUDatabaseEntry(wattage=850, efficiency="80+ Platinum", atx_version="ATX 2.52", gpu_connector="8-pin", quality_tier="high"),
    PSUDatabaseEntry(wattage=850, efficiency="80+ Titanium", atx_version="ATX 3.0", gpu_connector="12VHPWR", quality_tier="high"),
    PSUDatabaseEntry(wattage=1000, efficiency="80+ Gold", atx_version="ATX 2.52", gpu_connector="8-pin", quality_tier="mid"),
    PSUDatabaseEntry(wattage=1000, efficiency="80+ Platinum", atx_version="ATX 2.52", gpu_connector="8-pin", quality_tier="high"),
    PSUDatabaseEntry(wattage=1000, efficiency="80+ Titanium", atx_version="ATX 3.0", gpu_connector="12VHPWR", quality_tier="high"),
    PSUDatabaseEntry(wattage=1200, efficiency="80+ Platinum", atx_version="ATX 2.52", gpu_connector="8-pin", quality_tier="high"),
    PSUDatabaseEntry(wattage=1200, efficiency="80+ Titanium", atx_version="ATX 3.0", gpu_connector="12VHPWR", quality_tier="high"),
    PSUDatabaseEntry(wattage=1600, efficiency="80+ Titanium", atx_version="ATX 3.1", gpu_connector="12VHPWR", quality_tier="high"),
]


# ============================================================================
# HELPER FUNCTIONS
# ============================================================================
def find_cpu_by_name(name: str) -> Optional[CPUDatabaseEntry]:
    """Find CPU in database by name (fuzzy match)."""
    name_lower = name.lower()
    for key, cpu in CPU_DATABASE.items():
        if key.lower() in name_lower or name_lower in key.lower():
            return cpu
    return None


def find_gpu_by_name(name: str) -> Optional[GPUDatabaseEntry]:
    """Find GPU in database by name (fuzzy match)."""
    name_lower = name.lower()
    for key, gpu in GPU_DATABASE.items():
        if key.lower() in name_lower or name_lower in key.lower():
            return gpu
    return None


def find_motherboard_by_name(name: str) -> Optional[MotherboardDatabaseEntry]:
    """Find motherboard in database by name (fuzzy match)."""
    name_lower = name.lower()
    for key, mobo in MOTHERBOARD_DATABASE.items():
        if key.lower() in name_lower or name_lower in key.lower():
            return mobo
    return None


def get_socket_compatibility(cpu_socket: str, motherboard_socket: str) -> bool:
    """Check if CPU socket is compatible with motherboard socket."""
    return cpu_socket.lower() == motherboard_socket.lower()


def get_ram_compatibility(motherboard_ram_type: str, ram_type: str) -> bool:
    """Check if RAM type is compatible with motherboard."""
    # Handle DDR4/DDR5 dual-support CPUs
    if "/" in motherboard_ram_type:
        return ram_type in motherboard_ram_type
    # Handle DDR4/DDR5 dual-support RAM (should match motherboard)
    if "/" in ram_type:
        return motherboard_ram_type in ram_type
    return motherboard_ram_type.lower() == ram_type.lower()


def get_gpu_upgrade_candidates(current_gpu_name: str) -> List[str]:
    """Get list of valid GPU upgrades (better than current)."""
    current_gpu = find_gpu_by_name(current_gpu_name)
    if not current_gpu:
        return []
    
    # Define performance hierarchy
    tier_order = ["entry", "mid", "high", "extreme"]
    current_tier_idx = tier_order.index(current_gpu.performance_tier)
    
    candidates = []
    for key, gpu in GPU_DATABASE.items():
        if key == current_gpu_name:
            continue  # Skip same GPU
        
        # Only recommend higher or same tier but newer generation
        gpu_tier_idx = tier_order.index(gpu.performance_tier)
        if gpu_tier_idx > current_tier_idx:
            candidates.append(key)
        elif gpu_tier_idx == current_tier_idx and gpu.generation != current_gpu.generation:
            # Same tier but newer generation
            if gpu.vram_gb >= current_gpu.vram_gb:
                candidates.append(key)
    
    return candidates


def get_cpu_upgrade_candidates(current_cpu_name: str, current_motherboard_socket: str) -> List[str]:
    """Get list of valid CPU upgrades compatible with current motherboard."""
    current_cpu = find_cpu_by_name(current_cpu_name)
    if not current_cpu:
        return []
    
    candidates = []
    for key, cpu in CPU_DATABASE.items():
        if key == current_cpu_name:
            continue  # Skip same CPU
        
        # Must be compatible with current socket
        if cpu.socket != current_motherboard_socket:
            continue
        
        # Should be higher performance tier or same tier but better
        if cpu.performance_tier != current_cpu.performance_tier:
            if cpu.performance_tier in ["mid", "high", "extreme"] and current_cpu.performance_tier == "entry":
                candidates.append(key)
            elif cpu.performance_tier in ["high", "extreme"] and current_cpu.performance_tier == "mid":
                candidates.append(key)
            elif cpu.performance_tier == "extreme" and current_cpu.performance_tier == "high":
                candidates.append(key)
        else:
            # Same tier but more cores/threads
            if cpu.core_count > current_cpu.core_count:
                candidates.append(key)
    
    return candidates


def calculate_psu_requirement(cpu_tdp: int, gpu_tdp: int, headroom_percent: float = 0.35) -> int:
    """Calculate recommended PSU wattage based on components."""
    base_power = cpu_tdp + gpu_tdp + 150  # CPU + GPU + 150W for system overhead
    required = int(base_power * (1 + headroom_percent))
    
    # Round up to standard PSU wattages
    standard_wattages = [450, 550, 650, 750, 850, 1000, 1200, 1600]
    for wattage in standard_wattages:
        if wattage >= required:
            return wattage
    
    return 1600  # Default to highest if exceeds standard


def classify_pc_tier(cpu_tier: str, gpu_tier: str) -> str:
    """Classify PC tier based on CPU and GPU performance tiers."""
    tier_order = {"entry": 1, "mid": 2, "high": 3, "extreme": 4}
    
    cpu_score = tier_order.get(cpu_tier, 1)
    gpu_score = tier_order.get(gpu_tier, 1)
    
    # Use the lower of the two (bottleneck determines tier)
    overall_score = min(cpu_score, gpu_score)
    
    # Map back to tier name
    for tier, score in tier_order.items():
        if score == overall_score:
            return tier
    
    return "entry"
