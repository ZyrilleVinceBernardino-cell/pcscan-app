package com.pcscan.pcscan.data.api

import com.pcscan.pcscan.ui.dashboard.ComponentDetail

object LocalSpecDatabase {

    private data class LocalSpecEntry(
        val displayName: String,
        val manufacturer: String,
        val category: String,
        val score: Int,
        val aiConfidence: Int,
        val specs: List<String>
    )

    private val database: Map<String, LocalSpecEntry> = buildMap {
        put("ryzen 5 5500", LocalSpecEntry(
            displayName = "AMD Ryzen 5 5500",
            manufacturer = "AMD",
            category = "CPU",
            score = 91,
            aiConfidence = 98,
            specs = listOf(
                "6 Cores / 12 Threads",
                "4.2 GHz Boost",
                "65W TDP",
                "Socket AM4"
            )
        ))
        put("ryzen 5 5600", LocalSpecEntry(
            displayName = "AMD Ryzen 5 5600",
            manufacturer = "AMD",
            category = "CPU",
            score = 93,
            aiConfidence = 98,
            specs = listOf(
                "6 Cores / 12 Threads",
                "4.4 GHz Boost",
                "65W TDP",
                "Socket AM4"
            )
        ))
        put("ryzen 7 5700x", LocalSpecEntry(
            displayName = "AMD Ryzen 7 5700X",
            manufacturer = "AMD",
            category = "CPU",
            score = 94,
            aiConfidence = 98,
            specs = listOf(
                "8 Cores / 16 Threads",
                "4.6 GHz Boost",
                "65W TDP",
                "Socket AM4"
            )
        ))
        put("ryzen 7 5800x3d", LocalSpecEntry(
            displayName = "AMD Ryzen 7 5800X3D",
            manufacturer = "AMD",
            category = "CPU",
            score = 97,
            aiConfidence = 99,
            specs = listOf(
                "8 Cores / 16 Threads",
                "4.5 GHz Boost",
                "105W TDP",
                "Socket AM4"
            )
        ))
        put("ryzen 5 7600", LocalSpecEntry(
            displayName = "AMD Ryzen 5 7600",
            manufacturer = "AMD",
            category = "CPU",
            score = 94,
            aiConfidence = 98,
            specs = listOf(
                "6 Cores / 12 Threads",
                "5.1 GHz Boost",
                "65W TDP",
                "Socket AM5"
            )
        ))
        put("ryzen 7 7800x3d", LocalSpecEntry(
            displayName = "AMD Ryzen 7 7800X3D",
            manufacturer = "AMD",
            category = "CPU",
            score = 99,
            aiConfidence = 99,
            specs = listOf(
                "8 Cores / 16 Threads",
                "5.0 GHz Boost",
                "120W TDP",
                "Socket AM5"
            )
        ))
        put("intel core i5 12400f", LocalSpecEntry(
            displayName = "Intel Core i5-12400F",
            manufacturer = "Intel",
            category = "CPU",
            score = 91,
            aiConfidence = 98,
            specs = listOf(
                "6 Cores / 12 Threads",
                "4.4 GHz Boost",
                "65W TDP",
                "Socket LGA1700"
            )
        ))
        put("intel core i5 13400f", LocalSpecEntry(
            displayName = "Intel Core i5-13400F",
            manufacturer = "Intel",
            category = "CPU",
            score = 93,
            aiConfidence = 98,
            specs = listOf(
                "10 Cores / 16 Threads",
                "4.6 GHz Boost",
                "65W TDP",
                "Socket LGA1700"
            )
        ))
        put("intel core i7 13700k", LocalSpecEntry(
            displayName = "Intel Core i7-13700K",
            manufacturer = "Intel",
            category = "CPU",
            score = 97,
            aiConfidence = 98,
            specs = listOf(
                "16 Cores / 24 Threads",
                "5.4 GHz Boost",
                "125W TDP",
                "Socket LGA1700"
            )
        ))
        put("intel core i9 13900k", LocalSpecEntry(
            displayName = "Intel Core i9-13900K",
            manufacturer = "Intel",
            category = "CPU",
            score = 99,
            aiConfidence = 99,
            specs = listOf(
                "24 Cores / 32 Threads",
                "5.8 GHz Boost",
                "125W TDP",
                "Socket LGA1700"
            )
        ))
        put("intel core i5 14400f", LocalSpecEntry(
            displayName = "Intel Core i5-14400F",
            manufacturer = "Intel",
            category = "CPU",
            score = 93,
            aiConfidence = 98,
            specs = listOf(
                "10 Cores / 16 Threads",
                "4.7 GHz Boost",
                "65W TDP",
                "Socket LGA1700"
            )
        ))
        put("intel core i7 14700k", LocalSpecEntry(
            displayName = "Intel Core i7-14700K",
            manufacturer = "Intel",
            category = "CPU",
            score = 97,
            aiConfidence = 98,
            specs = listOf(
                "20 Cores / 28 Threads",
                "5.6 GHz Boost",
                "125W TDP",
                "Socket LGA1700"
            )
        ))

        put("rtx 2060 6gb", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 2060 6GB",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 85,
            aiConfidence = 97,
            specs = listOf(
                "6GB GDDR6",
                "1680 MHz Boost",
                "160W TDP",
                "PCIe 3.0 x16"
            )
        ))
        put("rtx 2060 super", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 2060 SUPER 8GB",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 87,
            aiConfidence = 97,
            specs = listOf(
                "8GB GDDR6",
                "1650 MHz Boost",
                "175W TDP",
                "PCIe 3.0 x16"
            )
        ))
        put("rtx 3050", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 3050 8GB",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 82,
            aiConfidence = 96,
            specs = listOf(
                "8GB GDDR6",
                "1777 MHz Boost",
                "130W TDP",
                "PCIe 4.0 x8"
            )
        ))
        put("rtx 3060", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 3060 12GB",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 87,
            aiConfidence = 97,
            specs = listOf(
                "12GB GDDR6",
                "1777 MHz Boost",
                "170W TDP",
                "PCIe 4.0 x16"
            )
        ))
        put("rtx 3060 ti", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 3060 Ti 8GB",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 90,
            aiConfidence = 97,
            specs = listOf(
                "8GB GDDR6",
                "1665 MHz Boost",
                "200W TDP",
                "PCIe 4.0 x16"
            )
        ))
        put("rtx 3070", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 3070 8GB",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 92,
            aiConfidence = 98,
            specs = listOf(
                "8GB GDDR6",
                "1725 MHz Boost",
                "220W TDP",
                "PCIe 4.0 x16"
            )
        ))
        put("rtx 3080", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 3080 10GB",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 95,
            aiConfidence = 98,
            specs = listOf(
                "10GB GDDR6X",
                "1710 MHz Boost",
                "320W TDP",
                "PCIe 4.0 x16"
            )
        ))
        put("rtx 4060", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 4060 8GB",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 89,
            aiConfidence = 97,
            specs = listOf(
                "8GB GDDR6",
                "2460 MHz Boost",
                "115W TDP",
                "PCIe 4.0 x8"
            )
        ))
        put("rtx 4060 ti", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 4060 Ti 8GB",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 91,
            aiConfidence = 98,
            specs = listOf(
                "8GB GDDR6",
                "2535 MHz Boost",
                "160W TDP",
                "PCIe 4.0 x8"
            )
        ))
        put("rtx 4070", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 4070 12GB",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 94,
            aiConfidence = 98,
            specs = listOf(
                "12GB GDDR6X",
                "2475 MHz Boost",
                "200W TDP",
                "PCIe 4.0 x16"
            )
        ))
        put("rtx 4070 ti", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 4070 Ti 12GB",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 96,
            aiConfidence = 98,
            specs = listOf(
                "12GB GDDR6X",
                "2610 MHz Boost",
                "285W TDP",
                "PCIe 4.0 x16"
            )
        ))
        put("rtx 4080", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 4080 16GB",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 98,
            aiConfidence = 99,
            specs = listOf(
                "16GB GDDR6X",
                "2505 MHz Boost",
                "320W TDP",
                "PCIe 4.0 x16"
            )
        ))
        put("rtx 4090", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 4090 24GB",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 99,
            aiConfidence = 99,
            specs = listOf(
                "24GB GDDR6X",
                "2520 MHz Boost",
                "450W TDP",
                "PCIe 4.0 x16"
            )
        ))
        put("rx 6600", LocalSpecEntry(
            displayName = "AMD Radeon RX 6600 8GB",
            manufacturer = "AMD",
            category = "GPU",
            score = 85,
            aiConfidence = 97,
            specs = listOf(
                "8GB GDDR6",
                "2491 MHz Boost",
                "132W TDP",
                "PCIe 4.0 x8"
            )
        ))
        put("rx 6600 xt", LocalSpecEntry(
            displayName = "AMD Radeon RX 6600 XT 8GB",
            manufacturer = "AMD",
            category = "GPU",
            score = 87,
            aiConfidence = 97,
            specs = listOf(
                "8GB GDDR6",
                "2589 MHz Boost",
                "160W TDP",
                "PCIe 4.0 x16"
            )
        ))
        put("rx 6700 xt", LocalSpecEntry(
            displayName = "AMD Radeon RX 6700 XT 12GB",
            manufacturer = "AMD",
            category = "GPU",
            score = 90,
            aiConfidence = 97,
            specs = listOf(
                "12GB GDDR6",
                "2581 MHz Boost",
                "230W TDP",
                "PCIe 4.0 x16"
            )
        ))
        put("rx 6800 xt", LocalSpecEntry(
            displayName = "AMD Radeon RX 6800 XT 16GB",
            manufacturer = "AMD",
            category = "GPU",
            score = 94,
            aiConfidence = 98,
            specs = listOf(
                "16GB GDDR6",
                "2250 MHz Boost",
                "300W TDP",
                "PCIe 4.0 x16"
            )
        ))
        put("rx 7600", LocalSpecEntry(
            displayName = "AMD Radeon RX 7600 8GB",
            manufacturer = "AMD",
            category = "GPU",
            score = 87,
            aiConfidence = 97,
            specs = listOf(
                "8GB GDDR6",
                "2655 MHz Boost",
                "165W TDP",
                "PCIe 4.0 x8"
            )
        ))
        put("rx 7700 xt", LocalSpecEntry(
            displayName = "AMD Radeon RX 7700 XT 12GB",
            manufacturer = "AMD",
            category = "GPU",
            score = 91,
            aiConfidence = 98,
            specs = listOf(
                "12GB GDDR6",
                "2544 MHz Boost",
                "245W TDP",
                "PCIe 4.0 x16"
            )
        ))
        put("rx 7800 xt", LocalSpecEntry(
            displayName = "AMD Radeon RX 7800 XT 16GB",
            manufacturer = "AMD",
            category = "GPU",
            score = 93,
            aiConfidence = 98,
            specs = listOf(
                "16GB GDDR6",
                "2430 MHz Boost",
                "263W TDP",
                "PCIe 4.0 x16"
            )
        ))
        put("rx 7900 xtx", LocalSpecEntry(
            displayName = "AMD Radeon RX 7900 XTX 24GB",
            manufacturer = "AMD",
            category = "GPU",
            score = 98,
            aiConfidence = 99,
            specs = listOf(
                "24GB GDDR6",
                "2500 MHz Boost",
                "355W TDP",
                "PCIe 4.0 x16"
            )
        ))

        put("lexar nm610", LocalSpecEntry(
            displayName = "Lexar NM610 NVMe SSD",
            manufacturer = "Lexar",
            category = "STOR",
            score = 86,
            aiConfidence = 96,
            specs = listOf(
                "1TB NVMe SSD",
                "PCIe Gen3 x4 NVMe",
                "M.2 2280",
                "Lexar"
            )
        ))
        put("lexar nm620", LocalSpecEntry(
            displayName = "Lexar NM620 NVMe SSD",
            manufacturer = "Lexar",
            category = "STOR",
            score = 87,
            aiConfidence = 96,
            specs = listOf(
                "1TB NVMe SSD",
                "PCIe Gen3 x4 NVMe",
                "M.2 2280",
                "Lexar"
            )
        ))
        put("lexar nm710", LocalSpecEntry(
            displayName = "Lexar NM710 NVMe SSD",
            manufacturer = "Lexar",
            category = "STOR",
            score = 91,
            aiConfidence = 97,
            specs = listOf(
                "1TB NVMe SSD",
                "PCIe Gen4 x4 NVMe",
                "M.2 2280",
                "Lexar"
            )
        ))
        put("samsung 980 pro", LocalSpecEntry(
            displayName = "Samsung 980 PRO NVMe SSD",
            manufacturer = "Samsung",
            category = "STOR",
            score = 95,
            aiConfidence = 98,
            specs = listOf(
                "1TB NVMe SSD",
                "PCIe Gen4 x4 NVMe",
                "M.2 2280",
                "Samsung"
            )
        ))
        put("samsung 990 pro", LocalSpecEntry(
            displayName = "Samsung 990 PRO NVMe SSD",
            manufacturer = "Samsung",
            category = "STOR",
            score = 97,
            aiConfidence = 99,
            specs = listOf(
                "1TB NVMe SSD",
                "PCIe Gen4 x4 NVMe",
                "M.2 2280",
                "Samsung"
            )
        ))
        put("wd sn850x", LocalSpecEntry(
            displayName = "WD Black SN850X NVMe SSD",
            manufacturer = "WD",
            category = "STOR",
            score = 95,
            aiConfidence = 98,
            specs = listOf(
                "1TB NVMe SSD",
                "PCIe Gen4 x4 NVMe",
                "M.2 2280",
                "Western Digital"
            )
        ))
        put("crucial p5 plus", LocalSpecEntry(
            displayName = "Crucial P5 Plus NVMe SSD",
            manufacturer = "Crucial",
            category = "STOR",
            score = 92,
            aiConfidence = 97,
            specs = listOf(
                "1TB NVMe SSD",
                "PCIe Gen4 x4 NVMe",
                "M.2 2280",
                "Crucial"
            )
        ))
        put("kingston nv3", LocalSpecEntry(
            displayName = "Kingston NV3 NVMe SSD",
            manufacturer = "Kingston",
            category = "STOR",
            score = 90,
            aiConfidence = 96,
            specs = listOf(
                "1TB NVMe SSD",
                "PCIe Gen4 x4 NVMe",
                "M.2 2280",
                "Kingston"
            )
        ))

        put("g.skill trident z5 rgb ddr5 6000", LocalSpecEntry(
            displayName = "G.Skill Trident Z5 RGB 32GB DDR5-6000",
            manufacturer = "G.Skill",
            category = "RAM",
            score = 93,
            aiConfidence = 98,
            specs = listOf(
                "32GB (2x16GB)",
                "DDR5 6000 MHz",
                "CL30 Latency",
                "G.Skill"
            )
        ))
        put("corsair vengeance ddr5 6000", LocalSpecEntry(
            displayName = "Corsair Vengeance 32GB DDR5-6000",
            manufacturer = "Corsair",
            category = "RAM",
            score = 92,
            aiConfidence = 97,
            specs = listOf(
                "32GB (2x16GB)",
                "DDR5 6000 MHz",
                "CL36 Latency",
                "Corsair"
            )
        ))
        put("kingston fury beast ddr5 6000", LocalSpecEntry(
            displayName = "Kingston FURY Beast 32GB DDR5-6000",
            manufacturer = "Kingston",
            category = "RAM",
            score = 91,
            aiConfidence = 97,
            specs = listOf(
                "32GB (2x16GB)",
                "DDR5 6000 MHz",
                "CL36 Latency",
                "Kingston"
            )
        ))
        put("g.skill ripjaws v ddr4 3600", LocalSpecEntry(
            displayName = "G.Skill Ripjaws V 16GB DDR4-3600",
            manufacturer = "G.Skill",
            category = "RAM",
            score = 88,
            aiConfidence = 96,
            specs = listOf(
                "16GB (2x8GB)",
                "DDR4 3600 MHz",
                "CL16 Latency",
                "G.Skill"
            )
        ))
        put("corsair vengeance lpx ddr4 3200", LocalSpecEntry(
            displayName = "Corsair Vengeance LPX 16GB DDR4-3200",
            manufacturer = "Corsair",
            category = "RAM",
            score = 85,
            aiConfidence = 96,
            specs = listOf(
                "16GB (2x8GB)",
                "DDR4 3200 MHz",
                "CL16 Latency",
                "Corsair"
            )
        ))

        put("msi b550 tomahawk", LocalSpecEntry(
            displayName = "MSI MAG B550 TOMAHAWK",
            manufacturer = "MSI",
            category = "MOBO",
            score = 93,
            aiConfidence = 98,
            specs = listOf(
                "AM4 • B550 Chipset",
                "ATX Form Factor",
                "DDR4 • 4 Slots",
                "Max 128GB RAM"
            )
        ))
        put("asus rog strix b550-f", LocalSpecEntry(
            displayName = "ASUS ROG Strix B550-F Gaming",
            manufacturer = "ASUS",
            category = "MOBO",
            score = 94,
            aiConfidence = 98,
            specs = listOf(
                "AM4 • B550 Chipset",
                "ATX Form Factor",
                "DDR4 • 4 Slots",
                "Max 128GB RAM"
            )
        ))
        put("asrock b450m pro4", LocalSpecEntry(
            displayName = "ASRock B450M Pro4",
            manufacturer = "ASRock",
            category = "MOBO",
            score = 87,
            aiConfidence = 96,
            specs = listOf(
                "AM4 • B450 Chipset",
                "Micro ATX",
                "DDR4 • 4 Slots",
                "Max 64GB RAM"
            )
        ))
        put("msi b760 tomahawk wifi", LocalSpecEntry(
            displayName = "MSI MAG B760 TOMAHAWK WIFI",
            manufacturer = "MSI",
            category = "MOBO",
            score = 93,
            aiConfidence = 98,
            specs = listOf(
                "LGA1700 • B760 Chipset",
                "ATX Form Factor",
                "DDR4 • 4 Slots",
                "Max 128GB RAM"
            )
        ))
        put("asus rog strix z790-a", LocalSpecEntry(
            displayName = "ASUS ROG Strix Z790-A Gaming",
            manufacturer = "ASUS",
            category = "MOBO",
            score = 96,
            aiConfidence = 98,
            specs = listOf(
                "LGA1700 • Z790 Chipset",
                "ATX Form Factor",
                "DDR5 • 4 Slots",
                "Max 192GB RAM"
            )
        ))
        put("gigabyte b650 aorus elite ax", LocalSpecEntry(
            displayName = "GIGABYTE B650 AORUS ELITE AX",
            manufacturer = "GIGABYTE",
            category = "MOBO",
            score = 93,
            aiConfidence = 98,
            specs = listOf(
                "AM5 • B650 Chipset",
                "ATX Form Factor",
                "DDR5 • 4 Slots",
                "Max 128GB RAM"
            )
        ))

        put("seasonic focus gx-750", LocalSpecEntry(
            displayName = "Seasonic Focus GX-750",
            manufacturer = "Seasonic",
            category = "PSU",
            score = 95,
            aiConfidence = 98,
            specs = listOf(
                "750W 80+ Gold",
                "Fully Modular",
                "Seasonic",
                "10 Year Warranty"
            )
        ))
        put("corsair rm750x", LocalSpecEntry(
            displayName = "Corsair RM750x",
            manufacturer = "Corsair",
            category = "PSU",
            score = 94,
            aiConfidence = 97,
            specs = listOf(
                "750W 80+ Gold",
                "Fully Modular",
                "Corsair",
                "10 Year Warranty"
            )
        ))
        put("evga 750 gq", LocalSpecEntry(
            displayName = "EVGA 750 GQ",
            manufacturer = "EVGA",
            category = "PSU",
            score = 91,
            aiConfidence = 97,
            specs = listOf(
                "750W 80+ Gold",
                "Semi Modular",
                "EVGA",
                "5 Year Warranty"
            )
        ))

        // ============================================================
        //  LATEST GPUs (2024-2026): RTX 50 Blackwell, RTX 40 SUPER, RX 9000
        // ============================================================
        put("nvidia geforce rtx 5090", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 5090",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 99,
            aiConfidence = 90,
            specs = listOf("32GB GDDR7", "575W TDP", "PCIe 5.0 x16", "336mm")
        ))
        put("geforce rtx 5090", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 5090",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 99,
            aiConfidence = 90,
            specs = listOf("32GB GDDR7", "575W TDP", "PCIe 5.0 x16", "336mm")
        ))
        put("rtx 5090", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 5090",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 99,
            aiConfidence = 90,
            specs = listOf("32GB GDDR7", "575W TDP", "PCIe 5.0 x16", "336mm")
        ))
        put("nvidia geforce rtx 5080", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 5080",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 96,
            aiConfidence = 90,
            specs = listOf("16GB GDDR7", "360W TDP", "PCIe 5.0 x16", "305mm")
        ))
        put("rtx 5080", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 5080",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 96,
            aiConfidence = 90,
            specs = listOf("16GB GDDR7", "360W TDP", "PCIe 5.0 x16", "305mm")
        ))
        put("nvidia geforce rtx 5070 ti", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 5070 Ti",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 92,
            aiConfidence = 89,
            specs = listOf("16GB GDDR7", "300W TDP", "PCIe 5.0 x16", "285mm")
        ))
        put("rtx 5070 ti", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 5070 Ti",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 92,
            aiConfidence = 89,
            specs = listOf("16GB GDDR7", "300W TDP", "PCIe 5.0 x16", "285mm")
        ))
        put("nvidia geforce rtx 5070", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 5070",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 88,
            aiConfidence = 89,
            specs = listOf("12GB GDDR7", "250W TDP", "PCIe 5.0 x16", "270mm")
        ))
        put("rtx 5070", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 5070",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 88,
            aiConfidence = 89,
            specs = listOf("12GB GDDR7", "250W TDP", "PCIe 5.0 x16", "270mm")
        ))
        put("nvidia geforce rtx 5060", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 5060",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 79,
            aiConfidence = 88,
            specs = listOf("8GB GDDR7", "170W TDP", "PCIe 5.0 x16", "242mm")
        ))
        put("rtx 5060", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 5060",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 79,
            aiConfidence = 88,
            specs = listOf("8GB GDDR7", "170W TDP", "PCIe 5.0 x16", "242mm")
        ))
        put("nvidia geforce rtx 4080 super", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 4080 SUPER",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 95,
            aiConfidence = 97,
            specs = listOf("16GB GDDR6X", "320W TDP", "PCIe 4.0 x16", "304mm")
        ))
        put("rtx 4080 super", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 4080 SUPER",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 95,
            aiConfidence = 97,
            specs = listOf("16GB GDDR6X", "320W TDP", "PCIe 4.0 x16", "304mm")
        ))
        put("nvidia geforce rtx 4070 ti super", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 4070 Ti SUPER",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 91,
            aiConfidence = 97,
            specs = listOf("16GB GDDR6X", "285W TDP", "PCIe 4.0 x16", "285mm")
        ))
        put("rtx 4070 ti super", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 4070 Ti SUPER",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 91,
            aiConfidence = 97,
            specs = listOf("16GB GDDR6X", "285W TDP", "PCIe 4.0 x16", "285mm")
        ))
        put("nvidia geforce rtx 4070 super", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 4070 SUPER",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 86,
            aiConfidence = 97,
            specs = listOf("12GB GDDR6X", "220W TDP", "PCIe 4.0 x16", "267mm")
        ))
        put("rtx 4070 super", LocalSpecEntry(
            displayName = "NVIDIA GeForce RTX 4070 SUPER",
            manufacturer = "NVIDIA",
            category = "GPU",
            score = 86,
            aiConfidence = 97,
            specs = listOf("12GB GDDR6X", "220W TDP", "PCIe 4.0 x16", "267mm")
        ))
        put("amd radeon rx 9070 xt", LocalSpecEntry(
            displayName = "AMD Radeon RX 9070 XT",
            manufacturer = "AMD",
            category = "GPU",
            score = 94,
            aiConfidence = 88,
            specs = listOf("16GB GDDR7", "320W TDP", "PCIe 5.0 x16", "287mm")
        ))
        put("rx 9070 xt", LocalSpecEntry(
            displayName = "AMD Radeon RX 9070 XT",
            manufacturer = "AMD",
            category = "GPU",
            score = 94,
            aiConfidence = 88,
            specs = listOf("16GB GDDR7", "320W TDP", "PCIe 5.0 x16", "287mm")
        ))
        put("amd radeon rx 9070", LocalSpecEntry(
            displayName = "AMD Radeon RX 9070",
            manufacturer = "AMD",
            category = "GPU",
            score = 90,
            aiConfidence = 88,
            specs = listOf("12GB GDDR7", "260W TDP", "PCIe 5.0 x16", "276mm")
        ))
        put("rx 9070", LocalSpecEntry(
            displayName = "AMD Radeon RX 9070",
            manufacturer = "AMD",
            category = "GPU",
            score = 90,
            aiConfidence = 88,
            specs = listOf("12GB GDDR7", "260W TDP", "PCIe 5.0 x16", "276mm")
        ))
        put("amd radeon rx 9060", LocalSpecEntry(
            displayName = "AMD Radeon RX 9060",
            manufacturer = "AMD",
            category = "GPU",
            score = 82,
            aiConfidence = 88,
            specs = listOf("8GB GDDR7", "175W TDP", "PCIe 5.0 x16", "245mm")
        ))
        put("rx 9060", LocalSpecEntry(
            displayName = "AMD Radeon RX 9060",
            manufacturer = "AMD",
            category = "GPU",
            score = 82,
            aiConfidence = 88,
            specs = listOf("8GB GDDR7", "175W TDP", "PCIe 5.0 x16", "245mm")
        ))
        put("amd radeon rx 8900 xt", LocalSpecEntry(
            displayName = "AMD Radeon RX 8900 XT",
            manufacturer = "AMD",
            category = "GPU",
            score = 97,
            aiConfidence = 88,
            specs = listOf("24GB GDDR7", "410W TDP", "PCIe 5.0 x16", "328mm")
        ))
        put("rx 8900 xt", LocalSpecEntry(
            displayName = "AMD Radeon RX 8900 XT",
            manufacturer = "AMD",
            category = "GPU",
            score = 97,
            aiConfidence = 88,
            specs = listOf("24GB GDDR7", "410W TDP", "PCIe 5.0 x16", "328mm")
        ))
        put("amd radeon rx 8800 xt", LocalSpecEntry(
            displayName = "AMD Radeon RX 8800 XT",
            manufacturer = "AMD",
            category = "GPU",
            score = 93,
            aiConfidence = 88,
            specs = listOf("16GB GDDR7", "295W TDP", "PCIe 5.0 x16", "295mm")
        ))
        put("rx 8800 xt", LocalSpecEntry(
            displayName = "AMD Radeon RX 8800 XT",
            manufacturer = "AMD",
            category = "GPU",
            score = 93,
            aiConfidence = 88,
            specs = listOf("16GB GDDR7", "295W TDP", "PCIe 5.0 x16", "295mm")
        ))

        // ============================================================
        //  LATEST CPUs (2024-2026): Ryzen 9000 Granite Ridge, Intel Arrow Lake Ultra
        // ============================================================
        put("amd ryzen 9 9950x", LocalSpecEntry(
            displayName = "AMD Ryzen 9 9950X",
            manufacturer = "AMD",
            category = "CPU",
            score = 99,
            aiConfidence = 98,
            specs = listOf("16C / 32T", "5.7 GHz Boost", "170W TDP", "Socket AM5")
        ))
        put("ryzen 9 9950x", LocalSpecEntry(
            displayName = "AMD Ryzen 9 9950X",
            manufacturer = "AMD",
            category = "CPU",
            score = 99,
            aiConfidence = 98,
            specs = listOf("16C / 32T", "5.7 GHz Boost", "170W TDP", "Socket AM5")
        ))
        put("amd ryzen 9 9900x", LocalSpecEntry(
            displayName = "AMD Ryzen 9 9900X",
            manufacturer = "AMD",
            category = "CPU",
            score = 97,
            aiConfidence = 98,
            specs = listOf("12C / 24T", "5.6 GHz Boost", "170W TDP", "Socket AM5")
        ))
        put("ryzen 9 9900x", LocalSpecEntry(
            displayName = "AMD Ryzen 9 9900X",
            manufacturer = "AMD",
            category = "CPU",
            score = 97,
            aiConfidence = 98,
            specs = listOf("12C / 24T", "5.6 GHz Boost", "170W TDP", "Socket AM5")
        ))
        put("amd ryzen 7 9800x3d", LocalSpecEntry(
            displayName = "AMD Ryzen 7 9800X3D",
            manufacturer = "AMD",
            category = "CPU",
            score = 98,
            aiConfidence = 98,
            specs = listOf("8C / 16T", "5.2 GHz Boost", "120W TDP", "Socket AM5")
        ))
        put("ryzen 7 9800x3d", LocalSpecEntry(
            displayName = "AMD Ryzen 7 9800X3D",
            manufacturer = "AMD",
            category = "CPU",
            score = 98,
            aiConfidence = 98,
            specs = listOf("8C / 16T", "5.2 GHz Boost", "120W TDP", "Socket AM5")
        ))
        put("amd ryzen 7 9700x", LocalSpecEntry(
            displayName = "AMD Ryzen 7 9700X",
            manufacturer = "AMD",
            category = "CPU",
            score = 93,
            aiConfidence = 98,
            specs = listOf("8C / 16T", "5.5 GHz Boost", "65W TDP", "Socket AM5")
        ))
        put("ryzen 7 9700x", LocalSpecEntry(
            displayName = "AMD Ryzen 7 9700X",
            manufacturer = "AMD",
            category = "CPU",
            score = 93,
            aiConfidence = 98,
            specs = listOf("8C / 16T", "5.5 GHz Boost", "65W TDP", "Socket AM5")
        ))
        put("amd ryzen 5 9600x", LocalSpecEntry(
            displayName = "AMD Ryzen 5 9600X",
            manufacturer = "AMD",
            category = "CPU",
            score = 88,
            aiConfidence = 98,
            specs = listOf("6C / 12T", "5.4 GHz Boost", "65W TDP", "Socket AM5")
        ))
        put("ryzen 5 9600x", LocalSpecEntry(
            displayName = "AMD Ryzen 5 9600X",
            manufacturer = "AMD",
            category = "CPU",
            score = 88,
            aiConfidence = 98,
            specs = listOf("6C / 12T", "5.4 GHz Boost", "65W TDP", "Socket AM5")
        ))
        put("amd ryzen 9 9950x3d", LocalSpecEntry(
            displayName = "AMD Ryzen 9 9950X3D",
            manufacturer = "AMD",
            category = "CPU",
            score = 99,
            aiConfidence = 89,
            specs = listOf("16C / 32T", "5.6 GHz Boost", "170W TDP", "Socket AM5")
        ))
        put("ryzen 9 9950x3d", LocalSpecEntry(
            displayName = "AMD Ryzen 9 9950X3D",
            manufacturer = "AMD",
            category = "CPU",
            score = 99,
            aiConfidence = 89,
            specs = listOf("16C / 32T", "5.6 GHz Boost", "170W TDP", "Socket AM5")
        ))
        put("intel core ultra 9 285k", LocalSpecEntry(
            displayName = "Intel Core Ultra 9 285K",
            manufacturer = "Intel",
            category = "CPU",
            score = 98,
            aiConfidence = 94,
            specs = listOf("24C / 24T", "5.7 GHz Boost", "125W TDP", "Socket LGA1851")
        ))
        put("ultra 9 285k", LocalSpecEntry(
            displayName = "Intel Core Ultra 9 285K",
            manufacturer = "Intel",
            category = "CPU",
            score = 98,
            aiConfidence = 94,
            specs = listOf("24C / 24T", "5.7 GHz Boost", "125W TDP", "Socket LGA1851")
        ))
        put("core ultra 9 285k", LocalSpecEntry(
            displayName = "Intel Core Ultra 9 285K",
            manufacturer = "Intel",
            category = "CPU",
            score = 98,
            aiConfidence = 94,
            specs = listOf("24C / 24T", "5.7 GHz Boost", "125W TDP", "Socket LGA1851")
        ))
        put("intel core ultra 7 265k", LocalSpecEntry(
            displayName = "Intel Core Ultra 7 265K",
            manufacturer = "Intel",
            category = "CPU",
            score = 94,
            aiConfidence = 94,
            specs = listOf("20C / 20T", "5.5 GHz Boost", "125W TDP", "Socket LGA1851")
        ))
        put("ultra 7 265k", LocalSpecEntry(
            displayName = "Intel Core Ultra 7 265K",
            manufacturer = "Intel",
            category = "CPU",
            score = 94,
            aiConfidence = 94,
            specs = listOf("20C / 20T", "5.5 GHz Boost", "125W TDP", "Socket LGA1851")
        ))
        put("core ultra 7 265k", LocalSpecEntry(
            displayName = "Intel Core Ultra 7 265K",
            manufacturer = "Intel",
            category = "CPU",
            score = 94,
            aiConfidence = 94,
            specs = listOf("20C / 20T", "5.5 GHz Boost", "125W TDP", "Socket LGA1851")
        ))
        put("intel core ultra 5 245k", LocalSpecEntry(
            displayName = "Intel Core Ultra 5 245K",
            manufacturer = "Intel",
            category = "CPU",
            score = 88,
            aiConfidence = 94,
            specs = listOf("14C / 14T", "5.2 GHz Boost", "125W TDP", "Socket LGA1851")
        ))
        put("ultra 5 245k", LocalSpecEntry(
            displayName = "Intel Core Ultra 5 245K",
            manufacturer = "Intel",
            category = "CPU",
            score = 88,
            aiConfidence = 94,
            specs = listOf("14C / 14T", "5.2 GHz Boost", "125W TDP", "Socket LGA1851")
        ))
        put("core ultra 5 245k", LocalSpecEntry(
            displayName = "Intel Core Ultra 5 245K",
            manufacturer = "Intel",
            category = "CPU",
            score = 88,
            aiConfidence = 94,
            specs = listOf("14C / 14T", "5.2 GHz Boost", "125W TDP", "Socket LGA1851")
        ))
        put("intel core i9 14900k", LocalSpecEntry(
            displayName = "Intel Core i9-14900K",
            manufacturer = "Intel",
            category = "CPU",
            score = 97,
            aiConfidence = 99,
            specs = listOf("24C / 32T", "6.0 GHz Boost", "253W TDP", "Socket LGA1700")
        ))
        put("i9 14900k", LocalSpecEntry(
            displayName = "Intel Core i9-14900K",
            manufacturer = "Intel",
            category = "CPU",
            score = 97,
            aiConfidence = 99,
            specs = listOf("24C / 32T", "6.0 GHz Boost", "253W TDP", "Socket LGA1700")
        ))
        put("intel core i7 14700k", LocalSpecEntry(
            displayName = "Intel Core i7-14700K",
            manufacturer = "Intel",
            category = "CPU",
            score = 93,
            aiConfidence = 99,
            specs = listOf("20C / 28T", "5.6 GHz Boost", "253W TDP", "Socket LGA1700")
        ))
        put("i7 14700k", LocalSpecEntry(
            displayName = "Intel Core i7-14700K",
            manufacturer = "Intel",
            category = "CPU",
            score = 93,
            aiConfidence = 99,
            specs = listOf("20C / 28T", "5.6 GHz Boost", "253W TDP", "Socket LGA1700")
        ))
        put("intel core i5 14600k", LocalSpecEntry(
            displayName = "Intel Core i5-14600K",
            manufacturer = "Intel",
            category = "CPU",
            score = 88,
            aiConfidence = 99,
            specs = listOf("14C / 20T", "5.3 GHz Boost", "181W TDP", "Socket LGA1700")
        ))
        put("i5 14600k", LocalSpecEntry(
            displayName = "Intel Core i5-14600K",
            manufacturer = "Intel",
            category = "CPU",
            score = 88,
            aiConfidence = 99,
            specs = listOf("14C / 20T", "5.3 GHz Boost", "181W TDP", "Socket LGA1700")
        ))
        put("amd ryzen 7 8700g", LocalSpecEntry(
            displayName = "AMD Ryzen 7 8700G",
            manufacturer = "AMD",
            category = "CPU",
            score = 90,
            aiConfidence = 97,
            specs = listOf("8C / 16T", "5.1 GHz Boost", "65W TDP", "Socket AM5")
        ))
        put("ryzen 7 8700g", LocalSpecEntry(
            displayName = "AMD Ryzen 7 8700G",
            manufacturer = "AMD",
            category = "CPU",
            score = 90,
            aiConfidence = 97,
            specs = listOf("8C / 16T", "5.1 GHz Boost", "65W TDP", "Socket AM5")
        ))
        put("amd ryzen 5 8600g", LocalSpecEntry(
            displayName = "AMD Ryzen 5 8600G",
            manufacturer = "AMD",
            category = "CPU",
            score = 84,
            aiConfidence = 97,
            specs = listOf("6C / 12T", "4.9 GHz Boost", "65W TDP", "Socket AM5")
        ))
        put("ryzen 5 8600g", LocalSpecEntry(
            displayName = "AMD Ryzen 5 8600G",
            manufacturer = "AMD",
            category = "CPU",
            score = 84,
            aiConfidence = 97,
            specs = listOf("6C / 12T", "4.9 GHz Boost", "65W TDP", "Socket AM5")
        ))

        // ============================================================
        //  LATEST RAMs (2024-2026): High-speed DDR5 kits 6400-8000 MHz
        // ============================================================
        put("g.skill trident z5 rgb ddr5 6400 cl32", LocalSpecEntry(
            displayName = "G.Skill Trident Z5 RGB DDR5-6400 CL32 32GB",
            manufacturer = "G.Skill",
            category = "RAM",
            score = 93,
            aiConfidence = 96,
            specs = listOf("32GB (2x16GB)", "DDR5 6400 MHz", "CL32-39-39-102", "G.Skill Trident Z5")
        ))
        put("trident z5 6400", LocalSpecEntry(
            displayName = "G.Skill Trident Z5 RGB DDR5-6400 CL32 32GB",
            manufacturer = "G.Skill",
            category = "RAM",
            score = 93,
            aiConfidence = 96,
            specs = listOf("32GB (2x16GB)", "DDR5 6400 MHz", "CL32-39-39-102", "G.Skill Trident Z5")
        ))
        put("g.skill trident z5 rgb ddr5 7200 cl34", LocalSpecEntry(
            displayName = "G.Skill Trident Z5 RGB DDR5-7200 CL34 32GB",
            manufacturer = "G.Skill",
            category = "RAM",
            score = 95,
            aiConfidence = 95,
            specs = listOf("32GB (2x16GB)", "DDR5 7200 MHz", "CL34-45-45-115", "G.Skill Trident Z5")
        ))
        put("trident z5 7200", LocalSpecEntry(
            displayName = "G.Skill Trident Z5 RGB DDR5-7200 CL34 32GB",
            manufacturer = "G.Skill",
            category = "RAM",
            score = 95,
            aiConfidence = 95,
            specs = listOf("32GB (2x16GB)", "DDR5 7200 MHz", "CL34-45-45-115", "G.Skill Trident Z5")
        ))
        put("g.skill trident z5 rgb ddr5 7600 cl36", LocalSpecEntry(
            displayName = "G.Skill Trident Z5 RGB DDR5-7600 CL36 32GB",
            manufacturer = "G.Skill",
            category = "RAM",
            score = 96,
            aiConfidence = 94,
            specs = listOf("32GB (2x16GB)", "DDR5 7600 MHz", "CL36-46-46-121", "G.Skill Trident Z5")
        ))
        put("trident z5 7600", LocalSpecEntry(
            displayName = "G.Skill Trident Z5 RGB DDR5-7600 CL36 32GB",
            manufacturer = "G.Skill",
            category = "RAM",
            score = 96,
            aiConfidence = 94,
            specs = listOf("32GB (2x16GB)", "DDR5 7600 MHz", "CL36-46-46-121", "G.Skill Trident Z5")
        ))
        put("g.skill trident z5 rgb ddr5 8000 cl38", LocalSpecEntry(
            displayName = "G.Skill Trident Z5 RGB DDR5-8000 CL38 32GB",
            manufacturer = "G.Skill",
            category = "RAM",
            score = 97,
            aiConfidence = 92,
            specs = listOf("32GB (2x16GB)", "DDR5 8000 MHz", "CL38-48-48-128", "G.Skill Trident Z5")
        ))
        put("trident z5 8000", LocalSpecEntry(
            displayName = "G.Skill Trident Z5 RGB DDR5-8000 CL38 32GB",
            manufacturer = "G.Skill",
            category = "RAM",
            score = 97,
            aiConfidence = 92,
            specs = listOf("32GB (2x16GB)", "DDR5 8000 MHz", "CL38-48-48-128", "G.Skill Trident Z5")
        ))
        put("g.skill trident z5 ddr5 64gb 6000 cl30", LocalSpecEntry(
            displayName = "G.Skill Trident Z5 64GB DDR5-6000 CL30",
            manufacturer = "G.Skill",
            category = "RAM",
            score = 94,
            aiConfidence = 96,
            specs = listOf("64GB (2x32GB)", "DDR5 6000 MHz", "CL30-38-38-96", "G.Skill Trident Z5")
        ))
        put("64gb ddr5 6000", LocalSpecEntry(
            displayName = "G.Skill Trident Z5 64GB DDR5-6000 CL30",
            manufacturer = "G.Skill",
            category = "RAM",
            score = 92,
            aiConfidence = 96,
            specs = listOf("64GB (2x32GB)", "DDR5 6000 MHz", "CL30-38-38-96", "XMP / EXPO")
        ))
        put("corsair dominator titanium rgb ddr5 6600 cl32", LocalSpecEntry(
            displayName = "Corsair Dominator Titanium RGB DDR5-6600 CL32 32GB",
            manufacturer = "Corsair",
            category = "RAM",
            score = 94,
            aiConfidence = 96,
            specs = listOf("32GB (2x16GB)", "DDR5 6600 MHz", "CL32-42-42-108", "Corsair Dominator")
        ))
        put("corsair dominator 6600", LocalSpecEntry(
            displayName = "Corsair Dominator Titanium RGB DDR5-6600 CL32 32GB",
            manufacturer = "Corsair",
            category = "RAM",
            score = 94,
            aiConfidence = 96,
            specs = listOf("32GB (2x16GB)", "DDR5 6600 MHz", "CL32-42-42-108", "Corsair Dominator")
        ))
        put("corsair vengeance ddr5 5600 cl36 32gb", LocalSpecEntry(
            displayName = "Corsair Vengeance 32GB DDR5-5600 CL36",
            manufacturer = "Corsair",
            category = "RAM",
            score = 88,
            aiConfidence = 96,
            specs = listOf("32GB (2x16GB)", "DDR5 5600 MHz", "CL36-39-39-76", "Corsair Vengeance")
        ))
        put("corsair vengeance ddr5 64gb 5200", LocalSpecEntry(
            displayName = "Corsair Vengeance 64GB DDR5-5200 CL40",
            manufacturer = "Corsair",
            category = "RAM",
            score = 90,
            aiConfidence = 96,
            specs = listOf("64GB (2x32GB)", "DDR5 5200 MHz", "CL40-42-42-84", "Corsair Vengeance")
        ))
        put("teamgroup delta ddr5 7200 cl34", LocalSpecEntry(
            displayName = "Teamgroup T-Force Delta DDR5-7200 CL34 32GB",
            manufacturer = "TeamGroup",
            category = "RAM",
            score = 93,
            aiConfidence = 95,
            specs = listOf("32GB (2x16GB)", "DDR5 7200 MHz", "CL34-45-45-115", "TeamGroup Delta")
        ))
        put("kingston fury beast ddr5 5600 cl40", LocalSpecEntry(
            displayName = "Kingston Fury Beast DDR5-5600 CL40 32GB",
            manufacturer = "Kingston",
            category = "RAM",
            score = 86,
            aiConfidence = 96,
            specs = listOf("32GB (2x16GB)", "DDR5 5600 MHz", "CL40-40-40-80", "Kingston Fury Beast")
        ))

        // ============================================================
        //  LATEST SSD STORAGEs (2024-2026): PCIe 4.0 / 5.0 NVMe Gen5
        // ============================================================
        put("samsung 990 evo plus", LocalSpecEntry(
            displayName = "Samsung 990 EVO Plus NVMe SSD",
            manufacturer = "Samsung",
            category = "STOR",
            score = 90,
            aiConfidence = 97,
            specs = listOf("1TB / 2TB PCIe 4.0 x4", "NVMe M.2 2280", "7250 MB/s Read", "Samsung 990 EVO Plus")
        ))
        put("990 evo plus", LocalSpecEntry(
            displayName = "Samsung 990 EVO Plus NVMe SSD",
            manufacturer = "Samsung",
            category = "STOR",
            score = 90,
            aiConfidence = 97,
            specs = listOf("1TB / 2TB PCIe 4.0 x4", "NVMe M.2 2280", "7250 MB/s Read", "Samsung 990 EVO Plus")
        ))
        put("samsung 990 pro", LocalSpecEntry(
            displayName = "Samsung 990 PRO NVMe SSD",
            manufacturer = "Samsung",
            category = "STOR",
            score = 93,
            aiConfidence = 98,
            specs = listOf("1TB / 2TB PCIe 4.0 x4", "NVMe M.2 2280", "7450 MB/s Read", "Samsung 990 PRO")
        ))
        put("990 pro", LocalSpecEntry(
            displayName = "Samsung 990 PRO NVMe SSD",
            manufacturer = "Samsung",
            category = "STOR",
            score = 93,
            aiConfidence = 98,
            specs = listOf("1TB / 2TB PCIe 4.0 x4", "NVMe M.2 2280", "7450 MB/s Read", "Samsung 990 PRO")
        ))
        put("crucial t705", LocalSpecEntry(
            displayName = "Crucial T705 PCIe 5.0 NVMe SSD",
            manufacturer = "Crucial",
            category = "STOR",
            score = 96,
            aiConfidence = 94,
            specs = listOf("1TB / 2TB PCIe 5.0 x4", "NVMe M.2 2280", "13600 MB/s Read", "Crucial T705 Gen5")
        ))
        put("t705", LocalSpecEntry(
            displayName = "Crucial T705 PCIe 5.0 NVMe SSD",
            manufacturer = "Crucial",
            category = "STOR",
            score = 96,
            aiConfidence = 94,
            specs = listOf("1TB / 2TB PCIe 5.0 x4", "NVMe M.2 2280", "13600 MB/s Read", "Crucial T705 Gen5")
        ))
        put("crucial t700", LocalSpecEntry(
            displayName = "Crucial T700 PCIe 5.0 NVMe SSD",
            manufacturer = "Crucial",
            category = "STOR",
            score = 94,
            aiConfidence = 97,
            specs = listOf("1TB / 2TB PCIe 5.0 x4", "NVMe M.2 2280", "12400 MB/s Read", "Crucial T700 Gen5")
        ))
        put("t700", LocalSpecEntry(
            displayName = "Crucial T700 PCIe 5.0 NVMe SSD",
            manufacturer = "Crucial",
            category = "STOR",
            score = 94,
            aiConfidence = 97,
            specs = listOf("1TB / 2TB PCIe 5.0 x4", "NVMe M.2 2280", "12400 MB/s Read", "Crucial T700 Gen5")
        ))
        put("wd black sn850x", LocalSpecEntry(
            displayName = "WD_BLACK SN850X NVMe SSD",
            manufacturer = "Western Digital",
            category = "STOR",
            score = 92,
            aiConfidence = 98,
            specs = listOf("1TB / 2TB PCIe 4.0 x4", "NVMe M.2 2280", "7300 MB/s Read", "WD BLACK SN850X")
        ))
        put("sn850x", LocalSpecEntry(
            displayName = "WD_BLACK SN850X NVMe SSD",
            manufacturer = "Western Digital",
            category = "STOR",
            score = 92,
            aiConfidence = 98,
            specs = listOf("1TB / 2TB PCIe 4.0 x4", "NVMe M.2 2280", "7300 MB/s Read", "WD BLACK SN850X")
        ))
        put("lexar nm790", LocalSpecEntry(
            displayName = "Lexar NM790 NVMe SSD",
            manufacturer = "Lexar",
            category = "STOR",
            score = 89,
            aiConfidence = 97,
            specs = listOf("1TB / 2TB PCIe 4.0 x4", "NVMe M.2 2280", "7400 MB/s Read", "Lexar NM790")
        ))
        put("nm790", LocalSpecEntry(
            displayName = "Lexar NM790 NVMe SSD",
            manufacturer = "Lexar",
            category = "STOR",
            score = 89,
            aiConfidence = 97,
            specs = listOf("1TB / 2TB PCIe 4.0 x4", "NVMe M.2 2280", "7400 MB/s Read", "Lexar NM790")
        ))
        put("corsair mp700", LocalSpecEntry(
            displayName = "Corsair MP700 PCIe 5.0 NVMe SSD",
            manufacturer = "Corsair",
            category = "STOR",
            score = 95,
            aiConfidence = 96,
            specs = listOf("1TB / 2TB PCIe 5.0 x4", "NVMe M.2 2280", "10000 MB/s Read", "Corsair MP700 Gen5")
        ))
        put("mp700", LocalSpecEntry(
            displayName = "Corsair MP700 PCIe 5.0 NVMe SSD",
            manufacturer = "Corsair",
            category = "STOR",
            score = 95,
            aiConfidence = 96,
            specs = listOf("1TB / 2TB PCIe 5.0 x4", "NVMe M.2 2280", "10000 MB/s Read", "Corsair MP700 Gen5")
        ))
        put("corsair mp600 pro xt", LocalSpecEntry(
            displayName = "Corsair MP600 PRO XT NVMe SSD",
            manufacturer = "Corsair",
            category = "STOR",
            score = 91,
            aiConfidence = 97,
            specs = listOf("1TB / 2TB PCIe 4.0 x4", "NVMe M.2 2280", "7100 MB/s Read", "Corsair MP600 PRO XT")
        ))
        put("kingston kc3000", LocalSpecEntry(
            displayName = "Kingston KC3000 NVMe SSD",
            manufacturer = "Kingston",
            category = "STOR",
            score = 91,
            aiConfidence = 97,
            specs = listOf("1TB / 2TB PCIe 4.0 x4", "NVMe M.2 2280", "7000 MB/s Read", "Kingston KC3000")
        ))
        put("kc3000", LocalSpecEntry(
            displayName = "Kingston KC3000 NVMe SSD",
            manufacturer = "Kingston",
            category = "STOR",
            score = 91,
            aiConfidence = 97,
            specs = listOf("1TB / 2TB PCIe 4.0 x4", "NVMe M.2 2280", "7000 MB/s Read", "Kingston KC3000")
        ))
        put("sabrent rocket 4 plus", LocalSpecEntry(
            displayName = "Sabrent Rocket 4 PLUS NVMe SSD",
            manufacturer = "Sabrent",
            category = "STOR",
            score = 91,
            aiConfidence = 97,
            specs = listOf("1TB / 2TB PCIe 4.0 x4", "NVMe M.2 2280", "7100 MB/s Read", "Sabrent Rocket 4 PLUS")
        ))
        put("sabrent rocket 5", LocalSpecEntry(
            displayName = "Sabrent Rocket 5 PCIe 5.0 NVMe SSD",
            manufacturer = "Sabrent",
            category = "STOR",
            score = 95,
            aiConfidence = 94,
            specs = listOf("1TB / 2TB PCIe 5.0 x4", "NVMe M.2 2280", "14000 MB/s Read", "Sabrent Rocket 5 Gen5")
        ))
        put("samsung 980 pro", LocalSpecEntry(
            displayName = "Samsung 980 PRO NVMe SSD",
            manufacturer = "Samsung",
            category = "STOR",
            score = 88,
            aiConfidence = 99,
            specs = listOf("1TB PCIe 4.0 x4", "NVMe M.2 2280", "7000 MB/s Read", "Samsung 980 PRO")
        ))
    }

    fun find(query: String): ComponentDetail? {
        val q = query.trim().lowercase().replace("\\s+".toRegex(), " ")
        val match = database.entries.firstOrNull { (key, _) ->
            q == key || q.contains(key) || key.contains(q) ||
                levenshtein(q, key) <= 2
        }?.value ?: run {
            val parts = q.split(" ").filter { it.length > 2 }
            database.entries.firstOrNull { (key, _) ->
                val keyParts = key.split(" ").toSet()
                parts.count { it in keyParts } >= (parts.size - 1).coerceAtLeast(2)
            }?.value
        } ?: return null

        val normCat = match.category.uppercase().let {
            when (it) {
                "GPU", "GRAPHICS", "VIDEO CARD", "VC" -> "GPU"
                "CPU", "PROCESSOR" -> "CPU"
                "RAM", "MEMORY", "MEM" -> "RAM"
                "STORAGE", "STOR", "SSD", "HDD", "NVME", "HARD DRIVE", "DISK" -> "STOR"
                "MOTHERBOARD", "MOBO", "MAINBOARD", "BOARD" -> "MOBO"
                "PSU", "POWER", "POWER SUPPLY" -> "PSU"
                "COOLER", "COOL", "COOLING", "HSF" -> "COOL"
                "CASE", "CHASSIS", "CASING", "TOWER" -> "CASE"
                else -> it.take(4)
            }
        }
        return ComponentDetail(
            name = match.displayName,
            category = normCat,
            score = match.score,
            specs = match.specs,
            aiConfidence = match.aiConfidence,
            manufacturer = match.manufacturer
        )
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[m][n]
    }
}
