package com.pcscan.pcscan.data.api

import com.pcscan.pcscan.ui.dashboard.ComponentDetail

object SmartQuerySpecGuesser {

    /** HARD REQUIREMENT: A query can only produce a guess if it contains hardware-indicating tokens.
    This prevents random words ("lemonade", "pizza", "apple" etc. are NEVER guesses as GPU even when no numbers or brand/series words.
    Returns TRUE if the query has any of: (a) at least 2 consecutive digits + known hardware suffix; (b) known hardware brand-series words. */
    private fun hasHardwareIndicatingTokens(qNormalized: String): Boolean {
        val q = qNormalized.trim()
        if (q.isBlank()) return false
        // (a) Hardware brand-series markers - EXPANDED 250+ tokens (ALL mainstream desktop PC DIY brands/chipsets/series/form factors)
        val hwWords = listOf(
            // ===== CPU brands =====
            "ryzen", "amd", "intel", "core", "pentium", "celeron", "athlon", "threadripper",
            // ===== GPU brands / key series =====
            "nvidia", "geforce", "rtx", "gtx", "radeon", "rx", "arc", "sapphire",
            // ===== Motherboard BRANDS =====
            "msi", "mag", "mpg", "meg", "asus", "rog", "tuf", "prime", "asrock", "steel legend",
            "pro4", "pro rs", "gigabyte", "aorus", "elite ax", "gaming x", "ultra durable",
            "biostar", "evga", "nzxt",
            // ===== Motherboard CHIPSETS (AMD AM4/AM5 + Intel LGA1200/LGA1700/LGA1851) =====
            "b450", "b550", "x570", "a520", "a620", "b650", "x670", "b760", "b660", "h610",
            "h510", "z590", "z690", "z790", "h770", "z890", "b860", "h810",
            // ===== Sockets =====
            "am4", "am5", "lga1200", "lga1700", "lga1851",
            // ===== Storage BRANDS + key series words =====
            "samsung", "990 pro", "980 pro", "990 evo", "wd", "western digital", "sn850x", "sn770",
            "crucial", "p5 plus", "t700", "t705", "mx500", "bx500",
            "kingston", "fury", "nv3", "kc3000", "lexar", "nm610", "nm620", "nm710", "nm790",
            "ares", "teamgroup", "team group", "mp34", "delta", "solidigm", "p41", "p44",
            "adata", "legend", "seagate", "firecuda", "barracuda", "toshiba", "ocz", "sabrent",
            "rocket", "m.2", "m2 nvme",
            // ===== RAM BRANDS + key series words =====
            "g.skill", "gskill", "trident", "ripjaws", "sniper", "corsair", "vengeance",
            "dominator", "lpx", "kingston fury", "beast", "impact", "renegade",
            "teamgroup", "team group", "t-force", "delta", "vulcan", "adata", "xpg", "lancer",
            "spectrix", "patriot", "viper", "steel", "elite", "xmp", "expo",
            // ===== PSU BRANDS + key series/watts words =====
            "seasonic", "focus", "prime", "flagship", "corsair", "rm", "rme", "rmx", "sf", "sf-l",
            "hxi", "axi", "cv", "cx", "evga", "supernova", "ga", "gq", "gm", "be quiet",
            "straight power", "dark power", "pure power", "cooler master", "mwe", "v gold",
            "thermaltake", "toughpower", "lian li", "sp", "nzxt", "c series",
            "seasonic", "xfx", "asrock", "deepcool",
            // ===== PSU efficiency / form-factor markers =====
            "80+", "bronze", "silver", "gold", "platinum", "titanium",
            "modular", "semi-modular", "non-modular", "full modular",
            "wattage", "w psu", "550w", "650w", "750w", "850w", "1000w", "1200w",
            // ===== Cooler BRANDS + key series words =====
            "noctua", "nh-d15", "nh-d14", "nh-u12a", "nh-l9i",
            "deepcool", "ak400", "ak500", "ak620", "ag400", "ls520", "lt520", "ls720", "lt720",
            "nzxt", "kraken", "x53", "x63", "x73", "z53", "z63", "z73", "elite",
            "corsair", "h60", "h100", "h115", "h150", "icue link",
            "lian li", "galahad", "ga ii", "trinity",
            "thermalright", "peerless assassin", "phantom spirit", "assassin x",
            "be quiet!", "dark rock", "shadow rock", "pure rock",
            "arctic", "freezer", "cooler master", "hyper 212", "masterair", "ma410",
            "evga", "clc", "aquacomputer", "ekwb",
            // Cooler size words (AIO rad sizes)
            "240mm", "280mm", "360mm", "420mm", "aio cooler", "air cooler", "liquid cooler", "cpu cooler",
            // ===== Case BRANDS + key lines / form factor words =====
            "lian li", "lancool", "o11 dynamic", "o11d", "o11 air", "lianli",
            "nzxt", "h5 flow", "h510", "h7 flow", "h710", "h9 flow", "s340",
            "cooler master", "haf", "nr200", "masterbox", "td500", "c700m",
            "corsair", "4000d", "4000x", "5000d", "5000x", "7000d", "220t", "275r", "280x",
            "fractal design", "north", "pop air", "meshify", "define", "torrent", "r5", "r6",
            "phanteks", "enthoo", "evolv", "p200a", "p300a", "p400a", "p500a",
            "tecware", "forge", "nexus", "vyse",
            "hyte", "y40", "y60", "y70",
            "montech", "air 903", "air 1000", "sky", "king",
            "rakk", "kasa", "kisig", "damru", "hanan", "lokal",
            // Case form factor words
            "atx", "micro atx", "matx", "mini itx", "itx", "e-atx", "eatx", "sff",
            "mid tower", "mid-tower", "full tower", "full-tower", "super tower",
            "mesh front", "tempered glass", "argb",
            // ===== Standard RAM/Perf markers =====
            "ddr4", "ddr5", "ddr3", "udimm", "dimm",
            "nvme", "pcie", "pci-e", "gen4", "gen5", "sata", "psu",
            "air cooler", "aio", "liquid cooler", "cpu cooler",
            "atx", "matx", "itx", "mid tower", "full tower"
        )
        for (w in hwWords) {
            if (q.contains(w, ignoreCase = true)) return true
        }
        // (b) Numbers >=2 consecutive digits (model numbers like 5600, 12400F, 4060, etc.)
        val rx = Regex("""\d{2,}""")
        val digitMatch = rx.find(q)
        if (digitMatch != null) return true
        return false
    }

    private class GuessEntry(
        val name: String,
        val manufacturer: String,
        val category: String,
        val score: Int,
        val aiConfidence: Int,
        val specs: List<String>
    )

    private val cpuTable: Map<String, GuessEntry> = buildMap {
        put("ryzen 3 4100", GuessEntry("AMD Ryzen 3 4100", "AMD", "CPU", 79, 92,
            listOf("4 Cores / 8 Threads", "3.8 GHz Boost", "65W TDP", "Socket AM4")))
        put("ryzen 5 3600", GuessEntry("AMD Ryzen 5 3600", "AMD", "CPU", 85, 95,
            listOf("6 Cores / 12 Threads", "4.2 GHz Boost", "65W TDP", "Socket AM4")))
        put("ryzen 5 3600x", GuessEntry("AMD Ryzen 5 3600X", "AMD", "CPU", 86, 95,
            listOf("6 Cores / 12 Threads", "4.4 GHz Boost", "95W TDP", "Socket AM4")))
        put("ryzen 5 4500", GuessEntry("AMD Ryzen 5 4500", "AMD", "CPU", 82, 93,
            listOf("6 Cores / 12 Threads", "4.1 GHz Boost", "65W TDP", "Socket AM4")))
        put("ryzen 5 5500", GuessEntry("AMD Ryzen 5 5500", "AMD", "CPU", 88, 96,
            listOf("6 Cores / 12 Threads", "4.2 GHz Boost", "65W TDP", "Socket AM4")))
        put("ryzen 5 5500x", GuessEntry("AMD Ryzen 5 5500X", "AMD", "CPU", 88, 96,
            listOf("6 Cores / 12 Threads", "4.2 GHz Boost", "65W TDP", "Socket AM4")))
        put("ryzen 5 5600", GuessEntry("AMD Ryzen 5 5600", "AMD", "CPU", 90, 97,
            listOf("6 Cores / 12 Threads", "4.4 GHz Boost", "65W TDP", "Socket AM4")))
        put("ryzen 5 5600x", GuessEntry("AMD Ryzen 5 5600X", "AMD", "CPU", 91, 97,
            listOf("6 Cores / 12 Threads", "4.6 GHz Boost", "65W TDP", "Socket AM4")))
        put("ryzen 5 5600g", GuessEntry("AMD Ryzen 5 5600G", "AMD", "CPU", 87, 96,
            listOf("6 Cores / 12 Threads", "4.4 GHz Boost", "65W TDP", "Socket AM4 (Vega 7 GPU)")))
        put("ryzen 5 7500f", GuessEntry("AMD Ryzen 5 7500F", "AMD", "CPU", 91, 97,
            listOf("6 Cores / 12 Threads", "5.0 GHz Boost", "65W TDP", "Socket AM5")))
        put("ryzen 5 7600", GuessEntry("AMD Ryzen 5 7600", "AMD", "CPU", 92, 98,
            listOf("6 Cores / 12 Threads", "5.1 GHz Boost", "65W TDP", "Socket AM5")))
        put("ryzen 5 7600x", GuessEntry("AMD Ryzen 5 7600X", "AMD", "CPU", 92, 98,
            listOf("6 Cores / 12 Threads", "5.3 GHz Boost", "105W TDP", "Socket AM5")))
        put("ryzen 7 3700x", GuessEntry("AMD Ryzen 7 3700X", "AMD", "CPU", 89, 96,
            listOf("8 Cores / 16 Threads", "4.4 GHz Boost", "65W TDP", "Socket AM4")))
        put("ryzen 7 5700", GuessEntry("AMD Ryzen 7 5700", "AMD", "CPU", 92, 97,
            listOf("8 Cores / 16 Threads", "4.6 GHz Boost", "65W TDP", "Socket AM4")))
        put("ryzen 7 5700g", GuessEntry("AMD Ryzen 7 5700G", "AMD", "CPU", 90, 96,
            listOf("8 Cores / 16 Threads", "4.6 GHz Boost", "65W TDP", "Socket AM4 (Vega 8 GPU)")))
        put("ryzen 7 5700x", GuessEntry("AMD Ryzen 7 5700X", "AMD", "CPU", 92, 97,
            listOf("8 Cores / 16 Threads", "4.6 GHz Boost", "65W TDP", "Socket AM4")))
        put("ryzen 7 5800", GuessEntry("AMD Ryzen 7 5800", "AMD", "CPU", 93, 97,
            listOf("8 Cores / 16 Threads", "4.7 GHz Boost", "65W TDP", "Socket AM4")))
        put("ryzen 7 5800x", GuessEntry("AMD Ryzen 7 5800X", "AMD", "CPU", 94, 98,
            listOf("8 Cores / 16 Threads", "4.8 GHz Boost", "105W TDP", "Socket AM4")))
        put("ryzen 7 5800x3d", GuessEntry("AMD Ryzen 7 5800X3D", "AMD", "CPU", 96, 99,
            listOf("8 Cores / 16 Threads", "4.5 GHz Boost", "105W TDP", "Socket AM4 (3D V-Cache)")))
        put("ryzen 7 7700", GuessEntry("AMD Ryzen 7 7700", "AMD", "CPU", 94, 98,
            listOf("8 Cores / 16 Threads", "5.3 GHz Boost", "65W TDP", "Socket AM5")))
        put("ryzen 7 7700x", GuessEntry("AMD Ryzen 7 7700X", "AMD", "CPU", 95, 98,
            listOf("8 Cores / 16 Threads", "5.4 GHz Boost", "105W TDP", "Socket AM5")))
        put("ryzen 7 7800x3d", GuessEntry("AMD Ryzen 7 7800X3D", "AMD", "CPU", 98, 99,
            listOf("8 Cores / 16 Threads", "5.0 GHz Boost", "120W TDP", "Socket AM5 (3D V-Cache)")))
        put("ryzen 9 5900x", GuessEntry("AMD Ryzen 9 5900X", "AMD", "CPU", 96, 98,
            listOf("12 Cores / 24 Threads", "4.8 GHz Boost", "105W TDP", "Socket AM4")))
        put("ryzen 9 5950x", GuessEntry("AMD Ryzen 9 5950X", "AMD", "CPU", 97, 99,
            listOf("16 Cores / 32 Threads", "4.9 GHz Boost", "105W TDP", "Socket AM4")))
        put("ryzen 9 7900", GuessEntry("AMD Ryzen 9 7900", "AMD", "CPU", 96, 98,
            listOf("12 Cores / 24 Threads", "5.4 GHz Boost", "65W TDP", "Socket AM5")))
        put("ryzen 9 7900x", GuessEntry("AMD Ryzen 9 7900X", "AMD", "CPU", 97, 99,
            listOf("12 Cores / 24 Threads", "5.6 GHz Boost", "170W TDP", "Socket AM5")))
        put("ryzen 9 7950x", GuessEntry("AMD Ryzen 9 7950X", "AMD", "CPU", 98, 99,
            listOf("16 Cores / 32 Threads", "5.7 GHz Boost", "170W TDP", "Socket AM5")))
        put("ryzen 9 7950x3d", GuessEntry("AMD Ryzen 9 7950X3D", "AMD", "CPU", 99, 99,
            listOf("16 Cores / 32 Threads", "5.7 GHz Boost", "120W TDP", "Socket AM5 (3D V-Cache)")))

        put("i3-10100", GuessEntry("Intel Core i3-10100", "Intel", "CPU", 75, 92,
            listOf("4 Cores / 8 Threads", "4.3 GHz Boost", "65W TDP", "Socket LGA1200")))
        put("i3-10105f", GuessEntry("Intel Core i3-10105F", "Intel", "CPU", 75, 92,
            listOf("4 Cores / 8 Threads", "4.4 GHz Boost", "65W TDP", "Socket LGA1200")))
        put("i3-12100f", GuessEntry("Intel Core i3-12100F", "Intel", "CPU", 80, 94,
            listOf("4 Cores / 8 Threads", "4.3 GHz Boost", "58W TDP", "Socket LGA1700")))
        put("i5-10400f", GuessEntry("Intel Core i5-10400F", "Intel", "CPU", 82, 94,
            listOf("6 Cores / 12 Threads", "4.3 GHz Boost", "65W TDP", "Socket LGA1200")))
        put("i5-11400f", GuessEntry("Intel Core i5-11400F", "Intel", "CPU", 83, 94,
            listOf("6 Cores / 12 Threads", "4.4 GHz Boost", "65W TDP", "Socket LGA1200")))
        put("i5-12400", GuessEntry("Intel Core i5-12400", "Intel", "CPU", 88, 96,
            listOf("6 Cores / 12 Threads", "4.4 GHz Boost", "65W TDP", "Socket LGA1700")))
        put("i5-12400f", GuessEntry("Intel Core i5-12400F", "Intel", "CPU", 88, 96,
            listOf("6 Cores / 12 Threads", "4.4 GHz Boost", "65W TDP", "Socket LGA1700")))
        put("i5-12600k", GuessEntry("Intel Core i5-12600K", "Intel", "CPU", 92, 97,
            listOf("10 Cores / 16 Threads", "4.9 GHz Boost", "125W TDP", "Socket LGA1700")))
        put("i5-12600kf", GuessEntry("Intel Core i5-12600KF", "Intel", "CPU", 92, 97,
            listOf("10 Cores / 16 Threads", "4.9 GHz Boost", "125W TDP", "Socket LGA1700")))
        put("i5-13400", GuessEntry("Intel Core i5-13400", "Intel", "CPU", 89, 96,
            listOf("10 Cores / 16 Threads", "4.6 GHz Boost", "65W TDP", "Socket LGA1700")))
        put("i5-13400f", GuessEntry("Intel Core i5-13400F", "Intel", "CPU", 89, 96,
            listOf("10 Cores / 16 Threads", "4.6 GHz Boost", "65W TDP", "Socket LGA1700")))
        put("i5-13500", GuessEntry("Intel Core i5-13500", "Intel", "CPU", 90, 97,
            listOf("14 Cores / 20 Threads", "4.8 GHz Boost", "65W TDP", "Socket LGA1700")))
        put("i5-13600k", GuessEntry("Intel Core i5-13600K", "Intel", "CPU", 94, 98,
            listOf("14 Cores / 20 Threads", "5.1 GHz Boost", "125W TDP", "Socket LGA1700")))
        put("i5-13600kf", GuessEntry("Intel Core i5-13600KF", "Intel", "CPU", 94, 98,
            listOf("14 Cores / 20 Threads", "5.1 GHz Boost", "125W TDP", "Socket LGA1700")))
        put("i5-14400", GuessEntry("Intel Core i5-14400", "Intel", "CPU", 90, 97,
            listOf("10 Cores / 16 Threads", "4.7 GHz Boost", "65W TDP", "Socket LGA1700")))
        put("i5-14400f", GuessEntry("Intel Core i5-14400F", "Intel", "CPU", 90, 97,
            listOf("10 Cores / 16 Threads", "4.7 GHz Boost", "65W TDP", "Socket LGA1700")))
        put("i5-14600k", GuessEntry("Intel Core i5-14600K", "Intel", "CPU", 94, 98,
            listOf("14 Cores / 20 Threads", "5.3 GHz Boost", "125W TDP", "Socket LGA1700")))
        put("i5-14600kf", GuessEntry("Intel Core i5-14600KF", "Intel", "CPU", 94, 98,
            listOf("14 Cores / 20 Threads", "5.3 GHz Boost", "125W TDP", "Socket LGA1700")))
        put("i7-12700k", GuessEntry("Intel Core i7-12700K", "Intel", "CPU", 95, 98,
            listOf("12 Cores / 20 Threads", "5.0 GHz Boost", "125W TDP", "Socket LGA1700")))
        put("i7-12700f", GuessEntry("Intel Core i7-12700F", "Intel", "CPU", 94, 98,
            listOf("12 Cores / 20 Threads", "4.9 GHz Boost", "65W TDP", "Socket LGA1700")))
        put("i7-13700k", GuessEntry("Intel Core i7-13700K", "Intel", "CPU", 96, 98,
            listOf("16 Cores / 24 Threads", "5.4 GHz Boost", "125W TDP", "Socket LGA1700")))
        put("i7-13700kf", GuessEntry("Intel Core i7-13700KF", "Intel", "CPU", 96, 98,
            listOf("16 Cores / 24 Threads", "5.4 GHz Boost", "125W TDP", "Socket LGA1700")))
        put("i7-13700f", GuessEntry("Intel Core i7-13700F", "Intel", "CPU", 95, 98,
            listOf("16 Cores / 24 Threads", "5.2 GHz Boost", "65W TDP", "Socket LGA1700")))
        put("i7-14700k", GuessEntry("Intel Core i7-14700K", "Intel", "CPU", 97, 98,
            listOf("20 Cores / 28 Threads", "5.6 GHz Boost", "125W TDP", "Socket LGA1700")))
        put("i7-14700kf", GuessEntry("Intel Core i7-14700KF", "Intel", "CPU", 97, 98,
            listOf("20 Cores / 28 Threads", "5.6 GHz Boost", "125W TDP", "Socket LGA1700")))
        put("i7-14700f", GuessEntry("Intel Core i7-14700F", "Intel", "CPU", 96, 98,
            listOf("20 Cores / 28 Threads", "5.4 GHz Boost", "65W TDP", "Socket LGA1700")))
        put("i9-12900k", GuessEntry("Intel Core i9-12900K", "Intel", "CPU", 97, 98,
            listOf("16 Cores / 24 Threads", "5.2 GHz Boost", "125W TDP", "Socket LGA1700")))
        put("i9-13900k", GuessEntry("Intel Core i9-13900K", "Intel", "CPU", 98, 99,
            listOf("24 Cores / 32 Threads", "5.8 GHz Boost", "125W TDP", "Socket LGA1700")))
        put("i9-13900kf", GuessEntry("Intel Core i9-13900KF", "Intel", "CPU", 98, 99,
            listOf("24 Cores / 32 Threads", "5.8 GHz Boost", "125W TDP", "Socket LGA1700")))
        put("i9-13900ks", GuessEntry("Intel Core i9-13900KS", "Intel", "CPU", 99, 99,
            listOf("24 Cores / 32 Threads", "6.0 GHz Boost", "150W TDP", "Socket LGA1700")))
        put("i9-14900k", GuessEntry("Intel Core i9-14900K", "Intel", "CPU", 99, 99,
            listOf("24 Cores / 32 Threads", "6.0 GHz Boost", "125W TDP", "Socket LGA1700")))
        put("i9-14900kf", GuessEntry("Intel Core i9-14900KF", "Intel", "CPU", 99, 99,
            listOf("24 Cores / 32 Threads", "6.0 GHz Boost", "125W TDP", "Socket LGA1700")))

        // ----- NEW CPUs (2024-2026): Arrow Lake Ultra + Ryzen 8000 APU + Ryzen 9000 Granite Ridge
        put("ryzen 5 8600g", GuessEntry("AMD Ryzen 5 8600G", "AMD", "CPU", 86, 97,
            listOf("6 Cores / 12 Threads", "4.9 GHz Boost", "65W TDP", "Socket AM5 (Radeon 760M iGPU)")))
        put("ryzen 7 8700g", GuessEntry("AMD Ryzen 7 8700G", "AMD", "CPU", 91, 97,
            listOf("8 Cores / 16 Threads", "5.1 GHz Boost", "65W TDP", "Socket AM5 (Radeon 780M iGPU)")))
        put("ryzen 5 9600x", GuessEntry("AMD Ryzen 5 9600X", "AMD", "CPU", 88, 98,
            listOf("6 Cores / 12 Threads", "5.4 GHz Boost", "65W TDP", "Socket AM5")))
        put("ryzen 7 9700x", GuessEntry("AMD Ryzen 7 9700X", "AMD", "CPU", 93, 98,
            listOf("8 Cores / 16 Threads", "5.5 GHz Boost", "65W TDP", "Socket AM5")))
        put("ryzen 7 9800x3d", GuessEntry("AMD Ryzen 7 9800X3D", "AMD", "CPU", 98, 99,
            listOf("8 Cores / 16 Threads", "5.2 GHz Boost", "120W TDP", "Socket AM5 (3D V-Cache)")))
        put("ryzen 9 9900x", GuessEntry("AMD Ryzen 9 9900X", "AMD", "CPU", 97, 99,
            listOf("12 Cores / 24 Threads", "5.6 GHz Boost", "170W TDP", "Socket AM5")))
        put("ryzen 9 9950x", GuessEntry("AMD Ryzen 9 9950X", "AMD", "CPU", 99, 99,
            listOf("16 Cores / 32 Threads", "5.7 GHz Boost", "170W TDP", "Socket AM5")))
        put("ryzen 9 9950x3d", GuessEntry("AMD Ryzen 9 9950X3D", "AMD", "CPU", 99, 98,
            listOf("16 Cores / 32 Threads", "5.6 GHz Boost", "170W TDP", "Socket AM5 (3D V-Cache)")))
        put("ultra 5 245k", GuessEntry("Intel Core Ultra 5 245K", "Intel", "CPU", 89, 95,
            listOf("14 Cores / 14 Threads", "5.2 GHz Boost", "125W TDP", "Socket LGA1851 (Arrow Lake)")))
        put("core ultra 5 245k", GuessEntry("Intel Core Ultra 5 245K", "Intel", "CPU", 89, 95,
            listOf("14 Cores / 14 Threads", "5.2 GHz Boost", "125W TDP", "Socket LGA1851 (Arrow Lake)")))
        put("ultra 7 265k", GuessEntry("Intel Core Ultra 7 265K", "Intel", "CPU", 94, 95,
            listOf("20 Cores / 20 Threads", "5.5 GHz Boost", "125W TDP", "Socket LGA1851 (Arrow Lake)")))
        put("core ultra 7 265k", GuessEntry("Intel Core Ultra 7 265K", "Intel", "CPU", 94, 95,
            listOf("20 Cores / 20 Threads", "5.5 GHz Boost", "125W TDP", "Socket LGA1851 (Arrow Lake)")))
        put("ultra 9 285k", GuessEntry("Intel Core Ultra 9 285K", "Intel", "CPU", 98, 95,
            listOf("24 Cores / 24 Threads", "5.7 GHz Boost", "125W TDP", "Socket LGA1851 (Arrow Lake)")))
        put("core ultra 9 285k", GuessEntry("Intel Core Ultra 9 285K", "Intel", "CPU", 98, 95,
            listOf("24 Cores / 24 Threads", "5.7 GHz Boost", "125W TDP", "Socket LGA1851 (Arrow Lake)")))
    }

    private val gpuTable: Map<String, GuessEntry> = buildMap {
        put("gt 1030", GuessEntry("NVIDIA GeForce GT 1030", "NVIDIA", "GPU", 55, 90,
            listOf("2GB GDDR5", "1468 MHz Boost", "30W TDP", "PCIe 3.0 x4")))
        put("gtx 1050 ti", GuessEntry("NVIDIA GeForce GTX 1050 Ti", "NVIDIA", "GPU", 65, 92,
            listOf("4GB GDDR5", "1392 MHz Boost", "75W TDP", "PCIe 3.0 x16")))
        put("gtx 1060 3gb", GuessEntry("NVIDIA GeForce GTX 1060 3GB", "NVIDIA", "GPU", 70, 93,
            listOf("3GB GDDR5", "1708 MHz Boost", "120W TDP", "PCIe 3.0 x16")))
        put("gtx 1060 6gb", GuessEntry("NVIDIA GeForce GTX 1060 6GB", "NVIDIA", "GPU", 72, 93,
            listOf("6GB GDDR5", "1708 MHz Boost", "120W TDP", "PCIe 3.0 x16")))
        put("gtx 1650", GuessEntry("NVIDIA GeForce GTX 1650", "NVIDIA", "GPU", 72, 93,
            listOf("4GB GDDR5", "1665 MHz Boost", "75W TDP", "PCIe 3.0 x16")))
        put("gtx 1660", GuessEntry("NVIDIA GeForce GTX 1660", "NVIDIA", "GPU", 76, 94,
            listOf("6GB GDDR5", "1785 MHz Boost", "120W TDP", "PCIe 3.0 x16")))
        put("gtx 1660 super", GuessEntry("NVIDIA GeForce GTX 1660 SUPER", "NVIDIA", "GPU", 78, 95,
            listOf("6GB GDDR6", "1785 MHz Boost", "125W TDP", "PCIe 3.0 x16")))
        put("gtx 1660 ti", GuessEntry("NVIDIA GeForce GTX 1660 Ti", "NVIDIA", "GPU", 80, 95,
            listOf("6GB GDDR6", "1770 MHz Boost", "120W TDP", "PCIe 3.0 x16")))
        put("rtx 2060", GuessEntry("NVIDIA GeForce RTX 2060", "NVIDIA", "GPU", 82, 96,
            listOf("6GB GDDR6", "1680 MHz Boost", "160W TDP", "PCIe 3.0 x16")))
        put("rtx 2060 6gb", GuessEntry("NVIDIA GeForce RTX 2060 6GB", "NVIDIA", "GPU", 82, 96,
            listOf("6GB GDDR6", "1680 MHz Boost", "160W TDP", "PCIe 3.0 x16")))
        put("rtx 2060 super", GuessEntry("NVIDIA GeForce RTX 2060 SUPER", "NVIDIA", "GPU", 84, 96,
            listOf("8GB GDDR6", "1650 MHz Boost", "175W TDP", "PCIe 3.0 x16")))
        put("rtx 2070", GuessEntry("NVIDIA GeForce RTX 2070", "NVIDIA", "GPU", 85, 96,
            listOf("8GB GDDR6", "1620 MHz Boost", "175W TDP", "PCIe 3.0 x16")))
        put("rtx 2070 super", GuessEntry("NVIDIA GeForce RTX 2070 SUPER", "NVIDIA", "GPU", 87, 97,
            listOf("8GB GDDR6", "1770 MHz Boost", "215W TDP", "PCIe 3.0 x16")))
        put("rtx 2080", GuessEntry("NVIDIA GeForce RTX 2080", "NVIDIA", "GPU", 89, 97,
            listOf("8GB GDDR6", "1710 MHz Boost", "215W TDP", "PCIe 3.0 x16")))
        put("rtx 2080 super", GuessEntry("NVIDIA GeForce RTX 2080 SUPER", "NVIDIA", "GPU", 91, 97,
            listOf("8GB GDDR6", "1815 MHz Boost", "250W TDP", "PCIe 3.0 x16")))
        put("rtx 2080 ti", GuessEntry("NVIDIA GeForce RTX 2080 Ti", "NVIDIA", "GPU", 92, 98,
            listOf("11GB GDDR6", "1635 MHz Boost", "250W TDP", "PCIe 3.0 x16")))
        put("rtx 3050", GuessEntry("NVIDIA GeForce RTX 3050 8GB", "NVIDIA", "GPU", 80, 95,
            listOf("8GB GDDR6", "1777 MHz Boost", "130W TDP", "PCIe 4.0 x8")))
        put("rtx 3060", GuessEntry("NVIDIA GeForce RTX 3060 12GB", "NVIDIA", "GPU", 85, 96,
            listOf("12GB GDDR6", "1777 MHz Boost", "170W TDP", "PCIe 4.0 x16")))
        put("rtx 3060 8gb", GuessEntry("NVIDIA GeForce RTX 3060 8GB", "NVIDIA", "GPU", 84, 96,
            listOf("8GB GDDR6", "1777 MHz Boost", "170W TDP", "PCIe 4.0 x16")))
        put("rtx 3060 ti", GuessEntry("NVIDIA GeForce RTX 3060 Ti 8GB", "NVIDIA", "GPU", 88, 97,
            listOf("8GB GDDR6", "1665 MHz Boost", "200W TDP", "PCIe 4.0 x16")))
        put("rtx 3060 ti gddr6x", GuessEntry("NVIDIA GeForce RTX 3060 Ti GDDR6X", "NVIDIA", "GPU", 88, 97,
            listOf("8GB GDDR6X", "1665 MHz Boost", "200W TDP", "PCIe 4.0 x16")))
        put("rtx 3070", GuessEntry("NVIDIA GeForce RTX 3070 8GB", "NVIDIA", "GPU", 90, 97,
            listOf("8GB GDDR6", "1725 MHz Boost", "220W TDP", "PCIe 4.0 x16")))
        put("rtx 3070 ti", GuessEntry("NVIDIA GeForce RTX 3070 Ti 8GB", "NVIDIA", "GPU", 92, 98,
            listOf("8GB GDDR6X", "1770 MHz Boost", "290W TDP", "PCIe 4.0 x16")))
        put("rtx 3080", GuessEntry("NVIDIA GeForce RTX 3080 10GB", "NVIDIA", "GPU", 94, 98,
            listOf("10GB GDDR6X", "1710 MHz Boost", "320W TDP", "PCIe 4.0 x16")))
        put("rtx 3080 12gb", GuessEntry("NVIDIA GeForce RTX 3080 12GB", "NVIDIA", "GPU", 95, 98,
            listOf("12GB GDDR6X", "1710 MHz Boost", "350W TDP", "PCIe 4.0 x16")))
        put("rtx 3080 ti", GuessEntry("NVIDIA GeForce RTX 3080 Ti 12GB", "NVIDIA", "GPU", 96, 98,
            listOf("12GB GDDR6X", "1665 MHz Boost", "350W TDP", "PCIe 4.0 x16")))
        put("rtx 3090", GuessEntry("NVIDIA GeForce RTX 3090 24GB", "NVIDIA", "GPU", 97, 99,
            listOf("24GB GDDR6X", "1695 MHz Boost", "350W TDP", "PCIe 4.0 x16")))
        put("rtx 3090 ti", GuessEntry("NVIDIA GeForce RTX 3090 Ti 24GB", "NVIDIA", "GPU", 98, 99,
            listOf("24GB GDDR6X", "1860 MHz Boost", "450W TDP", "PCIe 4.0 x16")))
        put("rtx 4060", GuessEntry("NVIDIA GeForce RTX 4060 8GB", "NVIDIA", "GPU", 87, 97,
            listOf("8GB GDDR6", "2460 MHz Boost", "115W TDP", "PCIe 4.0 x8")))
        put("rtx 4060 ti", GuessEntry("NVIDIA GeForce RTX 4060 Ti 8GB", "NVIDIA", "GPU", 89, 97,
            listOf("8GB GDDR6", "2535 MHz Boost", "160W TDP", "PCIe 4.0 x8")))
        put("rtx 4060 ti 16gb", GuessEntry("NVIDIA GeForce RTX 4060 Ti 16GB", "NVIDIA", "GPU", 90, 97,
            listOf("16GB GDDR6", "2535 MHz Boost", "165W TDP", "PCIe 4.0 x8")))
        put("rtx 4070", GuessEntry("NVIDIA GeForce RTX 4070 12GB", "NVIDIA", "GPU", 92, 98,
            listOf("12GB GDDR6X", "2475 MHz Boost", "200W TDP", "PCIe 4.0 x16")))
        put("rtx 4070 super", GuessEntry("NVIDIA GeForce RTX 4070 SUPER 12GB", "NVIDIA", "GPU", 93, 98,
            listOf("12GB GDDR6X", "2475 MHz Boost", "220W TDP", "PCIe 4.0 x16")))
        put("rtx 4070 ti", GuessEntry("NVIDIA GeForce RTX 4070 Ti 12GB", "NVIDIA", "GPU", 95, 98,
            listOf("12GB GDDR6X", "2610 MHz Boost", "285W TDP", "PCIe 4.0 x16")))
        put("rtx 4070 ti super", GuessEntry("NVIDIA GeForce RTX 4070 Ti SUPER 16GB", "NVIDIA", "GPU", 96, 99,
            listOf("16GB GDDR6X", "2610 MHz Boost", "285W TDP", "PCIe 4.0 x16")))
        put("rtx 4080", GuessEntry("NVIDIA GeForce RTX 4080 16GB", "NVIDIA", "GPU", 97, 99,
            listOf("16GB GDDR6X", "2505 MHz Boost", "320W TDP", "PCIe 4.0 x16")))
        put("rtx 4080 super", GuessEntry("NVIDIA GeForce RTX 4080 SUPER 16GB", "NVIDIA", "GPU", 98, 99,
            listOf("16GB GDDR6X", "2550 MHz Boost", "320W TDP", "PCIe 4.0 x16")))
        put("rtx 4090", GuessEntry("NVIDIA GeForce RTX 4090 24GB", "NVIDIA", "GPU", 99, 99,
            listOf("24GB GDDR6X", "2520 MHz Boost", "450W TDP", "PCIe 4.0 x16")))
        put("rtx 4090 d", GuessEntry("NVIDIA GeForce RTX 4090 D 24GB", "NVIDIA", "GPU", 99, 99,
            listOf("24GB GDDR6X", "2520 MHz Boost", "450W TDP", "PCIe 4.0 x16")))

        put("rx 5500 xt", GuessEntry("AMD Radeon RX 5500 XT", "AMD", "GPU", 68, 93,
            listOf("8GB GDDR6", "1845 MHz Boost", "130W TDP", "PCIe 4.0 x8")))
        put("rx 5600 xt", GuessEntry("AMD Radeon RX 5600 XT", "AMD", "GPU", 73, 94,
            listOf("6GB GDDR6", "1750 MHz Boost", "150W TDP", "PCIe 4.0 x8")))
        put("rx 5700", GuessEntry("AMD Radeon RX 5700", "AMD", "GPU", 76, 94,
            listOf("8GB GDDR6", "1725 MHz Boost", "180W TDP", "PCIe 4.0 x16")))
        put("rx 5700 xt", GuessEntry("AMD Radeon RX 5700 XT", "AMD", "GPU", 78, 95,
            listOf("8GB GDDR6", "1905 MHz Boost", "225W TDP", "PCIe 4.0 x16")))
        put("rx 6500 xt", GuessEntry("AMD Radeon RX 6500 XT", "AMD", "GPU", 72, 94,
            listOf("4GB GDDR6", "2815 MHz Boost", "107W TDP", "PCIe 4.0 x4")))
        put("rx 6600", GuessEntry("AMD Radeon RX 6600 8GB", "AMD", "GPU", 82, 96,
            listOf("8GB GDDR6", "2491 MHz Boost", "132W TDP", "PCIe 4.0 x8")))
        put("rx 6600 xt", GuessEntry("AMD Radeon RX 6600 XT 8GB", "AMD", "GPU", 85, 96,
            listOf("8GB GDDR6", "2589 MHz Boost", "160W TDP", "PCIe 4.0 x16")))
        put("rx 6650 xt", GuessEntry("AMD Radeon RX 6650 XT 8GB", "AMD", "GPU", 86, 96,
            listOf("8GB GDDR6", "2635 MHz Boost", "180W TDP", "PCIe 4.0 x16")))
        put("rx 6700", GuessEntry("AMD Radeon RX 6700 10GB", "AMD", "GPU", 87, 96,
            listOf("10GB GDDR6", "2581 MHz Boost", "175W TDP", "PCIe 4.0 x16")))
        put("rx 6700 xt", GuessEntry("AMD Radeon RX 6700 XT 12GB", "AMD", "GPU", 88, 97,
            listOf("12GB GDDR6", "2581 MHz Boost", "230W TDP", "PCIe 4.0 x16")))
        put("rx 6750 xt", GuessEntry("AMD Radeon RX 6750 XT 12GB", "AMD", "GPU", 89, 97,
            listOf("12GB GDDR6", "2600 MHz Boost", "250W TDP", "PCIe 4.0 x16")))
        put("rx 6800", GuessEntry("AMD Radeon RX 6800 16GB", "AMD", "GPU", 91, 97,
            listOf("16GB GDDR6", "2105 MHz Boost", "250W TDP", "PCIe 4.0 x16")))
        put("rx 6800 xt", GuessEntry("AMD Radeon RX 6800 XT 16GB", "AMD", "GPU", 93, 98,
            listOf("16GB GDDR6", "2250 MHz Boost", "300W TDP", "PCIe 4.0 x16")))
        put("rx 6900 xt", GuessEntry("AMD Radeon RX 6900 XT 16GB", "AMD", "GPU", 95, 98,
            listOf("16GB GDDR6", "2250 MHz Boost", "300W TDP", "PCIe 4.0 x16")))
        put("rx 6950 xt", GuessEntry("AMD Radeon RX 6950 XT 16GB", "AMD", "GPU", 96, 98,
            listOf("16GB GDDR6", "2310 MHz Boost", "335W TDP", "PCIe 4.0 x16")))
        put("rx 7600", GuessEntry("AMD Radeon RX 7600 8GB", "AMD", "GPU", 85, 96,
            listOf("8GB GDDR6", "2655 MHz Boost", "165W TDP", "PCIe 4.0 x8")))
        put("rx 7600 xt", GuessEntry("AMD Radeon RX 7600 XT 16GB", "AMD", "GPU", 87, 97,
            listOf("16GB GDDR6", "2755 MHz Boost", "190W TDP", "PCIe 4.0 x8")))
        put("rx 7700 xt", GuessEntry("AMD Radeon RX 7700 XT 12GB", "AMD", "GPU", 89, 97,
            listOf("12GB GDDR6", "2544 MHz Boost", "245W TDP", "PCIe 4.0 x16")))
        put("rx 7800 xt", GuessEntry("AMD Radeon RX 7800 XT 16GB", "AMD", "GPU", 92, 98,
            listOf("16GB GDDR6", "2430 MHz Boost", "263W TDP", "PCIe 4.0 x16")))
        put("rx 7900 xt", GuessEntry("AMD Radeon RX 7900 XT 20GB", "AMD", "GPU", 95, 98,
            listOf("20GB GDDR6", "2400 MHz Boost", "315W TDP", "PCIe 4.0 x16")))
        put("rx 7900 xtx", GuessEntry("AMD Radeon RX 7900 XTX 24GB", "AMD", "GPU", 97, 99,
            listOf("24GB GDDR6", "2500 MHz Boost", "355W TDP", "PCIe 4.0 x16")))
        put("rx 7900 gre", GuessEntry("AMD Radeon RX 7900 GRE 16GB", "AMD", "GPU", 94, 98,
            listOf("16GB GDDR6", "2245 MHz Boost", "260W TDP", "PCIe 4.0 x16")))

        // ----- NEW GPUs (2024-2026): RTX 50 Blackwell / RX 9000 (RDNA 4) / RX 8000 XT
        put("rtx 5060", GuessEntry("NVIDIA GeForce RTX 5060 8GB", "NVIDIA", "GPU", 79, 89,
            listOf("8GB GDDR7", "170W TDP", "PCIe 5.0 x16", "242mm Length")))
        put("rtx 5070", GuessEntry("NVIDIA GeForce RTX 5070 12GB", "NVIDIA", "GPU", 88, 89,
            listOf("12GB GDDR7", "250W TDP", "PCIe 5.0 x16", "270mm Length")))
        put("rtx 5070 ti", GuessEntry("NVIDIA GeForce RTX 5070 Ti 16GB", "NVIDIA", "GPU", 92, 89,
            listOf("16GB GDDR7", "300W TDP", "PCIe 5.0 x16", "285mm Length")))
        put("rtx 5080", GuessEntry("NVIDIA GeForce RTX 5080 16GB", "NVIDIA", "GPU", 96, 90,
            listOf("16GB GDDR7", "360W TDP", "PCIe 5.0 x16", "305mm Length")))
        put("rtx 5090", GuessEntry("NVIDIA GeForce RTX 5090 32GB", "NVIDIA", "GPU", 99, 90,
            listOf("32GB GDDR7", "575W TDP", "PCIe 5.0 x16", "336mm Length")))
        put("rx 9060", GuessEntry("AMD Radeon RX 9060 8GB", "AMD", "GPU", 82, 88,
            listOf("8GB GDDR6", "175W TDP", "PCIe 5.0 x16", "245mm Length")))
        put("rx 9070", GuessEntry("AMD Radeon RX 9070 12GB", "AMD", "GPU", 90, 88,
            listOf("12GB GDDR6", "260W TDP", "PCIe 5.0 x16", "276mm Length")))
        put("rx 9070 xt", GuessEntry("AMD Radeon RX 9070 XT 16GB", "AMD", "GPU", 94, 88,
            listOf("16GB GDDR6", "320W TDP", "PCIe 5.0 x16", "287mm Length")))
    }

    private val motherboardTable: Map<String, GuessEntry> = buildMap {
        // AMD AM4
        put("msi b450 tomahawk max", GuessEntry("MSI B450 TOMAHAWK MAX (AM4)", "MSI", "MOTHERBOARD", 78, 92,
            listOf("Socket AM4", "AMD B450 Chipset", "4x DDR4 DIMM", "2x M.2 NVMe PCIe 3.0", "ATX Form Factor")))
        put("asus prime b450m a", GuessEntry("ASUS PRIME B450M-A (AM4 mATX)", "ASUS", "MOTHERBOARD", 75, 91,
            listOf("Socket AM4", "AMD B450 Chipset", "4x DDR4 DIMM", "1x M.2 NVMe PCIe 3.0", "Micro-ATX")))
        put("msi mag b550 tomahawk wifi", GuessEntry("MSI MAG B550 TOMAHAWK WIFI (AM4)", "MSI", "MOTHERBOARD", 88, 96,
            listOf("Socket AM4", "AMD B550 Chipset", "4x DDR4 DIMM", "2x M.2 NVMe PCIe 4.0", "ATX Form Factor", "WiFi 6")))
        put("asus rog strix b550 f gaming wifi", GuessEntry("ASUS ROG STRIX B550-F GAMING WIFI (AM4)", "ASUS", "MOTHERBOARD", 90, 96,
            listOf("Socket AM4", "AMD B550 Chipset", "4x DDR4 DIMM", "2x M.2 NVMe PCIe 4.0", "ATX", "WiFi 6")))
        put("asus rog strix x570 e wifi ii", GuessEntry("ASUS ROG STRIX X570-E WIFI II (AM4)", "ASUS", "MOTHERBOARD", 93, 97,
            listOf("Socket AM4", "AMD X570 Chipset", "4x DDR4 DIMM", "3x M.2 NVMe PCIe 4.0", "ATX", "WiFi 6E")))
        put("asrock b450 pro4", GuessEntry("ASRock B450 Pro4 (AM4)", "ASRock", "MOTHERBOARD", 77, 92,
            listOf("Socket AM4", "AMD B450", "4x DDR4", "2x M.2", "ATX")))
        put("gigabyte b550 aorus elite ax", GuessEntry("GIGABYTE B550 AORUS ELITE AX (AM4)", "GIGABYTE", "MOTHERBOARD", 86, 95,
            listOf("Socket AM4", "AMD B550", "4x DDR4", "2x M.2 PCIe 4.0", "ATX", "WiFi 6")))
        // AMD AM5
        put("msi mag b650 tomahawk wifi", GuessEntry("MSI MAG B650 TOMAHAWK WIFI (AM5)", "MSI", "MOTHERBOARD", 89, 95,
            listOf("Socket AM5", "AMD B650 Chipset", "4x DDR5 DIMM", "2x M.2 NVMe PCIe 5.0", "ATX", "WiFi 6E")))
        put("asus rog strix b650e f gaming wifi", GuessEntry("ASUS ROG STRIX B650E-F GAMING WIFI (AM5)", "ASUS", "MOTHERBOARD", 92, 97,
            listOf("Socket AM5", "AMD B650E Chipset", "4x DDR5 DIMM", "3x M.2 PCIe 5.0", "ATX", "WiFi 6E")))
        put("asrock x670e steel legend", GuessEntry("ASRock X670E Steel Legend (AM5)", "ASRock", "MOTHERBOARD", 91, 95,
            listOf("Socket AM5", "AMD X670E Chipset", "4x DDR5", "4x M.2 PCIe 5.0", "ATX")))
        put("asus rog maximus z790 hero", GuessEntry("ASUS ROG MAXIMUS Z790 HERO (LGA1700)", "ASUS", "MOTHERBOARD", 97, 98,
            listOf("Socket LGA1700", "Intel Z790", "4x DDR5", "5x M.2 PCIe 5.0", "ATX", "WiFi 7")))
        // Intel LGA1700 12/13/14 Gen
        put("msi b760m mortar wifi", GuessEntry("MSI B760M MORTAR WIFI (LGA1700 mATX)", "MSI", "MOTHERBOARD", 82, 94,
            listOf("Socket LGA1700", "Intel B760 Chipset", "4x DDR5 DIMM", "2x M.2 PCIe 4.0", "Micro-ATX", "WiFi 6")))
        put("asus rog strix z790 a gaming wifi d4", GuessEntry("ASUS ROG STRIX Z790-A GAMING WIFI D4 (LGA1700)", "ASUS", "MOTHERBOARD", 89, 96,
            listOf("Socket LGA1700", "Intel Z790 Chipset", "4x DDR4 DIMM", "4x M.2", "ATX", "WiFi 6E")))
        put("msi mag z790 tomahawk wifi", GuessEntry("MSI MAG Z790 TOMAHAWK WIFI (LGA1700)", "MSI", "MOTHERBOARD", 91, 96,
            listOf("Socket LGA1700", "Intel Z790", "4x DDR5", "4x M.2 PCIe 5.0", "ATX", "WiFi 6E")))
        // Intel Gen 10/11 LGA1200
        put("msi z590 a pro", GuessEntry("MSI Z590-A PRO (LGA1200)", "MSI", "MOTHERBOARD", 80, 92,
            listOf("Socket LGA1200", "Intel Z590", "4x DDR4", "3x M.2 PCIe 4.0", "ATX")))
        put("asus rog strix z590 e gaming", GuessEntry("ASUS ROG STRIX Z590-E GAMING (LGA1200)", "ASUS", "MOTHERBOARD", 85, 94,
            listOf("Socket LGA1200", "Intel Z590", "4x DDR4", "4x M.2", "ATX", "WiFi 6")))
    }

    private val ramTable: Map<String, GuessEntry> = buildMap {
        // DDR4
        put("corsair vengeance lpx 16gb ddr4 3200", GuessEntry("Corsair Vengeance LPX 16GB (2x8GB) DDR4-3200", "Corsair", "RAM", 78, 94,
            listOf("16GB (2x8GB) Kit", "DDR4-3200 MHz", "CL16 Latency", "1.35V", "288-Pin UDIMM", "DDR4")))
        put("corsair vengeance lpx 32gb ddr4 3600", GuessEntry("Corsair Vengeance LPX 32GB (2x16GB) DDR4-3600", "Corsair", "RAM", 85, 95,
            listOf("32GB (2x16GB) Kit", "DDR4-3600 MHz", "CL18 Latency", "1.35V", "288-Pin UDIMM", "DDR4")))
        put("gskill trident z5 rgb 32gb ddr4 3600", GuessEntry("G.SKILL Trident Z5 RGB 32GB (2x16GB) DDR4-3600", "G.SKILL", "RAM", 88, 96,
            listOf("32GB (2x16GB)", "DDR4-3600 CL16", "1.35V", "288-Pin UDIMM", "DDR4")))
        put("kingston fury beast 16gb ddr4 3200", GuessEntry("Kingston FURY Beast 16GB (2x8GB) DDR4-3200", "Kingston", "RAM", 76, 93,
            listOf("16GB (2x8GB)", "DDR4-3200 CL16", "1.35V", "288-Pin UDIMM", "DDR4")))
        put("gskill ripjaws v 64gb ddr4 3600", GuessEntry("G.SKILL Ripjaws V 64GB (2x32GB) DDR4-3600", "G.SKILL", "RAM", 90, 96,
            listOf("64GB (2x32GB)", "DDR4-3600 CL18", "1.35V", "288-Pin UDIMM", "DDR4")))
        // DDR5
        put("corsair vengeance 32gb ddr5 6000", GuessEntry("Corsair Vengeance 32GB (2x16GB) DDR5-6000 EXPO", "Corsair", "RAM", 88, 96,
            listOf("32GB (2x16GB) Kit", "DDR5-6000 MHz", "CL30 Latency", "1.35V", "288-Pin UDIMM", "DDR5", "AMD EXPO / Intel XMP 3.0")))
        put("gskill trident z5 rgb 32gb ddr5 6400", GuessEntry("G.SKILL Trident Z5 RGB 32GB (2x16GB) DDR5-6400", "G.SKILL", "RAM", 92, 97,
            listOf("32GB (2x16GB)", "DDR5-6400 CL32", "1.35V", "288-Pin UDIMM", "DDR5", "XMP 3.0")))
        put("kingston fury beast 64gb ddr5 5600", GuessEntry("Kingston FURY Beast 64GB (2x32GB) DDR5-5600", "Kingston", "RAM", 90, 95,
            listOf("64GB (2x32GB)", "DDR5-5600 CL36", "1.35V", "288-Pin UDIMM", "DDR5")))
        put("corsair dominator platinum rgb 64gb ddr5 6600", GuessEntry("Corsair DOMINATOR PLATINUM RGB 64GB (2x32GB) DDR5-6600", "Corsair", "RAM", 95, 98,
            listOf("64GB (2x32GB)", "DDR5-6600 CL32", "1.4V", "288-Pin UDIMM", "DDR5")))
        put("teamgroup t force delta 32gb ddr5 6000", GuessEntry("TeamGroup T-Force Delta 32GB (2x16GB) DDR5-6000 RGB", "TeamGroup", "RAM", 86, 95,
            listOf("32GB (2x16GB)", "DDR5-6000 CL30", "1.35V", "288-Pin UDIMM", "DDR5")))
    }

    private val storageTable: Map<String, GuessEntry> = buildMap {
        put("samsung 990 pro 1tb", GuessEntry("Samsung 990 PRO 1TB NVMe PCIe 4.0 SSD", "Samsung", "STORAGE", 88, 96,
            listOf("1TB Capacity", "NVMe M.2 2280", "PCIe 4.0 x4", "7450 MB/s Read", "6900 MB/s Write", "NVMe SSD")))
        put("samsung 990 pro 2tb", GuessEntry("Samsung 990 PRO 2TB NVMe PCIe 4.0 SSD", "Samsung", "STORAGE", 92, 97,
            listOf("2TB Capacity", "NVMe M.2 2280", "PCIe 4.0 x4", "7450 MB/s Read", "6900 MB/s Write", "NVMe SSD")))
        put("samsung 990 pro 4tb", GuessEntry("Samsung 990 PRO 4TB NVMe PCIe 4.0 SSD", "Samsung", "STORAGE", 95, 98,
            listOf("4TB Capacity", "NVMe M.2 2280", "PCIe 4.0 x4", "7450 MB/s Read", "6900 MB/s Write", "NVMe SSD")))
        put("wd black sn850x 2tb", GuessEntry("WD_BLACK SN850X 2TB NVMe PCIe 4.0 SSD", "Western Digital", "STORAGE", 90, 96,
            listOf("2TB", "NVMe M.2 2280", "PCIe 4.0 x4", "7300 MB/s Read", "6300 MB/s Write", "NVMe SSD")))
        put("crucial t700 2tb", GuessEntry("Crucial T700 2TB NVMe PCIe 5.0 SSD", "Crucial", "STORAGE", 94, 97,
            listOf("2TB", "NVMe M.2 2280", "PCIe 5.0 x4", "12400 MB/s Read", "11800 MB/s Write", "NVMe SSD (PCIe 5)")))
        put("crucial p5 plus 1tb", GuessEntry("Crucial P5 Plus 1TB NVMe PCIe 4.0 SSD", "Crucial", "STORAGE", 82, 93,
            listOf("1TB", "NVMe M.2", "PCIe 4.0 x4", "6600 MB/s Read", "5000 MB/s Write", "NVMe SSD")))
        put("kingston kc3000 2tb", GuessEntry("Kingston KC3000 2TB NVMe PCIe 4.0 SSD", "Kingston", "STORAGE", 89, 95,
            listOf("2TB", "NVMe M.2 2280", "PCIe 4.0 x4", "7000 MB/s Read", "7000 MB/s Write", "NVMe SSD")))
        put("sabrent rocket 4 plus 8tb", GuessEntry("Sabrent Rocket 4 PLUS 8TB NVMe PCIe 4.0 SSD", "Sabrent", "STORAGE", 93, 96,
            listOf("8TB", "NVMe M.2", "PCIe 4.0 x4", "7100 MB/s Read", "6600 MB/s Write", "NVMe SSD")))
        put("adata legend 970 1tb", GuessEntry("ADATA Legend 970 1TB NVMe PCIe 4.0 SSD", "ADATA", "STORAGE", 83, 93,
            listOf("1TB", "NVMe M.2", "PCIe 4.0 x4", "7400 MB/s Read", "5800 MB/s Write", "NVMe SSD")))
        put("samsung 980 pro 1tb", GuessEntry("Samsung 980 PRO 1TB NVMe PCIe 4.0 SSD", "Samsung", "STORAGE", 86, 95,
            listOf("1TB", "NVMe M.2 2280", "PCIe 4.0 x4", "7000 MB/s Read", "5100 MB/s Write", "NVMe SSD")))
    }

    private val psuTable: Map<String, GuessEntry> = buildMap {
        put("corsair rm650x", GuessEntry("Corsair RM650x 650W 80+ Gold Full Modular", "Corsair", "PSU", 84, 95,
            listOf("650 Watts", "80 PLUS Gold Certified", "100% Modular Cables", "ATX 3.0 Ready", "10 Year Warranty", "ATX Form Factor")))
        put("corsair rm750x", GuessEntry("Corsair RM750x 750W 80+ Gold Full Modular", "Corsair", "PSU", 87, 96,
            listOf("750 Watts", "80 PLUS Gold", "Full Modular", "ATX 3.0", "10 Year Warranty", "ATX")))
        put("corsair rm850x", GuessEntry("Corsair RM850x 850W 80+ Gold Full Modular", "Corsair", "PSU", 89, 96,
            listOf("850 Watts", "80 PLUS Gold", "Full Modular", "ATX 3.0", "10 Year Warranty", "ATX")))
        put("corsair rm1000x", GuessEntry("Corsair RM1000x 1000W 80+ Gold Full Modular", "Corsair", "PSU", 92, 97,
            listOf("1000 Watts", "80 PLUS Gold", "Full Modular", "ATX 3.0 / PCIe 5.0 Ready", "10 Year Warranty", "ATX")))
        put("seasonic focus gx 750", GuessEntry("Seasonic FOCUS GX-750 750W 80+ Gold Full Modular", "Seasonic", "PSU", 88, 96,
            listOf("750 Watts", "80 PLUS Gold", "Full Modular", "ATX 3.0", "10 Year Warranty", "ATX")))
        put("seasonic focus px 850", GuessEntry("Seasonic FOCUS PX-850 850W 80+ Platinum Full Modular", "Seasonic", "PSU", 92, 97,
            listOf("850 Watts", "80 PLUS Platinum", "Full Modular", "ATX 3.0", "12 Year Warranty", "ATX")))
        put("asus prime 650w bronze", GuessEntry("ASUS PRIME 650W 80+ Bronze", "ASUS", "PSU", 74, 91,
            listOf("650 Watts", "80 PLUS Bronze Certified", "Non-Modular", "ATX Form Factor", "5 Year Warranty", "ATX")))
        put("asus rog strix 850w gold", GuessEntry("ASUS ROG STRIX 850W 80+ Gold Full Modular", "ASUS", "PSU", 89, 96,
            listOf("850 Watts", "80 PLUS Gold", "Full Modular", "ATX 3.0 / PCIe 5.0", "10 Year Warranty", "ATX")))
        put("evga supernova 750 ga", GuessEntry("EVGA SuperNOVA 750 GA 750W 80+ Gold Full Modular", "EVGA", "PSU", 85, 94,
            listOf("750 Watts", "80 PLUS Gold", "Full Modular", "ATX", "10 Year Warranty", "ATX")))
        put("be quiet straight power 12 1000w platinum", GuessEntry("be quiet! STRAIGHT POWER 12 1000W 80+ Platinum", "be quiet!", "PSU", 94, 98,
            listOf("1000 Watts", "80 PLUS Platinum", "Full Modular", "ATX 3.0", "10 Year Warranty", "ATX")))
        put("nzxt c750 750w gold", GuessEntry("NZXT C750 750W 80+ Gold Full Modular", "NZXT", "PSU", 86, 95,
            listOf("750 Watts", "80 PLUS Gold", "Full Modular", "ATX 3.0", "10 Year Warranty", "ATX")))
        put("cooler master mwe 650 bronze v2", GuessEntry("Cooler Master MWE 650 Bronze V2 650W 80+ Bronze", "Cooler Master", "PSU", 72, 90,
            listOf("650 Watts", "80 PLUS Bronze", "Non-Modular", "ATX", "5 Year Warranty", "ATX")))
    }

    private val normalizeKey: (String) -> String = { q ->
        q.trim().lowercase()
            .replace("(\\d+)\\s*gb\\b".toRegex(), "$1gb")
            .replace("\\s+|-".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    fun guess(query: String): ComponentDetail? {
        val q = normalizeKey(query)
        if (q.isBlank()) return null

        // LEMONADE BUG FIX: HARD REQUIREMENT - no hardware-indicating tokens in query -> RETURN NULL.
        // Random dictionary words (lemonade, pizza, apple, chair, etc.) -> NEVER produce a guess.
        // This blocks the SmartGuess fallback from inventing a GPU/CPU with random fake specs for arbitrary words.
        if (!hasHardwareIndicatingTokens(q)) {
            return null
        }

        // First, try exact match in cpuTable
        cpuTable.entries.forEach { (k, v) ->
            val nk = normalizeKey(k)
            if (q == nk) {
                return toDetail(v)
            }
        }

        // Then, try exact match in gpuTable
        gpuTable.entries.forEach { (k, v) ->
            val nk = normalizeKey(k)
            if (q == nk) {
                return toDetail(v)
            }
        }

        // Then, try exact match in motherboardTable
        motherboardTable.entries.forEach { (k, v) ->
            val nk = normalizeKey(k)
            if (q == nk) {
                return toDetail(v)
            }
        }

        // Then, try exact match in ramTable
        ramTable.entries.forEach { (k, v) ->
            val nk = normalizeKey(k)
            if (q == nk) {
                return toDetail(v)
            }
        }

        // Then, try exact match in storageTable
        storageTable.entries.forEach { (k, v) ->
            val nk = normalizeKey(k)
            if (q == nk) {
                return toDetail(v)
            }
        }

        // Then, try exact match in psuTable
        psuTable.entries.forEach { (k, v) ->
            val nk = normalizeKey(k)
            if (q == nk) {
                return toDetail(v)
            }
        }

        // Then, try partial match in cpuTable (query contains key)
        cpuTable.entries.forEach { (k, v) ->
            val nk = normalizeKey(k)
            if (q.contains(nk)) {
                return toDetail(v)
            }
        }

        // Then, try partial match in gpuTable (query contains key)
        gpuTable.entries.forEach { (k, v) ->
            val nk = normalizeKey(k)
            if (q.contains(nk)) {
                return toDetail(v)
            }
        }

        // Then, try partial match in motherboardTable
        motherboardTable.entries.forEach { (k, v) ->
            val nk = normalizeKey(k)
            if (q.contains(nk)) {
                return toDetail(v)
            }
        }

        // Then, try partial match in ramTable
        ramTable.entries.forEach { (k, v) ->
            val nk = normalizeKey(k)
            if (q.contains(nk)) {
                return toDetail(v)
            }
        }

        // Then, try partial match in storageTable
        storageTable.entries.forEach { (k, v) ->
            val nk = normalizeKey(k)
            if (q.contains(nk)) {
                return toDetail(v)
            }
        }

        // Then, try partial match in psuTable
        psuTable.entries.forEach { (k, v) ->
            val nk = normalizeKey(k)
            if (q.contains(nk)) {
                return toDetail(v)
            }
        }

        // Finally, try pattern-based generation
        return guessByPattern(q)
    }

    private fun toDetail(v: GuessEntry): ComponentDetail = ComponentDetail(
        name = v.name,
        category = v.category,
        score = v.score,
        specs = v.specs,
        aiConfidence = v.aiConfidence,
        manufacturer = v.manufacturer
    )

    private fun guessByPattern(q: String): ComponentDetail? {
        val hasIntel = q.contains("intel") || q.contains(" core i") || q.startsWith("i3 ") ||
            q.startsWith("i5 ") || q.startsWith("i7 ") || q.startsWith("i9 ") ||
            q.startsWith("i3-") || q.startsWith("i5-") || q.startsWith("i7-") || q.startsWith("i9-")
        val hasUltra = q.contains("ultra") || q.contains("core ultra")
        val hasRyzen = q.contains("ryzen") || q.contains("amd")
        val hasRadeon = q.contains("radeon") || (hasRyzen && q.contains("rx "))
        val hasNvidiaGpu = q.contains("nvidia") || q.contains("geforce") || q.contains("rtx") || q.contains("gtx") || q.contains("gt ")
        val hasMobo = q.contains("motherboard") || q.contains(" mainboard") || q.contains(" mobo") ||
            q.contains("b450") || q.contains("b550") || q.contains("x570") || q.contains("a520") ||
            q.contains("a620") || q.contains("b650") || q.contains("x670") || q.contains("x870") ||
            q.contains("b760") || q.contains("b660") || q.contains("h610") || q.contains("h510") ||
            q.contains("z590") || q.contains("z690") || q.contains("z790") || q.contains("h770") ||
            q.contains("z890") || q.contains("b860") || q.contains("h810") ||
            q.contains(" am4") || q.contains(" am5") || q.contains("lga1151") || q.contains("lga1200") ||
            q.contains("lga1700") || q.contains("lga1851") || q.contains("tomahawk") || q.contains("strix") ||
            q.contains("steel legend") || q.contains("aorus elite") || q.contains("prime b") || q.contains("mortar")
        val hasRam = q.contains("ddr4") || q.contains("ddr5") || q.contains("ddr3") || q.contains(" udimm") ||
            q.contains(" dimm") || q.contains("ram") || q.contains("memory") || q.contains("vengeance") ||
            q.contains("trident") || q.contains("ripjaws") || q.contains("fury beast") || q.contains("t-force") ||
            q.contains("dominator") || q.contains("lpx")
        val hasStorage = q.contains("nvme") || q.contains("ssd") || q.contains("hdd") || q.contains(" m.2") ||
            q.contains("pcie 4") || q.contains("pcie 5") || q.contains("990 pro") || q.contains("980 pro") ||
            q.contains("sn850") || q.contains("sn770") || q.contains("t700") || q.contains("p5 plus") ||
            q.contains("kc3000") || q.contains("rocket 4") || q.contains("firecuda") || q.contains("barracuda")
        val hasPsu = q.contains("psu") || q.contains("power supply") || q.contains("80+") ||
            q.contains("bronze") || q.contains("silver") || q.contains("gold") || q.contains("platinum") ||
            q.contains("titanium") || q.contains("modular") || q.contains(" rm650") || q.contains(" rm750") ||
            q.contains(" rm850") || q.contains(" rm1000") || q.contains("focus gx") || q.contains("focus px") ||
            q.contains("supernova") || q.contains("straight power") || q.contains("prime 650") || q.contains("rog strix 850") ||
            Regex("""\d{3,4}\s*w(?:atts?)?\b""", RegexOption.IGNORE_CASE).containsMatchIn(q)

        when {
            hasIntel || hasUltra -> {
                val genMatch = """(?:core\s*)?i[3579]-?(\d{2,5})[kft\s]*""".toRegex(RegexOption.IGNORE_CASE).find(q)
                val ultraMatch = """(?:core\s*)?ultra\s*([579])\s*(\d{3,5})[k\s]*""".toRegex(RegexOption.IGNORE_CASE).find(q)
                if (ultraMatch != null) {
                    val tier = when (ultraMatch.groupValues[1].toInt()) {
                        5 -> "Ultra 5"; 7 -> "Ultra 7"; 9 -> "Ultra 9"; else -> "Ultra 7"
                    }
                    val modelCode = ultraMatch.groupValues[2].toIntOrNull() ?: 265
                    val gen = modelCode / 100 // e.g. 285 -> 2, 265 -> 2, 245 -> 2 (Arrow Lake 200 series)
                    val series = modelCode % 100
                    val socket = if (gen >= 2) "LGA1851" else "LGA1700"
                    val (cores, threads) = when (tier) {
                        "Ultra 5" -> Pair(14, 14)
                        "Ultra 7" -> Pair(20, 20)
                        "Ultra 9" -> Pair(24, 24)
                        else -> Pair(14, 14)
                    }
                    val tdp = 125
                    val boost = when (tier) {
                        "Ultra 5" -> "5.2 GHz Boost"
                        "Ultra 7" -> "5.5 GHz Boost"
                        "Ultra 9" -> "5.7 GHz Boost"
                        else -> "5.2 GHz Boost"
                    }
                    val suffix = when {
                        q.endsWith("k", true) || q.endsWith("kf", true) -> "K"
                        else -> ""
                    }
                    return GuessEntry(
                        "Intel Core $tier $modelCode$suffix", "Intel", "CPU",
                        (85 + (if (gen >= 2) 10 else 0) + series / 10).coerceAtMost(99),
                        (90 + gen).coerceAtMost(96),
                        listOf("$cores Cores / $threads Threads", boost, "$tdp W TDP", "Socket $socket (Arrow Lake)")
                    ).let { toDetail(it) }
                }
                val numStr = genMatch?.groupValues?.get(1) ?: return null
                val gen = numStr.take(if (numStr.length >= 5) 2 else 1).toIntOrNull() ?: return null
                val series = numStr.drop(if (numStr.length >= 5) 2 else 1).toIntOrNull() ?: return null
                val tier = when {
                    q.startsWith("i3") -> "i3"
                    q.startsWith("i5") -> "i5"
                    q.startsWith("i7") -> "i7"
                    q.startsWith("i9") -> "i9"
                    else -> "i5"
                }
                val socket = when (gen) {
                    in 8..10 -> "LGA1151 v2"
                    11 -> "LGA1200"
                    in 12..14 -> "LGA1700"
                    15 -> "LGA1851"
                    else -> "LGA$gen"
                }
                val cores = when (tier) {
                    "i3" -> Pair(4, 8)
                    "i5" -> when (gen) {
                        in 8..11 -> Pair(6, 12)
                        12 -> if (series >= 600) Pair(10, 16) else Pair(6, 12)
                        13 -> if (series >= 600) Pair(14, 20) else if (series >= 500) Pair(14, 20) else Pair(10, 16)
                        14 -> if (series >= 600) Pair(14, 20) else Pair(10, 16)
                        15 -> if (series >= 600) Pair(14, 20) else Pair(10, 16)
                        else -> Pair(6, 12)
                    }
                    "i7" -> when (gen) {
                        in 8..9 -> Pair(8, 8)
                        10 -> Pair(8, 16)
                        11 -> Pair(8, 16)
                        12 -> Pair(12, 20)
                        13 -> Pair(16, 24)
                        14 -> Pair(20, 28)
                        15 -> Pair(20, 28)
                        else -> Pair(8, 16)
                    }
                    "i9" -> when (gen) {
                        in 10..11 -> Pair(8, 16)
                        12 -> Pair(16, 24)
                        13 -> Pair(24, 32)
                        14 -> Pair(24, 32)
                        15 -> Pair(24, 32)
                        else -> Pair(12, 24)
                    }
                    else -> Pair(6, 12)
                }
                val tdp = when {
                    q.endsWith("k") || q.endsWith("kf") || q.endsWith("ks") -> 125
                    tier == "i9" && series % 10 == 9 -> 125
                    else -> 65
                }
                val boost = when (tier) {
                    "i3" -> "4.3 GHz Boost"
                    "i5" -> when (gen) {
                        12 -> if (series >= 600) "4.9 GHz Boost" else "4.4 GHz Boost"
                        13 -> if (series >= 600) "5.1 GHz Boost" else "4.6 GHz Boost"
                        14 -> if (series >= 600) "5.3 GHz Boost" else "4.7 GHz Boost"
                        15 -> if (series >= 600) "5.4 GHz Boost" else "4.9 GHz Boost"
                        else -> "4.4 GHz Boost"
                    }
                    "i7" -> when (gen) {
                        12 -> "5.0 GHz Boost"
                        13 -> "5.4 GHz Boost"
                        14 -> "5.6 GHz Boost"
                        15 -> "5.7 GHz Boost"
                        else -> "4.9 GHz Boost"
                    }
                    "i9" -> when (gen) {
                        12 -> "5.2 GHz Boost"
                        13 -> if (series == 900) "6.0 GHz Boost" else "5.8 GHz Boost"
                        14 -> "6.0 GHz Boost"
                        15 -> "6.1 GHz Boost"
                        else -> "5.2 GHz Boost"
                    }
                    else -> "4.2 GHz Boost"
                }
                val tierScore = when (tier) {
                    "i3" -> 0
                    "i5" -> 10
                    "i7" -> 20
                    "i9" -> 30
                    else -> 10
                }
                val modelName = "Intel Core ${tier.uppercase()}-$numStr${if (q.contains("kf", true)) "KF" else if (q.contains(" f", true) || q.endsWith("f")) "F" else if (q.contains(" k", true) || q.endsWith("k")) "K" else ""}"
                return GuessEntry(
                    modelName, "Intel", "CPU",
                    (75 + tierScore + series / 50),
                    (85 + (gen - 10).coerceAtLeast(0)).coerceAtMost(98),
                    listOf(
                        "${cores.first} Cores / ${cores.second} Threads",
                        boost,
                        "$tdp W TDP",
                        "Socket $socket"
                    )
                ).let { toDetail(it) }
            }
            hasRyzen && !hasRadeon -> {
                val tierRx = """ryzen\s*([3579])\s*(\d)\s*(\d{3})[xgtfd\s]*""".toRegex(RegexOption.IGNORE_CASE).find(q)
                if (tierRx != null) {
                    val tier = tierRx.groupValues[1].toInt()
                    val gen = tierRx.groupValues[2].toInt()
                    val series = tierRx.groupValues[3].toInt()
                    val socket = if (gen <= 5) "AM4" else "AM5"
                    val cores = when (tier) {
                        3 -> Pair(4, 8)
                        5 -> Pair(6, 12)
                        7 -> Pair(8, 16)
                        9 -> when {
                            series >= 950 -> Pair(16, 32)
                            series >= 900 -> Pair(12, 24)
                            else -> Pair(8, 16)
                        }
                        else -> Pair(6, 12)
                    }
                    val tdp = when {
                        q.contains("x3d", true) -> if (gen >= 7) 120 else 105
                        q.endsWith("x") || q.contains(" x", true) -> if (gen >= 7) 105 else 105
                        tier == 9 && gen >= 7 -> 170
                        tier == 9 && gen >= 9 -> 170
                        else -> 65
                    }
                    val boost = when (tier) {
                        3 -> "3.9 GHz Boost"
                        5 -> when (gen) {
                            5 -> if (series >= 600) "4.4 GHz Boost" else "4.2 GHz Boost"
                            7 -> if (series >= 600) "5.3 GHz Boost" else "5.0 GHz Boost"
                            8 -> if (series >= 600) "5.0 GHz Boost" else "4.9 GHz Boost"
                            9 -> if (series >= 600) "5.4 GHz Boost" else "5.1 GHz Boost"
                            else -> "4.2 GHz Boost"
                        }
                        7 -> when (gen) {
                            5 -> if (q.contains("x3d", true)) "4.5 GHz Boost" else "4.7 GHz Boost"
                            7 -> if (q.contains("x3d", true)) "5.0 GHz Boost" else "5.4 GHz Boost"
                            8 -> if (q.contains("x3d", true)) "5.1 GHz Boost" else "5.1 GHz Boost"
                            9 -> if (q.contains("x3d", true)) "5.2 GHz Boost" else "5.5 GHz Boost"
                            else -> "4.5 GHz Boost"
                        }
                        9 -> when (gen) {
                            5 -> "4.9 GHz Boost"
                            7 -> if (series >= 950) "5.7 GHz Boost" else "5.6 GHz Boost"
                            8 -> "5.4 GHz Boost"
                            9 -> if (series >= 950) "5.7 GHz Boost" else "5.6 GHz Boost"
                            else -> "4.7 GHz Boost"
                        }
                        else -> "4.2 GHz Boost"
                    }
                    val suffix = buildString {
                        if (q.contains("x3d", true)) append("X3D")
                        else if (q.endsWith("x") || q.contains(" x", true)) append("X")
                        if (q.contains("g", true) && tier in listOf(5,7) && gen <= 8) append("G")
                        if (q.contains("f", true) && gen >= 7) append("F")
                    }
                    val modelName = "AMD Ryzen $tier ${gen}${String.format("%03d", series)}$suffix"
                    return GuessEntry(
                        modelName, "AMD", "CPU",
                        (78 + (tier - 3) * 4 + gen * 2).coerceAtMost(99),
                        (88 + gen).coerceAtMost(98),
                        listOf(
                            "${cores.first} Cores / ${cores.second} Threads",
                            boost,
                            "${tdp}W TDP",
                            "Socket $socket"
                        )
                    ).let { toDetail(it) }
                }
            }
            hasNvidiaGpu -> {
                val rtxMatch = """(?:geforce\s*)?rtx\s*(\d{4})\s*(ti|super|d|gt\s*|\s)?(\s*\d\s*gb)?\s*""".toRegex(RegexOption.IGNORE_CASE).find(q)
                if (rtxMatch != null) {
                    val model = rtxMatch.groupValues[1].toInt()
                    val gen = model / 1000
                    val modelNum = model % 1000
                    val variant = rtxMatch.groupValues[2].trim().lowercase()
                    val memGb = """(\d+)\s*gb""".toRegex(RegexOption.IGNORE_CASE).find(q)?.groupValues?.get(1)?.toIntOrNull()
                    val (vram, memType) = when (gen) {
                        20 -> Pair(memGb ?: 6, "GDDR6")
                        30 -> when {
                            modelNum >= 90 -> Pair(memGb ?: 24, "GDDR6X")
                            modelNum >= 80 -> Pair(memGb ?: (if (modelNum == 80 && variant == "12") 12 else 10), "GDDR6X")
                            modelNum == 70 && variant == "ti" -> Pair(memGb ?: 8, "GDDR6X")
                            modelNum == 60 && variant == "ti" && q.contains("gddr6x", true) -> Pair(memGb ?: 8, "GDDR6X")
                            else -> Pair(memGb ?: (if (modelNum == 60) 12 else 8), "GDDR6")
                        }
                        40 -> when {
                            modelNum >= 90 -> Pair(memGb ?: 24, "GDDR6X")
                            modelNum >= 80 -> Pair(memGb ?: 16, "GDDR6X")
                            modelNum >= 70 -> Pair(memGb ?: (if (variant == "ti super") 16 else 12), "GDDR6X")
                            modelNum == 60 -> Pair(memGb ?: (if (variant == "ti" || variant == "ti super") 8 else 8), "GDDR6")
                            else -> Pair(memGb ?: 12, "GDDR6")
                        }
                        50 -> when {
                            modelNum >= 90 -> Pair(memGb ?: 32, "GDDR7")
                            modelNum >= 80 -> Pair(memGb ?: 16, "GDDR7")
                            modelNum >= 70 -> Pair(memGb ?: (if (variant == "ti") 16 else 12), "GDDR7")
                            modelNum == 60 -> Pair(memGb ?: 8, "GDDR7")
                            else -> Pair(memGb ?: 12, "GDDR7")
                        }
                        else -> Pair(memGb ?: 8, "GDDR6")
                    }
                    val pcie = when {
                        gen >= 50 -> "PCIe 5.0 x16"
                        gen >= 40 && modelNum <= 60 -> "PCIe 4.0 x8"
                        gen >= 30 -> "PCIe 4.0 x16"
                        else -> "PCIe 3.0 x16"
                    }
                    val tdp = when (gen) {
                        20 -> when (modelNum) {
                            60 -> 160; 70 -> 175; 80 -> 215; else -> 160
                        }
                        30 -> when {
                            modelNum >= 90 -> if (modelNum == 95) 450 else 350
                            modelNum >= 80 -> if (modelNum == 85) 350 else 320
                            modelNum == 70 -> if (variant == "ti") 290 else 220
                            modelNum == 60 -> if (variant == "ti") 200 else 170
                            modelNum == 50 -> 130
                            else -> 170
                        }
                        40 -> when {
                            modelNum >= 90 -> 450
                            modelNum >= 80 -> 320
                            modelNum == 70 -> if (variant == "ti") 285 else 200
                            modelNum == 60 -> if (variant == "ti") 160 else 115
                            else -> 200
                        }
                        50 -> when {
                            modelNum >= 90 -> 575
                            modelNum >= 80 -> 360
                            modelNum == 70 -> if (variant == "ti") 300 else 250
                            modelNum == 60 -> 170
                            else -> 300
                        }
                        else -> 180
                    }
                    val boost = when (gen) {
                        20 -> "1680 MHz Boost"
                        30 -> when (modelNum) {
                            in 50..60 -> "1777 MHz Boost"
                            70 -> if (variant == "ti") "1770 MHz Boost" else "1725 MHz Boost"
                            80 -> "1710 MHz Boost"; 90 -> "1695 MHz Boost"; else -> "1700 MHz Boost"
                        }
                        40 -> when (modelNum) {
                            60 -> if (variant == "ti") "2535 MHz Boost" else "2460 MHz Boost"
                            70 -> if (variant == "ti") "2610 MHz Boost" else "2475 MHz Boost"
                            80 -> "2505 MHz Boost"; 90 -> "2520 MHz Boost"; else -> "2500 MHz Boost"
                        }
                        50 -> when (modelNum) {
                            60 -> "2475 MHz Boost"
                            70 -> "2700 MHz Boost"
                            80 -> "2625 MHz Boost"
                            90 -> "2650 MHz Boost"
                            else -> "2550 MHz Boost"
                        }
                        else -> "1700 MHz Boost"
                    }
                    val name = "NVIDIA GeForce RTX $model${variant.takeIf { it.isNotEmpty() }?.let { " ${it.uppercase()}" } ?: ""}${vram.let { " $it GB" }}".replace("GB GDDR", "GB").replace("   ", " ")
                    return GuessEntry(
                        name, "NVIDIA", "GPU",
                        (75 + (gen - 20) * 6 + modelNum / 20).coerceIn(70..99),
                        (90 + gen * 2).coerceAtMost(99),
                        listOf("$vram GB $memType", boost, "${tdp}W TDP", pcie)
                    ).let { toDetail(it) }
                }
            }
            (hasRadeon || (hasRyzen && q.contains("rx"))) -> {
                val rxMatch = """rx\s*(\d{4})\s*(xt)?\s*(\s*\d\s*gb)?\s*""".toRegex(RegexOption.IGNORE_CASE).find(q)
                if (rxMatch != null) {
                    val model = rxMatch.groupValues[1].toInt()
                    val gen = model / 1000
                    val series = model % 1000
                    val vram = """(\d+)\s*gb""".toRegex(RegexOption.IGNORE_CASE).find(q)?.groupValues?.get(1)?.toIntOrNull() ?: when {
                        series <= 599 -> if (series >= 50) 8 else 4
                        series <= 650 -> 8
                        series <= 699 -> 12
                        series <= 799 -> 12
                        series <= 899 -> 16
                        else -> 24
                    }
                    val memType = if (gen >= 8) "GDDR7" else if (gen == 9) "GDDR7" else "GDDR6"
                    val tdp = when (gen) {
                        5 -> when {
                            series < 550 -> 130
                            series < 570 -> 150
                            series < 580 -> 180
                            else -> 225
                        }
                        6 -> when {
                            series < 650 -> 107
                            series < 660 -> 132
                            series < 670 -> 160
                            series < 680 -> 230
                            series < 690 -> 250
                            else -> 300
                        }
                        7 -> when {
                            series < 760 -> 120
                            series < 770 -> 165
                            series < 780 -> 245
                            series < 790 -> 263
                            series < 795 -> 315
                            else -> 355
                        }
                        8 -> when {
                            series < 860 -> 200
                            series < 880 -> 260
                            series < 890 -> 295
                            else -> 410
                        }
                        9 -> when {
                            series < 960 -> 175
                            series < 970 -> 260
                            series < 975 -> 320
                            else -> 360
                        }
                        else -> 180
                    }
                    val boost = when (gen) {
                        5 -> "1905 MHz Boost"; 6 -> "2491 MHz Boost"
                        7 -> "2500 MHz Boost"; 8 -> "2700 MHz Boost"
                        9 -> "2800 MHz Boost"; else -> "2200 MHz Boost"
                    }
                    val pcie = if (gen >= 8) "PCIe 5.0 x16" else "PCIe 4.0 x16"
                    return GuessEntry(
                        "AMD Radeon RX $model${if (rxMatch.groupValues[2].isNotBlank()) " XT" else ""} $vram GB",
                        "AMD", "GPU",
                        (70 + (gen - 5) * 8 + series / 40).coerceIn(70..98),
                        (88 + gen * 2).coerceAtMost(98),
                        listOf("$vram GB $memType", boost, "${tdp}W TDP", pcie)
                    ).let { toDetail(it) }
                }
            }
            hasPsu -> {
                val wattMatch = Regex("""(\d{3,4})\s*w(?:atts?)?\b""", RegexOption.IGNORE_CASE).find(q)
                val watts = wattMatch?.groupValues?.get(1)?.toIntOrNull() ?: when {
                    q.contains("rm650", true) || q.contains("650w", true) -> 650
                    q.contains("rm750", true) || q.contains("750w", true) || q.contains("gx 750", true) -> 750
                    q.contains("rm850", true) || q.contains("850w", true) || q.contains("px 850", true) || q.contains("strix 850", true) -> 850
                    q.contains("rm1000", true) || q.contains("1000w", true) -> 1000
                    else -> 750
                }
                val cert = when {
                    q.contains("titanium", true) -> "80+ Titanium"
                    q.contains("platinum", true) -> "80+ Platinum"
                    q.contains("gold", true) -> "80+ Gold"
                    q.contains("silver", true) -> "80+ Silver"
                    q.contains("bronze", true) -> "80+ Bronze"
                    else -> "80+ Gold"
                }
                val modular = when {
                    q.contains("full modular", true) -> "Full Modular"
                    q.contains("semi modular", true) || q.contains("semi-modular", true) -> "Semi-Modular"
                    q.contains("non modular", true) || q.contains("non-modular", true) -> "Non-Modular"
                    watts <= 650 && cert == "80+ Bronze" -> "Non-Modular"
                    else -> "Full Modular"
                }
                val brand = when {
                    q.contains("corsair", true) -> "Corsair"
                    q.contains("seasonic", true) -> "Seasonic"
                    q.contains("asus", true) -> "ASUS"
                    q.contains("evga", true) -> "EVGA"
                    q.contains("be quiet", true) -> "be quiet!"
                    q.contains("nzxt", true) -> "NZXT"
                    q.contains("cooler master", true) -> "Cooler Master"
                    q.contains("thermaltake", true) -> "Thermaltake"
                    q.contains("deepcool", true) -> "DeepCool"
                    q.contains("xfx", true) -> "XFX"
                    else -> "Generic"
                }
                val psuScore = when {
                    watts >= 1000 -> 93
                    watts >= 850 -> 88
                    watts >= 750 -> 85
                    watts >= 650 -> 80
                    else -> 72
                }
                val queryTitle = q.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                val name = if (q.contains("rm", true) || q.contains("prime", true) || q.contains("focus", true) || q.contains("rog", true)) {
                    "$brand $watts W $cert $modular PSU".replace(" PSU PSU", " PSU")
                } else {
                    "$brand $queryTitle $cert PSU"
                }
                return GuessEntry(
                    name.trim().replace("  ", " "), brand, "PSU", psuScore, 92,
                    listOf("$watts Watts", "$cert Certified", "$modular", "ATX 3.0 Ready", "ATX Form Factor", "5-10 Year Warranty")
                ).let { toDetail(it) }
            }
            hasRam -> {
                val ddr = when {
                    q.contains("ddr5", true) -> "DDR5"
                    q.contains("ddr3", true) -> "DDR3"
                    else -> "DDR4"
                }
                val capMatch = Regex("""(\d{1,2})\s*gb\b""", RegexOption.IGNORE_CASE).findAll(q).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
                val totalCap = if (capMatch.size >= 2) capMatch.sum() else capMatch.firstOrNull() ?: when (ddr) {
                    "DDR5" -> 32
                    else -> 16
                }
                val speedMatch = Regex("""(?:$ddr[-\s]?)(\d{3,4})""", RegexOption.IGNORE_CASE).find(q)
                val speed = speedMatch?.groupValues?.get(1)?.toIntOrNull() ?: when (ddr) {
                    "DDR5" -> 6000
                    "DDR4" -> 3600
                    else -> 3200
                }
                val brand = when {
                    q.contains("corsair", true) || q.contains("vengeance", true) || q.contains("dominator", true) -> "Corsair"
                    q.contains("gskill", true) || q.contains("g.skill", true) || q.contains("trident", true) || q.contains("ripjaws", true) -> "G.SKILL"
                    q.contains("kingston", true) || q.contains("fury beast", true) -> "Kingston"
                    q.contains("teamgroup", true) || q.contains("team group", true) || q.contains("t force", true) || q.contains("t-force", true) -> "TeamGroup"
                    q.contains("adata", true) || q.contains("xpg", true) || q.contains("lancer", true) -> "ADATA"
                    q.contains("patriot", true) || q.contains("viper", true) -> "Patriot"
                    else -> "Generic"
                }
                val ramScore = when {
                    totalCap >= 64 -> 92
                    totalCap >= 32 -> 86
                    totalCap >= 16 -> 80
                    else -> 72
                }
                val sticks = if (totalCap == 16) "2x8GB" else if (totalCap == 32) "2x16GB" else if (totalCap == 64) "2x32GB" else "${totalCap}GB"
                val cl = when (ddr) {
                    "DDR5" -> if (speed >= 6400) "CL32" else if (speed >= 6000) "CL30" else "CL36"
                    else -> if (speed >= 3600) "CL18" else "CL16"
                }
                return GuessEntry(
                    "$brand $totalCap GB (${sticks}) $ddr-$speed", brand, "RAM", ramScore, 93,
                    listOf("$totalCap GB ($sticks Kit)", "$ddr-$speed MHz", "$cl Latency", "1.35V", "288-Pin UDIMM", ddr, if (ddr=="DDR5") "AMD EXPO / Intel XMP 3.0" else "Intel XMP 2.0")
                ).let { toDetail(it) }
            }
            hasStorage -> {
                val pcieVer = when {
                    q.contains("pcie 5", true) || q.contains("pcie5", true) || q.contains("gen 5", true) || q.contains("t700", true) -> "PCIe 5.0"
                    else -> "PCIe 4.0"
                }
                val isHdd = q.contains("hdd", true) || q.contains("barracuda", true) && !q.contains("nvme", true) && !q.contains("ssd", true)
                val capMatch = Regex("""(\d{1,3})\s*(tb|gb)\b""", RegexOption.IGNORE_CASE).find(q)
                val capacity = if (capMatch != null) {
                    val num = capMatch.groupValues[1].toIntOrNull() ?: 1
                    val unit = capMatch.groupValues[2].uppercase()
                    "$num $unit"
                } else if (q.contains("8tb", true)) "8 TB" else if (q.contains("4tb", true)) "4 TB" else "2 TB"
                val type = when {
                    isHdd -> "3.5 SATA HDD"
                    else -> "NVMe M.2 2280 SSD"
                }
                val brand = when {
                    q.contains("samsung", true) -> "Samsung"
                    q.contains("wd", true) || q.contains("western digital", true) || q.contains("black sn", true) -> "Western Digital"
                    q.contains("crucial", true) -> "Crucial"
                    q.contains("kingston", true) -> "Kingston"
                    q.contains("sabrent", true) -> "Sabrent"
                    q.contains("adata", true) || q.contains("legend", true) -> "ADATA"
                    q.contains("seagate", true) || q.contains("firecuda", true) || q.contains("barracuda", true) -> "Seagate"
                    q.contains("lexar", true) -> "Lexar"
                    q.contains("teamgroup", true) || q.contains("team group", true) -> "TeamGroup"
                    q.contains("toshiba", true) -> "Toshiba"
                    else -> "Generic"
                }
                val read = when (pcieVer) {
                    "PCIe 5.0" -> "12400 MB/s Read"
                    else -> "7400 MB/s Read"
                }
                val write = when (pcieVer) {
                    "PCIe 5.0" -> "11800 MB/s Write"
                    else -> "6500 MB/s Write"
                }
                val storScore = when {
                    capacity.contains("8") -> 93
                    capacity.contains("4") -> 90
                    capacity.contains("2") -> 86
                    else -> 80
                }
                return GuessEntry(
                    "$brand $capacity $pcieVer NVMe SSD".replace("NVMe NVMe", "NVMe").replace("  ", " "), brand, "STORAGE", storScore, 93,
                    listOf("$capacity Capacity", type, "$pcieVer x4", read, write, if (isHdd) "SATA HDD" else "NVMe SSD")
                ).let { toDetail(it) }
            }
            hasMobo -> {
                val socket = when {
                    q.contains("am5", true) || q.contains("b650", true) || q.contains("x670", true) || q.contains("x870", true) || q.contains("a620", true) -> "Socket AM5"
                    q.contains("am4", true) || q.contains("b450", true) || q.contains("b550", true) || q.contains("x570", true) || q.contains("x470", true) || q.contains("a520", true) -> "Socket AM4"
                    q.contains("lga1851", true) || q.contains("z890", true) || q.contains("b860", true) -> "Socket LGA1851"
                    q.contains("lga1700", true) || q.contains("z790", true) || q.contains("b760", true) || q.contains("z690", true) || q.contains("b660", true) || q.contains("h610", true) || q.contains("h770", true) || q.contains("h810", true) -> "Socket LGA1700"
                    q.contains("lga1200", true) || q.contains("z590", true) || q.contains("b560", true) || q.contains("h510", true) -> "Socket LGA1200"
                    q.contains("lga1151", true) -> "Socket LGA1151 v2"
                    else -> "Socket AM4"
                }
                val ddr = when (socket) {
                    "Socket AM5", "Socket LGA1700", "Socket LGA1851" -> "DDR5"
                    else -> "DDR4"
                }
                val brand = when {
                    q.contains("msi", true) || q.contains("mag", true) || q.contains("mpg", true) || q.contains("meg", true) -> "MSI"
                    q.contains("asus", true) || q.contains("rog", true) || q.contains("strix", true) || q.contains("tuf", true) || q.contains("prime", true) -> "ASUS"
                    q.contains("asrock", true) || q.contains("steel legend", true) || q.contains("pro4", true) || q.contains("pro rs", true) -> "ASRock"
                    q.contains("gigabyte", true) || q.contains("aorus", true) -> "GIGABYTE"
                    q.contains("biostar", true) -> "BIOSTAR"
                    else -> "Generic"
                }
                val form = when {
                    q.contains("micro atx", true) || q.contains("matx", true) || q.contains("-m ", true) || q.contains(" m a", true) -> "Micro-ATX"
                    q.contains("mini itx", true) || q.contains("itx", true) -> "Mini-ITX"
                    q.contains("e-atx", true) || q.contains("eatx", true) -> "E-ATX"
                    else -> "ATX"
                }
                val chipset = listOf("b450","b550","x570","x470","a520","a620","b650","x670","x870","b760","z790","z690","b660","h610","h770","z590","h510","b560","z890","b860","h810").firstOrNull { chip -> q.contains(chip, true) }?.uppercase() ?: when (socket) {
                    "Socket AM4" -> "B550"
                    "Socket AM5" -> "B650"
                    "Socket LGA1700" -> "Z790"
                    "Socket LGA1200" -> "Z590"
                    "Socket LGA1851" -> "Z890"
                    else -> "B550"
                }
                val moboScore = when {
                    socket.contains("1851") -> 94
                    socket.contains("AM5") || socket.contains("1700") -> 90
                    socket.contains("AM4") || socket.contains("1200") -> 82
                    else -> 78
                }
                return GuessEntry(
                    "$brand $chipset ${socket.replace("Socket ","")} ($form $ddr)".replace("  "," "), brand, "MOTHERBOARD", moboScore, 93,
                    listOf(socket, "$chipset Chipset", "4x $ddr DIMM", "2x M.2 NVMe ${if (ddr=="DDR5") "PCIe 5.0" else "PCIe 4.0"}", "$form Form Factor", if (socket!="Socket LGA1200" && socket!="LGA1151 v2") "WiFi 6" else "Gigabit LAN")
                ).let { toDetail(it) }
            }
        }
        return null
    }
}
