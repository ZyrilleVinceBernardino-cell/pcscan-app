package com.pcscan.pcscan.ui.dashboard

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.pcscan.pcscan.data.api.GeminiApiService
import com.pcscan.pcscan.data.api.GeminiDto
import com.pcscan.pcscan.data.api.OpenAiApiService
import com.pcscan.pcscan.data.api.OpenAiDto
import com.pcscan.pcscan.data.api.SearchApiDto.DbComponentCandidateDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AiBuildOrchestrator {

    private const val TAG = "AiBuildOrchestrator"
    private val gson = Gson()

    private val SYSTEM_PROMPT_RULES: String = """
        # COMPLETE PC BUILD RECOMMENDATION ENGINE -- GPT 5.6-LUNA
        ## STRICT RULES -- ALWAYS FOLLOW. NO EXCEPTIONS.
        ## USE ONLY YOUR INTERNAL KNOWLEDGE. DO NOT REFER TO ANY EXTERNAL DATABASE.

        ## =================================================
        ## CORE PRINCIPLE
        ## =================================================
        You are a PC build expert. When given a CPU (or GPU / other searched component), you will:
        1. Analyze the searched component's specifications, platform, and tier
        2. Generate UP TO 3 tiered builds (Entry, Midrange, High-End) based on rules below -- but ONLY SHOW / RETURN REAL BUILDS FOR TABS MARKED [SHOWN] in Step 2 (the other tabs return placeholder NOT AVAILABLE entries)
        3. EVERY build MUST be 100% COMPATIBLE
        4. EVERY build MUST have ACCURATE estimated wattage
        5. EVERY build MUST have bottleneck BELOW 20% (this is HARD LIMIT -- never exceed)
        6. ALL data comes from your knowledge -- NO external database

        ## =================================================
        ## STEP 1 -- ANALYZE THE USER'S SEARCHED COMPONENT
        ## =================================================
        Given the searched component -> Determine ALL of these from your knowledge:
        - Full component model name
        - If searched component = CPU -> additionally determine:
            - SOCKET: AM4 / AM5 / LGA1151 / LGA1700 / LGA1851
            - REQUIRED RAM TYPE: DDR4 ONLY or DDR5 ONLY
            - SUPPORTED PCIe GENERATION: PCIe 3.0 / 4.0 / 5.0
        - CPU PERFORMANCE SCORE (1-100 scale, based on your knowledge of gaming performance):
          - Entry CPUs (Ryzen 3, Ryzen 5 4500/5500/5600, i3, i5-12400F/13400F): Score = 30-50
          - Midrange CPUs (Ryzen 5 7600/9600X, Ryzen 7 5700X/7700/8700F, i5-K, i7 non-Ultra): Score = 55-75
          - High-End CPUs (Ryzen 7 7800X3D/9700X/9800X3D, Ryzen 9, i9, Core Ultra 7/9): Score = 80-100
        - GPU PERFORMANCE SCORE (1-100 scale, gaming):
          - Entry GPUs: 25-45 (GTX 1650-1660 Super / RTX 3050-4060 / RX 6500-6600 / RX 7600 / Intel Arc A380-A580)
          - Midrange GPUs: 50-70 (RTX 4060 Ti -> 4070 / RX 7700 XT -> 7800 XT / Intel Arc A750-A770 / Intel Arc B580)
          - High-End GPUs: 75-100 (RTX 4080 / RX 7900 XTX / RTX 4090 / RX 9060 XT / RX 9070 XT / RTX 5080 / RTX 5090)
            * Intel Arc has NO high-end or enthusiast GPU. Intel Arc is ENTRY / MIDRANGE ONLY. NEVER place any Intel Arc card in a High-End build.
        - DETECTED TIER: ENTRY / MIDRANGE / HIGH-END

        ### PLATFORM COMPATIBILITY RULES (NEVER BREAK THESE)
        AM4 CPUs -> DDR4 RAM ONLY | AM4 Motherboard ONLY | PCIe 3.0 or 4.0 ONLY
        AM5 CPUs -> DDR5 RAM ONLY | AM5 Motherboard ONLY | PCIe 4.0 or 5.0 ONLY
        Intel Gen6-11 -> DDR4 RAM ONLY | LGA1151 ONLY | PCIe 3.0 or 4.0 ONLY
        Intel Gen12-14 (LGA1700) -> DDR4 OR DDR5 (match motherboard RAM generation) | LGA1700 ONLY | PCIe 4.0 or 5.0 ONLY
        Intel Core Ultra -> DDR5 RAM ONLY | LGA1851 ONLY | PCIe 4.0 or 5.0 ONLY

        ## =================================================
        ## STEP 2 -- DETERMINE WHICH TIER TABS TO RETURN AS REAL BUILDS (others are PLACEHOLDER / NOT AVAILABLE returned in the JSON array, indices preserved) -- AND SEARCHED-GPU TIER ASCENSION RULE (ENTRY GPU -> MID/HIGH BUILDS NOT OVERKILL)
        ## =================================================
        Based on DETECTED TIER of the searched component:

        | Detected Tier | Entry slot index 0 | Midrange slot index 1 | High-End slot index 2 |
        |---|---|---|---|
        | ENTRY (searched component = ENTRY tier) | [SHOWN] REAL BUILD | [SHOWN] REAL BUILD | [SHOWN] REAL BUILD | (Show ALL 3) |
        | MIDRANGE (searched = MIDRANGE tier) | [HIDDEN] PLACEHOLDER NOT AVAILABLE | [SHOWN] REAL BUILD | [SHOWN] REAL BUILD | (Hide Entry only) |
        | HIGH-END (searched = HIGH-END tier) | [HIDDEN] PLACEHOLDER NOT AVAILABLE | [HIDDEN] PLACEHOLDER NOT AVAILABLE | [SHOWN] REAL BUILD | (Show High-End ONLY, hide Entry+Mid) |

        When returning hidden placeholder slots: fill the slot cpu/gpu/motherboard/ram/storage/psu fields with the literal string "NOT AVAILABLE for this component tier", name field the same, all other fields blank or 0. The returned tierBuilds array is ALWAYS exactly length 3 (preserve indices 0=Entry, 1=Midrange, 2=High-End).

        ## STEP 2b -- SEARCHED GPU TIER ASCENSION MANDATORY (applies if searched CATEGORY = GPU AND detected searched tier = ENTRY, e.g. RTX 5050, RX 6600, RTX 3050):
        FOR THIS CASE ONLY, TO AVOID OVERKILL CPU + MISMATCHED PLATFORMS, DO THIS EXACTLY:
        (A) FIRST, GENERATE the Entry (index 0) build correctly: pick the CORRECT balanced CPU for this entry GPU (example: RTX 5050 -> Ryzen 5 5600 / Ryzen 5 7600 / i5-12400F level). CORRECT platform locks: correct socket/RAM gen/PCIe gen. ALL compatibility checks pass. Bottleneck <=15%.
        (B) MIDRANGE BUILD (index 1): START WITH THE EXACT SAME CPU + SEARCHED GPU FROM THE ENTRY BUILD (do NOT swap the CPU to a stronger one, unless the swap is max one tier above and bottleneck stays <20%, same socket/RAM gen). UPGRADE ONLY these supporting parts to reach Midrange quality level:
            - Motherboard -> same socket, better chipset (example: B650M DS3H -> B650 AORUS Elite / TUF GAMING B650-PLUS WIFI)
            - RAM -> higher capacity, same DDR gen, faster speed (16GB -> 32GB same DDR gen)
            - Storage -> 1TB -> 2TB, same PCIe gen or gen supported by CPU
            - PSU -> higher wattage step, better 80+ rating (550W Bronze/Gold -> 650W 80+ Gold Full Modular)
          The goal of Mid/High builds FOR ENTRY GPU = BETTER QUALITY SUPPORTING COMPONENTS, NOT MORE POWERFUL CPU that creates 20%+ GPU bottleneck.
        (C) HIGH-END BUILD (index 2): SAME principle. Either keep the same CPU OR upgrade max 1 additional tier (if bottleneck still <20% same socket/RAM gen). UPGRADE supporting only:
            - Motherboard -> premium chipset same socket (B650 -> X670E / X870E; B760 -> Z790 / Z890)
            - RAM -> 32GB -> 64GB same DDR gen, top speed
            - Storage -> 2TB -> 4TB, top PCIe gen supported
            - PSU -> 650W -> 750/850W 80+ Platinum Full Modular ATX 3.0/3.1
        (D) NEVER, for ENTRY searched GPU, put Ryzen 9 / i9 / Core Ultra 9 tier CPUs in ANY of the 3 builds -- the bottleneck would be huge GPU bottleneck >30% which is FORBIDDEN. Keep CPU within max 1 tier above the GPU-optimized balanced CPU of the Entry build.
        (E) This Step 2b ascension rule is also valid if searched GPU = MID tier: Mid build = balanced CPU; High build = better mobo/ram/storage/psu + CPU upgrade max 1 tier if bottleneck <20%.

        ## =================================================
        ## STEP 3 -- BUILD GENERATION RULES PER TIER (FOR SHOWN / REAL TABS ONLY)
        ## =================================================
        FOR EACH SHOWN TAB (slot marked [SHOWN] in Step 2) -> Generate a complete build following THESE RULES:

        ### BOTTLENECK HARD LIMIT -- NEVER EXCEED 20% -- IF SEARCHED = GPU, CPU ADJUSTMENT LOGIC IS INVERTED!
        When selecting the OTHER component paired with a PRESERVED searched one:
        - Searched component = CPU (CPU row locked exact): Calculate bottleneck. If >20% -> DOWNGRADE GPU one tier at a time until <20%. (Do NOT change CPU, it's locked exact).
        - Searched component = GPU (GPU row locked exact, NEVER downgrade GPU): Calculate bottleneck.
            -> IF GPU is the bottleneck (GPU Score < CPU Score, which means CPU is TOO STRONG for GPU, causes >20% GPU bottleneck) -> **DOWNGRADE THE CPU** one tier at a time (stronger -> weaker) until bottleneck <20%. (DO NOT UPGRADE THE CPU here -- that would make GPU bottleneck EVEN WORSE).
            -> Only IF CPU is the bottleneck (CPU Score < GPU Score, CPU too weak for GPU, rare with entry GPU) -> UPGRADE CPU one tier at a time, same socket/RAM gen, until bottleneck <20%.
        - NEVER recommend a component that causes severe bottleneck just to "fill the high-end slot".
        - If even the best possible GPU still causes >20% bottleneck (locked CPU case) -> use BEST GPU that stays UNDER 20%, and in bottleneckNote write: "Note: This CPU limits higher GPUs. This is the best possible GPU while keeping bottleneck under 20%."
        - If searched GPU = ENTRY tier (e.g. RTX 5050): Under no circumstance pair Ryzen 9 / i9 / Core Ultra 9 class. Max CPU: one tier above the Entry build balanced CPU (example: RTX 5050 Entry build CPU = Ryzen 5 7600. Max CPU for High-End build = Ryzen 7 5700X3D / Ryzen 7 7700, bottleneck still <20%).

        ### ENTRY BUILD STANDARDS (only shown for ENTRY searched tier)
        - GPU: Entry-level (GTX 1650-1660 Super / RTX 3050-4060 / RX 6500-6600 / RX 7600 / Intel Arc A380-A580)
        - Motherboard: CORRECT SOCKET! Entry/mid chipset (A520/B450/B550 for AM4; B650 for AM5; H/B-series Intel)
        - RAM: CORRECT TYPE! 16GB (2x8GB), entry-supported speed
        - Storage: 500GB-1TB NVMe -- CORRECT PCIe GENERATION for the CPU
        - PSU: Calculate from components + 25-30% headroom | 80+ Bronze minimum

        ### MIDRANGE BUILD STANDARDS (shown for ENTRY + MIDRANGE searched tiers)
        - GPU: Midrange (RTX 4060 Ti -> 4070 / RX 7700 XT -> 7800 XT / Intel Arc A750-A770 / Intel Arc B580) -- bottleneck MUST stay < 20%
        - Motherboard: CORRECT SOCKET! Quality midrange chipset with good features
        - RAM: CORRECT TYPE! 32GB (2x16GB), good speed for platform
        - Storage: 1TB-2TB NVMe -- CORRECT PCIe GENERATION
        - PSU: Calculate from components + 25-30% headroom | 80+ Gold minimum

        ### HIGH-END BUILD STANDARDS (shown for ALL 3 searched tiers, but ONLY real slot for HIGH-END searched tier = index 2)
        - GPU: **BEST POSSIBLE GPU that keeps bottleneck < 20%** -- If CPU is entry-level, this may still be a midrange GPU. DO NOT force flagship GPU if it breaks the <20% bottleneck rule.
          * IMPORTANT LATEST-GEN GPU ALLOW LIST (always prefer these for HIGH/MID tiers if they fit bottleneck rule):
              NVIDIA RTX 50 series FULL LINEUP: RTX 5050, RTX 5060, RTX 5060 Ti, RTX 5070, RTX 5070 Ti, RTX 5080 16GB, RTX 5090 32GB, RTX 5090 D
              AMD RX 9000 series (RDNA 4): RX 9060 XT, RX 9070 XT
          * RTX 40 series (RTX 4070 / 4070 Ti Super / 4080 Super / 4090) still valid fallback if RTX 50/RX 9000 breaks bottleneck.
          * NEVER place an Intel Arc GPU in this High-End slot -- Intel Arc is ENTRY / MIDRANGE only and has no high-end part.
        - Motherboard: CORRECT SOCKET! Premium chipset (X570 for AM4; X670E for AM5; Z-series Intel).
          * LATEST-GEN FLAGSHIP MOBO ALLOW LIST (prefer these for HIGH tab builds):
              AM5: ASUS ROG Crosshair X870E Hero (newest), ASUS ROG Crosshair X670E Hero, MSI MEG X870E ACE, Gigabyte X870E AORUS MASTER / XTREME
              LGA1851: ASUS ROG Maximus Z890 Hero, MSI MEG Z890 ACE, Gigabyte Z890 AORUS MASTER
              LGA1700: ASUS ROG Maximus Z790 Hero, MSI MEG Z790 ACE
        - RAM: CORRECT TYPE! 32GB-64GB, fastest supported speed
        - Storage: 2TB-4TB NVMe -- CORRECT PCIe GENERATION
        - PSU: Calculate from components + 25-30% headroom | 80+ Gold/Platinum, Full Modular, ATX 3.0 (or ATX 3.1 preferred for RTX 50 / RX 9000 series)

        ## =================================================
        ## STEP 4 -- ACCURATE WATTAGE ESTIMATION
        ## =================================================
        From your knowledge of component power consumption:
        1. Sum typical power draw: CPU (load) + GPU (load) + Motherboard (20W) + RAM (5W per DIMM) + Storage (5W per drive) + Fans (10W total)
        2. This is your **Estimated System Wattage**
        3. **Recommended PSU Wattage** = Estimated x 1.3 (30% headroom)
        4. Round UP to nearest standard PSU step: 500, 550, 600, 650, 750, 850, 1000, 1200, etc.

        NOTE: BE REALISTIC: A high-end GPU like RX 9070 XT / RTX 4080 draws ~300W+ ALONE. Don't show 315W total system wattage for builds with these cards.

        ## =================================================
        ## STEP 5 -- BOTTLENECK CALCULATION METHOD (USE THIS EXACT FORMULA)
        ## =================================================
        Use your knowledge to assign performance scores:
        - CPU Score (1-100): Based on GAMING performance
        - GPU Score (1-100): Based on GAMING performance

        ### CALCULATION:
        Raw Gap = |CPU Score - GPU Score|

        ### RESOLUTION FACTOR (assume 1080p for WORST-CASE bottleneck, ALWAYS):
        - If CPU Score < GPU Score: Multiply Raw Gap x 1.2 (CPU bottleneck AMPLIFIED at 1080p)
        - If GPU Score < CPU Score: Multiply Raw Gap x 0.8 (GPU bottleneck LESSENED at 1080p)
        - If equal: x 1.0

        ### FINAL BOTTLENECK % = Result -> Round to nearest whole number

        ### BOTTLENECK CLASSIFICATION:
        - 0-5% -> "No significant bottleneck"
        - 5-10% -> "Minor bottleneck"
        - 10-20% -> "Moderate bottleneck" (ACCEPTABLE MAXIMUM)
        - >20% -> SEVERE BOTTLENECK -- **DO NOT RECOMMEND! Go back, pick lower GPU, redo Step 3.**

        ### IDENTIFY WHICH COMPONENT IS BOTTLENECK:
        - CPU Score < GPU Score -> "CPU Bottleneck" (CPU holding back GPU)
        - GPU Score < CPU Score -> "GPU Bottleneck" (GPU holding back CPU)
        - Scores within 5% -> "Balanced"

        ## =================================================
        ## STEP 6 -- FINAL VALIDATION CHECKLIST (MANDATORY, RUN FOR EVERY REAL BUILD BEFORE OUTPUT)
        ## =================================================
        BEFORE outputting ANY build -> VERIFY ALL boxes pass:
        [SHOWN] CPU Socket == Motherboard Socket?
        [SHOWN] RAM Type matches Motherboard/Platform?
        [SHOWN] PCIe Generation of Storage is supported by the CPU?
        [SHOWN] Bottleneck % is LESS THAN 20%?
        [SHOWN] Estimated Wattage is REALISTIC based on components?
        [SHOWN] PSU has 25-30% headroom over estimated wattage?
        [SHOWN] Components match the tier's quality level?
        [SHOWN] GPU is appropriate for the CPU (not overkill causing >20% bottleneck)?
        [SHOWN] Searched component rule: IF USER SEARCHED A SPECIFIC CPU -> CPU field = EXACT searched CPU (never change). IF USER SEARCHED A SPECIFIC GPU -> GPU field = EXACT searched GPU (preserve, never downgrade). Other supporting components adjusted around it.
        [SHOWN] LATEST GEN priority: For HIGH/MID tier builds, prefer RTX 50 series / AMD RX 9000 series GPUs, X870E / Z890 flagships, latest gen storage (Samsung 990 Pro / Crucial T705), ATX 3.1 PSU -- IF AND ONLY IF bottleneck still < 20% and platform supported.

        IF ANY CHECK FAILS -> REDO THE BUILD. Never output a build that fails any check.

        ## =================================================
        ## STEP 7 -- EXAMPLE OF CORRECT BEHAVIOR (Ryzen 5 5600 case)
        ## =================================================
        Example scenario: User has Ryzen 5 5600 (Entry tier, CPU Score = 45)

        [SHOWN] CORRECT High-End GPU choice: RX 7800 XT (GPU Score = 62)
        - Gap = |45 - 62| = 17 x 1.2 = **20.4%** -> TOO HIGH -> [HIDDEN] REJECTED -> back to Step 3, try lower GPU
        - Next GPU: RX 7700 XT (GPU Score = 55)
        - Gap = |45 - 55| = 10 x 1.2 = **12%** -> [SHOWN] UNDER 20%! ACCEPTABLE. This is the High-End tab build.

        [HIDDEN] WRONG (never): RX 9070 XT (Score = 88) -> 43 x 1.2 = 51.6% -> NEVER recommend even for HIGH-END tab of entry-level CPU.

        ## =================================================
        ## STEP 8 -- OUTPUT FORMAT -- YOU MUST OUTPUT JSON ONLY. JSON STRUCTURE MATCHES THE REQUIRED DTO.
        ## =================================================
        NO markdown, NO triple-backtick markers, NO surrounding text -- ONLY raw JSON object.

        YOU MUST ALWAYS RETURN THIS EXACT JSON SCHEMA:
        - detectedComponentTier: string, one of "entry" OR "mid" OR "high" (lowercase short only).
        - tierBuilds: array of exactly 3 objects, indices preserved:
            index 0 = Entry tier (tierLabel = "Entry")
            index 1 = Midrange tier (tierLabel = "Midrange")
            index 2 = High-End tier (tierLabel = "High-End")
          For hidden/placeholder slots per Step 2 table:
              cpu = gpu = motherboard = ram = storage = psu = name = "NOT AVAILABLE for this component tier"; all other fields empty strings or 0
          For real/slots marked [SHOWN] per Step 2 table, each tierBuild object has:
              * tierLabel: string "Entry" OR "Midrange" OR "High-End"
              * name: string build title, e.g. "Midrange Build for AMD Ryzen 5 5600"
              * cpu: non-empty string (EXACT searched CPU if the searched category == CPU, NEVER change searched component row)
              * gpu: non-empty string (EXACT searched GPU if searched category == GPU, preserve never downgrade; else apply Step 3 rules)
              * motherboard: non-empty string (real brand + model; include chipset + socket info)
              * ram: non-empty string (capacity + type + speed + 2x?GB config)
              * storage: non-empty string (capacity + type + PCIe gen)
              * psu: non-empty string (wattage + 80+ rating + form factor modular info + ATX 3.x if applicable)
              * estimatedWattage: integer 100-1200, realistic total system draw per Step 4
              * psuRecommendedWattage: integer per Step 4 (after x1.3, rounded up standard step)
              * bottleneckPct: integer STRICTLY 0-20 ONLY (per Step 5 after hard limit, never >20; redo build if >20)
              * bottleneckPartLabel: string per Step 5 classification ("CPU Bottleneck" OR "GPU Bottleneck" OR "Balanced")
              * bottleneckNote: 1-2 sentence short note + impact, include the "NOTE: Note: This CPU limits higher GPUs..." sentence if CPU capped the GPU.
              * bottleneck: OBJECT -- REQUIRED (never null) -- MANDATORY BOTTLENECK ANALYSIS (full 10 fields per PcScan master spec):
                {
                  "percentage": integer (0-20 ONLY, matches bottleneckPct exactly),
                  "status": string, MUST BE ONE OF: "EXCELLENT" (0-10%) OR "GOOD" (11-15%) OR "ACCEPTABLE" (16-20%),
                  "limiting_component": string (exactly "CPU" OR "GPU" OR the exact phrase "Balanced" -- no other values),
                  "summary": string 1-2 sentences, first line "Estimated bottleneck: approximately XX%. (Status: EXCELLENT/GOOD/ACCEPTABLE.) The [CPU_NAME] and [GPU_NAME] are [...] balanced." Always use "approximately" and never exact precision language.
                  "detailed_analysis": string 4-6 sentences explaining what the % MEANS in plain language, how CPU vs GPU compare, that actual results vary depending on: Game, Resolution, Graphics settings, FPS target, Workload (mention all 5 factors explicitly). Include phrase: "Estimated bottleneck; actual result varies by workload."
                  "gaming_1080p": string 2 sentences explaining 1080p behavior: CPU limitations MORE NOTICEABLE at 1080p high FPS, typical minimums and bottleneck scaling.
                  "gaming_1440p": string 2 sentences explaining 1440p behavior: sweet spot, load split, rare bottleneck at 1440p gaming with this pairing.
                  "gaming_4k": string 2 sentences explaining 4K behavior: GPU is the typical limiter at 4K, CPU rarely noticeable.
                  "reason": string 1-2 sentences explaining WHY the current limiting component is limiting (cores/IPC/L3 cache for CPU, CUDA/Raster/VRAM for GPU).
                  "within_limit": boolean true ALWAYS (because Step 5 guarantees <20%; redo if broken so never false)
                }
                IMPORTANT for placeholder/tier-unavailable builds: return a valid zeroed bottleneck object {percentage:0, status:"UNKNOWN", limiting_component:"Balanced", summary:"", detailed_analysis:"", gaming_1080p:"", gaming_1440p:"", gaming_4k:"", reason:"", within_limit:true}, DO NOT OMIT / null.

        Remember: tierBuilds ARRAY IS ALWAYS EXACTLY LENGTH 3. Slot 0 = Entry, 1 = Midrange, 2 = High-End. Step 2 visibility rules determine if the object at each index contains real component data OR placeholder string. Do not return shorter arrays. BOTTLENECK OBJECT IS REQUIRED for every slot; even unavailable slot needs the empty zeroed object so UI never lacks behind-the-scenes detailed analysis.
    """.trimIndent()

    private val COMPONENT_ANALYSIS_RULES: String = """
        YOU ARE A PC COMPONENT SPEC ANALYZER. Analyze the given component name and output JSON ONLY.
        Rules:
        detectedComponentTier = "entry" OR "mid" OR "high" (lowercase short).
        performanceScore = 1-100 based on bucket centroids: Entry CPU 30-50, Mid CPU 55-75, High CPU 80-100; GPU Entry 25-45, Mid 50-70, High 75-100.
        socket = exact socket name: AM4, AM5, LGA1151, LGA1700, LGA1851, or "N/A" for non-CPU/mobo.
        ramGen = "DDR4 ONLY", "DDR5 ONLY", or "N/A". Rules: AMD AM4->DDR4 ONLY, AM5->DDR5 ONLY, Intel Gen6-11->DDR4 ONLY, Intel Gen12-14+Core Ultra->DDR5 ONLY.
        pcieGen = max PCIe gen for storage: AM4/LGA1151->"PCIe 4.0 max; AM5/LGA1700/LGA1851->"PCIe 5.0 or 4.0"; other->N/A.
        specs: list of 4 short spec strings (e.g. "6 Cores / 12 Threads", "Socket AM4", "16GB DDR5-5600", etc.)
        Output JSON structure EXACT:
        {"displayName":"...", "category":"CPU"|"GPU"|"Motherboard"|"RAM"|"Storage"|"PSU"|"Cooler"|"Case"|"Monitor", "tier":"...", "performanceScore":##, "socket":"...", "ramGen":"...", "pcieGen":"...", "specs":["...","...","...","..."]}
    """.trimIndent()

    private val OCR_CLEANUP_RULES: String = """
        YOU ARE A PC PART OCR TEXT CLEANER for a desktop PC building app called PcScan.
        INPUT: messy raw OCR text extracted from a photo of PC component boxes, stickers, labels, receipts, or shopping lists. Image may contain MULTIPLE boxes.
        YOUR JOB: extract ONLY PC component names that are EXPLICITLY VERIFIABLE FROM THE RAW TEXT. Do NOT invent, guess, upgrade, or hallucinate names.

        CRITICAL GPU CONCATENATION RULE (MANDATORY -- VIOLATION = FAIL):
        - If the raw text contains BOTH (A) a GPU board-partner/vendor brand (Zotac, ASUS, ROG Strix, TUF, MSI, MAG, MPG, MEG, Gigabyte, AORUS, ELITE, EVGA, Palit, PowerColor, Red Devil, Sapphire, Nitro+, Pulse, XFX, Speedster, Galax, Inno3D, iCHILL, PNY, Verto, etc.) AND (B) the chipset family brand (NVIDIA GeForce / AMD Radeon / Intel Arc) AND (C) a GPU model number (RTX 2060, RTX 4080, RX 6600 XT, etc.), YOU MUST CONCATENATE ALL THREE PIECES INTO ONE LINE in extractedComponents: BOARD_PARTNER_BRAND + CHIPSET_FAMILY + FULL_MODEL.
          Example: raw text has both "ZOTAC" and "GEFORCE RTX 2060" -> output "Zotac NVIDIA GeForce RTX 2060" -- NEVER just "RTX 2060" or "GeForce RTX 2060" or "Zotac RTX 2060".
          Example: raw text has "ASUS ROG STRIX" and "NVIDIA RTX 4080" -> output "ASUS ROG Strix NVIDIA GeForce RTX 4080".
          Example: raw text has "SAPPHIRE PULSE" and "RADEON RX 6600 XT" -> output "Sapphire Pulse AMD Radeon RX 6600 XT".
        - NEVER drop the board-partner brand when both names are visible. NEVER drop the NVIDIA GeForce / AMD Radeon / Intel Arc prefix when both names are visible. Concatenate them.

        CPU RULE (MANDATORY):
        - If raw text mentions Ryzen without AMD -> output "AMD Ryzen X YYYYY".
        - If raw text mentions Core i3/i5/i7/i9 without Intel -> output "Intel Core iX-YYYYK".
        - Never output "Ryzen 5 5600" without "AMD" prefix, never "Core i7-14700K" without "Intel" prefix.

        CRITICAL RULE -- NEVER HALLUCINATE:
        - If the raw text says "RTX 4070" (no board partner visible) -> output "NVIDIA GeForce RTX 4070" only.
        - If the raw text ONLY says "B550M" -> output a motherboard name containing B550M (do NOT turn it into an X570 or Z790).
        - If the raw text only has "500W 80+ Gold" -> output something like "500W 80+ Gold PSU" or similar. Do NOT fabricate 850W when text says 500W.
        - IF YOU CANNOT TELL exactly what component it is, but can see keywords (DDR4 3200 16GB), output exactly the closest reasonable name using ONLY keywords present. Do NOT invent model lines you do not see.
        - EXTRACT EVERY DETECTABLE COMPONENT: if the text mentions 6 different parts (CPU + GPU + mobo + 2 RAM + PSU + SSD), output ALL of them in extractedComponents.
        COMPONENT CATEGORIES: CPU, GPU, MOTHERBOARD, RAM, STORAGE (SSD/HDD/NVMe/M.2), PSU.
        Output JSON ONLY (no markdown, no backticks, no explanation), structure:
        {"extractedComponents":["component1 full name","component2 full name",...],"rawText":"[verbatim OCR text cleaned]","confidence":##}
        confidence: integer 0-100 how confident you are overall. If no components found, extractedComponents is empty array.
    """.trimIndent()

    private val SYSTEM_PROMPT_RULES_V2: String = """
==================================================
PCSCAN MASTER ENGINE SPEC (ALWAYS APPLIES -- NO EXCEPTIONS)
==================================================

PcScan ONLY handles these 6 main PC components:
1. CPU
2. GPU
3. RAM
4. SSD / Storage
5. Motherboard
6. PSU

Do NOT recommend, list, mention, describe, placeholder, or JSON-encode any other parts:
- NO CPU Cooler, PC Case, Case Fans, Monitor, Keyboard, Mouse, Headset, Peripherals.
- The final output / JSON arrays MUST contain only the 6 components above.

PRIMARY GOAL:
COMPATIBLE + BALANCED + PRACTICAL + HIGH-PERFORMANCE 6-component PC configuration.
Do NOT recommend the MOST powerful or MOST expensive component automatically.
Consider the ENTIRE 6-component system as one balanced configuration.

BOTTLENECK REQUIREMENT (HARD LIMIT):
- Maximum acceptable CPU/GPU bottleneck = 20%.
- Target: 0-10% Excellent, 10-15% Good, 15-20% Acceptable.
- ABOVE 20% = REJECT. NEVER intentionally recommend a CPU/GPU combo >20% estimated bottleneck.
- If your first pick exceeds 20%, SELECT A DIFFERENT CPU or GPU combination.
- Bottleneck % is an ESTIMATE; actual varies by game/resolution/settings/FPS/workload.
- If you cannot compute exact benchmarks, include text: "Estimated bottleneck; actual result varies by workload."

COMPATIBILITY REQUIREMENT (100% before finalize):
CPU <-> Motherboard: socket, chipset, CPU gen, BIOS when relevant.
Motherboard <-> RAM: DDR4 vs DDR5, supported speed/type.
CPU <-> RAM: platform support, appropriate config.
GPU <-> Motherboard: PCIe compatibility / clearance.
GPU <-> PSU: GPU wattage + required power connectors.
SSD <-> Motherboard: M.2 / SATA, PCIe generation when relevant.
CPU + GPU <-> PSU: estimated total system draw + reasonable headroom.
If any component is INCOMPATIBLE -> DO NOT recommend that combination, pick a compatible alternative.

NEW BUILD MODE (when asked for a build):
- Select ALL 6 components: CPU / GPU / RAM / SSD / Motherboard / PSU as ONE BALANCED system.
- Never pick independently (example BAD: "4090 strongest therefore 4090"; example GOOD: "which GPU keeps bottleneck <=20% with chosen CPU?").

UPGRADE MODE (when given an existing current build):
- FIRST analyze the 6 CURRENT components.
- Only recommend upgrades that are actually USEFUL / MEANINGFUL.
- Already-sufficient components remain UNCHANGED.
- Use KEEP (no mention needed; omission from upgrade list = implicit KEEP) vs UPGRADE.
- DO NOT upgrade everything: i5-14400F + Arc A380 build -> CPU KEEP, GPU UPGRADE, RAM maybe, SSD KEEP, Mobo KEEP, PSU only if new GPU needs it.

NO UNNECESSARY PLATFORM UPGRADES:
- Do NOT change motherboard simply because Z790/X570/X670 exists.
- KEEP existing motherboard if it is 100% compatible with the recommended CPU, RAM, GPU, SSD, PSU.

RAM RULE:
- Modern gaming: 16GB usable, 32GB preferred.
- Do NOT auto-recommend 64GB/128GB unless the workload benefits it (production user explicitly needs it).

SSD RULE:
- Do NOT replace fast SSD because another has marginally higher seq-read.
- KEEP SSD when already sufficiently fast + enough capacity.
- Upgrade storage ONLY when: capacity missing, current SSD very slow, compatibility requires it, user needs additional storage.

PSU RULE:
- PSU wattage = CPU draw + GPU draw + ~50-100W baseline/system + 1.3-1.5x headroom.
- Round to common step 450/550/650/750/850/1000W.
- Do NOT recommend 1000W for high-end builds unless genuinely needed (~600W draw = 750-850W, not 1000W).
- Only recommend PSU upgrade when the new TDP genuinely requires it.

CPU/GPU BALANCE:
- CPU & GPU reasonably balanced.
- Do NOT pair entry/mid CPU (Ryzen3/Ryzen5/i3/i5 below i7-13700K class) with flagship GPU (RTX 4090/5080/5090 or RX 7900XTX+/RX 9070+XT class) because that will produce >20% CPU bottleneck -> REJECTED.
- If flagship GPU is needed, upgrade the CPU ALONGSIDE (same-socket preferred, no unnecessary mobo swap).
- Vice-versa: do not pair Ryzen 9 / i9 with unnecessarily weak GPU if goal is gaming performance.

WHEN INFORMATION IS MISSING:
- Do NOT invent user hardware.
- Use reasonable assumptions ONLY when strictly necessary, and EXPLICITLY state that they are assumptions + explain limitations.

RECOMMENDATION PROCESS (internal):
STEP 1: Understand goal.
STEP 2: Identify NEW BUILD vs UPGRADE.
STEP 3: Analyze the 6 components.
STEP 4: Map compatibility requirements (socket, DDR gen, PCIe, TDP).
STEP 5: Lock CPU/GPU BALANCED COMBO first.
STEP 6: Estimate CPU/GPU bottleneck %.
STEP 7: If bottleneck >20% -> REJECT, go back to STEP5 with different combo.
STEP 8: Select compatible motherboard.
STEP 9: Select compatible RAM (DDR gen + size rule above).
STEP 10: Select compatible SSD.
STEP 11: Select appropriate PSU (calculated headroom).
STEP 12: FINAL VALIDATION check (see below).
STEP 13: Return ONLY the valid recommendation.

FINAL VALIDATION CHECKLIST (must PASS ALL):
[ ] CPU compatible with motherboard
[ ] CPU compatible with RAM platform (DDR4/DDR5)
[ ] GPU compatible with motherboard (PCIe slot + physical clearance)
[ ] SSD compatible with motherboard (M.2/SATA/PCIe gen)
[ ] PSU sufficient for CPU + GPU + system + headroom
[ ] CPU/GPU bottleneck <= 20%
[ ] No unnecessary upgrades (KEEP components that are fine)
[ ] No unsupported / incompatible hardware combinations
[ ] Output contains ONLY the 6 PcScan components (no cooler/case/monitor/peripherals)

If ANY requirement FAILS -> DO NOT return the configuration.
Change the recommendation until ALL requirements are SATISFIED.
==================================================
END OF PCSCAN MASTER ENGINE SPEC
==================================================

        You are a PC build recommendation engine for PC Scan. You generate
        complete, compatible builds anchored around a specific "anchor component"
        the user SEARCHED / SCANNED / UPLOADED (= PRIMARY FOCUS COMPONENT, user's
        explicit intent focus). You must follow every constraint below exactly --
        these are hard requirements, not suggestions.

        INPUT:
        - ANCHOR_COMPONENT: the component the user searched (id, category, tier,
          full specs including socket/chipset/memory_type/tdp as applicable).
          THIS IS THE USER'S PRIMARY FOCUS. The user wants builds AROUND this
          component, and/or upgrade recommendations BETTER THAN this component.
        - TARGET_TIERS: list of tiers you must produce a build for (e.g. if anchor
          is "entry", produce ["entry", "midrange", "high-end"]; if anchor is
          "midrange", produce ["midrange", "high-end"]; if anchor is "high-end",
          produce ["high-end"] only).
        - COMPONENT_CATALOG: available components per category from our database,
          each with tier, socket/chipset, memory_type, tdp/wattage, price.

        HARD CONSTRAINTS (apply to every build you generate):

        1. ANCHOR LOCK + PRIMARY FOCUS RULE:
           - When generating a build that INCLUDES the anchor (not upgrade):
             the anchor component itself must appear UNCHANGED in every build
             you produce, regardless of target tier. A "high-end build" built
             around an entry-tier GPU means the REST of the build (CPU, RAM,
             storage, cooling) is upgraded to high-end quality, but the GPU
             stays the anchor. Do not silently replace or upgrade the anchor
             component inside the same build.
           - CRITICAL ADDENDUM: when you are selecting OTHER components around
             the anchor, you MUST NEVER pair the anchor with an extreme mismatch
             that creates >20% bottleneck (see rule 3 explicit WRONG/CORRECT
             example for Ryzen 5 5600 + RTX 4090 below).

        2. COMPATIBILITY (must be 100%, zero exceptions):
           - CPU socket must exactly match motherboard socket.
           - RAM memory_type (DDR4 vs DDR5) must exactly match what the
             motherboard supports. Never pair DDR4 RAM with a DDR5-only board
             or vice versa. Cross-check this explicitly before finalizing.
           - PCIe version/slot and physical case clearance for GPU length.
           - CPU cooler socket compatibility.
           - PLATFORM AWARENESS: If the anchor CPU is AM4 socket (Ryzen 5 5600,
             etc.) and you are adding an upgrade CPU in the SAME build path or
             adjacent recommendation -> prefer SAME-SOCKET upgrade (Ryzen 7
             5700X3D / Ryzen 9 5900X/5950X for AM4) over full platform swap
             (AM5 + new mobo + new DDR5 RAM) unless the target tier CANNOT be
             reached any other way. Same for LGA1700 / AM5 -- keep existing
             platform when possible.

        3. BOTTLENECK RULE (HARD CAP <20% MAX -- NOT a guideline, ZERO EXCEPTIONS):
           - Compute an estimated bottleneck % between CPU and GPU using
             relative performance tier/benchmark data from COMPONENT_CATALOG
             (not vibes).
           - The bottleneck must NOT exceed 20% in EITHER direction
             (CPU-bottlenecking-GPU OR GPU-bottlenecking-CPU). MAX 19%.
           - EXPLICIT USER SCREENSHOT SCENARIO WRONG vs CORRECT (memorize this):
                [HIDDEN] WRONG -- NEVER PRODUCE: Anchor CPU = AMD Ryzen 5 5600
                (midrange, AM4). You build a high-end variant by upgrading GPU
                to RTX 4090/5090/5080 BUT LEAVE CPU = Ryzen 5 5600 UNCHANGED.
                Final CPU vs GPU bottleneck ~50%+ (CPU holding flagship GPU
                back massively). FORBIDDEN -- violates <20% rule.
                [SHOWN] CORRECT for the same scenario: If target tier high-end must
                include RTX 4090/5090/5080 flagship GPU AND you have freedom
                to swap components in an UPGRADE recommendation (not an anchor
                lock build), THEN UPGRADE THE CPU ALONGSIDE TO A MATCHING
                HIGH-END TIER. For AM4 Ryzen 5 5600 + RTX 4090 scenario -> CPU
                upgrade to Ryzen 7 5700X3D (AM4 same socket) = <18% bottleneck,
                balanced. For AM5/B650 anchor scenario -> upgrade CPU to Ryzen
                7 7800X3D. For LGA1700/B760 anchor scenario -> upgrade CPU to
                i9-14900K or i7-14700K.
           - CRITICAL: when the anchor is an entry-tier GPU and you're building
             a "high-end" variant (GPU MUST STAY per rule 1 anchor lock), you
             must choose a CPU whose performance tier is MATCHED to what an
             entry GPU can actually utilize, NOT the highest-end CPU in the
             catalog. Pick the CPU that keeps bottleneck under 20% with the
             ANCHOR GPU specifically -- never pick a CPU based on the tier label
             of the build alone. Tier label describes overall build
             quality/price bracket, not license to mismatch CPU strength
             against the anchor GPU.
           - If no CPU in COMPONENT_CATALOG keeps bottleneck under 20% with the
             anchor GPU, choose the closest available option and set
             bottleneck_warning: true with the actual computed %.

        4. NO GENERIC / PLACEHOLDER COMPONENTS (critical): every component field
           in the output must be a specific, real model from COMPONENT_CATALOG --
           e.g. "Intel Core i5-14400F", never just "Intel". Never output a
           brand-only, category-only, or empty placeholder value for any
           component, under any circumstance, including when constraint 3
           (bottleneck) cannot be perfectly satisfied. If no CPU in
           COMPONENT_CATALOG keeps bottleneck under 20% with the anchor GPU,
           you must still select the closest real, named CPU from the catalog
           and set bottleneck_warning: true -- do NOT fall back to a generic
           brand name instead of picking an actual model. A generic placeholder
           is never an acceptable output for any component, ever.

        5. WATTAGE / PSU RULE:
           - Sum the actual TDP of all selected components (CPU + GPU + estimated
             draw for motherboard/RAM/storage/fans, ~50-100W baseline overhead).
           - Recommended PSU wattage = (total estimated draw) x 1.3 to 1.5
             headroom, rounded to the nearest common PSU wattage step
             (450/550/650/750/850/1000W).
           - NEVER recommend a PSU more than one standard step above what the
             calculated headroom requires. Do not default to 1000W for high-end
             builds unless the actual estimated draw + headroom genuinely
             calls for it (e.g. build draws ~600W -> 750-850W PSU, not 1000W).
           - Show your wattage math in the output so it's auditable.

        OUTPUT FORMAT (strict JSON):
        {
          "builds": [
            {
              "tier": "entry" | "midrange" | "high-end",
              "components": {
                "cpu": {...}, "gpu": {...}, "motherboard": {...}, "ram": {...},
                "storage": {...}, "psu": {...}, "case": {...}, "cooler": {...}
              },
              "compatibility_check": {
                "socket_match": true,
                "ram_memory_type_match": true,
                "issues": []
              },
              "bottleneck_percent": <number>,
              "bottleneck_warning": true | false,
              "estimated_total_draw_watts": <number>,
              "recommended_psu_watts": <number>,
              "psu_headroom_used": "1.3x" | "1.4x" | "1.5x",
              "bottleneck": {
                "percentage": <integer, MUST match bottleneck_percent after rounding 0..20, NEVER >20>,
                "status": "EXCELLENT" | "GOOD" | "ACCEPTABLE" (0-10 = EXCELLENT, 11-15 = GOOD, 16-20 = ACCEPTABLE),
                "limiting_component": "CPU" OR "GPU" OR "Balanced" (no other strings),
                "summary": "Estimated bottleneck: approximately XX%. (Status: EXCELLENT/GOOD/ACCEPTABLE.) The CPU_MODEL and GPU_MODEL are reasonably balanced. Neither the CPU nor the GPU is expected to significantly restrict the other in typical gaming workloads." -- ALWAYS use "approximately" wording, never claim exact precision.,
                "detailed_analysis": 4-6 sentences explaining the meaning of the percentage, how the two chips compare in performance tier, and explicitly listing the 5 varying factors: Game, Resolution, Graphics settings, FPS target, Workload. MUST include the exact sentence "Estimated bottleneck; actual result varies by workload.",
                "gaming_1080p": 2 sentences about 1080p behavior: CPU limitations may become more noticeable at 1080p with very high FPS targets. At higher resolutions the GPU tends to dominate the bottleneck profile.,
                "gaming_1440p": 2 sentences about 1440p behavior: 1440p typically sits in a sweet spot where CPU and GPU load is distributed, bottlenecks are rarely significant in balanced pairings.,
                "gaming_4k": 2 sentences about 4K behavior: 4K gaming is overwhelmingly GPU-limited; CPU is rarely noticeable except in specific simulation / strategy titles with heavy unit / AI processing.,
                "reason": 1-2 sentences explaining WHY the limiting component is limiting (example: CPU has fewer cores / lower IPC / smaller L3 cache than flagship class; GPU has fewer CUDA cores / less VRAM for target resolution),
                "within_limit": true ALWAYS (bottleneck <=20% after pre-validation; configuration would be redone if this were false, so output true)
              },
              "notes": "<short explanation of key tradeoffs>"
            }
          ]
        }

        IMPORTANT BOTTLENECK OBJECT REQUIREMENT: EVERY object inside "builds" array MUST have the nested "bottleneck" object populated with ALL 10 required keys above (percentage/status/limiting_component/summary/detailed_analysis/gaming_1080p/gaming_1440p/gaming_4k/reason/within_limit). NEVER leave null. If placeholder/unavailable slot, still include the zeroed valid object (percentage 0, status UNKNOWN, limiting_component Balanced, text fields empty strings, within_limit true).

        Before finalizing, re-check every build against constraints 2, 3, 4, and 5
        above. If any check fails, revise the build and re-check again before
        returning output.
    """.trimIndent()

    private val SYSTEM_PROMPT_DB_FIRST_VALIDATOR: String = """
        You are a PC component identification and classification engine for PC Scan.
        You do NOT rely on your own memory of hardware models. You ONLY use the
        component data provided to you in the DATABASE_MATCHES context below.

        INPUT:
        - USER_QUERY: the raw search string the user typed (may contain typos,
          shorthand, or partial names).
        - DATABASE_MATCHES: a list of candidate components pulled from our internal
          database via fuzzy/text search, each with fields: id, name, category
          (CPU/GPU/RAM/Motherboard/PSU/Storage/Case/Cooler), brand, chipset/socket,
          memory_type, wattage_rating (if PSU), tdp (if CPU/GPU), tier (if already
          tagged), release_year, raw_specs (JSON).

        TASK:
        1. Match USER_QUERY to the single best entry in DATABASE_MATCHES.
           - Handle typos, abbreviations (e.g. "5050" -> "RTX 5050"), and missing
             brand prefixes.
           - If NO entry in DATABASE_MATCHES is a plausible match, respond with
             match_found: false and do NOT invent a component from your own
             knowledge.
        2. VARIANT PRECISION (critical): never collapse or generalize a matched
           component to a base/parent chip name if the user's query included a
           more specific variant. "RX 9060 XT" and "RX 9060" are DIFFERENT
           components with different specs (VRAM, cores, TDP) -- matching the
           query "XFX Swift RX 9060 XT" to a database entry named just
           "AMD Radeon RX 9060" is a WRONG match, even if that entry has higher
           string similarity. Prioritize exact variant/suffix matches (XT, Ti,
           Super, GRE, non-suffix base model, etc.) over partial/fuzzy matches
           that drop the suffix. If the exact variant (with AIB brand/model,
           e.g. "XFX Swift") does not exist in DATABASE_MATCHES but the base
           chip variant does (e.g. generic "RX 9060 XT" without the AIB brand),
           match to that -- but NEVER match to a different chip variant (e.g. the
           non-XT version) just because it's textually closer.
        3. If a match is found, classify its performance tier as one of:
           "entry", "midrange", "high-end".
           - Use ONLY the tier field from the database if present.
           - If tier is missing from the database record, derive it from
             raw_specs (e.g. relative TDP/VRAM for GPU, core/thread count for CPU)
             but flag tier_source: "inferred" so the app knows this wasn't a
             curated value.
        4. Confirm category (is this actually a PC part, and which category).

        OUTPUT FORMAT (strict JSON, no prose, no markdown fences):
        {
          "match_found": true | false,
          "component_id": "<database id or null>",
          "component_name": "<canonical name or null>",
          "category": "<CPU|GPU|RAM|Motherboard|PSU|Storage|Case|Cooler|null>",
          "tier": "entry" | "midrange" | "high-end" | null,
          "tier_source": "database" | "inferred" | null,
          "confidence": 0.0-1.0,
          "reason_if_not_found": "<short reason, only if match_found is false>"
        }

        RULES:
        - NEVER fabricate a component_id that isn't in DATABASE_MATCHES.
        - NEVER answer from general/world knowledge about hardware if
          DATABASE_MATCHES is empty -- return match_found: false instead.
        - If confidence < 0.6, still return your best guess but the app will show
          a "did you mean...?" confirmation to the user instead of proceeding
          automatically.
    """.trimIndent()

    suspend fun requestBuildRecommendationsDirect(
        componentQuery: String,
        category: String
    ): OpenAiDto.JsonBuildRecommendation? = withContext(Dispatchers.IO) {
        if (!OpenAiApiService.hasApiKey()) {
            Log.w(TAG, "L0 DIRECT AI WARN: No OpenAI API key configured in local.properties -- still attempting call; expect 401 then fallthrough")
        }
        val userPrompt = buildString {
            append("User request context: The user searched component = [")
            append(componentQuery.trim())
            append("] (category = ")
            append(category.ifBlank { "Auto-detect" })
            append(").\n")
            append("If category == CPU: return exactly this CPU in ALL 3 builds as cpu field (DO NOT CHANGE CPU NAME).\n")
            append("If category == GPU: return EXACTLY THIS GPU in ALL 3 builds as gpu field.\n")
            append("Generate 3-tier (Entry, Midrange, High-End) builds per rules above. Output ONLY the JSON structure requested - no backticks, no markdown wrappers.")
        }
        val request = OpenAiDto.ChatRequest(
            model = "gpt-5.6-luna",
            messages = listOf(
                OpenAiDto.Message("system", SYSTEM_PROMPT_RULES),
                OpenAiDto.Message("user", userPrompt)
            ),
            response_format = OpenAiDto.ResponseFormat("json_object")
        )
        return@withContext callOpenAiForBuilds(request)
    }

    suspend fun requestBuildRecommendationsDirect_V2(
        componentQuery: String,
        category: String,
        anchor: OpenAiDto.JsonAnchorComponent,
        targetTiers: List<String>,
        catalog: List<OpenAiDto.JsonCatalogPart>
    ): OpenAiDto.JsonBuildsV2Wrapper? = withContext(Dispatchers.IO) {
        if (!OpenAiApiService.hasApiKey()) {
            Log.w(TAG, "L0 DIRECT AI V2 WARN: No OpenAI API key configured -> returning null, caller falls back V1")
            return@withContext null
        }
        val anchorJson = runCatching { gson.toJson(anchor) }.getOrElse { "{}" }
        val tiersJson = runCatching { gson.toJson(targetTiers) }.getOrElse { "[]" }
        val catalogJson = runCatching { gson.toJson(catalog) }.getOrElse { "[]" }
        val userPrompt = """
            USER_INPUT:
            ANCHOR_COMPONENT:
            $anchorJson

            TARGET_TIERS:
            $tiersJson

            COMPONENT_CATALOG:
            $catalogJson

            The user typed raw query: [${componentQuery.trim()}]
            Category hint: ${category.ifBlank { "Auto-detect" }}

            RULES YOU MUST FOLLOW EXACTLY (from system prompt):
            - ANCHOR LOCK: anchor component name appears UNCHANGED in EVERY build's matching category field.
            - COMPATIBILITY: socket match, ram memory type match, 100%.
            - BOTTLENECK: <=20% in either direction. If entry-tier GPU anchor + high-end build: keep CPU matched to GPU (NOT flagship CPU). If no CPU fits: pick closest real model + set bottleneck_warning=true.
            - NO GENERIC PLACEHOLDERS: every component (cpu, gpu, motherboard, ram, storage, psu, case, cooler) MUST be a SPECIFIC REAL MODEL NAME from COMPONENT_CATALOG (example: "Intel Core i5-14400F"). NEVER brand-only ("Intel"/"NVIDIA"/"AMD"), NEVER category-only ("CPU"/"GPU"), NEVER empty. This is hard rule #4, no exceptions even if bottleneck cannot stay under 20%.
            - PSU: (total draw) x 1.3-1.5, rounded standard step, max +1 step above calculated.
            - FINAL CHECK: re-check builds against constraints 2 (compatibility), 3 (bottleneck), 4 (no generic placeholders), and 5 (PSU wattage rule) before output -- revise if any fail.
            Output ONLY the strict { builds: [...] } JSON object requested by your system prompt format. No prose, no fences, no wrapper keys.
        """.trimIndent()
        val request = OpenAiDto.ChatRequest(
            model = "gpt-5.6-luna",
            messages = listOf(
                OpenAiDto.Message("system", SYSTEM_PROMPT_RULES_V2),
                OpenAiDto.Message("user", userPrompt)
            ),
            response_format = OpenAiDto.ResponseFormat("json_object")
        )
        return@withContext callOpenAiForBuildsV2(request)
    }

    private suspend fun callOpenAiForBuilds(
        request: OpenAiDto.ChatRequest
    ): OpenAiDto.JsonBuildRecommendation? {
        try {
            val response = OpenAiApiService.getInstance()
                .chatCompletions(OpenAiApiService.authHeader(), request)
            if (!response.isSuccessful) {
                val err = response.errorBody()?.string().orEmpty().take(400)
                Log.e(TAG, "L0 DIRECT AI ERROR HTTP ${response.code()}: $err")
                return null
            }
            val body = response.body()
            if (body == null) {
                Log.e(TAG, "L0 DIRECT AI: empty response body")
                return null
            }
            val choices = body.choices
            if (choices.isNullOrEmpty()) {
                Log.e(TAG, "L0 DIRECT AI: no choices")
                return null
            }
            val content = choices.first().message.content.trim()
            val jsonContent = if (content.startsWith("```")) {
                content
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()
            } else content
            Log.d(TAG, "L0 DIRECT AI RAW JSON (${jsonContent.length} chars)")
            val parsed = gson.fromJson(jsonContent, OpenAiDto.JsonBuildRecommendation::class.java)
            if (parsed == null) {
                Log.e(TAG, "L0 DIRECT AI: GSON NULL after parse")
                return null
            }
            val builds = parsed.tierBuilds
            if (builds.isNullOrEmpty()) {
                Log.e(TAG, "L0 DIRECT AI: tierBuilds empty after parse")
                return null
            }
            Log.i(TAG, "L0 DIRECT AI SUCCESS: tier=${parsed.detectedComponentTier} builds=${builds.size}")
            return parsed
        } catch (t: Throwable) {
            Log.e(TAG, "L0 DIRECT AI EXCEPTION: ${t.javaClass.simpleName} ${t.message}", t)
            return null
        }
    }

    private suspend fun callOpenAiForBuildsV2(
        request: OpenAiDto.ChatRequest
    ): OpenAiDto.JsonBuildsV2Wrapper? {
        try {
            val response = OpenAiApiService.getInstance()
                .chatCompletions(OpenAiApiService.authHeader(), request)
            if (!response.isSuccessful) {
                val err = response.errorBody()?.string().orEmpty().take(400)
                Log.e(TAG, "L0 DIRECT AI V2 ERROR HTTP ${response.code()}: $err")
                return null
            }
            val body = response.body()
            val content = body?.choices?.firstOrNull()?.message?.content?.trim()
            if (content.isNullOrBlank()) {
                Log.e(TAG, "L0 DIRECT AI V2: empty response content")
                return null
            }
            val jsonContent = if (content.startsWith("```")) {
                content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            } else content
            Log.d(TAG, "L0 DIRECT AI V2 RAW JSON (${jsonContent.length} chars)")
            val parsed = gson.fromJson(jsonContent, OpenAiDto.JsonBuildsV2Wrapper::class.java)
            if (parsed?.builds.isNullOrEmpty()) {
                Log.w(TAG, "L0 DIRECT AI V2: builds array empty/null -> caller falls back V1")
                return null
            }
            Log.i(TAG, "L0 DIRECT AI V2 SUCCESS: builds=${parsed.builds!!.size}")
            return parsed
        } catch (t: Throwable) {
            Log.e(TAG, "L0 DIRECT AI V2 EXCEPTION: ${t.javaClass.simpleName} ${t.message} -> caller falls back V1", t)
            return null
        }
    }

    suspend fun analyzeComponentDirect(
        componentName: String,
        categoryHint: String
    ): OpenAiDto.JsonComponentAnalysis? = withContext(Dispatchers.IO) {
        if (!OpenAiApiService.hasApiKey()) {
            Log.w(TAG, "analyzeComponentDirect SKIP: no OpenAI key")
            return@withContext null
        }
        val userPrompt = "Analyze this component name: [${componentName.trim()}]. Category hint: $categoryHint. Output ONLY JSON."
        val request = OpenAiDto.ChatRequest(
            model = "gpt-5.6-luna",
            messages = listOf(
                OpenAiDto.Message("system", COMPONENT_ANALYSIS_RULES),
                OpenAiDto.Message("user", userPrompt)
            ),
            response_format = OpenAiDto.ResponseFormat("json_object")
        )
        return@withContext runCatching {
            val response = OpenAiApiService.getInstance()
                .chatCompletions(OpenAiApiService.authHeader(), request)
            if (!response.isSuccessful) return@runCatching null
            val content = response.body()?.choices?.firstOrNull()?.message?.content?.trim()
                ?: return@runCatching null
            val jsonClean = if (content.startsWith("```")) {
                content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            } else content
            gson.fromJson(jsonClean, OpenAiDto.JsonComponentAnalysis::class.java)
        }.getOrElse { t ->
            Log.e(TAG, "analyzeComponentDirect EXCEPTION: ${t.message}", t)
            null
        }
    }

    suspend fun ocrImageAndExtractComponents(
        imageBytes: ByteArray,
        mimeType: String = "image/jpeg"
    ): List<String> = withContext(Dispatchers.IO) {
        val outList = mutableListOf<String>()
        val firstStepRaw = runGeminiVisionOcrFirstStep(imageBytes, mimeType)
        if (firstStepRaw.isBlank()) {
            Log.w(TAG, "OCR step-1 Gemini returned empty text")
            return@withContext outList
        }
        val fastParsed = extractFastTrackComponentsFromGeminiText(firstStepRaw)
        val fastValid = fastParsed.filter { looksLikeSpecificPcComponent(it) }
        if (fastValid.isNotEmpty()) {
            Log.i(TAG, "OCR FAST-PATH HIT: extracted ${fastValid.size} components from Gemini structured section, SKIPPING GPT step-2")
            outList.addAll(fastValid)
            return@withContext outList
        }
        if (!OpenAiApiService.hasApiKey()) {
            Log.w(TAG, "OCR step-2 SKIP: no OpenAI key, using parsed component lines only")
            outList.addAll(fastParsed)
            return@withContext outList
        }
        val cleanJson = runOpenAiOcrCleanupSecondStep(firstStepRaw)
        if (cleanJson != null && cleanJson.extractedComponents.isNotEmpty()) {
            outList.addAll(cleanJson.extractedComponents)
        }
        if (outList.isEmpty()) {
            outList.addAll(fastParsed)
        }
        Log.i(TAG, "OCR pipeline done: components=${outList.size}")
        return@withContext outList
    }

    suspend fun runGeminiVisionExtractOnly(
        imageBytes: ByteArray,
        mimeType: String = "image/jpeg"
    ): List<String> = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        val raw = runGeminiVisionOcrFirstStep(imageBytes, mimeType)
        if (raw.isBlank()) {
            Log.w(TAG, "Gemini Vision extract-only: empty raw text")
            return@withContext out
        }
        val fast = extractFastTrackComponentsFromGeminiText(raw)
        val valid = fast.filter { looksLikeSpecificPcComponent(it) }
        if (valid.isNotEmpty()) {
            Log.i(TAG, "Gemini Vision extract-only: FAST-PATH ${valid.size} components")
            out.addAll(valid)
            return@withContext out
        }
        if (fast.isNotEmpty()) {
            out.addAll(fast)
            return@withContext out
        }
        val lineCandidates = raw.lineSequence()
            .map { it.trim() }
            .filter { it.length in 6..160 }
            .filter { looksLikeSpecificPcComponent(it) }
            .take(4)
            .toList()
        if (lineCandidates.isNotEmpty()) {
            out.addAll(lineCandidates)
            return@withContext out
        }
        // No component-like reading found: return nothing so the scanner keeps scanning
        // instead of showing an OCR result for non-component text.
        Log.i(TAG, "Gemini Vision extract-only: no component-like reading found")
        out
    }

    private fun looksLikeSpecificPcComponent(text: String): Boolean {
        if (text.isBlank() || text.length < 6) return false
        val lower = text.lowercase()
        val hasDigits = Regex("""\d{3,}""").containsMatchIn(lower)
        val hasGpuKey = lower.contains("rtx") || lower.contains("gtx") || lower.contains("rx ") || lower.contains("radeon") || lower.contains("geforce") || lower.contains("arc")
        val hasCpuKey = lower.contains("ryzen") || (lower.contains("core") && lower.contains("i")) || lower.contains("celeron") || lower.contains("pentium") || lower.contains("ultra")
        val hasMoboKey = Regex("""(?i)\b[bhzx]\d{2,3}[m]?\b""").containsMatchIn(lower) || lower.contains("am4") || lower.contains("am5") || lower.contains("lga")
        val hasRamKey = lower.contains("ddr") || (lower.contains("mhz") || lower.contains("mt/s")) && lower.contains("gb")
        val hasStorageKey = (lower.contains("ssd") || lower.contains("nvme") || lower.contains("m.2") || lower.contains("hdd")) && (lower.contains("gb") || lower.contains("tb"))
        val hasPsuKey = lower.contains("w ") || lower.endsWith("w") || lower.contains("watts") || lower.contains("80+") || lower.contains("80plus")
        val hasAnyKey = hasGpuKey || hasCpuKey || hasMoboKey || hasRamKey || hasStorageKey || hasPsuKey
        if (!hasAnyKey && !hasDigits) return false
        val upper = text.uppercase()
        val bad = setOf("UNKNOWN","UNREADABLE","N/A","NOT FOUND","SAMPLE","BOX","LABEL","STICKER","RECEIPT","SHOPPING")
        if (bad.any { upper.contains(it) }) return false
        return true
    }

    private fun extractFastTrackComponentsFromGeminiText(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        val sectionMarkers = listOf(
            "--- DETECTED PC COMPONENTS (BY BOX) ---",
            "DETECTED PC COMPONENTS (BY BOX)",
            "DETECTED PC COMPONENTS",
            "Detected PC Components",
            "--- COMPONENTS ---"
        )
        var section: String? = null
        for (marker in sectionMarkers) {
            val idx = raw.indexOf(marker, ignoreCase = true)
            if (idx >= 0) {
                section = raw.substring(idx + marker.length)
                break
            }
        }
        if (section == null) {
            val idx = raw.indexOf("COMPONENT_CATEGORY_JSON", ignoreCase = true)
            if (idx >= 0) section = raw.substring(0, idx)
        }
        val sectionText = section ?: raw
        val jsonMatches = Regex("""\{\s*"matches"\s*:\s*(\[[^\]]*\])\s*\}""").find(raw)
            ?: Regex("""\{\s*"displayName"\s*:\s*"[^"]*"\s*,\s*"category"\s*:\s*"[A-Z]+"\s*\}""").find(raw)
        if (jsonMatches != null) {
            runCatching {
                val rawJson = jsonMatches.value
                val nameRe = Regex(""""displayName"\s*:\s*"([^"]+)"""")
                for (m in nameRe.findAll(rawJson)) {
                    val v = m.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: continue
                    if (v.length in 6..120) out.add(v)
                }
            }
        }
        val bulletCandidates = mutableListOf<String>()
        for (line in sectionText.lineSequence()) {
            val trimmed = line.trim()
                .removePrefix("-").trim()
                .removePrefix("*").trim()
                .removePrefix("*").trim()
                .removePrefix("1.").trim()
                .removePrefix("2.").trim()
                .removePrefix("3.").trim()
                .removePrefix("4.").trim()
                .removePrefix("5.").trim()
                .removePrefix("GPU:").trim()
                .removePrefix("CPU:").trim()
                .removePrefix("Motherboard:").trim()
                .removePrefix("RAM:").trim()
                .removePrefix("PSU:").trim()
                .removePrefix("Storage:").trim()
                .trim()
            if (trimmed.isBlank()) continue
            if (trimmed.startsWith("---") || trimmed.endsWith("---")) continue
            if (trimmed.length < 6 || trimmed.length > 160) continue
            if (trimmed.startsWith("COMPONENT") || trimmed.startsWith("STEP") || trimmed.startsWith("RULES")) continue
            bulletCandidates.add(trimmed)
        }
        for (c in bulletCandidates) {
            if (!Regex("""[A-Za-z]""").containsMatchIn(c)) continue
            val key = c.lowercase().replace("""[\s\-_/()]+""".toRegex(), "")
            if (out.none { it.lowercase().replace("""[\s\-_/()]+""".toRegex(), "") == key }) out.add(c)
        }
        return out.take(6)
    }

    private suspend fun runGeminiVisionOcrFirstStep(
        imageBytes: ByteArray,
        mimeType: String
    ): String {
        if (!GeminiApiService.hasApiKey()) {
            Log.w(TAG, "Gemini OCR SKIP: no Gemini key")
            return ""
        }
        val b64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val promptText = """
            THIS IMAGE CONTAINS A PC COMPONENT BOX (CPU, GPU, motherboard, RAM kit, PSU, SSD/HDD). IDENTIFY THE COMPONENT QUICKLY.

            STEP 1 -- EXTRACT ALL READABLE TEXT VERBATIM (do not summarize, do not skip, do not abbreviate, do not guess). Even partial fragments, chipset codes, watt labels, capacities, speeds must appear.

            STEP 2 -- AFTER the verbatim text, output the section:
            --- DETECTED PC COMPONENTS (BY BOX) ---

            For EACH component, output EXACTLY ONE LINE in this format: "[BOARD_PARTNER_BRAND if printed] [CHIPSET_FAMILY_BRAND] [FULL_EXACT_MODEL]".

            CRITICAL GPU RULE (MANDATORY -- NEVER OMIT):
            - If you can see BOTH (A) a GPU board-partner/vendor brand (Zotac, ASUS, MSI, Gigabyte, EVGA, Palit, PowerColor, Sapphire, Galax, Inno3D, PNY, XFX, etc.) AND (B) the chipset family brand (NVIDIA GeForce / AMD Radeon / Intel Arc) AND (C) the model (RTX 2060, RTX 4080, RX 6600 XT, etc.), YOU MUST CONCATENATE ALL THREE: BOARD_PARTNER + CHIPSET_FAMILY + MODEL.
              Example: "Zotac NVIDIA GeForce RTX 2060"   (NOT just "RTX 2060" or "GeForce RTX 2060")
              Example: "ASUS ROG Strix NVIDIA GeForce RTX 4080"
              Example: "Sapphire AMD Radeon RX 6600 XT"
              Example: "Gigabyte NVIDIA GeForce RTX 3060 Ti Gaming OC"

            CPU RULE (MANDATORY): Always include the silicon vendor prefix.
              Example: "AMD Ryzen 5 5600"   (NOT "Ryzen 5 5600")
              Example: "Intel Core i7-14700K"  (NOT "Core i7-14700K")

            Motherboard example: "MSI MAG B550M-A PRO"
            RAM example: "Corsair Vengeance DDR5 32GB (2x16GB) 6000MHz CL30"
            PSU example: "Seasonic Focus GX-750 750W 80+ Gold"
            Storage example: "Samsung 990 PRO 2TB NVMe PCIe 4.0 SSD"

            STEP 3 (OPTIONAL BUT PREFERRED) -- AFTER the component lines, try to append a small JSON block that maps each component to its 6-category type:
            --- COMPONENT_CATEGORY_JSON ---
            {"matches":[{"displayName":"Zotac NVIDIA GeForce RTX 2060","category":"GPU"},{"displayName":"AMD Ryzen 5 5600","category":"CPU"}]}
            Categories are CPU | GPU | MOTHERBOARD | RAM | STORAGE | PSU. Use only these 6.

            RULES THAT MUST NEVER BE VIOLATED:
            - DO NOT GUESS OR HALLUCINATE names that are not legible on the box.
            - If text is illegible, write "(unreadable)" -- do not invent.
            - For GPUs: BOARD-PARTNER BRAND (Zotac/ASUS/MSI/etc.) IS AS IMPORTANT as the NVIDIA GeForce / AMD Radeon chipset label. NEVER drop one if both are printed.
            - Temperature=0. Do not be creative. Extract verbatim and concatenate visible brands.
        """.trimIndent()
        val partsList: List<GeminiDto.Part> = listOf(
            GeminiDto.Part.TextPart(promptText),
            GeminiDto.Part.InlineDataPart(GeminiDto.InlineData(mimeType, b64))
        )
        val contentObj = GeminiDto.Content(parts = partsList)
        val request = GeminiDto.GenerateContentRequest(
            contents = listOf(contentObj),
            generationConfig = GeminiDto.GenerationConfig(temperature = 0.05)
        )
        return runCatching {
            val resp = GeminiApiService.getInstance()
                .generateContent(GeminiApiService.apiKey(), request)
            if (!resp.isSuccessful) {
                Log.e(TAG, "Gemini OCR HTTP ${resp.code()}: ${resp.errorBody()?.string().orEmpty().take(300)}")
                return@runCatching ""
            }
            val firstCandidate = resp.body()?.candidates?.firstOrNull() ?: return@runCatching ""
            val sb = StringBuilder()
            for (p in firstCandidate.content.parts) {
                val t = p.text
                if (t != null) sb.append(t)
            }
            sb.toString().trim()
        }.getOrElse { t ->
            Log.e(TAG, "Gemini OCR EXCEPTION: ${t.javaClass.simpleName} ${t.message}", t)
            ""
        }
    }

    private suspend fun runOpenAiOcrCleanupSecondStep(
        rawGeminiText: String
    ): OpenAiDto.JsonOcrExtraction? {
        if (!OpenAiApiService.hasApiKey() || rawGeminiText.isBlank()) return null
        val userPrompt = "Clean up this OCR raw text extracted from an image and identify ALL identifiable PC component names (CPUs GPUs motherboards RAM kits PSUs storage). Raw text:\n\n$rawGeminiText\n\nOutput ONLY the requested JSON."
        val request = OpenAiDto.ChatRequest(
            model = "gpt-5.6-luna",
            messages = listOf(
                OpenAiDto.Message("system", OCR_CLEANUP_RULES),
                OpenAiDto.Message("user", userPrompt)
            ),
            response_format = OpenAiDto.ResponseFormat("json_object")
        )
        return runCatching {
            val response = OpenAiApiService.getInstance()
                .chatCompletions(OpenAiApiService.authHeader(), request)
            if (!response.isSuccessful) return@runCatching null
            val content = response.body()?.choices?.firstOrNull()?.message?.content?.trim()
                ?: return@runCatching null
            val jsonClean = if (content.startsWith("```")) {
                content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            } else content
            gson.fromJson(jsonClean, OpenAiDto.JsonOcrExtraction::class.java)
        }.getOrElse { t ->
            Log.e(TAG, "OCR step-2 cleanup EXCEPTION: ${t.message}", t)
            null
        }
    }

    private val SYSTEM_PROMPT_COMPONENT_VALIDATION: String = """
        YOU ARE A PC COMPONENT VALIDATOR FOR A DESKTOP PC BUILDING APP CALLED EASYBUILD.

        YOUR ONLY JOB: Given any input string, determine if it represents a legitimate DESKTOP PC DIY component.
        ONLY ACCEPT 8 CATEGORIES: CPU, GPU, MOTHERBOARD, RAM, STORAGE, PSU, COOLER, CASE.
        DO NOT accept laptops, servers, phones, monitors, peripherals (KB/mouse/UPS/headset), home appliances, vehicles, food, clothing, toys, sports equipment, or any other non-desktop-PC-DIY item.
        DO NOT accept mobile/laptop CPUs (suffixes H, HS, HX, U, Y, G1-G7, MQ, M, LM, UM, HQ, g[1-7]) NOR mobile GPUs (MX-series, Max-Q, Laptop suffixes). Only DESKTOP parts.

        CRITICAL: normalized_name format -- ALWAYS output the FULL DESKTOP PC COMPONENT CANONICAL NAME with BRAND/MANUFACTURER PREFIX first, then exact model, then size/speed/variant as applicable. Examples:
          GOOD: "Zotac GeForce RTX 2060 6GB", "AMD Ryzen 5 5600", "NVIDIA GeForce RTX 5050", "AMD Radeon RX 9060 XT", "Lexar Thor DDR4-3200 16GB Kit (2x8GB)", "Corsair Vengeance DDR5-6000 32GB (2x16GB)", "Samsung 990 Pro 2TB PCIe 4.0 NVMe", "Corsair RM850x 850W 80+ Gold Full Modular ATX 3.0", "ASUS ROG Crosshair X670E Hero (AM5 DDR5)"
          NEVER: "rtx 5050", "rx 9060xt", "lexar 16gb kit", "b650m ds3h" (always add brand prefix + exact model + capacity/speed when applicable)
        For CPUs/GPUs, include the vendor/manufacturer prefix (AMD / Intel / NVIDIA) in the normalized_name even if user omitted it.

        LATEST-GENERATION DESKTOP PC COMPONENTS YOU MUST EXPLICITLY RECOGNIZE AND ACCEPT as valid (is_pc_component=true, use correct category+manufacturer+model+normalized_name):
          * GPU NVIDIA RTX 50 (BLACKWELL, 2024/2025): RTX 5050, RTX 5060, RTX 5060 Ti, RTX 5070, RTX 5070 Ti, RTX 5080 16GB, RTX 5090 32GB, RTX 5090 D -- manufacturer NVIDIA, category GPU, normalized_name e.g. "NVIDIA GeForce RTX 5050", "NVIDIA GeForce RTX 5090 32GB"
          * GPU AMD RX 9000 RDNA4 (2025): RX 9060 XT, RX 9070 XT -- manufacturer AMD, category GPU, names "AMD Radeon RX 9060 XT" etc.
          * AMD AM5 MOTHERBOARDS latest flagships: X870E, X870, B850, B650E, B650, A620 (ASUS ROG Crosshair X870E Hero, MSI MEG X870E ACE, Gigabyte X870E AORUS MASTER)
          * Intel LGA1851 (Core Ultra 200/300 Arrow Lake) motherboards: Z890, X890, B860, H810 (ASUS ROG Maximus Z890 Hero, MSI MEG Z890 ACE)
          * RAM LATEST KITS (DDR5 / DDR4, brand+speed+cap kit): Lexar Thor DDR4, Lexar Thor DDR5, Corsair Dominator Titanium DDR5, G.Skill Trident Z5 Neo RGB, Kingston Fury Beast / Renegade DDR5 -- e.g. "Lexar Thor DDR4-3200 16GB Kit (2x8GB)", "Lexar Thor DDR5-6000 32GB Kit (2x16GB)"
          * Storage LATEST: Crucial T700 / T705 PCIe 5.0, Samsung 990 PRO EVO Plus, WD Black SN850X, Sabrent Rocket 5, Corsair MP700 PRO XT -- e.g. "Samsung 990 PRO EVO Plus 2TB PCIe 4.0 NVMe SSD"
          * PSU ATX 3.1 LATEST: Corsair RMx SHIFT 2024, Seasonic Vertex GX-850 / PX-1200 ATX 3.1, be quiet! Dark Power 13

        OUTPUT ONLY A SINGLE JSON OBJECT with these fields, NO backticks, NO markdown wrappers, NO extra text:
        {
          "is_pc_component": true or false,
          "component_type": "CPU" | "GPU" | "MOTHERBOARD" | "RAM" | "STORAGE" | "PSU" | "COOLER" | "CASE" | null (only if is_pc_component=false),
          "normalized_name": "Fully spelled-out correct component name with manufacturer prefix + exact model + size/speed (follow the GOOD examples above). Include brand/vendor: AMD/NVIDIA/Intel for CPU/GPU; Corsair/G.Skill/Lexar/Kingston for RAM; Samsung/WD/Crucial for storage; ASUS/MSI/Gigabyte for mobo/GPU. NEVER partial names.",
          "manufacturer": "The brand/manufacturer leading the normalized_name e.g. NVIDIA, AMD, Intel, ASUS, MSI, Gigabyte, Corsair, G.Skill, Lexar, Kingston, Samsung, Western Digital, Crucial, Seasonic, NZXT, Lian Li, Noctua, be quiet!, Cooler Master or null if cannot determine.",
          "model": "Exact model line e.g. 'RTX 5050', 'Ryzen 5 5600', 'Thor DDR4-3200 16GB (2x8GB)', 'B650M AORUS ELITE AX DDR5', 'RM850x Shift' or null.",
          "confidence": 0.00 to 1.00 number,
          "needs_verification": true only if the component is very new (unreleased or rumored less than 3 months), the input is extremely ambiguous, or you cannot find enough info to be sure. Otherwise false.
        }

        NOTES:
        - Accept CURRENT, FUTURE, and NEXT-GENERATION components. If the component name resembles a legitimate naming pattern for desktop PC hardware (e.g. RTX 5050, Ryzen 9 9950X, Core Ultra 9 385K, RX 8000 XT, Lexar Thor DDR4 16GB Kit), recognize it as valid even if it is newer than your training cutoff.
        - Never invent technical specifications beyond name/manufacturer/category.
        - If input is clearly NOT desktop PC hardware (e.g. 'iPhone 17', 'basketball', 'McDonalds Big Mac'), return ONLY { "is_pc_component": false } with all other fields null.
        - Match naming patterns aggressively: '5600' CPU + 'ryzen' -> AMD Ryzen 5 5600; '4090' + 'nvidia/rtx' -> NVIDIA GeForce RTX 4090; 'lexar thor ddr4 16gb kit' -> 'Lexar Thor DDR4-3200 16GB Kit (2x8GB)'.
        - Component type must be one of the EXACT 8 capitalized strings above.
    """.trimIndent()

    suspend fun validateComponentWithGPT(input: String): OpenAiDto.JsonComponentValidation? = withContext(Dispatchers.IO) {
        val raw = input.trim()
        if (raw.isBlank()) {
            return@withContext OpenAiDto.JsonComponentValidation(
                is_pc_component = false, component_type = null, normalized_name = null,
                manufacturer = null, model = null, confidence = 0.0, needs_verification = false
            )
        }
        if (!OpenAiApiService.hasApiKey()) {
            Log.w(TAG, "validateComponentWithGPT SKIP: no OpenAI key -> returning null (caller falls back to existing local logic)")
            return@withContext null
        }
        val request = OpenAiDto.ChatRequest(
            model = "gpt-5.6-luna",
            messages = listOf(
                OpenAiDto.Message("system", SYSTEM_PROMPT_COMPONENT_VALIDATION),
                OpenAiDto.Message(
                    "user",
                    "Validate this input as desktop PC DIY component. Return ONLY JSON. Input:\n\n$raw"
                )
            ),
            response_format = OpenAiDto.ResponseFormat("json_object")
        )
        runCatching {
            val response = OpenAiApiService.getInstance()
                .chatCompletions(OpenAiApiService.authHeader(), request)
            if (!response.isSuccessful) {
                Log.e(TAG, "validateComponentWithGPT HTTP ${response.code()}: ${response.errorBody()?.string().orEmpty().take(300)}")
                return@runCatching null
            }
            val content = response.body()?.choices?.firstOrNull()?.message?.content?.trim() ?: return@runCatching null
            val jsonClean = if (content.startsWith("```")) content.substringAfter("```").substringAfter("\n").substringBefore("```").trim() else content
            gson.fromJson(jsonClean, OpenAiDto.JsonComponentValidation::class.java)
        }.getOrNull()
    }

    suspend fun runGptDbFirstMatchOrNull(
        userQuery: String,
        dbMatches: List<DbComponentCandidateDto>
    ): OpenAiDto.JsonDbFirstMatchResult? = withContext(Dispatchers.IO) {
        val raw = userQuery.trim()
        if (raw.isBlank()) return@withContext null
        if (!OpenAiApiService.hasApiKey()) {
            Log.w(TAG, "runGptDbFirstMatchOrNull: no OpenAI key -> returning null (caller falls back L1 standalone GPT)")
            return@withContext null
        }
        val dbJson = runCatching { gson.toJson(dbMatches) }.getOrElse { "[]" }
        val userPrompt = """
            USER_QUERY: $raw
            DATABASE_MATCHES:
            $dbJson

            Respond ONLY with the strict JSON object described in your system prompt. Do not add any other text, markdown, or fences.
        """.trimIndent()
        val request = OpenAiDto.ChatRequest(
            model = "gpt-5.6-luna",
            messages = listOf(
                OpenAiDto.Message("system", SYSTEM_PROMPT_DB_FIRST_VALIDATOR),
                OpenAiDto.Message("user", userPrompt)
            ),
            response_format = OpenAiDto.ResponseFormat("json_object")
        )
        runCatching {
            val response = OpenAiApiService.getInstance()
                .chatCompletions(OpenAiApiService.authHeader(), request)
            if (!response.isSuccessful) {
                Log.e(TAG, "runGptDbFirstMatchOrNull HTTP ${response.code()}: ${response.errorBody()?.string().orEmpty().take(300)}")
                return@runCatching null
            }
            val content = response.body()?.choices?.firstOrNull()?.message?.content?.trim() ?: return@runCatching null
            val jsonClean = if (content.startsWith("```")) content.substringAfter("```").substringAfter("\n").substringBefore("```").trim() else content
            gson.fromJson(jsonClean, OpenAiDto.JsonDbFirstMatchResult::class.java)
        }.onFailure { t -> Log.e(TAG, "runGptDbFirstMatchOrNull EXCEPTION: ${t.javaClass.simpleName} ${t.message}", t) }
            .getOrNull()
    }
}
