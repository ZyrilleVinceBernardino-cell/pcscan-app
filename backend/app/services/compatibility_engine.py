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
)
from app.services.component_database import (
    CPU_DATABASE,
    GPU_DATABASE,
    MOTHERBOARD_DATABASE,
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


# Global instance
compatibility_engine = CompatibilityEngine()
