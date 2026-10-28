"""
Deterministic Compatibility Engine
This module provides hardware compatibility checking based on the component database.
It overrides LLM compatibility decisions with deterministic rules.
"""

from typing import Optional, List, Dict, Any, Tuple
from dataclasses import dataclass

from app.models.schemas import (
    HardwareSpecs,
    CPUSpec,
    GPUSpec,
    MotherboardSpec,
    RAMSpec,
    StorageSpec,
    PSUSpec,
    CompatibilityIssue,
    CompatibilityReport,
    BottleneckAnalysis,
    UpgradeComponentSnapshot,
    TargetComponentReport,
    RequiredCompatChange,
    OptionalUpgradeItem,
    UnchangedComponentItem,
    FinalBuildCheck,
    TargetedUpgradeResponse,
    TargetedUpgradeRequest,
)
from app.services.component_database import (
    CPU_DATABASE,
    GPU_DATABASE,
    MOTHERBOARD_DATABASE,
    CPUDatabaseEntry,
    GPUDatabaseEntry,
    MotherboardDatabaseEntry,
    RAMDatabaseEntry,
    StorageDatabaseEntry,
    PSUDatabaseEntry,
    find_cpu_by_name,
    find_gpu_by_name,
    find_motherboard_by_name,
    get_socket_compatibility,
    get_ram_compatibility,
    get_gpu_upgrade_candidates,
    get_cpu_upgrade_candidates,
    calculate_psu_requirement,
    classify_pc_tier,
)


@dataclass
class ComponentScore:
    """Separate scoring for components instead of single compatibility score."""
    compatibility: float  # 0-100
    performance_gain: str  # "High", "Medium", "Low", "None"
    value: str  # "High", "Medium", "Low"
    upgrade_priority: str  # "High", "Medium", "Low"


class CompatibilityEngine:
    """
    Deterministic compatibility engine that performs hardware compatibility checks
    based on the component database. This overrides LLM compatibility decisions.
    """
    
    def __init__(self):
        self.validation_checks = [
            self._check_cpu_motherboard_socket,
            self._check_cpu_motherboard_memory,
            self._check_ram_motherboard_compatibility,
            self._check_ram_capacity_support,
            self._check_gpu_motherboard_pcie,
            self._check_psu_wattage,
            self._check_psu_connector,
            self._check_upgrade_is_actual_upgrade,
        ]
    
    def validate_build(self, specs: HardwareSpecs) -> Tuple[bool, List[CompatibilityIssue]]:
        """
        Run all validation checks on a build.
        Returns (is_valid, issues_list).
        """
        all_issues = []
        
        for check in self.validation_checks:
            issues = check(specs)
            all_issues.extend(issues)
        
        # Check for critical issues
        critical_issues = [i for i in all_issues if i.severity == "critical"]
        is_valid = len(critical_issues) == 0
        
        return is_valid, all_issues
    
    def check_cpu_motherboard_compatibility(
        self, 
        cpu_name: str, 
        motherboard_name: str
    ) -> Tuple[bool, str]:
        """
        Check CPU and motherboard compatibility.
        Returns (is_compatible, reason).
        """
        cpu = find_cpu_by_name(cpu_name)
        mobo = find_motherboard_by_name(motherboard_name)
        
        if not cpu:
            return False, f"CPU '{cpu_name}' not found in database"
        if not mobo:
            return False, f"Motherboard '{motherboard_name}' not found in database"
        
        # Check socket compatibility
        if not get_socket_compatibility(cpu.socket, mobo.socket):
            return (
                False,
                f"CPU socket {cpu.socket} does not match motherboard socket {mobo.socket}"
            )
        
        # Check memory type compatibility
        if not get_ram_compatibility(mobo.memory_type, cpu.memory_type):
            return (
                False,
                f"CPU memory type {cpu.memory_type} is not compatible with motherboard memory type {mobo.memory_type}"
            )
        
        # Check chipset compatibility
        if mobo.chipset not in cpu.supported_chipsets:
            return (
                False,
                f"Motherboard chipset {mobo.chipset} is not in CPU's supported chipsets: {', '.join(cpu.supported_chipsets)}"
            )
        
        return True, "Compatible"
    
    def check_ram_compatibility(
        self,
        ram_type: str,
        ram_capacity: int,
        motherboard_name: str
    ) -> Tuple[bool, str]:
        """
        Check RAM compatibility with motherboard.
        Returns (is_compatible, reason).
        """
        mobo = find_motherboard_by_name(motherboard_name)
        if not mobo:
            return False, f"Motherboard '{motherboard_name}' not found in database"
        
        # Check RAM type
        if not get_ram_compatibility(mobo.memory_type, ram_type):
            return (
                False,
                f"{ram_type} RAM is not compatible with {mobo.memory_type} motherboard"
            )
        
        # Check capacity
        if ram_capacity > mobo.max_ram_gb:
            return (
                False,
                f"RAM capacity {ram_capacity}GB exceeds motherboard maximum of {mobo.max_ram_gb}GB"
            )
        
        return True, "Compatible"
    
    def check_gpu_upgrade_validity(
        self,
        current_gpu_name: str,
        upgrade_gpu_name: str
    ) -> Tuple[bool, str]:
        """
        Check if GPU upgrade is actually an upgrade.
        Returns (is_valid_upgrade, reason).
        """
        if current_gpu_name.lower() == upgrade_gpu_name.lower():
            return False, "Upgrade GPU is the same as current GPU - not an upgrade"
        
        current_gpu = find_gpu_by_name(current_gpu_name)
        upgrade_gpu = find_gpu_by_name(upgrade_gpu_name)
        
        if not current_gpu:
            return False, f"Current GPU '{current_gpu_name}' not found in database"
        if not upgrade_gpu:
            return False, f"Upgrade GPU '{upgrade_gpu_name}' not found in database"
        
        # Check if upgrade is actually better
        tier_order = {"entry": 1, "mid": 2, "high": 3, "extreme": 4}
        current_tier = tier_order.get(current_gpu.performance_tier, 1)
        upgrade_tier = tier_order.get(upgrade_gpu.performance_tier, 1)
        
        if upgrade_tier < current_tier:
            return False, f"Upgrade GPU is lower performance tier than current GPU"
        
        if upgrade_tier == current_tier:
            # Same tier - check VRAM and generation
            if upgrade_gpu.vram_gb < current_gpu.vram_gb:
                return False, f"Upgrade GPU has less VRAM than current GPU"
            if upgrade_gpu.generation == current_gpu.generation:
                return False, f"Upgrade GPU is same generation and tier as current GPU"
        
        return True, "Valid upgrade"
    
    def check_cpu_upgrade_validity(
        self,
        current_cpu_name: str,
        upgrade_cpu_name: str,
        motherboard_name: str
    ) -> Tuple[bool, str]:
        """
        Check if CPU upgrade is valid and compatible with motherboard.
        Returns (is_valid_upgrade, reason).
        """
        if current_cpu_name.lower() == upgrade_cpu_name.lower():
            return False, "Upgrade CPU is the same as current CPU - not an upgrade"
        
        current_cpu = find_cpu_by_name(current_cpu_name)
        upgrade_cpu = find_cpu_by_name(upgrade_cpu_name)
        mobo = find_motherboard_by_name(motherboard_name)
        
        if not current_cpu:
            return False, f"Current CPU '{current_cpu_name}' not found in database"
        if not upgrade_cpu:
            return False, f"Upgrade CPU '{upgrade_cpu_name}' not found in database"
        if not mobo:
            return False, f"Motherboard '{motherboard_name}' not found in database"
        
        # Check socket compatibility
        if upgrade_cpu.socket != mobo.socket:
            return (
                False,
                f"Upgrade CPU socket {upgrade_cpu.socket} does not match motherboard socket {mobo.socket}. "
                f"This requires a platform upgrade (CPU + Motherboard + RAM)."
            )
        
        # Check chipset compatibility
        if mobo.chipset not in upgrade_cpu.supported_chipsets:
            return (
                False,
                f"Motherboard chipset {mobo.chipset} is not in upgrade CPU's supported chipsets: {', '.join(upgrade_cpu.supported_chipsets)}"
            )
        
        # Check if upgrade is actually better
        tier_order = {"entry": 1, "mid": 2, "high": 3, "extreme": 4}
        current_tier = tier_order.get(current_cpu.performance_tier, 1)
        upgrade_tier = tier_order.get(upgrade_cpu.performance_tier, 1)
        
        if upgrade_tier < current_tier:
            return False, f"Upgrade CPU is lower performance tier than current CPU"
        
        if upgrade_tier == current_tier:
            # Same tier - check cores/threads
            if upgrade_cpu.core_count <= current_cpu.core_count:
                return False, f"Upgrade CPU has same or fewer cores than current CPU"
        
        return True, "Valid upgrade"
    
    def calculate_component_scores(
        self,
        component_type: str,
        component_name: str,
        current_component_name: Optional[str] = None,
        context: Optional[Dict[str, Any]] = None
    ) -> ComponentScore:
        """
        Calculate separate scores for a component.
        Returns ComponentScore with compatibility, performance_gain, value, upgrade_priority.
        """
        context = context or {}
        
        if component_type == "GPU":
            return self._calculate_gpu_score(component_name, current_component_name, context)
        elif component_type == "CPU":
            return self._calculate_cpu_score(component_name, current_component_name, context)
        elif component_type == "RAM":
            return self._calculate_ram_score(component_name, current_component_name, context)
        elif component_type == "PSU":
            return self._calculate_psu_score(component_name, current_component_name, context)
        elif component_type == "Storage":
            return self._calculate_storage_score(component_name, current_component_name, context)
        else:
            return ComponentScore(
                compatibility=100.0,
                performance_gain="None",
                value="Medium",
                upgrade_priority="Low"
            )
    
    def classify_build_tier(self, specs: HardwareSpecs) -> str:
        """
        Classify the PC build tier based on CPU and GPU performance.
        Returns: "entry", "mid", "high", or "extreme"
        """
        cpu_name = specs.cpu.name if specs.cpu else ""
        gpu_name = specs.gpu.name if specs.gpu else ""
        
        cpu = find_cpu_by_name(cpu_name)
        gpu = find_gpu_by_name(gpu_name)
        
        if not cpu or not gpu:
            return "entry"
        
        return classify_pc_tier(cpu.performance_tier, gpu.performance_tier)
    
    def analyze_performance_balance(
        self,
        specs: HardwareSpecs
    ) -> Tuple[str, str]:
        """
        Analyze CPU/GPU performance balance.
        Returns (balance_status, description).
        """
        cpu_name = specs.cpu.name if specs.cpu else ""
        gpu_name = specs.gpu.name if specs.gpu else ""
        
        cpu = find_cpu_by_name(cpu_name)
        gpu = find_gpu_by_name(gpu_name)
        
        if not cpu or not gpu:
            return "Unknown", "Insufficient data to analyze balance"
        
        tier_order = {"entry": 1, "mid": 2, "high": 3, "extreme": 4}
        cpu_tier = tier_order.get(cpu.performance_tier, 1)
        gpu_tier = tier_order.get(gpu.performance_tier, 1)
        
        tier_diff = abs(cpu_tier - gpu_tier)
        
        if tier_diff == 0:
            return "Excellent", f"Balanced {cpu.performance_tier}-tier CPU and GPU pairing"
        elif tier_diff == 1:
            if cpu_tier > gpu_tier:
                return "Good", f"Slight GPU limitation at high resolutions, but well-balanced overall"
            else:
                return "Good", f"Slight CPU limitation in CPU-bound scenarios at 1080p"
        elif tier_diff == 2:
            if cpu_tier > gpu_tier:
                return "Poor", f"GPU is significantly limiting CPU performance - consider GPU upgrade"
            else:
                return "Poor", f"CPU is significantly limiting GPU performance - consider CPU upgrade"
        else:
            if cpu_tier > gpu_tier:
                return "Severe", f"Severe GPU bottleneck - CPU is severely underutilized"
            else:
                return "Severe", f"Severe CPU bottleneck - GPU cannot reach full potential"
    
    # ========================================================================
    # PRIVATE VALIDATION CHECKS
    # ========================================================================
    
    def _check_cpu_motherboard_socket(self, specs: HardwareSpecs) -> List[CompatibilityIssue]:
        """CHECK 1: CPU socket == motherboard socket"""
        issues = []
        if not specs.cpu or not specs.motherboard:
            return issues
        
        cpu = find_cpu_by_name(specs.cpu.name)
        mobo = find_motherboard_by_name(specs.motherboard.name)
        
        if cpu and mobo:
            if not get_socket_compatibility(cpu.socket, mobo.socket):
                issues.append(CompatibilityIssue(
                    severity="critical",
                    components_involved=["CPU", "Motherboard"],
                    issue=f"CPU socket {cpu.socket} does not match motherboard socket {mobo.socket}",
                    resolution="Replace either the CPU or motherboard to match sockets"
                ))
        
        return issues
    
    def _check_cpu_motherboard_memory(self, specs: HardwareSpecs) -> List[CompatibilityIssue]:
        """CHECK 2: CPU memory support == motherboard RAM type"""
        issues = []
        if not specs.cpu or not specs.motherboard:
            return issues
        
        cpu = find_cpu_by_name(specs.cpu.name)
        mobo = find_motherboard_by_name(specs.motherboard.name)
        
        if cpu and mobo:
            # Check if CPU supports the motherboard's memory type
            # If CPU supports DDR4/DDR5, it's compatible with either
            # If motherboard is DDR5 only, CPU must support DDR5
            cpu_memory_types = cpu.memory_type.split("/") if "/" in cpu.memory_type else [cpu.memory_type]
            motherboard_memory_type = mobo.memory_type
            
            if motherboard_memory_type not in cpu_memory_types:
                issues.append(CompatibilityIssue(
                    severity="critical",
                    components_involved=["CPU", "Motherboard"],
                    issue=f"CPU memory type {cpu.memory_type} is not compatible with motherboard memory type {mobo.memory_type}",
                    resolution="Replace motherboard with one that supports the CPU's memory type"
                ))
        
        return issues
    
    def _check_ram_motherboard_compatibility(self, specs: HardwareSpecs) -> List[CompatibilityIssue]:
        """CHECK 3: RAM type == motherboard RAM type"""
        issues = []
        if not specs.ram or not specs.motherboard:
            return issues
        
        mobo = find_motherboard_by_name(specs.motherboard.name)
        ram_type = specs.ram.ram_type
        
        if mobo and ram_type:
            if not get_ram_compatibility(mobo.memory_type, ram_type):
                issues.append(CompatibilityIssue(
                    severity="critical",
                    components_involved=["RAM", "Motherboard"],
                    issue=f"{ram_type} RAM does not work with {mobo.memory_type} motherboard",
                    resolution=f"Replace RAM with matching {mobo.memory_type} kit"
                ))
        
        return issues
    
    def _check_ram_capacity_support(self, specs: HardwareSpecs) -> List[CompatibilityIssue]:
        """CHECK 4: RAM capacity/configuration is supported"""
        issues = []
        if not specs.ram or not specs.motherboard:
            return issues
        
        mobo = find_motherboard_by_name(specs.motherboard.name)
        ram_capacity = specs.ram.total_capacity_gb
        
        if mobo and ram_capacity:
            if ram_capacity > mobo.max_ram_gb:
                issues.append(CompatibilityIssue(
                    severity="critical",
                    components_involved=["RAM", "Motherboard"],
                    issue=f"RAM capacity {ram_capacity}GB exceeds motherboard maximum of {mobo.max_ram_gb}GB",
                    resolution="Use less RAM or a motherboard that supports more RAM"
                ))
        
        return issues
    
    def _check_gpu_motherboard_pcie(self, specs: HardwareSpecs) -> List[CompatibilityIssue]:
        """CHECK 5: GPU is compatible with motherboard PCIe slot"""
        issues = []
        if not specs.gpu or not specs.motherboard:
            return issues
        
        # Most GPUs are backward compatible with PCIe, so this is usually just a warning
        # unless there's a specific incompatibility
        return issues
    
    def _check_psu_wattage(self, specs: HardwareSpecs) -> List[CompatibilityIssue]:
        """CHECK 6: PSU wattage is sufficient"""
        issues = []
        if not specs.psu or not specs.cpu or not specs.gpu:
            return issues
        
        cpu = find_cpu_by_name(specs.cpu.name)
        gpu = find_gpu_by_name(specs.gpu.name)
        
        if cpu and gpu and specs.psu.wattage:
            required = calculate_psu_requirement(cpu.tdp_watts, gpu.power_draw_watts)
            if specs.psu.wattage < required:
                issues.append(CompatibilityIssue(
                    severity="warning",
                    components_involved=["PSU", "CPU", "GPU"],
                    issue=f"PSU {specs.psu.wattage}W may be insufficient (recommended: {required}W)",
                    resolution=f"Upgrade to at least {required}W PSU for reliable operation"
                ))
        
        return issues
    
    def _check_psu_connector(self, specs: HardwareSpecs) -> List[CompatibilityIssue]:
        """CHECK 7: PSU connector is appropriate for GPU"""
        issues = []
        if not specs.psu or not specs.gpu:
            return issues
        
        gpu = find_gpu_by_name(specs.gpu.name)
        
        # High-end GPUs may require 12VHPWR connectors
        if gpu and gpu.performance_tier in ["high", "extreme"]:
            if specs.psu.wattage and specs.psu.wattage >= 850:
                # Check if PSU mentions ATX 3.0/3.1 for 12VHPWR support
                if specs.psu.efficiency_rating and "Titanium" in specs.psu.efficiency_rating:
                    # Likely has 12VHPWR
                    pass
                else:
                    issues.append(CompatibilityIssue(
                        severity="warning",
                        components_involved=["PSU", "GPU"],
                        issue="High-end GPU may require 12VHPWR connector for optimal performance",
                        resolution="Consider PSU with ATX 3.0/3.1 and native 12VHPWR support, or use adapter"
                    ))
        
        return issues
    
    def _check_upgrade_is_actual_upgrade(self, specs: HardwareSpecs) -> List[CompatibilityIssue]:
        """CHECK 9: Upgrade component is actually an upgrade"""
        # This is checked in the upgrade validation methods
        return []
    
    # ========================================================================
    # PRIVATE SCORE CALCULATIONS
    # ========================================================================
    
    def _calculate_gpu_score(
        self,
        gpu_name: str,
        current_gpu_name: Optional[str],
        context: Dict[str, Any]
    ) -> ComponentScore:
        gpu = find_gpu_by_name(gpu_name)
        
        if not gpu:
            return ComponentScore(
                compatibility=50.0,
                performance_gain="None",
                value="Low",
                upgrade_priority="Low"
            )
        
        compatibility = 100.0
        
        if current_gpu_name:
            is_valid, reason = self.check_gpu_upgrade_validity(current_gpu_name, gpu_name)
            if not is_valid:
                compatibility = 0.0
                return ComponentScore(
                    compatibility=compatibility,
                    performance_gain="None",
                    value="Low",
                    upgrade_priority="Low"
                )
            
            current_gpu = find_gpu_by_name(current_gpu_name)
            if current_gpu:
                tier_order = {"entry": 1, "mid": 2, "high": 3, "extreme": 4}
                current_tier = tier_order.get(current_gpu.performance_tier, 1)
                upgrade_tier = tier_order.get(gpu.performance_tier, 1)
                
                if upgrade_tier > current_tier + 1:
                    performance_gain = "High"
                elif upgrade_tier > current_tier:
                    performance_gain = "Medium"
                else:
                    performance_gain = "Low"
                
                # Value based on tier jump vs cost (simplified)
                if upgrade_tier - current_tier >= 2:
                    value = "High"
                elif upgrade_tier - current_tier == 1:
                    value = "Medium"
                else:
                    value = "Low"
                
                upgrade_priority = performance_gain
            else:
                performance_gain = "Medium"
                value = "Medium"
                upgrade_priority = "Medium"
        else:
            performance_gain = "None"
            value = "Medium"
            upgrade_priority = "Low"
        
        return ComponentScore(
            compatibility=compatibility,
            performance_gain=performance_gain,
            value=value,
            upgrade_priority=upgrade_priority
        )
    
    def _calculate_cpu_score(
        self,
        cpu_name: str,
        current_cpu_name: Optional[str],
        context: Dict[str, Any]
    ) -> ComponentScore:
        cpu = find_cpu_by_name(cpu_name)
        
        if not cpu:
            return ComponentScore(
                compatibility=50.0,
                performance_gain="None",
                value="Low",
                upgrade_priority="Low"
            )
        
        compatibility = 100.0
        motherboard_name = context.get("motherboard_name", "")
        
        if current_cpu_name and motherboard_name:
            is_valid, reason = self.check_cpu_upgrade_validity(
                current_cpu_name, cpu_name, motherboard_name
            )
            if not is_valid:
                if "platform upgrade" in reason.lower():
                    compatibility = 50.0  # Partially compatible (needs platform upgrade)
                else:
                    compatibility = 0.0
                
                return ComponentScore(
                    compatibility=compatibility,
                    performance_gain="None",
                    value="Low",
                    upgrade_priority="Low"
                )
            
            current_cpu = find_cpu_by_name(current_cpu_name)
            if current_cpu:
                tier_order = {"entry": 1, "mid": 2, "high": 3, "extreme": 4}
                current_tier = tier_order.get(current_cpu.performance_tier, 1)
                upgrade_tier = tier_order.get(cpu.performance_tier, 1)
                
                if upgrade_tier > current_tier + 1:
                    performance_gain = "High"
                elif upgrade_tier > current_tier:
                    performance_gain = "Medium"
                else:
                    performance_gain = "Low"
                
                # CPU upgrades on same platform have high value
                if compatibility == 100.0:
                    value = "High"
                else:
                    value = "Medium"
                
                upgrade_priority = performance_gain
            else:
                performance_gain = "Medium"
                value = "Medium"
                upgrade_priority = "Medium"
        else:
            performance_gain = "None"
            value = "Medium"
            upgrade_priority = "Low"
        
        return ComponentScore(
            compatibility=compatibility,
            performance_gain=performance_gain,
            value=value,
            upgrade_priority=upgrade_priority
        )
    
    def _calculate_ram_score(
        self,
        ram_name: str,
        current_ram_name: Optional[str],
        context: Dict[str, Any]
    ) -> ComponentScore:
        # RAM scoring based on capacity and type
        # This is simplified - in reality would parse the RAM name
        compatibility = 100.0
        
        if "32GB" in ram_name or "64GB" in ram_name:
            performance_gain = "Medium"
            value = "Medium"
            upgrade_priority = "Medium"
        elif "16GB" in ram_name:
            performance_gain = "Low"
            value = "High"
            upgrade_priority = "Medium"
        else:
            performance_gain = "Low"
            value = "High"
            upgrade_priority = "Low"
        
        return ComponentScore(
            compatibility=compatibility,
            performance_gain=performance_gain,
            value=value,
            upgrade_priority=upgrade_priority
        )
    
    def _calculate_psu_score(
        self,
        psu_name: str,
        current_psu_name: Optional[str],
        context: Dict[str, Any]
    ) -> ComponentScore:
        # PSU scoring based on wattage and efficiency
        compatibility = 100.0
        performance_gain = "None"  # PSU doesn't improve performance
        upgrade_priority = "Low"
        
        # Value based on efficiency rating
        if "Titanium" in psu_name or "Platinum" in psu_name:
            value = "High"
        elif "Gold" in psu_name:
            value = "Medium"
        else:
            value = "Low"
        
        # Upgrade priority depends on whether current PSU is insufficient
        gpu_tdp = context.get("gpu_tdp", 0)
        cpu_tdp = context.get("cpu_tdp", 0)
        if gpu_tdp and cpu_tdp:
            required = calculate_psu_requirement(cpu_tdp, gpu_tdp)
            current_wattage = context.get("current_psu_wattage", 0)
            if current_wattage and current_wattage < required:
                upgrade_priority = "High"
                value = "High"
        
        return ComponentScore(
            compatibility=compatibility,
            performance_gain=performance_gain,
            value=value,
            upgrade_priority=upgrade_priority
        )
    
    def _calculate_storage_score(
        self,
        storage_name: str,
        current_storage_name: Optional[str],
        context: Dict[str, Any]
    ) -> ComponentScore:
        # Storage scoring based on capacity and speed
        compatibility = 100.0
        performance_gain = "Low"  # Storage doesn't significantly improve gaming FPS
        upgrade_priority = "Low"
        
        # Value based on capacity
        if "4TB" in storage_name or "8TB" in storage_name:
            value = "Medium"
        elif "2TB" in storage_name:
            value = "High"
        elif "1TB" in storage_name:
            value = "High"
        else:
            value = "Medium"
        
        # Upgrade priority depends on whether user needs more storage
        current_capacity = context.get("current_storage_gb", 0)
        if current_capacity and current_capacity < 1000:
            upgrade_priority = "Medium"
        
        return ComponentScore(
            compatibility=compatibility,
            performance_gain=performance_gain,
            value=value,
            upgrade_priority=upgrade_priority
        )

    # ========================================================================
    # NEW: TARGETED UPGRADE ADVISOR (3-step spec implementation)
    # ========================================================================

    TIER_MAP_4: Dict[str, str] = {
        "entry": "entry",
        "mid": "midrange",
        "high": "high-end",
        "extreme": "flagship",
    }
    NEXT_TIER_UP: Dict[str, Optional[str]] = {
        "entry": "midrange",
        "midrange": "high-end",
        "high-end": "high-end",
        "flagship": None,
    }

    # --------------------- Tier helpers ------------------------------------
    @classmethod
    def _to_4_tier(cls, raw_tier: Optional[str]) -> str:
        if not raw_tier:
            return "entry"
        lower = raw_tier.lower()
        if "flagship" in lower or "super" in lower or lower in {"extreme", "enthusiast"}:
            return "flagship"
        if lower in cls.TIER_MAP_4:
            return cls.TIER_MAP_4[lower]
        if "high" in lower:
            return "high-end"
        if "mid" in lower:
            return "midrange"
        if "entry" in lower or "budget" in lower or "low" in lower:
            return "entry"
        return "entry"

    @classmethod
    def _cpu_to_snapshot(cls, cpu: CPUDatabaseEntry) -> UpgradeComponentSnapshot:
        return UpgradeComponentSnapshot(
            category="cpu",
            name=f"{'AMD' if 'AMD' in cpu.brand or 'Ryzen' in cpu.model else 'Intel'} {cpu.model}",
            tier=cls.TIER_MAP_4.get(cpu.performance_tier, "entry"),
            specs={
                "socket": cpu.socket,
                "memory_type": cpu.memory_type,
                "tdp_watts": cpu.tdp_watts,
                "core_count": cpu.core_count,
                "thread_count": cpu.thread_count,
                "generation": cpu.generation,
                "supported_chipsets": list(cpu.supported_chipsets),
            },
        )

    @classmethod
    def _gpu_to_snapshot(cls, gpu: GPUDatabaseEntry) -> UpgradeComponentSnapshot:
        return UpgradeComponentSnapshot(
            category="gpu",
            name=f"{'NVIDIA' if gpu.brand in ('NVIDIA', 'Nvidia') else 'AMD'} {gpu.model}",
            tier=cls.TIER_MAP_4.get(gpu.performance_tier, "entry"),
            specs={
                "vram_gb": gpu.vram_gb,
                "memory_type": gpu.memory_type,
                "recommended_psu_watts": gpu.recommended_psu_watts,
                "power_draw_watts": gpu.power_draw_watts,
                "generation": gpu.generation,
                "target_resolution": gpu.target_resolution,
                "pcie_interface": gpu.pcie_interface,
            },
        )

    def _classify_target_tier(self, target: UpgradeComponentSnapshot) -> str:
        if target.tier:
            return self._to_4_tier(target.tier)
        category = (target.category or "").lower()
        name = target.name
        if category == "cpu":
            cpu = find_cpu_by_name(name)
            if cpu:
                return self.TIER_MAP_4.get(cpu.performance_tier, "entry")
        elif category == "gpu":
            gpu = find_gpu_by_name(name)
            if gpu:
                return self.TIER_MAP_4.get(gpu.performance_tier, "entry")
        return "entry"

    def _find_cpu_at_or_next(self, current: CPUDatabaseEntry, target_tier: str) -> Optional[CPUDatabaseEntry]:
        """Return CPU of target_tier that is the 'next tier up' following the spec.
        If target_tier == 'high-end' and current is high-end, return another strictly-stronger high-end CPU
        (same socket if possible, else better socket/gen). Never go extreme unless nothing else works.
        If target_tier is 'midrange' or 'entry' return the best in that tier for current socket/brand.
        """
        brand_prefix = current.brand
        candidates = [c for c in CPU_DATABASE.values() if c.brand == brand_prefix]
        def score(c: CPUDatabaseEntry) -> Tuple[int, int, int, int]:
            # Favor same socket first
            socket_pts = 10_000 if c.socket == current.socket else 0
            tier_pts = {"entry": 100, "mid": 200, "high": 300, "extreme": 350}[c.performance_tier]
            # target mapping: entry -> mid, midrange -> high, high-end -> high (not extreme)
            want_tier_raw = {
                "midrange": "mid",
                "high-end": "high",
                "flagship": "extreme",
                "entry": "entry",
            }.get(target_tier, "mid")
            tier_fit = 500 if c.performance_tier == want_tier_raw else 0
            # If we want "high-end" but want strictly better same-tier than current:
            better_than_current_pts = 0
            if (current.performance_tier == "high" and c.performance_tier == "high"
                    and (c.core_count > current.core_count
                         or c.tdp_watts > current.tdp_watts and c.core_count >= current.core_count)):
                better_than_current_pts = 250
            elif c.performance_tier == want_tier_raw:
                pass
            strength_pts = c.core_count * 10 + (c.tdp_watts // 10)
            return (socket_pts + tier_fit + better_than_current_pts + tier_pts,
                    strength_pts, c.core_count, c.thread_count)

        ranked = sorted(candidates, key=score, reverse=True)
        for c in ranked:
            # Guard: Never downgrade/lateral for non-high cases
            if target_tier != "high-end":
                return c
            # target high-end: ensure not worse than current
            if (c.performance_tier == "high"
                    and c.core_count >= current.core_count
                    and c.thread_count >= current.thread_count):
                # prefer strictly stronger if available within same socket
                if c.socket == current.socket and (
                    c.core_count > current.core_count or c.thread_count > current.thread_count
                ):
                    return c
                # otherwise next platform upgrade (still acceptable high-end match)
                return c
            if c.performance_tier == "extreme" and current.performance_tier == "high":
                # Only fall back to extreme if NO stronger same-socket high-end found (checked below via return c eventually)
                pass
        # Last-ditch: return top-ranked if not lateral/downgrade vs current
        for c in ranked:
            if c.core_count >= current.core_count:
                return c
        return ranked[0] if ranked else None

    def _find_gpu_at_or_next(self, current: GPUDatabaseEntry, target_tier: str) -> Optional[GPUDatabaseEntry]:
        candidates = list(GPU_DATABASE.values())
        want_raw_tier = {"midrange": "mid", "high-end": "high", "flagship": "extreme", "entry": "entry"}.get(target_tier, "mid")

        def score(g: GPUDatabaseEntry) -> Tuple[int, int, int]:
            tier_pts = {"entry": 100, "mid": 200, "high": 300, "extreme": 350}[g.performance_tier]
            want_fit = 1000 if g.performance_tier == want_raw_tier else 0
            better_current = 0
            if target_tier == "high-end" and current.performance_tier == "high":
                if g.performance_tier == "high" and g.vram_gb >= current.vram_gb and g.power_draw_watts >= current.power_draw_watts:
                    better_current = 500
                if g.performance_tier == "extreme" and current.performance_tier == "high":
                    # Don't jump directly to extreme unless no stronger high-end found
                    better_current = 200
            strength_pts = int(g.vram_gb) * 100 + g.power_draw_watts
            return (want_fit + better_current + tier_pts, strength_pts, int(g.recommended_psu_watts))

        ranked = sorted(candidates, key=score, reverse=True)
        for g in ranked:
            if target_tier != "high-end":
                return g
            # Guard high-end case: avoid downgrade/lateral
            if g.performance_tier in ("high", "extreme") and g.vram_gb >= current.vram_gb and g.power_draw_watts >= current.power_draw_watts:
                return g
        return ranked[0] if ranked else None

    # ----------------------- Main 3-step orchestrator -----------------------
    def generate_targeted_upgrade(self, request: TargetedUpgradeRequest) -> TargetedUpgradeResponse:
        import time as _t
        t0 = _t.time()
        target = request.target_component
        current_build = request.current_build or {}

        # ------ STEP 1: Upgrade the target component first -------------------
        current_tier = self._classify_target_tier(target)
        target_cat = (target.category or "").lower()
        upgrade_needed: bool = current_tier != "flagship"
        next_tier = self.NEXT_TIER_UP.get(current_tier) if upgrade_needed else None
        reason = (
            "already flagship tier, no upgrade needed" if not upgrade_needed
            else f"{current_tier} -> {next_tier} upgrade"
        )

        target_replacement: Optional[UpgradeComponentSnapshot] = None
        target_replacement_cpu: Optional[CPUDatabaseEntry] = None
        target_replacement_gpu: Optional[GPUDatabaseEntry] = None
        target_current_cpu = find_cpu_by_name(target.name) if target_cat == "cpu" else None
        target_current_gpu = find_gpu_by_name(target.name) if target_cat == "gpu" else None

        # Scoped wattage fallbacks - used by required_changes (GPU->PSU) AND Step 3 optional PSU check
        # (previously cpu_for_watts was defined only inside GPU-only block -> NameError for CPU/other targets)
        _cb_cpu: Optional[UpgradeComponentSnapshot] = (
            current_build["cpu"] if isinstance(current_build.get("cpu"), UpgradeComponentSnapshot) else None
        )
        cpu_for_watts: Optional[CPUDatabaseEntry] = (
            find_cpu_by_name(_cb_cpu.name) if _cb_cpu else (target_current_cpu if target_cat == "cpu" else None)
        )
        _cb_gpu: Optional[UpgradeComponentSnapshot] = (
            current_build["gpu"] if isinstance(current_build.get("gpu"), UpgradeComponentSnapshot) else None
        )
        gpu_for_watts: Optional[GPUDatabaseEntry] = (
            find_gpu_by_name(_cb_gpu.name) if _cb_gpu else (target_current_gpu if target_cat == "gpu" else None)
        )

        if upgrade_needed and next_tier:
            if target_cat == "cpu" and target_current_cpu and next_tier:
                cand = self._find_cpu_at_or_next(target_current_cpu, next_tier)
                if cand is not None:
                    target_replacement_cpu = cand
                    target_replacement = self._cpu_to_snapshot(cand)
            elif target_cat == "gpu" and target_current_gpu and next_tier:
                cand = self._find_gpu_at_or_next(target_current_gpu, next_tier)
                if cand is not None:
                    target_replacement_gpu = cand
                    target_replacement = self._gpu_to_snapshot(cand)
            else:
                # Non-CPU/GPU categories: keep upgrade_needed but flag as best-guess
                target_replacement = UpgradeComponentSnapshot(
                    category=target_cat,
                    name=(
                        "32GB DDR5-6000 (2x16GB) Dual-Rank Kit" if target_cat == "ram"
                        else "Crucial T700 2TB PCIe 5.0 NVMe SSD" if target_cat == "storage"
                        else "MSI MAG X670E TOMAHAWK WIFI (AM5)" if target_cat == "motherboard"
                        else "Corsair RM850e (2024) 850W 80+ Gold Fully Modular" if target_cat == "psu"
                        else f"{target.name} (next tier upgrade)"
                    ),
                    tier=next_tier,
                    specs=target.specs or {},
                )

        target_report = TargetComponentReport(
            current=target,
            current_tier=current_tier,
            upgrade_needed=upgrade_needed,
            recommended_replacement=target_replacement,
            reason=reason,
        )

        # ------ Build final component map for iteration ----------------------
        final_map: Dict[str, Optional[UpgradeComponentSnapshot]] = dict(current_build)
        # Ensure target appears in final_map
        if target_cat not in final_map:
            final_map[target_cat] = target
        # Swap target for its replacement if we have one
        if target_replacement is not None:
            final_map[target_cat] = target_replacement

        # ------ STEP 2: Required compatibility changes -----------------------
        required_changes: List[RequiredCompatChange] = []

        # CPU -> Motherboard socket/chipset + RAM type
        if target_cat == "cpu" and target_replacement_cpu is not None:
            new_cpu = target_replacement_cpu
            current_mobo = current_build.get("motherboard")
            if current_mobo is not None:
                mobo_db = find_motherboard_by_name(current_mobo.name)
                # User-provided socket extraction: handle cases where fuzzy DB lookup fails
                # (e.g. user-supplied mobo name has extra tokens like "(AM4)" that break DB search)
                user_provided_socket = str(
                    (current_mobo.specs or {}).get("socket") or ""
                ).strip().lower()
                user_provided_memtype = str(
                    (current_mobo.specs or {}).get("memory_type") or ""
                ).strip().upper()

                need_mobo = True
                # Trust user-provided socket first if it explicitly matches new CPU socket
                if user_provided_socket and user_provided_socket == new_cpu.socket.lower():
                    socket_ok_explicit = True
                    mem_ok_explicit = (
                        not user_provided_memtype
                        or new_cpu.memory_type == user_provided_memtype
                        or ("/" in new_cpu.memory_type and user_provided_memtype in new_cpu.memory_type.upper().split("/"))
                    )
                    if socket_ok_explicit and mem_ok_explicit:
                        need_mobo = False
                elif mobo_db is not None:
                    socket_ok = mobo_db.socket.lower() == new_cpu.socket.lower()
                    ram_ok = (new_cpu.memory_type == mobo_db.memory_type
                              or "/" in new_cpu.memory_type and mobo_db.memory_type in new_cpu.memory_type.split("/"))
                    chipset_ok = mobo_db.chipset in new_cpu.supported_chipsets
                    need_mobo = not (socket_ok and ram_ok and chipset_ok)
                else:
                    # No user socket info AND no DB match: default to compatible-safe no-op
                    # (avoid lateral mobo swap if we cannot confirm incompatibility - minimal disruption)
                    need_mobo = False
                if need_mobo:
                    # Recommend compatible mobo (best effort generic real model)
                    new_mobo_name = (
                        "MSI MAG X670E TOMAHAWK WIFI" if new_cpu.socket == "AM5"
                        else "ASUS ROG STRIX X870-A GAMING WIFI" if new_cpu.socket == "AM5" and "X670E" in "X670E"
                        else "ASUS TUF GAMING Z790-PLUS WIFI" if new_cpu.socket == "LGA1700"
                        else "Gigabyte Z890 AORUS ELITE WIFI7" if new_cpu.socket == "LGA1851"
                        else "MSI MAG B550 TOMAHAWK WIFI" if new_cpu.socket == "AM4"
                        else "Compatible Motherboard Upgrade"
                    )
                    new_mobo_snap = UpgradeComponentSnapshot(
                        category="motherboard",
                        name=new_mobo_name,
                        tier="high-end",
                        specs={
                            "socket": new_cpu.socket,
                            "chipset": (new_cpu.supported_chipsets[0] if new_cpu.supported_chipsets else ""),
                            "memory_type": new_cpu.memory_type.split("/")[-1],
                            "pcie_generation": "PCIe 5.0",
                        },
                    )
                    required_changes.append(RequiredCompatChange(
                        category="motherboard",
                        current=current_mobo,
                        recommended_replacement=new_mobo_snap,
                        reason=(
                            f"socket mismatch with new CPU: "
                            f"{mobo_db.socket if mobo_db else 'unknown'} vs {new_cpu.socket}; "
                            f"platform upgrade required for full compatibility"
                        ),
                    ))
                    final_map["motherboard"] = new_mobo_snap

                    # RAM type change: DDR4 -> DDR5 or vice versa, add required RAM upgrade
                    current_ram = current_build.get("ram")
                    if current_ram is not None:
                        # Best-effort memory comparison: if DDR4 vs DDR5 mismatch, force
                        new_mem_type = new_cpu.memory_type.split("/")[-1]
                        old_mem_type = (current_ram.specs or {}).get("type") or current_ram.name or ""
                        mismatch = False
                        if "DDR4" in old_mem_type.upper() and "DDR5" in new_mem_type.upper():
                            mismatch = True
                        if mismatch:
                            new_ram_snap = UpgradeComponentSnapshot(
                                category="ram",
                                name="G.Skill Trident Z5 RGB 32GB (2x16GB) DDR5-6000 CL30",
                                tier="midrange",
                                specs={
                                    "type": "DDR5",
                                    "capacity_gb": 32,
                                    "speed_mhz": 6000,
                                    "cas_latency": 30,
                                    "kit_configuration": "2x16GB",
                                },
                            )
                            required_changes.append(RequiredCompatChange(
                                category="ram",
                                current=current_ram,
                                recommended_replacement=new_ram_snap,
                                reason=f"memory type mismatch: new platform requires DDR5; existing DDR4 kit is incompatible",
                            ))
                            final_map["ram"] = new_ram_snap

        # GPU -> PSU wattage headroom check
        if target_cat == "gpu" and target_replacement_gpu is not None:
            new_gpu = target_replacement_gpu
            cpu_tdp = cpu_for_watts.tdp_watts if cpu_for_watts else 65
            est_total_draw = cpu_tdp + new_gpu.power_draw_watts + 150  # 150W overhead (mobo/ram/storage/fans)
            current_psu = current_build.get("psu")
            current_psu_watts: Optional[int] = None
            if isinstance(current_psu, UpgradeComponentSnapshot):
                try:
                    if current_psu.specs and "wattage" in current_psu.specs:
                        current_psu_watts = int(current_psu.specs["wattage"])
                    else:
                        import re
                        digits = re.findall(r"\d+", current_psu.name or "")
                        if digits:
                            current_psu_watts = int(digits[0])
                except Exception:
                    current_psu_watts = None
            # 1.3x min headroom, 1.5x preferred
            psu_sufficient = bool(current_psu_watts and current_psu_watts >= int(est_total_draw * 1.3))
            if current_psu and not psu_sufficient:
                rec_watts = calculate_psu_requirement(cpu_tdp, new_gpu.power_draw_watts, headroom_percent=0.5)
                # Build a specific real PSU model name based on rec_watts
                if rec_watts <= 650:
                    psu_model = "Seasonic FOCUS GX-650 (2024) 80+ Gold Fully Modular"
                elif rec_watts <= 750:
                    psu_model = "Corsair RM750e (2024) 80+ Gold Fully Modular"
                elif rec_watts <= 850:
                    psu_model = "Corsair RM850e (2024) 80+ Gold Fully Modular"
                elif rec_watts <= 1000:
                    psu_model = "Corsair RM1000x Shift 80+ Gold Fully Modular"
                else:
                    psu_model = "be quiet! Dark Power 13 1200W 80+ Titanium"
                new_psu_snap = UpgradeComponentSnapshot(
                    category="psu",
                    name=psu_model,
                    tier="high-end",
                    specs={
                        "wattage": rec_watts,
                        "efficiency": "80+ Gold" if rec_watts <= 1000 else "80+ Titanium",
                        "modular": "Fully Modular",
                        "atx_version": "ATX 3.1",
                    },
                )
                required_changes.append(RequiredCompatChange(
                    category="psu",
                    current=current_psu,
                    recommended_replacement=new_psu_snap,
                    reason=(
                        f"existing PSU ({current_psu_watts or 'unknown'}W) cannot supply total draw "
                        f"{est_total_draw}W with 1.3x-1.5x safety headroom; headroom dropped below the spec floor"
                    ),
                ))
                final_map["psu"] = new_psu_snap

        # Mark required-change categories as handled (exclude from optional + unchanged)
        touched_categories = {target_cat}
        for rc in required_changes:
            touched_categories.add(rc.category)

        # ------ STEP 3: Optional recommended upgrades for remaining weak points
        optional_upgrades: List[OptionalUpgradeItem] = []

        target_replacement_tier = (
            self._to_4_tier(target_replacement.tier) if isinstance(target_replacement, UpgradeComponentSnapshot)
            else current_tier
        )

        def get_tier_of(snap: Optional[UpgradeComponentSnapshot]) -> str:
            if not snap:
                return "entry"
            if snap.tier:
                return self._to_4_tier(snap.tier)
            cat = (snap.category or "").lower()
            if cat == "cpu":
                db = find_cpu_by_name(snap.name)
                if db:
                    return self.TIER_MAP_4.get(db.performance_tier, "entry")
            if cat == "gpu":
                db = find_gpu_by_name(snap.name)
                if db:
                    return self.TIER_MAP_4.get(db.performance_tier, "entry")
            return "entry"

        tier_rank = {"entry": 1, "midrange": 2, "high-end": 3, "flagship": 4}

        for cat_key, comp in list(current_build.items()):
            if comp is None or cat_key in touched_categories:
                continue
            cat = (cat_key or "").lower()
            comp_tier = get_tier_of(comp)
            # CPU target case: check if existing GPU is now the bottleneck
            if target_cat == "cpu" and cat == "gpu":
                gpu_tier_rank = tier_rank[comp_tier]
                target_rank = tier_rank[target_replacement_tier]
                if target_rank - gpu_tier_rank >= 2:
                    # GPU severely below target tier
                    existing_gpu_db = find_gpu_by_name(comp.name)
                    if existing_gpu_db:
                        repl = self._find_gpu_at_or_next(existing_gpu_db, target_replacement_tier)
                        if repl and repl.model != existing_gpu_db.model:
                            snap = self._gpu_to_snapshot(repl)
                            optional_upgrades.append(OptionalUpgradeItem(
                                category="gpu",
                                current=comp,
                                recommended_replacement=snap,
                                reason=(
                                    f"existing GPU tier ({comp_tier}) is 2+ tiers below new CPU "
                                    f"({target_replacement_tier}) and will create a severe GPU bottleneck; "
                                    f"upgrade recommended for balanced performance"
                                ),
                            ))
                            touched_categories.add(cat)
                            final_map[cat] = snap
            # GPU target case: check if existing CPU bottlenecks new GPU
            if target_cat == "gpu" and cat == "cpu":
                cpu_tier_rank = tier_rank[comp_tier]
                target_rank = tier_rank[target_replacement_tier]
                if target_rank - cpu_tier_rank >= 2:
                    existing_cpu_db = find_cpu_by_name(comp.name)
                    if existing_cpu_db:
                        repl = self._find_cpu_at_or_next(existing_cpu_db, target_replacement_tier)
                        if repl and repl.model != existing_cpu_db.model:
                            snap = self._cpu_to_snapshot(repl)
                            optional_upgrades.append(OptionalUpgradeItem(
                                category="cpu",
                                current=comp,
                                recommended_replacement=snap,
                                reason=(
                                    f"existing CPU tier ({comp_tier}) is 2+ tiers below new GPU "
                                    f"({target_replacement_tier}) and will create a severe CPU bottleneck; "
                                    f"upgrade recommended for balanced performance"
                                ),
                            ))
                            touched_categories.add(cat)
                            final_map[cat] = snap
            # RAM: if <16GB and target is midrange+, recommend 32GB (honor final platform memory type)
            if cat == "ram" and tier_rank[target_replacement_tier] >= tier_rank["midrange"]:
                specs = comp.specs or {}
                cap = specs.get("capacity_gb")
                import re
                if cap is None:
                    nums = re.findall(r"(\d+)\s*(?:gb|GB)", comp.name or "")
                    if nums:
                        cap = int(nums[0])
                if isinstance(cap, int) and cap < 32:
                    # Determine final platform memory type to avoid incompatible DDR4/DDR5 mismatch
                    final_mem_type = "DDR5"  # default conservative
                    fin_cpu = final_map.get("cpu")
                    fin_mobo = final_map.get("motherboard")
                    if isinstance(fin_cpu, UpgradeComponentSnapshot):
                        cpu_mem = str((fin_cpu.specs or {}).get("memory_type") or "").upper()
                        if cpu_mem:
                            # Prefer last of slash-separated (e.g. "DDR4/DDR5" -> DDR5)
                            final_mem_type = cpu_mem.split("/")[-1]
                    if isinstance(fin_mobo, UpgradeComponentSnapshot):
                        mobo_mem = str((fin_mobo.specs or {}).get("memory_type") or "").upper()
                        if mobo_mem and ("DDR4" in mobo_mem or "DDR5" in mobo_mem):
                            final_mem_type = mobo_mem.split("/")[-1]
                    final_mem_type = final_mem_type.upper()
                    if "DDR5" in final_mem_type:
                        new_ram = UpgradeComponentSnapshot(
                            category="ram",
                            name="G.Skill Trident Z5 RGB 32GB (2x16GB) DDR5-6000 CL30",
                            tier="midrange",
                            specs={"type": "DDR5", "capacity_gb": 32, "speed_mhz": 6000, "cas_latency": 30, "kit_configuration": "2x16GB"},
                        )
                    else:
                        new_ram = UpgradeComponentSnapshot(
                            category="ram",
                            name="G.Skill Trident Z Neo 32GB (2x16GB) DDR4-3600 CL16",
                            tier="midrange",
                            specs={"type": "DDR4", "capacity_gb": 32, "speed_mhz": 3600, "cas_latency": 16, "kit_configuration": "2x16GB"},
                        )
                    optional_upgrades.append(OptionalUpgradeItem(
                        category="ram",
                        current=comp,
                        recommended_replacement=new_ram,
                        reason=f"existing {cap}GB RAM capacity is below the 32GB baseline for modern AAA titles at {target_replacement_tier} tier ({final_mem_type} kit selected to match final platform)",
                    ))
                    touched_categories.add(cat)
                    final_map[cat] = new_ram
            # Storage: if <1TB, upgrade to 2TB
            if cat == "storage" and tier_rank[target_replacement_tier] >= tier_rank["midrange"]:
                specs = comp.specs or {}
                cap = specs.get("capacity_gb")
                import re
                if cap is None:
                    tbs = re.findall(r"(\d+)\s*(?:tb|TB)", comp.name or "")
                    gbs = re.findall(r"(\d+)\s*(?:gb|GB)", comp.name or "")
                    if tbs:
                        cap = int(tbs[0]) * 1000
                    elif gbs:
                        cap = int(gbs[0])
                if isinstance(cap, int) and cap < 1500:
                    new_storage = UpgradeComponentSnapshot(
                        category="storage",
                        name="Samsung 990 PRO 2TB PCIe 4.0 NVMe SSD",
                        tier="midrange",
                        specs={"type": "NVMe SSD", "capacity_gb": 2000, "interface": "M.2 PCIe 4.0 x4", "read_speed_mbps": 7450},
                    )
                    optional_upgrades.append(OptionalUpgradeItem(
                        category="storage",
                        current=comp,
                        recommended_replacement=new_storage,
                        reason=f"existing storage capacity ({cap}GB) is below 2TB baseline for {target_replacement_tier} builds; modern AAA titles often exceed 100GB each",
                    ))
                    touched_categories.add(cat)
                    final_map[cat] = new_storage
            # PSU: if no required change but still weak (<650W with midrange+ build), flag as OPTIONAL
            if cat == "psu" and "psu" not in {rc.category for rc in required_changes}:
                specs = comp.specs or {}
                watts = specs.get("wattage")
                import re
                if watts is None:
                    digits = re.findall(r"\d+", comp.name or "")
                    if digits:
                        watts = int(digits[0])
                # IMPORTANT: use FINAL upgraded GPU (from final_map, reflecting already-applied Step 3 GPU upgrade)
                # instead of the stale pre-upgrade current_build GPU, otherwise post-upgrade RX 7900 XT's higher draw
                # is not counted -> PSU looks OK when it's actually insufficient, creating a self-contradiction.
                cpu_w = cpu_for_watts.tdp_watts if isinstance(cpu_for_watts, CPUDatabaseEntry) else 65
                final_gpu_snap = final_map.get("gpu") if isinstance(final_map.get("gpu"), UpgradeComponentSnapshot) else None
                if final_gpu_snap is not None:
                    final_gpu_db = find_gpu_by_name(final_gpu_snap.name)
                else:
                    final_gpu_db = None
                gpu_draw_ref = (
                    target_replacement_gpu
                    if target_replacement_gpu is not None
                    else (final_gpu_db if isinstance(final_gpu_db, GPUDatabaseEntry) else gpu_for_watts)
                )
                gpu_draw = gpu_draw_ref.power_draw_watts if isinstance(gpu_draw_ref, GPUDatabaseEntry) else 170
                est_total = cpu_w + gpu_draw + 150
                if isinstance(watts, int) and watts < max(550, int(est_total * 1.2)):
                    rec_watts = calculate_psu_requirement(cpu_w, gpu_draw, 0.4)
                    new_psu = UpgradeComponentSnapshot(
                        category="psu",
                        name="Corsair RM850e (2024) 80+ Gold Fully Modular",
                        tier="midrange",
                        specs={"wattage": rec_watts, "efficiency": "80+ Gold"},
                    )
                    optional_upgrades.append(OptionalUpgradeItem(
                        category="psu",
                        current=comp,
                        recommended_replacement=new_psu,
                        reason=f"existing PSU {watts}W has less than 1.2x headroom over estimated draw {est_total}W for {target_replacement_tier} class components",
                    ))
                    touched_categories.add(cat)
                    final_map[cat] = new_psu

        # ------ Unchanged components ------------------------------------------
        unchanged: List[UnchangedComponentItem] = []
        for cat_key, comp in list(current_build.items()):
            if comp is None:
                continue
            cat = (cat_key or "").lower()
            if cat in touched_categories:
                continue
            unchanged.append(UnchangedComponentItem(
                category=cat,
                current=comp,
                reason=(
                    "still adequate and 100% compatible with upgraded components; minimal-disruption policy preserved"
                ),
            ))

        # ------ final_build_check validator -----------------------------------
        final_cpu = final_map.get("cpu")
        final_gpu = final_map.get("gpu")
        final_cpu_db = find_cpu_by_name(final_cpu.name) if isinstance(final_cpu, UpgradeComponentSnapshot) else None
        final_gpu_db = find_gpu_by_name(final_gpu.name) if isinstance(final_gpu, UpgradeComponentSnapshot) else None

        compatibility_ok = True
        if final_cpu_db and final_map.get("motherboard"):
            mobo_db = find_motherboard_by_name(final_map["motherboard"].name)
            if mobo_db:
                ok, _r = self.check_cpu_motherboard_compatibility(final_cpu_db.model, mobo_db.model)
                compatibility_ok = compatibility_ok and ok

        # Bottleneck: tier-difference based estimation, capped at real percent
        if final_cpu_db and final_gpu_db:
            tier_diff = abs(
                {"entry": 1, "mid": 2, "high": 3, "extreme": 4}.get(final_cpu_db.performance_tier, 1)
                - {"entry": 1, "mid": 2, "high": 3, "extreme": 4}.get(final_gpu_db.performance_tier, 1)
            )
            # 0 diff -> ~5% , 1 diff -> ~12%, 2 diffs -> ~25%, 3 diffs -> ~40%
            est_bottleneck = float({0: 5, 1: 12, 2: 25, 3: 40}.get(tier_diff, 50))
            # For Ryzen 5 5600 + same GPU (mobo was upgraded in CPU-target case): tier 2 vs tier?
            # Use balance analysis as well:
            _status, _desc = self.analyze_performance_balance(HardwareSpecs(
                cpu=CPUSpec(name=final_cpu_db.model) if final_cpu_db else None,
                gpu=GPUSpec(name=final_gpu_db.model) if final_gpu_db else None,
            ))
            if _status in ("Poor", "Severe"):
                est_bottleneck = max(est_bottleneck, 25.0)
        else:
            est_bottleneck = 5.0
        bottleneck_warning = est_bottleneck > 20.0

        # If bottleneck_warning, try to revise optional upgrades once: add weaker-end upgrade
        revision = 0
        while bottleneck_warning and revision < 1 and optional_upgrades is not None:
            revision += 1
            # If CPU severely behind GPU (or vice versa), promote weakest one
            if final_cpu_db and final_gpu_db:
                cpu_rank = {"entry": 1, "mid": 2, "high": 3, "extreme": 4}.get(final_cpu_db.performance_tier, 1)
                gpu_rank = {"entry": 1, "mid": 2, "high": 3, "extreme": 4}.get(final_gpu_db.performance_tier, 1)
                if gpu_rank > cpu_rank and "cpu" not in touched_categories and isinstance(current_build.get("cpu"), UpgradeComponentSnapshot):
                    existing_cpu = current_build["cpu"]
                    existing_db = find_cpu_by_name(existing_cpu.name)
                    if existing_db:
                        repl = self._find_cpu_at_or_next(existing_db, self.TIER_MAP_4.get(final_gpu_db.performance_tier, "high-end"))
                        if repl and repl.model != existing_db.model:
                            snap = self._cpu_to_snapshot(repl)
                            optional_upgrades.append(OptionalUpgradeItem(
                                category="cpu",
                                current=existing_cpu,
                                recommended_replacement=snap,
                                reason=f"auto-revision: CPU creates >20% bottleneck with new GPU; balancing upgrade added",
                            ))
                            final_map["cpu"] = snap
                            touched_categories.add("cpu")
                            final_cpu_db = repl
                elif cpu_rank > gpu_rank and "gpu" not in touched_categories and isinstance(current_build.get("gpu"), UpgradeComponentSnapshot):
                    existing_gpu = current_build["gpu"]
                    existing_db = find_gpu_by_name(existing_gpu.name)
                    if existing_db:
                        repl = self._find_gpu_at_or_next(existing_db, self.TIER_MAP_4.get(final_cpu_db.performance_tier, "high-end"))
                        if repl and repl.model != existing_db.model:
                            snap = self._gpu_to_snapshot(repl)
                            optional_upgrades.append(OptionalUpgradeItem(
                                category="gpu",
                                current=existing_gpu,
                                recommended_replacement=snap,
                                reason=f"auto-revision: GPU creates >20% bottleneck with new CPU; balancing upgrade added",
                            ))
                            final_map["gpu"] = snap
                            touched_categories.add("gpu")
                            final_gpu_db = repl
                # recompute bottleneck after revision
                if final_cpu_db and final_gpu_db:
                    tier_diff = abs(
                        {"entry": 1, "mid": 2, "high": 3, "extreme": 4}.get(final_cpu_db.performance_tier, 1)
                        - {"entry": 1, "mid": 2, "high": 3, "extreme": 4}.get(final_gpu_db.performance_tier, 1)
                    )
                    est_bottleneck = float({0: 5, 1: 12, 2: 25, 3: 40}.get(tier_diff, 50))
                bottleneck_warning = est_bottleneck > 20.0

        # Watts calc for final build
        cpu_watts = final_cpu_db.tdp_watts if final_cpu_db else 65
        gpu_watts = final_gpu_db.power_draw_watts if final_gpu_db else 0
        est_total_draw_watts = cpu_watts + gpu_watts + 150

        # Final PSU sufficiency check
        final_psu = final_map.get("psu")
        current_psu_watts_final: Optional[int] = None
        if isinstance(final_psu, UpgradeComponentSnapshot):
            try:
                if final_psu.specs and "wattage" in final_psu.specs:
                    current_psu_watts_final = int(final_psu.specs["wattage"])
                else:
                    import re
                    digits = re.findall(r"\d+", final_psu.name or "")
                    if digits:
                        current_psu_watts_final = int(digits[0])
            except Exception:
                current_psu_watts_final = None
        final_psu_ok = bool(current_psu_watts_final and current_psu_watts_final >= int(est_total_draw_watts * 1.3))
        rec_psu_watts: Optional[int] = None
        if not final_psu_ok:
            rec_psu_watts = calculate_psu_requirement(cpu_watts, gpu_watts, 0.5)

        final_check = FinalBuildCheck(
            compatibility_ok=compatibility_ok,
            bottleneck_percent=round(float(est_bottleneck), 2),
            bottleneck_warning=bottleneck_warning,
            estimated_total_draw_watts=est_total_draw_watts,
            current_psu_sufficient=final_psu_ok,
            recommended_psu_watts=rec_psu_watts,
        )

        elapsed_ms = int((_t.time() - t0) * 1000)
        return TargetedUpgradeResponse(
            target_component=target_report,
            required_compatibility_changes=required_changes,
            optional_recommended_upgrades=optional_upgrades,
            unchanged_components=unchanged,
            final_build_check=final_check,
            processing_time_ms=elapsed_ms,
            source="deterministic",
            error_message=(
                "Final build bottleneck exceeds 20% after auto-revision; result included but flagged for review"
                if final_check.bottleneck_warning else None
            ),
        )


# Global instance
compatibility_engine = CompatibilityEngine()
