"""
Test script for PC Compatibility Engine
Runs all 10 test cases specified in the requirements.
"""

import sys
import os

# Add the backend directory to the path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from app.models.schemas import (
    HardwareSpecs,
    CPUSpec,
    GPUSpec,
    MotherboardSpec,
    RAMSpec,
    StorageSpec,
    PSUSpec,
)
from app.services.compatibility_engine import compatibility_engine
from app.services.component_database import (
    find_cpu_by_name,
    find_gpu_by_name,
    find_motherboard_by_name,
)


def create_test_specs(cpu_name, gpu_name, motherboard_name=None, ram_type="DDR4", ram_capacity=16):
    """Helper function to create test hardware specs."""
    specs = HardwareSpecs()
    
    if cpu_name:
        cpu = find_cpu_by_name(cpu_name)
        if cpu:
            specs.cpu = CPUSpec(
                name=cpu_name,
                brand=cpu.brand,
                cores=cpu.core_count,
                threads=cpu.thread_count,
                socket=cpu.socket,
                tdp_watts=cpu.tdp_watts
            )
    
    if gpu_name:
        gpu = find_gpu_by_name(gpu_name)
        if gpu:
            specs.gpu = GPUSpec(
                name=gpu_name,
                brand=gpu.brand,
                vram_gb=gpu.vram_gb,
                memory_type=gpu.memory_type,
                tdp_watts=gpu.power_draw_watts
            )
    
    if motherboard_name:
        mobo = find_motherboard_by_name(motherboard_name)
        if mobo:
            specs.motherboard = MotherboardSpec(
                name=motherboard_name,
                socket=mobo.socket,
                chipset=mobo.chipset,
                ram_type=mobo.memory_type
            )
    
    specs.ram = RAMSpec(
        name=f"{ram_capacity}GB {ram_type}",
        ram_type=ram_type,
        total_capacity_gb=ram_capacity
    )
    
    specs.storage = StorageSpec(
        name="1TB NVMe",
        type="NVMe SSD",
        capacity_gb=1000
    )
    
    specs.psu = PSUSpec(
        name="650W PSU",
        wattage=650,
        efficiency_rating="80+ Gold"
    )
    
    return specs


def run_test_case(test_num, test_name, specs, expected_result):
    """Run a single test case and return the result."""
    print(f"\n{'='*60}")
    print(f"TEST {test_num}: {test_name}")
    print(f"{'='*60}")
    
    print(f"CPU: {specs.cpu.name if specs.cpu else 'None'}")
    print(f"GPU: {specs.gpu.name if specs.gpu else 'None'}")
    print(f"Motherboard: {specs.motherboard.name if specs.motherboard else 'None'}")
    print(f"RAM: {specs.ram.name if specs.ram else 'None'}")
    
    # Run compatibility check
    is_valid, issues = compatibility_engine.validate_build(specs)
    
    print(f"\nValidation Result:")
    print(f"  Valid: {is_valid}")
    print(f"  Issues found: {len(issues)}")
    
    for issue in issues:
        print(f"  - [{issue.severity.upper()}] {issue.issue}")
        if issue.resolution:
            print(f"    Resolution: {issue.resolution}")
    
    # Check if result matches expected
    if expected_result == "INCOMPATIBLE":
        result = "FAIL" if is_valid else "PASS"
    else:
        result = "PASS" if is_valid else "FAIL"
    
    print(f"\nExpected: {expected_result}")
    print(f"Result: {result}")
    
    return result


def main():
    """Run all 10 test cases."""
    print("="*60)
    print("PC COMPATIBILITY ENGINE TEST SUITE")
    print("="*60)
    
    results = []
    
    # TEST 1: i3-12100F + X670E
    specs1 = create_test_specs("i3-12100F", "RTX 3060", "X670E")
    result1 = run_test_case(1, "i3-12100F + X670E", specs1, "INCOMPATIBLE")
    results.append(("TEST 1", result1))
    
    # TEST 2: i3-12100F + Z790 DDR5 + DDR5 RAM
    specs2 = create_test_specs("i3-12100F", "RTX 3060", "Z790", "DDR5", 32)
    result2 = run_test_case(2, "i3-12100F + Z790 DDR5 + DDR5 RAM", specs2, "COMPATIBLE")
    results.append(("TEST 2", result2))
    
    # TEST 3: i3-12100F + Z790 DDR5 + DDR4 RAM
    specs3 = create_test_specs("i3-12100F", "RTX 3060", "Z790", "DDR4", 16)
    result3 = run_test_case(3, "i3-12100F + Z790 DDR5 + DDR4 RAM", specs3, "INCOMPATIBLE")
    results.append(("TEST 3", result3))
    
    # TEST 4: Ryzen 5 5500 + B450 + DDR4
    specs4 = create_test_specs("Ryzen 5 5500", "RTX 2060", "B450M", "DDR4", 16)
    result4 = run_test_case(4, "Ryzen 5 5500 + B450 + DDR4", specs4, "COMPATIBLE")
    results.append(("TEST 4", result4))
    
    # TEST 5: Ryzen 5 5500 + X670E
    specs5 = create_test_specs("Ryzen 5 5500", "RTX 2060", "X670E")
    result5 = run_test_case(5, "Ryzen 5 5500 + X670E", specs5, "INCOMPATIBLE")
    results.append(("TEST 5", result5))
    
    # TEST 6: Ryzen 5 5500 + B450 + Ryzen 7 5700X3D upgrade
    specs6 = create_test_specs("Ryzen 5 5500", "RTX 2060", "B450M", "DDR4", 16)
    is_valid_upgrade, reason = compatibility_engine.check_cpu_upgrade_validity(
        "Ryzen 5 5500", "Ryzen 7 5700X3D", "B450M"
    )
    print(f"\n{'='*60}")
    print(f"TEST 6: Ryzen 5 5500 + B450 + Ryzen 7 5700X3D upgrade")
    print(f"{'='*60}")
    print(f"CPU: Ryzen 5 5500")
    print(f"Motherboard: B450M")
    print(f"Upgrade CPU: Ryzen 7 5700X3D")
    print(f"\nUpgrade Validation Result:")
    print(f"  Valid: {is_valid_upgrade}")
    print(f"  Reason: {reason}")
    result6 = "PASS" if is_valid_upgrade else "FAIL"
    print(f"\nExpected: COMPATIBLE ONLY IF BIOS SUPPORT IS VERIFIED")
    print(f"Result: {result6}")
    results.append(("TEST 6", result6))
    
    # TEST 7: RTX 2060 current GPU + RTX 2060 upgrade
    is_valid_upgrade, reason = compatibility_engine.check_gpu_upgrade_validity(
        "RTX 2060", "RTX 2060"
    )
    print(f"\n{'='*60}")
    print(f"TEST 7: RTX 2060 current GPU + RTX 2060 upgrade")
    print(f"{'='*60}")
    print(f"Current GPU: RTX 2060")
    print(f"Upgrade GPU: RTX 2060")
    print(f"\nUpgrade Validation Result:")
    print(f"  Valid: {is_valid_upgrade}")
    print(f"  Reason: {reason}")
    result7 = "PASS" if not is_valid_upgrade else "FAIL"
    print(f"\nExpected: REJECT — NOT AN UPGRADE")
    print(f"Result: {result7}")
    results.append(("TEST 7", result7))
    
    # TEST 8: RTX 2060 + RTX 5060 upgrade
    is_valid_upgrade, reason = compatibility_engine.check_gpu_upgrade_validity(
        "RTX 2060", "RTX 5060"
    )
    print(f"\n{'='*60}")
    print(f"TEST 8: RTX 2060 + RTX 5060 upgrade")
    print(f"{'='*60}")
    print(f"Current GPU: RTX 2060")
    print(f"Upgrade GPU: RTX 5060")
    print(f"\nUpgrade Validation Result:")
    print(f"  Valid: {is_valid_upgrade}")
    print(f"  Reason: {reason}")
    result8 = "PASS" if is_valid_upgrade else "FAIL"
    print(f"\nExpected: VALID UPGRADE")
    print(f"Result: {result8}")
    results.append(("TEST 8", result8))
    
    # TEST 9: RTX 2060 + 64GB DDR4 + 4TB NVMe + 850W PSU
    specs9 = create_test_specs("Ryzen 5 5500", "RTX 2060", "B450M", "DDR4", 64)
    specs9.storage = StorageSpec(name="4TB NVMe", type="NVMe SSD", capacity_gb=4000)
    specs9.psu = PSUSpec(name="850W PSU", wattage=850, efficiency_rating="80+ Gold")
    
    build_tier = compatibility_engine.classify_build_tier(specs9)
    print(f"\n{'='*60}")
    print(f"TEST 9: RTX 2060 + 64GB DDR4 + 4TB NVMe + 850W PSU")
    print(f"{'='*60}")
    print(f"CPU: Ryzen 5 5500")
    print(f"GPU: RTX 2060")
    print(f"RAM: 64GB DDR4")
    print(f"Storage: 4TB NVMe")
    print(f"PSU: 850W")
    print(f"\nTier Classification Result:")
    print(f"  Build Tier: {build_tier}")
    result9 = "PASS" if build_tier == "entry" else "FAIL"
    print(f"\nExpected: Do not automatically classify as High-End")
    print(f"Result: {result9}")
    results.append(("TEST 9", result9))
    
    # TEST 10: i3-12100F + RTX 5090
    specs10 = create_test_specs("i3-12100F", "RTX 5090", "Z790", "DDR5", 32)
    specs10.psu = PSUSpec(name="1200W PSU", wattage=1200, efficiency_rating="80+ Titanium")
    
    is_valid, issues = compatibility_engine.validate_build(specs10)
    balance_status, balance_description = compatibility_engine.analyze_performance_balance(specs10)
    
    print(f"\n{'='*60}")
    print(f"TEST 10: i3-12100F + RTX 5090")
    print(f"{'='*60}")
    print(f"CPU: i3-12100F")
    print(f"GPU: RTX 5090")
    print(f"\nValidation Result:")
    print(f"  Hardware Compatibility: {'YES' if is_valid else 'NO'}")
    print(f"Performance Balance: {balance_status}")
    print(f"Balance Description: {balance_description}")
    result10 = "PASS" if is_valid and balance_status in ["Poor", "Severe"] else "FAIL"
    print(f"\nExpected: Hardware compatibility = YES, Performance balance = POOR / CPU BOTTLENECK RISK")
    print(f"Result: {result10}")
    results.append(("TEST 10", result10))
    
    # Print summary
    print(f"\n{'='*60}")
    print("TEST SUMMARY")
    print(f"{'='*60}")
    
    passed = sum(1 for _, result in results if result == "PASS")
    total = len(results)
    
    for test_name, result in results:
        print(f"{test_name}: {result}")
    
    print(f"\nTotal: {passed}/{total} tests passed")
    
    if passed == total:
        print("\nAll tests passed!")
        return 0
    else:
        print(f"\n{total - passed} test(s) failed")
        return 1


if __name__ == "__main__":
    exit(main())
