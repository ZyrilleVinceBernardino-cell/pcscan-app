package com.pcscan.pcscan.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.pcscan.pcscan.data.api.OpenAiApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.tasks.await

enum class BuildTier {
    ENTRY,
    MID,
    HIGH
}

enum class RecentActivityType {
    SEARCH,
    UPLOAD,
    OCR,
    SAVED_BUILD,
    SAVED_UPGRADE,
    RANGE_BUILD
}

data class RecentActivityItem(
    val id: String,
    val type: RecentActivityType,
    val name: String,
    val category: String,
    val timestamp: Long
)

data class SavedBuildItem(
    val id: String,
    val component: String,
    val tier: BuildTier,
    val build: BuildRecommendation,
    val isRangeBuild: Boolean = false,
    val budgetAmount: Long = 0L,
    val budgetTierLabel: String = "",
    val usageLabel: String = ""
) {
    /** True for range builds, including legacy saves that only carry the name marker. */
    val resolvesAsRangeBuild: Boolean
        get() = isRangeBuild || RANGE_BUILD_NAME_REGEX.containsMatchIn(build.name.trim())

    /** Budget from stored metadata, or parsed from the legacy "PHP 30,000 Range Build" name. */
    val resolvedBudgetAmount: Long
        get() {
            if (!resolvesAsRangeBuild) return 0L
            if (budgetAmount > 0L) return budgetAmount
            val raw = Regex("([\\d,]+)").find(build.name)?.groupValues?.get(1) ?: return 0L
            return raw.replace(",", "").toLongOrNull() ?: 0L
        }

    /** Tier key ("ENTRY"/"MID"/"HIGH"/"FLAGSHIP") for the range header. */
    val resolvedTierKey: String
        get() {
            if (budgetTierLabel.isNotBlank()) return budgetTierLabel
            val amount = resolvedBudgetAmount
            return when {
                amount < 50_000L -> "ENTRY"
                amount < 100_000L -> "MID"
                amount < 200_000L -> "HIGH"
                else -> "FLAGSHIP"
            }
        }

    companion object {
        private const val RANGE_BUILD_NAME_MARKER = "Range Build"
        private val RANGE_BUILD_NAME_REGEX = Regex("""(?<!-)Range Build$""", RegexOption.IGNORE_CASE)
    }
}

data class SavedUpgradeItem(
    val id: String,
    val component: String,
    val hasExistingBuild: Boolean,
    val path: UpgradePath,
    val paths: List<UpgradePath> = emptyList(),
    val displayTitle: String = "",
    val tierCount: Int = 0,
    val totalComponentCount: Int = 0,
    val basedOnText: String = "",
    val currentBuildTierLabel: String = ""
)

data class InvalidSearchEvent(
    val query: String,
    val message: String,
    val rejectionCategory: String,
    val formFactor: String? = null
)

open class Event<out T>(private val content: T) {
    private var hasBeenHandled = false
    fun getContentIfNotHandled(): T? = if (hasBeenHandled) null else {
        hasBeenHandled = true
        content
    }
    fun peek(): T = content
}

class RecommendationViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val appContext: android.content.Context = application.applicationContext
    private val repository: RecommendationRepository = RecommendationRepositoryProvider.current
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()

    @Volatile
    private var stateGeneration: Long = 0L
    private val activeAsyncJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<kotlinx.coroutines.Job>()

    private val buildCache: MutableMap<Pair<String, BuildTier>, BuildRecommendation> =
        mutableMapOf()
    private var buildCacheComponentKey: String? = null
    private var detectedTierCache: String? = null
    private val buildCacheMutex = Mutex()

    private val _allBuildsCacheProgress = MutableLiveData(0)
    val allBuildsCacheProgress: LiveData<Int> = _allBuildsCacheProgress
    private val _allBuildsReady = MutableLiveData(false)
    val allBuildsReady: LiveData<Boolean> = _allBuildsReady

    private val _searchProgress = MutableLiveData(0)
    val searchProgress: LiveData<Int> = _searchProgress
    private val _searchResultsReady = MutableLiveData(false)
    val searchResultsReady: LiveData<Boolean> = _searchResultsReady

    private val _upgradePrepareProgress = MutableLiveData(0)
    val upgradePrepareProgress: LiveData<Int> = _upgradePrepareProgress

    private val _buildCompatibilityResult =
        MutableLiveData<RecommendationMockData.BuildCompatibilityResult?>(null)
    val buildCompatibilityResult: LiveData<RecommendationMockData.BuildCompatibilityResult?> =
        _buildCompatibilityResult

    private val _yesBuildPipelineResult =
        MutableLiveData<SpecSheetPipelineResult?>(null)
    val yesBuildPipelineResult: LiveData<SpecSheetPipelineResult?> =
        _yesBuildPipelineResult

    private val _upgradePrepareLoadingLabels = MutableLiveData<Pair<String, String>?>(null)
    val upgradePrepareLoadingLabels: LiveData<Pair<String, String>?> = _upgradePrepareLoadingLabels

    private val _searchQuery = MutableLiveData(savedStateHandle[KEY_SEARCH_QUERY] ?: "")
    val searchQuery: LiveData<String> = _searchQuery

    private val _selectedComponent = MutableLiveData<String?>(
        savedStateHandle[KEY_SELECTED_COMPONENT]
    )
    val selectedComponent: LiveData<String?> = _selectedComponent
    private val _selectedBuildTier = MutableLiveData(
        savedStateHandle.get<String>(KEY_SELECTED_BUILD_TIER)
            ?.let(BuildTier::valueOf)
            ?: BuildTier.ENTRY
    )
    val selectedBuildTier: LiveData<BuildTier> = _selectedBuildTier
    private val _hasExistingBuild = MutableLiveData<Boolean?>(
        savedStateHandle.get<Boolean>(KEY_HAS_EXISTING_BUILD)
    )
    val hasExistingBuild: LiveData<Boolean?> = _hasExistingBuild

    private val _currentBuild = MutableLiveData<CurrentBuild>(
        deserializeCurrentBuild(savedStateHandle.get<String>(KEY_CURRENT_BUILD))
    )
    val currentBuild: LiveData<CurrentBuild> = _currentBuild

    fun setCurrentBuild(build: CurrentBuild) {
        _currentBuild.value = build
        persistState()
        viewModelScope.launch {
            publishUpgradePathState()
            publishUpgradeRecoState()
        }
    }

    private val _searchResultsScreenState =
        MutableLiveData<ScreenState<SearchResultsUiState>>(ScreenState.Idle)
    val searchResultsScreenState: LiveData<ScreenState<SearchResultsUiState>> = _searchResultsScreenState

    private val _buildRecommendationScreenState =
        MutableLiveData<ScreenState<BuildRecommendationUiState>>(ScreenState.Idle)
    val buildRecommendationScreenState: LiveData<ScreenState<BuildRecommendationUiState>> =
        _buildRecommendationScreenState

    private val _upgradePathScreenState =
        MutableLiveData<ScreenState<UpgradePathUiState>>(ScreenState.Idle)
    val upgradePathScreenState: LiveData<ScreenState<UpgradePathUiState>> = _upgradePathScreenState

    private val _upgradeRecoScreenState =
        MutableLiveData<ScreenState<UpgradeRecoUiState>>(ScreenState.Idle)
    val upgradeRecoScreenState: LiveData<ScreenState<UpgradeRecoUiState>> = _upgradeRecoScreenState

    private val _savedBuilds = MutableLiveData(loadSavedBuilds())
    val savedBuilds: LiveData<List<SavedBuildItem>> = _savedBuilds

    private val _savedUpgrades = MutableLiveData(loadSavedUpgrades())
    val savedUpgrades: LiveData<List<SavedUpgradeItem>> = _savedUpgrades

    private val _recentActivities = MutableLiveData(loadRecentActivities())
    val recentActivities: LiveData<List<RecentActivityItem>> = _recentActivities

    private val _invalidSearchEvent = MutableLiveData<Event<InvalidSearchEvent>>()
    val invalidSearchEvent: LiveData<Event<InvalidSearchEvent>> = _invalidSearchEvent

    private val _scanCount = MutableLiveData(0)
    val scanCount: LiveData<Int> = _scanCount

    private val countedScanSessionKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The scan counter is per account so it is restored when the same user logs back in. */
    private fun scanCountKey(uid: String? = currentUserId()): String =
        if (uid.isNullOrBlank()) KEY_SCAN_COUNT else "${KEY_SCAN_COUNT}_$uid"

    /**
     * Reads the per-account scan counter. Counts stored before per-account tracking existed
     * (legacy [KEY_SCAN_COUNT]) are migrated once to the first account that reads them.
     */
    private fun readScanCount(): Int {
        val prefs = appContext.getSharedPreferences(PREF_DASHBOARD, 0)
        val key = scanCountKey()
        if (!prefs.contains(key)) {
            val legacy = prefs.getInt(KEY_SCAN_COUNT, 0)
            if (legacy > 0) {
                prefs.edit().putInt(key, legacy).remove(KEY_SCAN_COUNT).apply()
            }
        }
        return prefs.getInt(key, 0)
    }

    fun refreshScanCount() {
        _scanCount.postValue(readScanCount())
    }

    fun getScanCount(): Int {
        val v = readScanCount()
        _scanCount.value = v
        return v
    }

    fun incrementScanCount(
        dedupKey: String? = null
    ): Boolean {
        if (!dedupKey.isNullOrBlank()) {
            if (!countedScanSessionKeys.add(dedupKey)) {
                return false
            }
        }
        val key = scanCountKey()
        val prefs = appContext.getSharedPreferences(PREF_DASHBOARD, 0)
        val updated = prefs.getInt(key, 0) + 1
        prefs.edit().putInt(key, updated).apply()
        _scanCount.postValue(updated)
        return true
    }

    /**
     * Clears the signed-in user's local data so nothing carries over to the next session.
     * Firestore documents are left untouched and the per-account scan counter stays in prefs,
     * so it can be restored when the same account logs back in.
     */
    fun clearLocalUserDataOnLogout() {
        _savedBuilds.value = emptyList()
        persistSavedBuilds(emptyList())
        _savedUpgrades.value = emptyList()
        persistSavedUpgrades(emptyList())
        _recentActivities.value = emptyList()
        persistRecentActivities(emptyList())
        _scanCount.value = 0
        countedScanSessionKeys.clear()
        resetAllSearchAndBuildState()
    }

    /**
     * Restores account data after a successful login: saved builds/upgrades come back from
     * Firestore and the per-account scan counter is read from prefs. Recent activity always
     * starts empty for the new session.
     */
    fun onUserLoggedIn() {
        _recentActivities.value = emptyList()
        persistRecentActivities(emptyList())
        refreshScanCount()
        viewModelScope.launch {
            syncSavedBuildsFromFirestore()
            syncSavedUpgradesFromFirestore()
        }
    }

    init {
        if (!_searchQuery.value.isNullOrBlank() || !_selectedComponent.value.isNullOrBlank()) {
            refreshAllStates()
        }
        viewModelScope.launch {
            syncSavedBuildsFromFirestore()
            syncSavedUpgradesFromFirestore()
        }
    }

    fun submitSearch(query: String) {
        clearBuildCache()
        _searchQuery.value = query
        _selectedBuildTier.value = BuildTier.ENTRY
        _hasExistingBuild.value = null
        _selectedComponent.value = null
        _buildRecommendationScreenState.value = ScreenState.Idle
        _upgradePathScreenState.value = ScreenState.Idle
        _upgradeRecoScreenState.value = ScreenState.Idle
        _searchResultsScreenState.value = ScreenState.Loading
        _searchProgress.value = 0
        _searchResultsReady.value = false

        val genAtStart = stateGeneration
        val job = viewModelScope.launch {
            runCatching {
                if (stateGeneration != genAtStart) return@runCatching
                _searchProgress.postValue(18)
                if (stateGeneration != genAtStart) return@runCatching
                val exact = withTimeoutOrNull(45_000L) { repository.getExactSearchResult(query) }
                    ?: error("Search timed out. Check your connection and retry.")
                if (stateGeneration != genAtStart) return@runCatching
                when (exact) {
                    is ExactSearchResult.Success -> {
                        _searchProgress.postValue(55)
                        if (stateGeneration != genAtStart) return@runCatching
                        val component = exact.component.name
                        _selectedComponent.value = component
                        addRecentActivity(
                            type = RecentActivityType.SEARCH,
                            name = component,
                            category = exact.component.category.ifBlank { "Component" }
                        )
                        persistState()
                        if (stateGeneration != genAtStart) return@runCatching
                        publishExactSearchResult(exact)
                    }
                    is ExactSearchResult.NotFound -> {
                        _searchProgress.postValue(100)
                        _searchResultsReady.postValue(true)
                        if (stateGeneration != genAtStart) return@runCatching
                        _searchResultsScreenState.value = ScreenState.Error(exact.reason)
                    }
                    is ExactSearchResult.InvalidQuery -> {
                        _searchProgress.postValue(100)
                        _searchResultsReady.postValue(true)
                        if (stateGeneration != genAtStart) return@runCatching
                        _invalidSearchEvent.postValue(Event(InvalidSearchEvent(
                            query = exact.query,
                            message = exact.message,
                            rejectionCategory = exact.rejectionCategory,
                            formFactor = exact.formFactor
                        )))
                        _searchResultsScreenState.value = ScreenState.Idle
                    }
                    is ExactSearchResult.ApiOrNetworkError -> {
                        _searchProgress.postValue(100)
                        _searchResultsReady.postValue(true)
                        if (stateGeneration != genAtStart) return@runCatching
                        _searchResultsScreenState.value = ScreenState.Error(
                            if (exact.isNetworkError) "Network error. Start backend or check WIFI. ${exact.reason}"
                            else exact.reason
                        )
                    }
                }
            }.onFailure { error ->
                if (stateGeneration != genAtStart) return@onFailure
                _searchProgress.postValue(100)
                _searchResultsReady.postValue(true)
                _searchResultsScreenState.value = ScreenState.Error(
                    error.message ?: "Unable to load search results."
                )
            }
        }
        job.invokeOnCompletion {
            activeAsyncJobs.remove(job)
        }
        activeAsyncJobs.add(job)
    }

    fun restoreSearch(query: String, selected: String? = null) {
        clearBuildCache()

        if (_searchQuery.value != query) {
            _searchQuery.value = query
        }
        _selectedBuildTier.value = BuildTier.ENTRY
        _hasExistingBuild.value = null
        _searchProgress.value = 0
        _searchResultsReady.value = false

        val genAtStart = stateGeneration
        if (!selected.isNullOrBlank()) {
            _searchResultsScreenState.value = ScreenState.Loading
            val job = viewModelScope.launch {
                runCatching {
                    if (stateGeneration != genAtStart) return@runCatching
                    _searchProgress.postValue(15)
                    if (stateGeneration != genAtStart) return@runCatching
                    val exact = withTimeoutOrNull(45_000L) { repository.getExactSearchResult(selected) }
                        ?: error("Search timed out. Check your connection and retry.")
                    if (stateGeneration != genAtStart) return@runCatching
                    when (exact) {
                        is ExactSearchResult.Success -> {
                            _searchProgress.postValue(20)
                            if (stateGeneration != genAtStart) return@runCatching
                            // The component already shown on the result screen (search result / OCR result)
                            // stays the authoritative base. Overwriting it here with the DB canonical name
                            // makes the Build Recommendation "Based on" silently change when the user
                            // switches tiers later, so only fall back to the canonical name when none is set.
                            val established = _selectedComponent.value?.takeIf { it.isNotBlank() }
                                ?: selected?.takeIf { it.isNotBlank() }
                            if (established == null) {
                                _selectedComponent.value = exact.component.name
                            }
                            persistState()
                            if (stateGeneration != genAtStart) return@runCatching
                            publishExactSearchResult(exact)
                        }
                        is ExactSearchResult.InvalidQuery -> {
                            _searchProgress.postValue(100)
                            _searchResultsReady.postValue(true)
                            if (stateGeneration != genAtStart) return@runCatching
                            _invalidSearchEvent.postValue(Event(InvalidSearchEvent(
                                query = exact.query.ifBlank { selected },
                                message = exact.message,
                                rejectionCategory = exact.rejectionCategory,
                                formFactor = exact.formFactor
                            )))
                            _searchResultsScreenState.value = ScreenState.Idle
                        }
                        is ExactSearchResult.NotFound -> {
                            _searchProgress.postValue(100)
                            _searchResultsReady.postValue(true)
                            if (stateGeneration != genAtStart) return@runCatching
                            _searchResultsScreenState.value = ScreenState.Error(exact.reason)
                        }
                        is ExactSearchResult.ApiOrNetworkError -> {
                            _searchProgress.postValue(100)
                            _searchResultsReady.postValue(true)
                            if (stateGeneration != genAtStart) return@runCatching
                            _searchResultsScreenState.value = ScreenState.Error(
                                if (exact.isNetworkError) "Network error. Start backend or check WIFI. ${exact.reason}" else exact.reason
                            )
                        }
                    }
                }.onFailure { error ->
                    if (stateGeneration != genAtStart) return@onFailure
                    _searchProgress.postValue(100)
                    _searchResultsReady.postValue(true)
                    _searchResultsScreenState.value = ScreenState.Error(
                        error.message ?: "Unable to restore search results."
                    )
                }
            }
            job.invokeOnCompletion { activeAsyncJobs.remove(job) }
            activeAsyncJobs.add(job)
            return
        }

        _selectedComponent.value = null
        _searchResultsScreenState.value = ScreenState.Loading
        val job2 = viewModelScope.launch {
            runCatching {
                if (stateGeneration != genAtStart) return@runCatching
                _searchProgress.postValue(15)
                if (stateGeneration != genAtStart) return@runCatching
                val exact = withTimeoutOrNull(45_000L) { repository.getExactSearchResult(query) }
                    ?: error("Search timed out. Check your connection and retry.")
                if (stateGeneration != genAtStart) return@runCatching
                when (exact) {
                    is ExactSearchResult.Success -> {
                        _searchProgress.postValue(40)
                        if (stateGeneration != genAtStart) return@runCatching
                        val component = exact.component.name
                        _selectedComponent.value = component
                        persistState()
                        if (stateGeneration != genAtStart) return@runCatching
                        publishExactSearchResult(exact)
                    }
                    is ExactSearchResult.NotFound -> {
                        _searchProgress.postValue(100)
                        _searchResultsReady.postValue(true)
                        if (stateGeneration != genAtStart) return@runCatching
                        _searchResultsScreenState.value = ScreenState.Error(exact.reason)
                    }
                    is ExactSearchResult.InvalidQuery -> {
                        _searchProgress.postValue(100)
                        _searchResultsReady.postValue(true)
                        if (stateGeneration != genAtStart) return@runCatching
                        _invalidSearchEvent.postValue(Event(InvalidSearchEvent(
                            query = exact.query,
                            message = exact.message,
                            rejectionCategory = exact.rejectionCategory,
                            formFactor = exact.formFactor
                        )))
                        _searchResultsScreenState.value = ScreenState.Idle
                    }
                    is ExactSearchResult.ApiOrNetworkError -> {
                        _searchProgress.postValue(100)
                        _searchResultsReady.postValue(true)
                        if (stateGeneration != genAtStart) return@runCatching
                        _searchResultsScreenState.value = ScreenState.Error(
                            if (exact.isNetworkError) "Network error. Start backend or check WIFI. ${exact.reason}"
                            else exact.reason
                        )
                    }
                }
            }.onFailure { error ->
                if (stateGeneration != genAtStart) return@onFailure
                _searchProgress.postValue(100)
                _searchResultsReady.postValue(true)
                _searchResultsScreenState.value = ScreenState.Error(
                    error.message ?: "Unable to restore search results."
                )
            }
        }
        job2.invokeOnCompletion { activeAsyncJobs.remove(job2) }
        activeAsyncJobs.add(job2)
    }

    fun ensureSelectedComponent(component: String) {
        val tierChanged = _selectedBuildTier.value != BuildTier.ENTRY
        val compChanged = _selectedComponent.value != component

        if (compChanged) {
            clearBuildCache()
        }

        if (component.isNotBlank() && compChanged) {
            _selectedComponent.value = component
        }
        if (tierChanged) {
            _selectedBuildTier.value = BuildTier.ENTRY
        }
        if (compChanged || tierChanged) {
            persistState()
            refreshAllStates()
        } else if (_buildRecommendationScreenState.value !is ScreenState.Success) {
            // Nothing changed, but the screen still has no rendered build state. Publish it so the
            // loading overlay is not left waiting on a state that never arrives.
            refreshAllStates()
        }
    }

    private fun clearBuildCache() {
        buildCache.clear()
        buildCacheComponentKey = null
        detectedTierCache = null
    }

    fun resetAllSearchAndBuildState() {
        stateGeneration++
        val snapshot = activeAsyncJobs.toSet()
        activeAsyncJobs.clear()
        snapshot.forEach { job ->
            runCatching { job.cancel() }
        }
        clearBuildCache()
        _searchQuery.value = ""
        _selectedComponent.value = null
        _selectedBuildTier.value = BuildTier.ENTRY
        _hasExistingBuild.value = null
        _currentBuild.value = CurrentBuild()
        _searchResultsScreenState.value = ScreenState.Idle
        _buildRecommendationScreenState.value = ScreenState.Idle
        _upgradePathScreenState.value = ScreenState.Idle
        _upgradeRecoScreenState.value = ScreenState.Idle
        _yesBuildPipelineResult.value = null
        _upgradePrepareProgress.value = 0
        _upgradePrepareLoadingLabels.value = null
        resetBuildCompatibilityResult()
        _allBuildsCacheProgress.value = 0
        _allBuildsReady.value = false
        _searchProgress.value = 0
        _searchResultsReady.value = false
        savedStateHandle.remove<String>(KEY_SEARCH_QUERY)
        savedStateHandle.remove<String>(KEY_SELECTED_COMPONENT)
        savedStateHandle.remove<String>(KEY_SELECTED_BUILD_TIER)
        savedStateHandle.remove<Boolean>(KEY_HAS_EXISTING_BUILD)
        savedStateHandle.remove<String>(KEY_CURRENT_BUILD)
        persistState()
    }

    fun resetYesBuildPipelineAndProgress() {
        _yesBuildPipelineResult.value = null
        _upgradePrepareProgress.value = 0
        _upgradePrepareLoadingLabels.value = null
        resetBuildCompatibilityResult()
        _upgradeRecoScreenState.value = ScreenState.Idle
        _upgradePathScreenState.value = ScreenState.Idle
    }

    /** True only when the cached build set belongs to [component] and has all three tiers. */
    fun isBuildCacheReadyFor(component: String): Boolean =
        component.isNotBlank() && buildCacheComponentKey == component && buildCache.size >= 3

    /** Placeholder tiers are never generated for the searched component tier; the UI hides them. */
    private fun isPlaceholderBuild(build: BuildRecommendation?): Boolean {
        if (build == null) return true
        val marker = "NOT AVAILABLE for this component tier"
        return build.name.equals(marker, ignoreCase = true) || build.cpu.equals(marker, ignoreCase = true)
    }

    private fun isTierBuildAvailableFor(component: String, tier: BuildTier): Boolean {
        if (component.isBlank()) return false
        val build = buildCache[component to tier] ?: return false
        return !isPlaceholderBuild(build)
    }

    /** True when [tier] has a real build for the current component, so its tab can be shown/clicked. */
    fun isTierBuildAvailable(tier: BuildTier): Boolean {
        val component = _selectedComponent.value?.takeIf { it.isNotBlank() } ?: return false
        return isTierBuildAvailableFor(component, tier)
    }

    fun preloadAllBuildsFor(component: String) {
        if (component.isBlank()) return
        val cacheValid = buildCacheComponentKey == component && buildCache.size >= 3 && detectedTierCache != null
        if (cacheValid) {
            _allBuildsCacheProgress.value = 100
            viewModelScope.launch {
                // Publish the current build BEFORE signaling ready, so the screen already has a
                // build to render the moment the loading overlay finishes.
                runCatching { publishBuildRecommendationState() }
                _allBuildsReady.value = true
            }
            return
        }
        _allBuildsCacheProgress.value = 0
        _allBuildsReady.value = false
        val category = RecommendationMockData.resolveCategory(component)
        viewModelScope.launch {
            runCatching {
                ensureAllBuildsCached(component, category)
                publishBuildRecommendationState()
                _allBuildsCacheProgress.value = 100
                _allBuildsReady.value = true
            }.onFailure {
                _allBuildsCacheProgress.value = 100
                _allBuildsReady.value = true
            }
        }
    }

    fun selectComponent(component: String) {
        clearBuildCache()
        _selectedComponent.value = component
        _selectedBuildTier.value = BuildTier.ENTRY
        _hasExistingBuild.value = null
        persistState()
        refreshAllStates()
    }

    fun selectBuildTier(tier: BuildTier) {
        val component = _selectedComponent.value ?: "GPU"
        val cacheKey = component to tier
        val isCached = buildCacheComponentKey == component && buildCache.containsKey(cacheKey)

        _selectedBuildTier.value = tier
        persistState()
        viewModelScope.launch {
            publishBuildRecommendationState(showLoading = !isCached)
        }
    }

    fun setUpgradeMode(hasExistingBuild: Boolean) {
        if (_hasExistingBuild.value == hasExistingBuild &&
            _upgradePathScreenState.value is ScreenState.Success
        ) {
            return
        }
        _hasExistingBuild.value = hasExistingBuild
        persistState()
        viewModelScope.launch {
            publishUpgradePathState(showLoading = true)
        }
    }

    fun saveCurrentOriginalBuild(): Int {
        val component = _selectedComponent.value?.takeIf { it.isNotBlank() } ?: return 0
        val build = _currentBuild.value ?: return 0
        val hasBuild = _hasExistingBuild.value
        if (build.isEmpty) return 0
        if (hasBuild != true) return 0

        val tier = RecommendationMockData.detectBuildTier(build)
        val cpuName = build.cpu.ifBlank { "Not provided" }
        val gpuName = build.gpu.ifBlank { "Not provided" }
        val moboName = build.motherboard.ifBlank { "Not provided" }
        val ramName = build.ram.ifBlank { "Not provided" }
        val storageName = build.storage.ifBlank { "Not provided" }
        val psuName = build.psu.ifBlank { "Not provided" }
        val defaultCase = "Deepcool MATREXX 55 MESH 4F ARGB (ATX, Mid Tower, Black)"
        val defaultCooler = "Stock CPU Cooler (Included)"

        val psuWattDigits = Regex("""\d{3,4}""").find(psuName)?.value?.toIntOrNull()
        val wattageLabel = if (psuWattDigits != null) "${psuWattDigits}W" else "550W"
        val tierLabel = when (tier) {
            BuildTier.ENTRY -> "Entry-Level"
            BuildTier.MID -> "Mid-Range"
            BuildTier.HIGH -> "High-End"
        }
        val buildName = "My Current Build - $tierLabel (based on: $component)"
        val label = "Current Build"
        val buildRec = BuildRecommendation(
            label = label,
            name = buildName,
            cpu = cpuName,
            gpu = gpuName,
            motherboard = moboName,
            ram = ramName,
            storage = storageName,
            psu = psuName,
            wattage = wattageLabel,
            wattageNote = "Based on PSU entered by user",
            bottleneck = "0.0%",
            bottleneckNote = "Current build baseline bottleneck",
            pcCase = defaultCase,
            cpuCooler = defaultCooler,
            bottleneckAnalysis = BottleneckAnalysis.EMPTY
        )
        val currentSaved = _savedBuilds.value.orEmpty()
        val itemId = buildSavedBuildId(component, tier, buildName)
        if (currentSaved.any { it.id == itemId }) return 0

        val newItem = SavedBuildItem(
            id = itemId,
            component = component,
            tier = tier,
            build = buildRec
        )
        val updated = listOf(newItem) + currentSaved
        _savedBuilds.value = updated
        persistSavedBuilds(updated)
        addRecentActivity(
            type = RecentActivityType.SAVED_BUILD,
            name = buildName,
            category = "Build"
        )
        viewModelScope.launch {
            persistSavedBuildToFirestore(newItem)
        }
        return 1
    }

    fun saveCurrentBuild(
        displayNameOverride: String? = null,
        isRangeBuild: Boolean = false,
        budgetAmount: Long = 0L,
        budgetTierLabel: String = "",
        usageLabel: String = ""
    ): Boolean {
        val state = _buildRecommendationScreenState.value as? ScreenState.Success ?: return false
        val component = _selectedComponent.value?.takeIf { it.isNotBlank() } ?: return false
        // Only the build currently displayed on the selected tier tab is saveable. Builds shown on
        // the other tier tabs are recommendations to browse, never the save target.
        val tier = _selectedBuildTier.value ?: BuildTier.ENTRY
        val originalBuild = buildCache[component to tier] ?: state.data.build ?: return false
        val build = if (!displayNameOverride.isNullOrBlank()) {
            originalBuild.copy(name = displayNameOverride)
        } else {
            originalBuild
        }
        val currentSaved = _savedBuilds.value.orEmpty()
        val itemId = buildSavedBuildId(component, tier, build.name)

        if (currentSaved.any { it.id == itemId }) {
            return false
        }

        val newItem = SavedBuildItem(
            id = itemId,
            component = component,
            tier = tier,
            build = build,
            isRangeBuild = isRangeBuild,
            budgetAmount = budgetAmount,
            budgetTierLabel = budgetTierLabel,
            usageLabel = usageLabel
        )
        val updated = listOf(
            newItem
        ) + currentSaved

        _savedBuilds.value = updated
        persistSavedBuilds(updated)
        addRecentActivity(
            type = if (isRangeBuild) RecentActivityType.RANGE_BUILD else RecentActivityType.SAVED_BUILD,
            name = build.name,
            category = "Build"
        )
        viewModelScope.launch {
            persistSavedBuildToFirestore(newItem)
        }
        return true
    }

    fun removeSavedBuild(id: String) {
        val updated = _savedBuilds.value.orEmpty().filterNot { it.id == id }
        _savedBuilds.value = updated
        persistSavedBuilds(updated)
        viewModelScope.launch {
            deleteSavedBuildFromFirestore(id)
        }
    }

    /** True when the given build is already stored for this user. */
    fun isBuildSaved(component: String, tier: BuildTier, buildName: String): Boolean {
        val id = buildSavedBuildId(component, tier, buildName)
        return _savedBuilds.value.orEmpty().any { it.id == id }
    }

    /** True when the build displayed on the currently selected tier tab is already stored. */
    fun isCurrentBuildSaved(): Boolean {
        val component = _selectedComponent.value?.takeIf { it.isNotBlank() } ?: return false
        val tier = _selectedBuildTier.value ?: return false
        val build = buildCache[component to tier] ?: return false
        return isBuildSaved(component, tier, build.name)
    }

    /**
     * Save state for screens that store a build under a display-name override (e.g. range builds).
     * Reuses the exact id logic of [saveCurrentBuild] so the Save button can never disagree with
     * the tier/build that will actually be persisted.
     */
    fun isBuildSavedForDisplayName(displayName: String): Boolean {
        val component = _selectedComponent.value?.takeIf { it.isNotBlank() } ?: return false
        val tier = _selectedBuildTier.value ?: return false
        return isBuildSaved(component, tier, displayName)
    }

    private fun detectedTierToBuildTier(raw: String?): BuildTier? {
        return when (raw?.trim()?.lowercase()) {
            "entry" -> BuildTier.ENTRY
            "mid", "midrange" -> BuildTier.MID
            "high", "high-end", "highend", "high end" -> BuildTier.HIGH
            else -> null
        }
    }

    /**
     * Picks the tier that should actually be rendered for this component.
     * If the currently selected tier is only a placeholder, fall forward to the detected tier
     * or the nearest real cached build so the screen never opens on a "--" build when a real
     * recommendation already exists.
     */
    private fun resolveDisplayBuildTier(
        component: String,
        preferredTier: BuildTier?,
        detectedTierRaw: String?
    ): BuildTier {
        fun available(tier: BuildTier): Boolean = isTierBuildAvailableFor(component, tier)

        if (preferredTier != null && available(preferredTier)) return preferredTier

        val detectedTier = detectedTierToBuildTier(detectedTierRaw)
        if (detectedTier != null && available(detectedTier)) return detectedTier

        val fallbackOrder = when (detectedTier) {
            BuildTier.ENTRY -> listOf(BuildTier.ENTRY, BuildTier.MID, BuildTier.HIGH)
            BuildTier.MID -> listOf(BuildTier.MID, BuildTier.HIGH, BuildTier.ENTRY)
            BuildTier.HIGH -> listOf(BuildTier.HIGH, BuildTier.MID, BuildTier.ENTRY)
            null -> listOf(BuildTier.MID, BuildTier.HIGH, BuildTier.ENTRY)
        }

        return fallbackOrder.firstOrNull(::available)
            ?: preferredTier
            ?: detectedTier
            ?: BuildTier.ENTRY
    }

    fun saveCurrentUpgrade(): Boolean {
        val state = _upgradePathScreenState.value as? ScreenState.Success ?: return false
        val component = _selectedComponent.value?.takeIf { it.isNotBlank() } ?: return false
        val hasExistingBuild = _hasExistingBuild.value ?: return false
        val path = state.data.path ?: return false
        return saveUpgradePathsInternal(
            component = component,
            hasExistingBuild = hasExistingBuild,
            paths = listOf(path),
            displayTitleOverride = null
        ) == 1
    }

    fun removeSavedUpgrade(id: String) {
        val updated = _savedUpgrades.value.orEmpty().filterNot { it.id == id }
        _savedUpgrades.value = updated
        persistSavedUpgrades(updated)
        viewModelScope.launch {
            deleteSavedUpgradeFromFirestore(id)
        }
    }

    @Deprecated(
        "Use saveUpgradePaths for multi-tier saves. This function is kept for single-path sources " +
            "and still wraps the single path into a multi-tier normalized item (one row in Saved screen)."
    )
    fun saveUpgradeItem(
        component: String,
        hasExistingBuild: Boolean,
        path: UpgradePath
    ): Boolean {
        return saveUpgradePathsInternal(
            component = component,
            hasExistingBuild = hasExistingBuild,
            paths = listOf(path),
            displayTitleOverride = null
        ) == 1
    }

    fun saveUpgradePaths(
        component: String,
        hasExistingBuild: Boolean,
        paths: List<UpgradePath>,
        displayTitle: String = ""
    ): Int {
        return saveUpgradePathsInternal(
            component = component,
            hasExistingBuild = hasExistingBuild,
            paths = paths,
            displayTitleOverride = displayTitle
        )
    }

    /** Canonical per-tier saved-item ids for the given upgrade paths (one id per saveable tier). */
    private fun buildUpgradeSaveIds(
        component: String,
        hasExistingBuild: Boolean,
        paths: List<UpgradePath>,
        displayTitleOverride: String? = null
    ): List<String> {
        val validPaths = paths.filterNot {
            it.title.startsWith("Your component is already", ignoreCase = true) ||
                it.title.startsWith("Your build is already", ignoreCase = true)
        }
        if (validPaths.isEmpty()) return emptyList()
        return validPaths.map { path ->
            val title = displayTitleOverride?.takeIf { it.isNotBlank() }
                ?: path.title.takeIf { it.isNotBlank() }
                ?: "Upgrade Path"
            buildSavedUpgradeId(component, hasExistingBuild, title)
        }
    }

    /** True when every given upgrade tier is already saved for this user. */
    fun isUpgradePathsSaved(
        component: String,
        hasExistingBuild: Boolean,
        paths: List<UpgradePath>,
        displayTitle: String = ""
    ): Boolean {
        val ids = buildUpgradeSaveIds(component, hasExistingBuild, paths, displayTitle)
        if (ids.isEmpty()) return false
        val savedIds = _savedUpgrades.value.orEmpty().map { it.id }.toSet()
        return ids.all { it in savedIds }
    }

    /** True when the currently displayed upgrade recommendations are already saved for this user. */
    fun isCurrentUpgradeSaved(): Boolean {
        val state = _upgradeRecoScreenState.value as? ScreenState.Success ?: return false
        val component = _selectedComponent.value?.takeIf { it.isNotBlank() } ?: return false
        val hasExistingBuild = _hasExistingBuild.value ?: true
        val allTiers = listOfNotNull(state.data.entryPath, state.data.midPath, state.data.highPath)
        return isUpgradePathsSaved(component, hasExistingBuild, allTiers)
    }

    private fun saveUpgradePathsInternal(
        component: String,
        hasExistingBuild: Boolean,
        paths: List<UpgradePath>,
        displayTitleOverride: String?
    ): Int {
        val safeComponent = component.takeIf { it.isNotBlank() } ?: return 0
        val validPaths = paths.filterNot {
            it.title.startsWith("Your component is already", ignoreCase = true) ||
                it.title.startsWith("Your build is already", ignoreCase = true)
        }
        if (validPaths.isEmpty()) return 0
        val uiSnapshot = (_upgradeRecoScreenState.value as? ScreenState.Success)?.data
        val snapshotBasedOn = uiSnapshot?.basedOnText?.takeIf { it.isNotBlank() }
            ?: "Based on: $safeComponent"
        val snapshotTierLabel = uiSnapshot?.currentBuildTierLabel.orEmpty()

        var savedCount = 0
        var currentSaved = _savedUpgrades.value.orEmpty()
        validPaths.forEach { path ->
            val displayTitle = displayTitleOverride?.takeIf { it.isNotBlank() }
                ?: path.title.takeIf { it.isNotBlank() }
                ?: "Upgrade Path"
            val itemId = buildSavedUpgradeId(safeComponent, hasExistingBuild, displayTitle)
            if (currentSaved.any { it.id == itemId }) return@forEach
            val rawItem = SavedUpgradeItem(
                id = itemId,
                component = safeComponent,
                hasExistingBuild = hasExistingBuild,
                path = path,
                paths = listOf(path),
                displayTitle = displayTitle,
                tierCount = 1,
                totalComponentCount = path.components.size,
                basedOnText = snapshotBasedOn,
                currentBuildTierLabel = snapshotTierLabel
            )
            val newItem = normalizeSavedUpgradeItem(rawItem)
            currentSaved = listOf(newItem) + currentSaved
            _savedUpgrades.value = currentSaved
            persistSavedUpgrades(currentSaved)
            addRecentActivity(
                type = RecentActivityType.SAVED_UPGRADE,
                name = newItem.displayTitle,
                category = "Upgrade"
            )
            viewModelScope.launch {
                persistSavedUpgradeToFirestore(newItem)
            }
            savedCount++
        }
        return savedCount
    }

    fun restoreSavedUpgrade(item: SavedUpgradeItem) {
        clearBuildCache()
        _searchQuery.value = item.component
        _selectedComponent.value = item.component
        _selectedBuildTier.value = BuildTier.ENTRY
        _hasExistingBuild.value = item.hasExistingBuild
        persistState()
        refreshAllStates()
    }

    fun forceUpgradeRecoUiStateFromSaved(
        basedOnText: String,
        currentBuildTierLabel: String,
        entry: UpgradePath?,
        mid: UpgradePath?,
        high: UpgradePath?
    ) {
        val currentBuildForUiState: CurrentBuild = _currentBuild.value ?: CurrentBuild()
        val tierCount = listOfNotNull(entry, mid, high).size.coerceAtLeast(1)
        val uiState = UpgradeRecoUiState(
            basedOnText = basedOnText,
            currentBuild = currentBuildForUiState,
            currentBuildTier = currentBuildTierLabel.takeIf { it.isNotBlank() }?.let { label ->
                when {
                    label.contains("entry", true) -> BuildTier.ENTRY
                    label.contains("mid", true) -> BuildTier.MID
                    label.contains("high", true) -> BuildTier.HIGH
                    else -> null
                }
            },
            currentBuildTierLabel = currentBuildTierLabel,
            tierCount = tierCount,
            entryPath = entry,
            midPath = mid,
            highPath = high
        )
        _upgradeRecoScreenState.value = ScreenState.Success(uiState)
    }

    private fun persistState() {
        savedStateHandle[KEY_SEARCH_QUERY] = _searchQuery.value
        savedStateHandle[KEY_SELECTED_COMPONENT] = _selectedComponent.value
        savedStateHandle[KEY_SELECTED_BUILD_TIER] = _selectedBuildTier.value?.name
        savedStateHandle[KEY_HAS_EXISTING_BUILD] = _hasExistingBuild.value
        savedStateHandle[KEY_CURRENT_BUILD] = serializeCurrentBuild(_currentBuild.value)
    }

    private fun persistSavedBuilds(items: List<SavedBuildItem>) {
        val serialized = ArrayList(items.map(::serializeSavedBuild))
        savedStateHandle[KEY_SAVED_BUILDS] = serialized
        val prefs = appContext.getSharedPreferences(PREF_DASHBOARD, 0)
        val sizeKey = KEY_PREFS_BUILD_COUNT
        val edit = prefs.edit()
        edit.putInt(sizeKey, serialized.size)
        serialized.forEachIndexed { idx, s ->
            edit.putString("${KEY_PREFS_BUILD_ITEM}_$idx", s)
        }
        var i = serialized.size
        while (true) {
            val key = "${KEY_PREFS_BUILD_ITEM}_$i"
            if (!prefs.contains(key)) break
            edit.remove(key)
            i++
        }
        edit.apply()
    }

    private fun loadSavedBuilds(): List<SavedBuildItem> {
        val prefs = appContext.getSharedPreferences(PREF_DASHBOARD, 0)
        val count = prefs.getInt(KEY_PREFS_BUILD_COUNT, -1)
        if (count > 0) {
            val fromPrefs = (0 until count).mapNotNull { idx ->
                val s = prefs.getString("${KEY_PREFS_BUILD_ITEM}_$idx", null) ?: return@mapNotNull null
                deserializeSavedBuild(s)
            }
            if (fromPrefs.isNotEmpty()) {
                savedStateHandle[KEY_SAVED_BUILDS] = ArrayList(fromPrefs.map(::serializeSavedBuild))
                return fromPrefs
            }
        }
        return savedStateHandle.get<ArrayList<String>>(KEY_SAVED_BUILDS)
            ?.mapNotNull(::deserializeSavedBuild)
            .orEmpty()
    }

    private fun persistSavedUpgrades(items: List<SavedUpgradeItem>) {
        val serialized = ArrayList(items.map(::serializeSavedUpgrade))
        savedStateHandle[KEY_SAVED_UPGRADES] = serialized
        val prefs = appContext.getSharedPreferences(PREF_DASHBOARD, 0)
        val sizeKey = KEY_PREFS_UPGRADE_COUNT
        val edit = prefs.edit()
        edit.putInt(sizeKey, serialized.size)
        serialized.forEachIndexed { idx, s ->
            edit.putString("${KEY_PREFS_UPGRADE_ITEM}_$idx", s)
        }
        var i = serialized.size
        while (true) {
            val key = "${KEY_PREFS_UPGRADE_ITEM}_$i"
            if (!prefs.contains(key)) break
            edit.remove(key)
            i++
        }
        edit.apply()
    }

    private fun loadSavedUpgrades(): List<SavedUpgradeItem> {
        val prefs = appContext.getSharedPreferences(PREF_DASHBOARD, 0)
        val count = prefs.getInt(KEY_PREFS_UPGRADE_COUNT, -1)
        if (count > 0) {
            val fromPrefs = (0 until count).mapNotNull { idx ->
                val s = prefs.getString("${KEY_PREFS_UPGRADE_ITEM}_$idx", null) ?: return@mapNotNull null
                deserializeSavedUpgrade(s)
            }
            if (fromPrefs.isNotEmpty()) {
                savedStateHandle[KEY_SAVED_UPGRADES] = ArrayList(fromPrefs.map(::serializeSavedUpgrade))
                return fromPrefs
            }
        }
        return savedStateHandle.get<ArrayList<String>>(KEY_SAVED_UPGRADES)
            ?.mapNotNull(::deserializeSavedUpgrade)
            .orEmpty()
    }

    fun addRecentActivity(type: RecentActivityType, name: String, category: String) {
        val id = "${type.name}-${System.currentTimeMillis()}"
        val newItem = RecentActivityItem(
            id = id,
            type = type,
            name = name,
            category = category,
            timestamp = System.currentTimeMillis()
        )
        val currentActivities = _recentActivities.value.orEmpty()
        val updatedActivities = (listOf(newItem) + currentActivities).take(RECENT_ACTIVITY_LIMIT)
        _recentActivities.value = updatedActivities
        persistRecentActivities(updatedActivities)
    }

    private fun persistRecentActivities(items: List<RecentActivityItem>) {
        val serialized = ArrayList(items.map(::serializeRecentActivity))
        savedStateHandle[KEY_RECENT_ACTIVITIES] = serialized
        val prefs = appContext.getSharedPreferences(PREF_DASHBOARD, 0)
        val sizeKey = KEY_PREFS_RECENT_COUNT
        val edit = prefs.edit()
        edit.putInt(sizeKey, serialized.size)
        serialized.forEachIndexed { idx, s ->
            edit.putString("${KEY_PREFS_RECENT_ITEM}_$idx", s)
        }
        var i = serialized.size
        while (true) {
            val key = "${KEY_PREFS_RECENT_ITEM}_$i"
            if (!prefs.contains(key)) break
            edit.remove(key)
            i++
        }
        edit.apply()
    }

    private fun loadRecentActivities(): List<RecentActivityItem> {
        val prefs = appContext.getSharedPreferences(PREF_DASHBOARD, 0)
        val count = prefs.getInt(KEY_PREFS_RECENT_COUNT, -1)
        if (count > 0) {
            val fromPrefs = (0 until count).mapNotNull { idx ->
                val s = prefs.getString("${KEY_PREFS_RECENT_ITEM}_$idx", null) ?: return@mapNotNull null
                deserializeRecentActivity(s)
            }
            if (fromPrefs.isNotEmpty()) {
                val trimmed = fromPrefs.take(RECENT_ACTIVITY_LIMIT)
                savedStateHandle[KEY_RECENT_ACTIVITIES] = ArrayList(trimmed.map(::serializeRecentActivity))
                return trimmed
            }
        }
        return savedStateHandle.get<ArrayList<String>>(KEY_RECENT_ACTIVITIES)
            ?.mapNotNull(::deserializeRecentActivity)
            ?.take(RECENT_ACTIVITY_LIMIT)
            .orEmpty()
    }

    private fun serializeRecentActivity(item: RecentActivityItem): String {
        return listOf(
            item.id,
            item.type.name,
            item.name,
            item.category,
            item.timestamp.toString()
        ).joinToString(RECENT_ACTIVITY_SEPARATOR)
    }

    private fun deserializeRecentActivity(value: String): RecentActivityItem? {
        val parts = value.split(RECENT_ACTIVITY_SEPARATOR)
        if (parts.size != 5) return null

        return runCatching {
            RecentActivityItem(
                id = parts[0],
                type = RecentActivityType.valueOf(parts[1]),
                name = parts[2],
                category = parts[3],
                timestamp = parts[4].toLong()
            )
        }.getOrNull()
    }

    private fun currentUserId(): String? = auth.currentUser?.uid?.takeIf { it.isNotBlank() }

    private suspend fun syncSavedBuildsFromFirestore() {
        val userId = currentUserId() ?: return
        runCatching {
            val snapshot = firestore.collection(USERS_COLLECTION)
                .document(userId)
                .collection(SAVED_BUILDS_COLLECTION)
                .get()
                .await()

            val remote = snapshot.documents.mapNotNull { doc ->
                val component = doc.getString(FIELD_COMPONENT).orEmpty()
                val tierName = doc.getString(FIELD_TIER).orEmpty()
                val label = doc.getString(FIELD_LABEL).orEmpty()
                val name = doc.getString(FIELD_NAME).orEmpty()
                val cpu = doc.getString(FIELD_CPU).orEmpty()
                val gpu = doc.getString(FIELD_GPU).orEmpty()
                val motherboard = doc.getString(FIELD_MOTHERBOARD).orEmpty()
                val ram = doc.getString(FIELD_RAM).orEmpty()
                val storage = doc.getString(FIELD_STORAGE).orEmpty()
                val psu = doc.getString(FIELD_PSU).orEmpty()
                val wattage = doc.getString(FIELD_WATTAGE).orEmpty()
                val wattageNote = doc.getString(FIELD_WATTAGE_NOTE).orEmpty()
                val bottleneck = doc.getString(FIELD_BOTTLENECK).orEmpty()
                val bottleneckNote = doc.getString(FIELD_BOTTLENECK_NOTE).orEmpty()
                val savedAt = doc.getLong(FIELD_SAVED_AT) ?: 0L

                val tier = runCatching { BuildTier.valueOf(tierName) }.getOrNull() ?: return@mapNotNull null
                if (component.isBlank() || name.isBlank()) return@mapNotNull null

                val pctFromDoc = doc.getLong(FIELD_BN_PCT)?.toInt()
                val pct = pctFromDoc ?: Regex("(\\d+)").find(bottleneck)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val bn = BottleneckAnalysis(
                    percentage = pct.coerceIn(0, 20),
                    status = doc.getString(FIELD_BN_STATUS).orEmpty().ifBlank {
                        when {
                            pct <= 10 -> "EXCELLENT"
                            pct <= 15 -> "GOOD"
                            else -> "ACCEPTABLE"
                        }
                    },
                    limitingComponent = doc.getString(FIELD_BN_LIMITING).orEmpty().ifBlank { "Balanced" },
                    summary = doc.getString(FIELD_BN_SUMMARY).orEmpty().ifBlank {
                        "Estimated bottleneck: approximately ${pct.coerceIn(0, 20)}%."
                    },
                    detailedAnalysis = doc.getString(FIELD_BN_DETAILED).orEmpty().ifBlank { bottleneckNote.ifBlank { "Estimated bottleneck; actual result varies by workload." } },
                    gaming1080p = doc.getString(FIELD_BN_1080P).orEmpty(),
                    gaming1440p = doc.getString(FIELD_BN_1440P).orEmpty(),
                    gaming4k = doc.getString(FIELD_BN_4K).orEmpty(),
                    reason = doc.getString(FIELD_BN_REASON).orEmpty(),
                    withinLimit = doc.getBoolean(FIELD_BN_WITHIN_LIMIT) ?: true
                )

                SavedBuildItem(
                    id = doc.id,
                    component = component,
                    tier = tier,
                    build = BuildRecommendation(
                        label = label,
                        name = name,
                        cpu = cpu,
                        gpu = gpu,
                        motherboard = motherboard,
                        ram = ram,
                        storage = storage,
                        psu = psu,
                        wattage = wattage,
                        wattageNote = wattageNote,
                        bottleneck = bottleneck,
                        bottleneckNote = bottleneckNote,
                        bottleneckAnalysis = bn
                    ),
                    isRangeBuild = doc.getBoolean(FIELD_IS_RANGE_BUILD) ?: false,
                    budgetAmount = doc.getLong(FIELD_BUDGET_AMOUNT) ?: 0L,
                    budgetTierLabel = doc.getString(FIELD_BUDGET_TIER_LABEL).orEmpty(),
                    usageLabel = doc.getString(FIELD_USAGE_LABEL).orEmpty()
                ) to savedAt
            }
                .sortedByDescending { it.second }
                .map { it.first }

            val merged = (_savedBuilds.value.orEmpty() + remote)
                .distinctBy { it.id }
            if (merged.isNotEmpty()) {
                _savedBuilds.value = merged
                persistSavedBuilds(merged)
            }
        }
    }

    private suspend fun syncSavedUpgradesFromFirestore() {
        val userId = currentUserId() ?: return
        runCatching {
            val snapshot = firestore.collection(USERS_COLLECTION)
                .document(userId)
                .collection(SAVED_UPGRADES_COLLECTION)
                .get()
                .await()

            val remote = snapshot.documents.mapNotNull { doc ->
                val component = doc.getString(FIELD_COMPONENT).orEmpty()
                val mode = doc.getBoolean(FIELD_HAS_EXISTING_BUILD) ?: return@mapNotNull null
                val title = doc.getString(FIELD_TITLE).orEmpty()
                val description = doc.getString(FIELD_DESCRIPTION).orEmpty()
                val stepOne = doc.getString(FIELD_STEP_ONE).orEmpty()
                val stepTwo = doc.getString(FIELD_STEP_TWO).orEmpty()
                val stepThree = doc.getString(FIELD_STEP_THREE).orEmpty()
                val savedAt = doc.getLong(FIELD_SAVED_AT) ?: 0L

                if (component.isBlank() || title.isBlank()) return@mapNotNull null

                SavedUpgradeItem(
                    id = doc.id,
                    component = component,
                    hasExistingBuild = mode,
                    path = UpgradePath(
                        title = title,
                        description = description,
                        stepOne = stepOne,
                        stepTwo = stepTwo,
                        stepThree = stepThree
                    )
                ) to savedAt
            }
                .sortedByDescending { it.second }
                .map { it.first }

            val merged = (_savedUpgrades.value.orEmpty() + remote)
                .distinctBy { it.id }
            if (merged.isNotEmpty()) {
                _savedUpgrades.value = merged
                persistSavedUpgrades(merged)
            }
        }
    }

    private suspend fun persistSavedBuildToFirestore(item: SavedBuildItem) {
        val userId = currentUserId() ?: return
        val bn = item.build.bottleneckAnalysis
        val data = hashMapOf<String, Any>(
            FIELD_COMPONENT to item.component,
            FIELD_TIER to item.tier.name,
            FIELD_LABEL to item.build.label,
            FIELD_NAME to item.build.name,
            FIELD_CPU to item.build.cpu,
            FIELD_GPU to item.build.gpu,
            FIELD_MOTHERBOARD to item.build.motherboard,
            FIELD_RAM to item.build.ram,
            FIELD_STORAGE to item.build.storage,
            FIELD_PSU to item.build.psu,
            FIELD_WATTAGE to item.build.wattage,
            FIELD_WATTAGE_NOTE to item.build.wattageNote,
            FIELD_BOTTLENECK to item.build.bottleneck,
            FIELD_BOTTLENECK_NOTE to item.build.bottleneckNote,
            FIELD_BN_PCT to (bn?.percentage ?: 0),
            FIELD_BN_STATUS to (bn?.status ?: ""),
            FIELD_BN_LIMITING to (bn?.limitingComponent ?: ""),
            FIELD_BN_SUMMARY to (bn?.summary ?: ""),
            FIELD_BN_DETAILED to (bn?.detailedAnalysis ?: ""),
            FIELD_BN_1080P to (bn?.gaming1080p ?: ""),
            FIELD_BN_1440P to (bn?.gaming1440p ?: ""),
            FIELD_BN_4K to (bn?.gaming4k ?: ""),
            FIELD_BN_REASON to (bn?.reason ?: ""),
            FIELD_BN_WITHIN_LIMIT to (bn?.withinLimit ?: true),
            FIELD_IS_RANGE_BUILD to item.isRangeBuild,
            FIELD_BUDGET_AMOUNT to item.budgetAmount,
            FIELD_BUDGET_TIER_LABEL to item.budgetTierLabel,
            FIELD_USAGE_LABEL to item.usageLabel,
            FIELD_SAVED_AT to System.currentTimeMillis()
        )
        runCatching {
            firestore.collection(USERS_COLLECTION)
                .document(userId)
                .collection(SAVED_BUILDS_COLLECTION)
                .document(item.id)
                .set(data)
                .await()
        }
    }

    private suspend fun deleteSavedBuildFromFirestore(id: String) {
        val userId = currentUserId() ?: return
        runCatching {
            firestore.collection(USERS_COLLECTION)
                .document(userId)
                .collection(SAVED_BUILDS_COLLECTION)
                .document(id)
                .delete()
                .await()
        }
    }

    private suspend fun persistSavedUpgradeToFirestore(item: SavedUpgradeItem) {
        val userId = currentUserId() ?: return
        val data = hashMapOf<String, Any>(
            FIELD_COMPONENT to item.component,
            FIELD_HAS_EXISTING_BUILD to item.hasExistingBuild,
            FIELD_TITLE to item.path.title,
            FIELD_DESCRIPTION to item.path.description,
            FIELD_STEP_ONE to item.path.stepOne,
            FIELD_STEP_TWO to item.path.stepTwo,
            FIELD_STEP_THREE to item.path.stepThree,
            FIELD_SAVED_AT to System.currentTimeMillis()
        )
        runCatching {
            firestore.collection(USERS_COLLECTION)
                .document(userId)
                .collection(SAVED_UPGRADES_COLLECTION)
                .document(item.id)
                .set(data)
                .await()
        }
    }

    private suspend fun deleteSavedUpgradeFromFirestore(id: String) {
        val userId = currentUserId() ?: return
        runCatching {
            firestore.collection(USERS_COLLECTION)
                .document(userId)
                .collection(SAVED_UPGRADES_COLLECTION)
                .document(id)
                .delete()
                .await()
        }
    }

    private fun refreshAllStates() {
        viewModelScope.launch {
            // Each publish is isolated: if one fails (e.g. search results), the build recommendation
            // state must still be published. Otherwise the screen stays empty after loading and only
            // shows a build once the user manually taps a tier.
            runCatching { publishSearchResultsState() }
            runCatching { publishBuildRecommendationState() }
            runCatching { publishUpgradePathState() }
            runCatching { publishUpgradeRecoState() }
        }
    }

    private suspend fun refreshAllStatesSuspend() {
        // (SPEED FEATURE) SPEED PARALLEL: All 4 publish calls are independent (different LiveData targets) -> run concurrently with async/awaitAll.
        // Saves: publish latency sum(A+B+C+D) -> max(A,B,C,D) typically 30-60% faster.
        coroutineScope {
            val tasks: List<Deferred<Unit>> = listOf(
                async { publishSearchResultsState() },
                async { publishBuildRecommendationState() },
                async { publishUpgradePathState() },
                async { publishUpgradeRecoState() },
            )
            tasks.awaitAll()
        }
    }

    fun resetBuildCompatibilityResult() {
        _buildCompatibilityResult.value = null
        _upgradePrepareProgress.value = 0
    }

    fun prepareBuildForUpgrade(
        build: CurrentBuild,
        primaryComponent: String,
        hasExistingBuild: Boolean = true,
        specSheetText: String = ""
    ) {
        resetBuildCompatibilityResult()
        clearBuildCache()
        _upgradeRecoScreenState.value = ScreenState.Idle
        _upgradePathScreenState.value = ScreenState.Idle
        _yesBuildPipelineResult.value = null
        _upgradePrepareLoadingLabels.value = null

        viewModelScope.launch {
            runCatching {
                _upgradePrepareProgress.postValue(10)

                val safeComponent = primaryComponent.ifBlank {
                    listOf(build.gpu, build.cpu, build.motherboard, build.ram, build.storage, build.psu)
                        .firstOrNull { it.isNotBlank() } ?: "Component"
                }

                val pipeline = if (specSheetText.isNotBlank() || OpenAiApiService.hasApiKey()) {
                    withTimeoutOrNull(45000L) {
                        repository.analyzeSpecSheetPipelineOrNull(
                            specSheetText = specSheetText,
                            fallbackBuild = build,
                            onProgress = { progress: Int, title: String, subtitle: String ->
                                _upgradePrepareProgress.postValue(progress)
                                _upgradePrepareLoadingLabels.postValue(Pair(title, subtitle))
                            },
                            primaryComponent = safeComponent
                        )
                    }
                } else null

                val rawParsed = pipeline?.parsedBuild
                val pipelineBuildToUse = if (rawParsed != null && !rawParsed.isEmpty) rawParsed else null
                val effectiveBuild = pipelineBuildToUse ?: build
                val compatResult = if (pipeline != null && pipelineBuildToUse != null) {
                    pipeline.compatibility
                } else {
                    RecommendationMockData.checkBuildCompatibility(effectiveBuild)
                }
                _buildCompatibilityResult.postValue(compatResult)
                if (pipeline != null && pipelineBuildToUse != null) {
                    _yesBuildPipelineResult.postValue(pipeline)
                } else if (pipeline != null) {
                    val safePipeline = pipeline.copy(parsedBuild = effectiveBuild)
                    runCatching {
                        _yesBuildPipelineResult.postValue(safePipeline)
                    }
                }
                _upgradePrepareProgress.postValue(if (pipeline != null) 40 else 30)

                if (!compatResult.isCompatible) {
                    return@runCatching
                }

                kotlinx.coroutines.delay(200L)
                _upgradePrepareProgress.postValue(55)

                _selectedComponent.value = safeComponent
                _currentBuild.value = effectiveBuild
                _hasExistingBuild.value = hasExistingBuild
                _selectedBuildTier.value = BuildTier.ENTRY
                persistState()

                kotlinx.coroutines.delay(150L)
                _upgradePrepareProgress.postValue(70)

                publishUpgradeRecoState()
                kotlinx.coroutines.delay(150L)
                _upgradePrepareProgress.postValue(85)

                publishUpgradePathState()
                kotlinx.coroutines.delay(150L)
                _upgradePrepareProgress.postValue(100)
            }.onFailure {
                _upgradePrepareProgress.postValue(100)
                val fallbackResult = RecommendationMockData.BuildCompatibilityResult(
                    isCompatible = true,
                    issues = emptyList()
                )
                if (_buildCompatibilityResult.value == null) {
                    _buildCompatibilityResult.postValue(fallbackResult)
                }
            }
        }
    }

    fun loadUpgradeRecommendations() {
        if (_upgradeRecoScreenState.value is ScreenState.Success) {
            return
        }
        _upgradeRecoScreenState.value = ScreenState.Loading
        viewModelScope.launch {
            try {
                android.util.Log.d("NoBuildYet", "[NoBuildYet] loadUpgradeRecommendations started")
                _upgradePrepareProgress.postValue(0)

                val searchedComponent = _selectedComponent.value ?: "Component"
                android.util.Log.d("NoBuildYet", "[NoBuildYet] Searched component: $searchedComponent")

                _upgradePrepareProgress.postValue(20)

                val searchedCategory = RecommendationMockData.resolveCategory(searchedComponent)
                android.util.Log.d("NoBuildYet", "[NoBuildYet] Category resolved: $searchedCategory")
                _upgradePrepareProgress.postValue(45)

                val searchedCompTier = RecommendationMockData.estimateComponentPerformanceTier(searchedComponent, searchedCategory)
                android.util.Log.d("NoBuildYet", "[NoBuildYet] Tier estimated: $searchedCompTier")
                _upgradePrepareProgress.postValue(65)

                _upgradePrepareProgress.postValue(85)
                publishUpgradeRecoState()

                _upgradePrepareProgress.postValue(100)
                android.util.Log.d("NoBuildYet", "[NoBuildYet] loadUpgradeRecommendations completed successfully")
            } catch (e: Exception) {
                android.util.Log.e("NoBuildYet", "[NoBuildYet] Error in loadUpgradeRecommendations", e)
                _upgradePrepareProgress.postValue(100)
                if (_upgradeRecoScreenState.value !is ScreenState.Success) {
                    _upgradeRecoScreenState.value = ScreenState.Error(
                        e.message ?: "Unable to load upgrade recommendations."
                    )
                }
            } finally {
                android.util.Log.d("NoBuildYet", "[NoBuildYet] loadUpgradeRecommendations finally block")
                // Ensure loading state is reset if not already Success or Error
                if (_upgradeRecoScreenState.value is ScreenState.Loading) {
                    android.util.Log.w("NoBuildYet", "[NoBuildYet] Loading state still active in finally - resetting to Error")
                    _upgradeRecoScreenState.value = ScreenState.Error("Unable to load upgrade recommendations.")
                }
            }
        }
    }

    fun saveAllUpgradeRecommendations(): Pair<Int, Int> {
        val state = _upgradeRecoScreenState.value as? ScreenState.Success ?: return Pair(0, 0)
        val component = _selectedComponent.value?.takeIf { it.isNotBlank() } ?: return Pair(0, 0)
        val hasExistingBuild = _hasExistingBuild.value ?: true
        val allTiers = listOfNotNull(state.data.entryPath, state.data.midPath, state.data.highPath)
        val upgradeCount = saveUpgradePathsInternal(
            component = component,
            hasExistingBuild = hasExistingBuild,
            paths = allTiers,
            displayTitleOverride = null
        )
        val buildSavedCount = if (hasExistingBuild) saveCurrentOriginalBuild() else 0
        return Pair(upgradeCount, buildSavedCount)
    }

    private fun publishExactSearchResult(exact: ExactSearchResult.Success) {
        // Recognition already supplies the result; build generation belongs to its own screen.
        val component = exact.component.name
        _searchProgress.postValue(100)
        _searchResultsReady.postValue(true)
        _searchResultsScreenState.value = ScreenState.Success(
            SearchResultsUiState(
                title = "Results for: $component",
                results = listOf(component),
                selectedComponent = component,
                componentDetail = exact.component
            )
        )
    }

    private suspend fun publishSearchResultsState(resultsOverride: List<String>? = null) {
        val query = _searchQuery.value ?: "Results"
        val selectedNormalized = _selectedComponent.value
        val current = (_searchResultsScreenState.value as? ScreenState.Success)?.data
        if (resultsOverride == null && selectedNormalized != null &&
            current?.selectedComponent == selectedNormalized && current?.componentDetail != null
        ) {
            return
        }
        val baseResults = resultsOverride ?: repository.getSearchResults(query)
        val results = if (!selectedNormalized.isNullOrBlank()) {
            (listOf(selectedNormalized) + baseResults.filter { it != selectedNormalized }).distinct()
        } else {
            baseResults
        }
        runCatching {
            val selected = selectedNormalized ?: results.firstOrNull()
            val componentDetail = selected?.let { repository.getComponentDetails(it) }
            val displayTitle = if (!selectedNormalized.isNullOrBlank()) {
                "Results for: $selectedNormalized"
            } else {
                "Results for: $query"
            }
            ScreenState.Success(
                SearchResultsUiState(
                    title = displayTitle,
                    results = results,
                    selectedComponent = selected,
                    componentDetail = componentDetail
                )
            )
        }.onSuccess { state ->
            _searchResultsScreenState.value = state
        }.onFailure { error ->
            _searchResultsScreenState.value = ScreenState.Error(
                error.message ?: "Unable to load search results."
            )
        }
    }

    /** Caches all three tier builds and returns the tier that ends up selected for display. */
    private suspend fun ensureAllBuildsCached(component: String, category: String): BuildTier {
        return buildCacheMutex.withLock {
            val currentSelected = _selectedBuildTier.value
            if (buildCacheComponentKey == component && buildCache.size >= 3 && detectedTierCache != null) {
                _allBuildsCacheProgress.postValue(100)
                return@withLock currentSelected ?: BuildTier.ENTRY
            }

            buildCache.clear()
            buildCacheComponentKey = component
            detectedTierCache = null
            _allBuildsCacheProgress.postValue(5)

            val recommendations = repository.getBuildRecommendations(component)
            _allBuildsCacheProgress.postValue(18)

            val detectedTierRaw = runCatching { repository.getDetectedComponentTier(component) }.getOrDefault("mid")
            detectedTierCache = detectedTierRaw
            _allBuildsCacheProgress.postValue(28)

            val tiers = listOf(
                Triple(BuildTier.ENTRY, recommendations.getOrNull(0), "ENTRY BUILD"),
                Triple(BuildTier.MID, recommendations.getOrNull(1), "MIDRANGE BUILD"),
                Triple(BuildTier.HIGH, recommendations.getOrNull(2), "HIGH-END BUILD")
            )

            tiers.forEachIndexed { idx, (tier, rawBuild, label) ->
                val build = rawBuild ?: recommendations.firstOrNull()
                // Keep the searched/scanned/uploaded component as the actual anchor of every build
                // tier, then let the detected-tier filter decide which tabs the user may access.
                val enforcedBuild = build?.let { b ->
                    enforceComponentInBuild(b, component, category)
                }
                val cleanedBuild = enforcedBuild?.copy(
                    name = removePriceFromBuildName(enforcedBuild.name),
                    label = label
                )
                val validatedBuild = cleanedBuild?.let(::ensureBottleneckAnalysisSanity)
                if (validatedBuild != null) {
                    buildCache[component to tier] = validatedBuild
                }
                when (idx) {
                    0 -> _allBuildsCacheProgress.postValue(50)
                    1 -> _allBuildsCacheProgress.postValue(75)
                    2 -> _allBuildsCacheProgress.postValue(95)
                }
            }

            // Never auto-select a tier whose build is a placeholder: those tabs are hidden from the
            // user, so selecting one would render an empty "--" build. Prefer the detected tier when
            // it has a real build, otherwise the nearest tier that does.
            resolveDisplayBuildTier(component, currentSelected, detectedTierCache)
        }
    }

    private fun publishBuildFromCache(component: String, tier: BuildTier): ScreenState<BuildRecommendationUiState> {
        val cachedBuildRaw = buildCache[component to tier]
        // Never publish a Success state with a null build: that leaves the build screen blank.
        val cachedBuild = cachedBuildRaw?.let(::ensureBottleneckAnalysisSanity)
            ?: return ScreenState.Error("Build generation failed. Please try again.")
        val tierRaw = detectedTierCache ?: "mid"
        val tierLabel = when (tierRaw.trim().lowercase()) {
            "entry" -> "DETECTED TIER: Entry"
            "mid", "midrange" -> "DETECTED TIER: Midrange"
            "high", "high-end", "highend", "high end" -> "DETECTED TIER: High-End"
            else -> "DETECTED TIER: Midrange"
        }
        val bn = cachedBuild.bottleneckAnalysis
        val valid = bn != null &&
            bn.percentage in 0..20 &&
            bn.withinLimit &&
            bn.detailedAnalysis.isNotBlank() &&
            bn.detailedAnalysis.length > 10
        if (!valid) {
            return ScreenState.Error("Build validation failed. Please retry.")
        }
        return ScreenState.Success(
            BuildRecommendationUiState(
                basedOnText = "Based on: $component",
                selectedTier = tier,
                detectedComponentTier = tierRaw,
                detectedTierLabel = tierLabel,
                build = cachedBuild
            )
        )
    }

    private suspend fun publishBuildRecommendationState(showLoading: Boolean = false) {
        val component = _selectedComponent.value ?: "GPU"
        val selectedTier = _selectedBuildTier.value ?: BuildTier.ENTRY
        val category = RecommendationMockData.resolveCategory(component)

        val cacheValid = buildCacheComponentKey == component && buildCache.size >= 3

        if (!cacheValid) {
            if (showLoading) {
                _buildRecommendationScreenState.value = ScreenState.Loading
            }
            val result = runCatching {
                val resolvedTier = ensureAllBuildsCached(component, category)
                if (resolvedTier != selectedTier) {
                    _selectedBuildTier.value = resolvedTier
                    persistState()
                }
                publishBuildFromCache(component, resolvedTier)
            }
            if (showLoading) {
                kotlinx.coroutines.delay(1200L)
            }
            result
                .onSuccess { state ->
                    _buildRecommendationScreenState.value = state
                }.onFailure { error ->
                    _buildRecommendationScreenState.value = ScreenState.Error(
                        error.message ?: "Unable to load build recommendations."
                    )
                }
        } else {
            val resolvedTier = resolveDisplayBuildTier(component, selectedTier, detectedTierCache)
            if (resolvedTier != selectedTier) {
                _selectedBuildTier.value = resolvedTier
                persistState()
            }
            _buildRecommendationScreenState.value =
                publishBuildFromCache(component, resolvedTier)
        }
    }

    private fun removePriceFromBuildName(name: String): String {
        var cleaned = name
        val patternsToRemove = listOf(
            Regex("""\(PHP\s*\d[\d,]*\+?[^)]*\)""", RegexOption.IGNORE_CASE),
            Regex("""PHP\s*\d[\d,]*\+?\w*""", RegexOption.IGNORE_CASE),
            Regex("""\$\s*\d[\d,]*\+?\w*"""),
            Regex("""P\s*\d[\d,]*\+?\w*"""),
            Regex("""\(.*price.*\)""", RegexOption.IGNORE_CASE),
            Regex("""\(.*budget.*\)""", RegexOption.IGNORE_CASE),
            Regex("""\(.*cost.*\)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patternsToRemove) {
            cleaned = cleaned.replace(pattern, "")
        }
        cleaned = cleaned.replace(Regex("""\s+"""), " ").trim()
        return cleaned.ifBlank { "Custom Build" }
    }

    private fun enforceComponentInBuild(build: BuildRecommendation, component: String, category: String): BuildRecommendation {
        return when (category) {
            "gpu" -> build.copy(gpu = component)
            "cpu" -> build.copy(cpu = component)
            "ram" -> build.copy(ram = component)
            "motherboard" -> build.copy(motherboard = component)
            "storage" -> build.copy(storage = component)
            "psu" -> build.copy(psu = component)
            else -> build
        }
    }

    private fun ensureBottleneckAnalysisSanity(build: BuildRecommendation): BuildRecommendation {
        val unavailableMarker = "NOT AVAILABLE for this component tier"
        val isUnavailable = build.name.equals(unavailableMarker, ignoreCase = true) ||
            build.cpu.equals(unavailableMarker, ignoreCase = true)
        if (isUnavailable) {
            val placeholder = (build.bottleneckAnalysis ?: BottleneckAnalysis.EMPTY).copy(
                limitingComponent = "Balanced",
                summary = build.bottleneck.ifBlank { "Tier unavailable." },
                detailedAnalysis = build.bottleneckNote.ifBlank { "This tier is unavailable for the searched component tier." },
                reason = "Placeholder tier-unavailable build.",
                withinLimit = true,
                percentage = 0,
                status = "UNAVAILABLE"
            )
            return if (build.bottleneckAnalysis == placeholder) build else build.copy(bottleneckAnalysis = placeholder)
        }

        val existing = build.bottleneckAnalysis
        val pctFromLegacyStr = Regex("""(\d{1,2})""").find(build.bottleneck)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val fallbackPct = pctFromLegacyStr?.coerceIn(0, 20) ?: 12

        val valid = existing != null &&
            existing.percentage in 0..20 &&
            existing.withinLimit &&
            existing.detailedAnalysis.isNotBlank() &&
            existing.detailedAnalysis.length > 10 &&
            existing.status.isNotBlank() &&
            existing.status != "UNKNOWN" &&
            existing.limitingComponent.isNotBlank() &&
            existing.summary.isNotBlank() &&
            existing.reason.isNotBlank() &&
            existing.gaming1080p.isNotBlank() &&
            existing.gaming1440p.isNotBlank() &&
            existing.gaming4k.isNotBlank()
        if (valid) {
            return build
        }

        val cpu = build.cpu.ifBlank { "AMD Ryzen 5 7600" }
        val gpu = build.gpu.ifBlank { "NVIDIA GeForce RTX 4060" }
        val pct = existing?.percentage?.takeIf { it in 0..20 } ?: fallbackPct
        val limitingRaw = existing?.limitingComponent.orEmpty().ifBlank {
            when {
                build.bottleneckNote.contains("cpu", ignoreCase = true) -> "CPU"
                build.bottleneckNote.contains("gpu", ignoreCase = true) -> "GPU"
                else -> "Balanced"
            }
        }
        val status = when {
            pct <= 10 -> "EXCELLENT"
            pct <= 15 -> "GOOD"
            else -> "ACCEPTABLE"
        }
        val limiting = when {
            limitingRaw.contains("cpu", ignoreCase = true) -> "CPU"
            limitingRaw.contains("gpu", ignoreCase = true) -> "GPU"
            else -> "Balanced"
        }
        val summary = "Estimated bottleneck: approximately ${pct}%. (Status: $status.) The $cpu and $gpu are reasonably balanced. Neither the CPU nor the GPU is expected to significantly restrict the other in typical gaming workloads."
        val noteRaw = build.bottleneckNote
        val prefix = if (noteRaw.isBlank()) "" else noteRaw.trim() + " "
        val detailed = buildString {
            append(prefix)
            append("This estimate reflects the typical performance difference between the two chips across a representative suite of modern titles. ")
            append("If the CPU has fewer cores, lower IPC, or smaller L3 cache than a flagship class, it can limit high-end GPU throughput at low resolutions. ")
            append("If the GPU has fewer compute units or lower VRAM capacity, it becomes the limiting factor as resolution and texture detail increase. ")
            append("Actual results vary depending on five factors: Game, Resolution, Graphics settings, FPS target, and Workload. ")
            append("Estimated bottleneck; actual result varies by workload.")
        }
        val g1080 = if (limiting == "CPU") {
            "At 1080p the Ryzen/i5-class CPU may become more noticeable at high FPS targets such as 144Hz or 240Hz. Turn-based / strategy titles are unlikely to show the difference, but competitive shooters often scale harder into CPU performance at this resolution."
        } else {
            "At 1080p this pairing is typically GPU-bound, though fast-paced competitive titles with uncapped frame rates can still stress the CPU enough to keep the GPU waiting. For 60 FPS casual gameplay the experience is generally smooth."
        }
        val g1440 = "At 1440p this pairing usually lands in the 1440p sweet spot where load is distributed between CPU and GPU. Bottleneck becomes rare in most AAA titles; CPU-limited scenarios occur mainly in simulation-heavy games with many units or heavy draw-call overhead."
        val g4k = "At 4K rendering the GPU is almost always the limiting component regardless of the CPU for most AAA titles. Unless running extremely high-FPS simulation or strategy workloads, the CPU will rarely be the bottleneck at 4K resolution."
        val reason = when (limiting) {
            "CPU" -> "The CPU is the limitation because its per-thread performance, core count, or cache size are not high enough to keep up with the GPU at low resolutions when the GPU is lightly loaded."
            "GPU" -> "The GPU is the limitation because its raw CUDA/shader count or onboard VRAM capacity cannot push the pixel throughput needed at the target resolution before the CPU runs out of work to hand it."
            else -> "Neither component substantially limits the other in this pairing; their relative performance tiers are well matched for balanced 1440p and typical 4K gaming workloads."
        }
        val rebuilt = BottleneckAnalysis(
            percentage = pct,
            status = status,
            limitingComponent = limiting,
            summary = summary,
            detailedAnalysis = detailed.trim(),
            gaming1080p = g1080,
            gaming1440p = g1440,
            gaming4k = g4k,
            reason = reason,
            withinLimit = pct <= 20
        )
        return build.copy(bottleneckAnalysis = rebuilt)
    }

    private suspend fun publishUpgradePathState(showLoading: Boolean = false) {
        if (showLoading) {
            _upgradePathScreenState.value = ScreenState.Loading
        }

        runCatching {
            val component = _selectedComponent.value ?: "Component"
            val hasExistingBuild = _hasExistingBuild.value
            val path = if (hasExistingBuild == null) {
                null
            } else {
                repository.getUpgradePath(component, hasExistingBuild)
            }

            ScreenState.Success(
                UpgradePathUiState(
                    basedOnText = "Based on: $component",
                    hasExistingBuild = hasExistingBuild,
                    isSummaryVisible = path != null && hasExistingBuild != null,
                    pathModeText = when (hasExistingBuild) {
                        true -> "Current Build"
                        false -> "New Build"
                        null -> ""
                    },
                    path = path
                )
            )
        }.onSuccess { state ->
            _upgradePathScreenState.value = state
        }.onFailure { error ->
            _upgradePathScreenState.value = ScreenState.Error(
                error.message ?: "Unable to load upgrade path."
            )
        }
    }

    private suspend fun publishUpgradeRecoState(showLoading: Boolean = false) {
        if (showLoading) {
            _upgradeRecoScreenState.value = ScreenState.Loading
        }

        runCatching {
            val searchedComponent = _selectedComponent.value ?: "Component"
            val hasExistingBuild = _hasExistingBuild.value ?: true
            val build = _currentBuild.value ?: CurrentBuild()

            android.util.Log.d("NoBuildYet", "[NoBuildYet] Search started: $searchedComponent")
            android.util.Log.d("NoBuildYet", "[NoBuildYet] Has existing build: $hasExistingBuild")

            val fullBuildAvailable = hasExistingBuild && !build.isEmpty

            if (hasExistingBuild && !build.isEmpty) {
                val v3Attempt = runCatching {
                    val uiState = RecommendationMockData.buildV3UpgradeUiState(searchedComponent, true, build)
                    android.util.Log.d("UpgradeV3", "[V3] Built V3 UpgradeRecoUiState for $searchedComponent, tier=${uiState.currentBuildTier}, paths=${uiState.tierCount}")
                    ScreenState.Success(uiState)
                }
                if (v3Attempt.isSuccess) {
                    return@runCatching v3Attempt.getOrThrow()
                } else {
                    android.util.Log.w("UpgradeV3", "[V3] V3 generator failed, falling back to legacy pipeline: ${v3Attempt.exceptionOrNull()?.message}", v3Attempt.exceptionOrNull())
                }
            }

            val detectedTier = if (fullBuildAvailable) {
                RecommendationMockData.detectBuildTier(build)
            } else {
                null
            }
            val detectedTierLabel = when (detectedTier) {
                BuildTier.ENTRY -> "Entry-Level Build"
                BuildTier.MID -> "Mid-Range Build"
                BuildTier.HIGH -> "High-End Build"
                null -> ""
            }

            var entryPath: UpgradePath? = null
            var midPath: UpgradePath? = null
            var highPath: UpgradePath? = null

            when {
                _yesBuildPipelineResult.value != null -> {
                    val pipeline = _yesBuildPipelineResult.value!!
                    val step3 = pipeline.step3
                    val pipelineBuildAvailable = hasExistingBuild && !pipeline.parsedBuild.isEmpty
                    val anyFullBuildAvailable = fullBuildAvailable || pipelineBuildAvailable
                    val buildForTier = when {
                        fullBuildAvailable -> build
                        pipelineBuildAvailable -> pipeline.parsedBuild
                        else -> pipeline.parsedBuild
                    }
                    val pipelineDetectedTier: BuildTier? = if (buildForTier.isEmpty) null else RecommendationMockData.detectBuildTier(buildForTier)
                    val effectiveBuild = buildForTier
                    val effectiveTierFromLocal: RecommendationMockData.UserCompTier3? = when (pipelineDetectedTier) {
                        BuildTier.ENTRY -> RecommendationMockData.UserCompTier3.ENTRY
                        BuildTier.MID -> RecommendationMockData.UserCompTier3.MIDRANGE
                        BuildTier.HIGH -> RecommendationMockData.UserCompTier3.HIGH_END
                        null -> null
                    }
                    val effectiveTier = if (anyFullBuildAvailable && effectiveTierFromLocal != null) effectiveTierFromLocal else pipeline.buildTier3
                    val localMidPath = runCatching { repository.getUpgradePath(searchedComponent, effectiveBuild, BuildTier.MID) }.getOrNull()
                    val localHighPath = runCatching { repository.getUpgradePath(searchedComponent, effectiveBuild, BuildTier.HIGH) }.getOrNull()
                    when (effectiveTier) {
                        RecommendationMockData.UserCompTier3.ENTRY -> {
                            entryPath = null
                            val midTitle = step3.mid_upgrade_title?.takeIf { it.isNotBlank() } ?: localMidPath?.title ?: "Midrange Upgrade Path"
                            val midDesc = step3.mid_upgrade_description?.takeIf { it.isNotBlank() }
                                ?: localMidPath?.description
                                ?: "Balanced upgrades to convert your ENTRY build into a solid MIDRANGE 1440p gaming system."
                            val midComps = localMidPath?.components?.takeIf { it.isNotEmpty() }
                                ?: step3.mid_upgrade_components?.takeIf { it.isNotEmpty() }
                                ?: repository.getUpgradePath(searchedComponent, effectiveBuild, BuildTier.MID).components
                            midPath = UpgradePath(
                                title = midTitle,
                                description = midDesc,
                                tier = "MIDRANGE",
                                components = midComps
                            )
                            highPath = null
                            android.util.Log.d("YesBuild", "[YesBuild] ENTRY build detected (local mix-aware) - 1 upgrade tier (midrange only)")
                        }
                        RecommendationMockData.UserCompTier3.MIDRANGE -> {
                            entryPath = null
                            midPath = null
                            val highTitle = step3.high_upgrade_title.takeIf { it.isNotBlank() } ?: localHighPath?.title ?: "High-End Upgrade Path"
                            val highDesc = step3.high_upgrade_description.takeIf { it.isNotBlank() }
                                ?: localHighPath?.description
                                ?: "Upgrades to transform your MIDRANGE build into a 4K high-end flagship class system. Platform-aware: keep compatible mobo/RAM/SSD, upgrade bottleneck parts."
                            val highComps = localHighPath?.components?.takeIf { it.isNotEmpty() }
                                ?: step3.high_upgrade_components.takeIf { it.isNotEmpty() }
                                ?: repository.getUpgradePath(searchedComponent, effectiveBuild, BuildTier.HIGH).components
                            highPath = UpgradePath(
                                title = highTitle,
                                description = highDesc,
                                tier = "HIGH-END",
                                components = highComps
                            )
                            android.util.Log.d("YesBuild", "[YesBuild] MIDRANGE build detected (local mix-aware) - 1 upgrade tier (high only)")
                        }
                        RecommendationMockData.UserCompTier3.HIGH_END -> {
                            entryPath = null
                            midPath = null
                            val topOfLine = if (anyFullBuildAvailable && isCurrentBuildAlreadyTopTier(effectiveBuild, searchedComponent)) {
                                buildTopOfLineUpgradePath(effectiveBuild, searchedComponent)
                            } else null
                            if (topOfLine != null || step3.no_upgrade_available) {
                                val fallback = topOfLine ?: buildTopOfLineUpgradePath(effectiveBuild, searchedComponent)
                                val gptMsg = step3.no_upgrade_message.takeIf { it.isNotBlank() }
                                val curr = effectiveBuild
                                val comps = mutableListOf<UpgradeComponent>()
                                if (curr.cpu.isNotBlank()) comps += UpgradeComponent("CPU", curr.cpu, "Current CPU already top of the line / high-end tier for your platform", 99, 100f, null)
                                if (curr.gpu.isNotBlank()) comps += UpgradeComponent("GPU", curr.gpu, "Current GPU already top of the line / flagship-tier GPU", 99, 100f, null)
                                if (curr.motherboard.isNotBlank()) comps += UpgradeComponent("MOTHERBOARD", curr.motherboard, "Current motherboard already platform-compatible high-end board", 99, 100f, null)
                                if (curr.ram.isNotBlank()) comps += UpgradeComponent("RAM", curr.ram, "Current RAM already large high-end memory kit", 99, 100f, null)
                                if (curr.storage.isNotBlank()) comps += UpgradeComponent("STORAGE", curr.storage, "Current storage already high-end capacity/speed", 99, 100f, null)
                                if (curr.psu.isNotBlank()) comps += UpgradeComponent("PSU", curr.psu, "Current PSU already high-wattage reliable 80+ Gold/Platinum/Titanium", 99, 100f, null)
                                val finalComps = comps.ifEmpty { fallback.components }
                                highPath = UpgradePath(
                                    title = fallback.title,
                                    description = gptMsg ?: fallback.description,
                                    tier = "HIGH-END",
                                    components = finalComps
                                )
                            } else {
                                val highTitle = step3.high_upgrade_title.takeIf { it.isNotBlank() } ?: localHighPath?.title ?: "High-End Adjustments"
                                val highDesc = step3.high_upgrade_description.takeIf { it.isNotBlank() }
                                    ?: localHighPath?.description
                                    ?: "Optional minor adjustments and strongest possible upgrades for your already high-end build."
                                val highComps = localHighPath?.components?.takeIf { it.isNotEmpty() }
                                    ?: step3.high_upgrade_components.takeIf { it.isNotEmpty() }
                                    ?: repository.getUpgradePath(searchedComponent, effectiveBuild, BuildTier.HIGH).components
                                highPath = UpgradePath(
                                    title = highTitle,
                                    description = highDesc,
                                    tier = "HIGH-END",
                                    components = highComps
                                )
                            }
                            android.util.Log.d("YesBuild", "[YesBuild] HIGH_END build detected (local mix-aware) - 1 tier")
                        }
                    }
                }
                detectedTier == BuildTier.ENTRY -> {
                    entryPath = null
                    if (fullBuildAvailable) {
                        midPath = repository.getUpgradePath(searchedComponent, build, BuildTier.MID)
                        highPath = null
                    } else {
                        midPath = repository.getUpgradePath(searchedComponent, hasExistingBuild, BuildTier.MID)
                        highPath = repository.getUpgradePath(searchedComponent, hasExistingBuild, BuildTier.HIGH)
                    }
                }
                detectedTier == BuildTier.MID -> {
                    entryPath = null
                    midPath = null
                    highPath = if (fullBuildAvailable) repository.getUpgradePath(searchedComponent, build, BuildTier.HIGH)
                    else repository.getUpgradePath(searchedComponent, hasExistingBuild, BuildTier.HIGH)
                }
                detectedTier == BuildTier.HIGH -> {
                    entryPath = null
                    midPath = null
                    highPath = if (fullBuildAvailable && isCurrentBuildAlreadyTopTier(build, searchedComponent)) {
                        buildTopOfLineUpgradePath(build, searchedComponent)
                    } else if (fullBuildAvailable) {
                        repository.getUpgradePath(searchedComponent, build, BuildTier.HIGH)
                    } else {
                        repository.getUpgradePath(searchedComponent, hasExistingBuild, BuildTier.HIGH)
                    }
                }
                else -> {
                    val searchedCategory = RecommendationMockData.resolveCategory(searchedComponent)
                    val userCompTier = RecommendationMockData.classifyUserComponentTier3(searchedComponent, searchedCategory)
                    when (userCompTier) {
                        RecommendationMockData.UserCompTier3.ENTRY -> {
                            entryPath = null
                            if (fullBuildAvailable) {
                                midPath = repository.getUpgradePath(searchedComponent, build, BuildTier.MID)
                                highPath = null
                            } else {
                                midPath = repository.getUpgradePath(searchedComponent, hasExistingBuild, BuildTier.MID)
                                highPath = repository.getUpgradePath(searchedComponent, hasExistingBuild, BuildTier.HIGH)
                            }
                        }
                        RecommendationMockData.UserCompTier3.MIDRANGE -> {
                            entryPath = null
                            midPath = null
                            highPath = if (fullBuildAvailable) {
                                repository.getUpgradePath(searchedComponent, build, BuildTier.HIGH)
                            } else {
                                repository.getUpgradePath(searchedComponent, hasExistingBuild, BuildTier.HIGH)
                            }
                        }
                        RecommendationMockData.UserCompTier3.HIGH_END -> {
                            entryPath = null
                            midPath = null
                            val strongerComp = if (fullBuildAvailable) null else RecommendationMockData.findStrongerHighEndUpgradeOrNull(searchedComponent, searchedCategory)
                            highPath = if (fullBuildAvailable && isCurrentBuildAlreadyTopTier(build, searchedComponent)) {
                                buildTopOfLineUpgradePath(build, searchedComponent)
                            } else if (fullBuildAvailable) {
                                repository.getUpgradePath(searchedComponent, build, BuildTier.HIGH)
                            } else if (strongerComp != null) {
                                val strongCat = RecommendationMockData.resolveCategory(strongerComp)
                                runCatching {
                                    repository.getUpgradePath(strongerComp, hasExistingBuild, BuildTier.HIGH).let { base ->
                                        base.copy(
                                            title = "High-End Upgrade Path",
                                            description = "No-compromise upgrade for maximum gaming, productivity, and future-proof performance. Upgrades ${searchedComponent} to a significantly stronger high-end option."
                                        )
                                    }
                                }.getOrElse {
                                    val baseComponents = listOf(
                                        UpgradeComponent(
                                            category = strongCat,
                                            name = strongerComp,
                                            description = "Significantly stronger high-end upgrade over your current $searchedComponent",
                                            compatibility = 96,
                                            progressPercent = 100f,
                                            scores = null
                                        )
                                    )
                                    UpgradePath(
                                        title = "High-End Upgrade Path",
                                        description = "No-compromise upgrade for maximum gaming, productivity, and future-proof performance. Upgrades ${searchedComponent} to a significantly stronger high-end option.",
                                        tier = "HIGH-END",
                                        components = baseComponents
                                    )
                                }
                            } else {
                                val baseComponents = listOf(
                                    UpgradeComponent(
                                        category = searchedCategory,
                                        name = searchedComponent,
                                        description = "Your component is already at the top of the current high-end lineup.",
                                        compatibility = 99,
                                        progressPercent = 100f,
                                        scores = null
                                    )
                                )
                                UpgradePath(
                                    title = "Your component is already a high-end component",
                                    description = "No stronger component is currently recommended in the same high-end tier. Your part is already top of the line; upgrade only for next-gen generational jumps or platform changes.",
                                    tier = "HIGH-END",
                                    components = baseComponents
                                )
                            }
                        }
                    }
                }
            }

            val tierCount = listOfNotNull(entryPath, midPath, highPath).size
            android.util.Log.d("NoBuildYet", "[NoBuildYet] Updating UI with $tierCount paths")

            ScreenState.Success(
                UpgradeRecoUiState(
                    basedOnText = "Based on: $searchedComponent",
                    currentBuild = build,
                    currentBuildTier = detectedTier,
                    currentBuildTierLabel = detectedTierLabel,
                    tierCount = tierCount,
                    entryPath = entryPath,
                    midPath = midPath,
                    highPath = highPath
                )
            )
        }.onSuccess { state ->
            android.util.Log.d("NoBuildYet", "[NoBuildYet] Success - updating UI state")
            _upgradeRecoScreenState.value = state
        }.onFailure { error ->
            android.util.Log.e("NoBuildYet", "[NoBuildYet] Error: ${error.message}", error)
            _upgradeRecoScreenState.value = ScreenState.Error(
                error.message ?: "Unable to load upgrade recommendations."
            )
        }
    }

    private fun isCurrentBuildAlreadyTopTier(build: CurrentBuild, searchedComponent: String): Boolean {
        if (build.isEmpty) return false
        val nonBlankParts = listOfNotNull(
            build.cpu.takeIf { it.isNotBlank() },
            build.gpu.takeIf { it.isNotBlank() },
            build.motherboard.takeIf { it.isNotBlank() },
            build.ram.takeIf { it.isNotBlank() },
            build.storage.takeIf { it.isNotBlank() },
            build.psu.takeIf { it.isNotBlank() },
            searchedComponent.takeIf { it.isNotBlank() }
        ).distinct()
        if (nonBlankParts.isEmpty()) return false
        return nonBlankParts.all { part ->
            val cat = RecommendationMockData.resolveCategory(part)
            RecommendationMockData.classifyUserComponentTier3(part, cat) == RecommendationMockData.UserCompTier3.HIGH_END
        }
    }

    private fun buildTopOfLineUpgradePath(build: CurrentBuild, searchedComponent: String): UpgradePath {
        val comps = mutableListOf<UpgradeComponent>()
        if (build.cpu.isNotBlank()) comps += UpgradeComponent("CPU", build.cpu, "Current CPU already top of the line / high-end tier for your platform", 99, 100f, null)
        if (build.gpu.isNotBlank()) comps += UpgradeComponent("GPU", build.gpu, "Current GPU already top of the line / flagship-tier GPU", 99, 100f, null)
        if (build.motherboard.isNotBlank()) comps += UpgradeComponent("MOTHERBOARD", build.motherboard, "Current motherboard already platform-compatible high-end board", 99, 100f, null)
        if (build.ram.isNotBlank()) comps += UpgradeComponent("RAM", build.ram, "Current RAM already large high-end memory kit", 99, 100f, null)
        if (build.storage.isNotBlank()) comps += UpgradeComponent("STORAGE", build.storage, "Current storage already high-end capacity/speed", 99, 100f, null)
        if (build.psu.isNotBlank()) comps += UpgradeComponent("PSU", build.psu, "Current PSU already high-wattage reliable 80+ Gold/Platinum/Titanium", 99, 100f, null)
        val finalComps = comps.ifEmpty {
            listOf(
                UpgradeComponent(
                    category = RecommendationMockData.resolveCategory(searchedComponent),
                    name = searchedComponent,
                    description = "Your component is already top of the line for its current platform generation.",
                    compatibility = 99,
                    progressPercent = 100f,
                    scores = null
                )
            )
        }
        return UpgradePath(
            title = "Your current build is already using top-of-the-line components",
            description = "All of your current build parts are already classified at the highest available tier for today's platform. No more incremental upgrades are recommended within your current socket / chipset generation; upgrade only for next-gen generational platform jumps or brand new architecture releases.",
            tier = "HIGH-END",
            components = finalComps
        )
    }

    companion object {
        private const val PREF_DASHBOARD = "dashboard_prefs"
        private const val KEY_SCAN_COUNT = "key_scan_count"
        private const val KEY_PREFS_BUILD_COUNT = "prefs_saved_build_count"
        private const val KEY_PREFS_BUILD_ITEM = "prefs_saved_build_item"
        private const val KEY_PREFS_UPGRADE_COUNT = "prefs_saved_upgrade_count"
        private const val KEY_PREFS_UPGRADE_ITEM = "prefs_saved_upgrade_item"
        private const val KEY_PREFS_RECENT_COUNT = "prefs_recent_count"
        private const val KEY_PREFS_RECENT_ITEM = "prefs_recent_item"
        private const val RECENT_ACTIVITY_LIMIT = 5
        private const val KEY_SEARCH_QUERY = "recommendation_search_query"
        private const val KEY_SELECTED_COMPONENT = "recommendation_selected_component"
        private const val KEY_SELECTED_BUILD_TIER = "recommendation_selected_build_tier"
        private const val KEY_HAS_EXISTING_BUILD = "recommendation_has_existing_build"
        private const val KEY_CURRENT_BUILD = "recommendation_current_build"
        private const val KEY_SAVED_BUILDS = "recommendation_saved_builds"
        private const val KEY_SAVED_UPGRADES = "recommendation_saved_upgrades"
        private const val KEY_RECENT_ACTIVITIES = "recommendation_recent_activities"
        private const val SAVED_BUILD_SEPARATOR = "\u001F"
        private const val SAVED_UPGRADE_SEPARATOR = "\u001E"
        private const val RECENT_ACTIVITY_SEPARATOR = "\u001D"
        private const val CURRENT_BUILD_SEPARATOR = "\u001C"
        private const val UPGRADE_V2_MARKER = "V2|"
        private const val SUB_PATH_SEPARATOR = "\u0018"
        private const val SUB_COMPONENT_SEPARATOR = "\u0017"
        private const val SUB_FIELD_SEPARATOR = "\u0016"

        private const val USERS_COLLECTION = "users"
        private const val SAVED_BUILDS_COLLECTION = "savedBuilds"
        private const val SAVED_UPGRADES_COLLECTION = "savedUpgrades"

        private const val FIELD_COMPONENT = "component"
        private const val FIELD_TIER = "tier"
        private const val FIELD_LABEL = "label"
        private const val FIELD_NAME = "name"
        private const val FIELD_CPU = "cpu"
        private const val FIELD_GPU = "gpu"
        private const val FIELD_MOTHERBOARD = "motherboard"
        private const val FIELD_RAM = "ram"
        private const val FIELD_STORAGE = "storage"
        private const val FIELD_PSU = "psu"
        private const val FIELD_WATTAGE = "wattage"
        private const val FIELD_WATTAGE_NOTE = "wattageNote"
        private const val FIELD_BOTTLENECK = "bottleneck"
        private const val FIELD_BOTTLENECK_NOTE = "bottleneckNote"

        private const val FIELD_BN_PCT = "bn_pct"
        private const val FIELD_BN_STATUS = "bn_status"
        private const val FIELD_BN_LIMITING = "bn_limiting"
        private const val FIELD_BN_SUMMARY = "bn_summary"
        private const val FIELD_BN_DETAILED = "bn_detailed"
        private const val FIELD_BN_1080P = "bn_1080p"
        private const val FIELD_BN_1440P = "bn_1440p"
        private const val FIELD_BN_4K = "bn_4k"
        private const val FIELD_BN_REASON = "bn_reason"
        private const val FIELD_BN_WITHIN_LIMIT = "bn_within_limit"
        private const val FIELD_IS_RANGE_BUILD = "isRangeBuild"
        private const val FIELD_BUDGET_AMOUNT = "budgetAmount"
        private const val FIELD_BUDGET_TIER_LABEL = "budgetTierLabel"
        private const val FIELD_USAGE_LABEL = "usageLabel"

        private const val FIELD_HAS_EXISTING_BUILD = "hasExistingBuild"
        private const val FIELD_TITLE = "title"
        private const val FIELD_DESCRIPTION = "description"
        private const val FIELD_STEP_ONE = "stepOne"
        private const val FIELD_STEP_TWO = "stepTwo"
        private const val FIELD_STEP_THREE = "stepThree"

        private const val FIELD_SAVED_AT = "savedAt"

        private fun buildSavedBuildId(component: String, tier: BuildTier, buildName: String): String {
            return listOf(component, tier.name, buildName)
                .joinToString("|")
                .lowercase()
        }

        private fun serializeSavedBuild(item: SavedBuildItem): String {
            val bn = item.build.bottleneckAnalysis
            val base = listOf(
                item.id,
                item.component,
                item.tier.name,
                item.build.label,
                item.build.name,
                item.build.cpu,
                item.build.gpu,
                item.build.motherboard,
                item.build.ram,
                item.build.storage,
                item.build.psu,
                item.build.wattage,
                item.build.wattageNote,
                item.build.bottleneck,
                item.build.bottleneckNote,
                (bn?.percentage ?: 0).toString(),
                bn?.status.orEmpty(),
                bn?.limitingComponent.orEmpty(),
                bn?.summary.orEmpty(),
                bn?.detailedAnalysis.orEmpty(),
                bn?.gaming1080p.orEmpty(),
                bn?.gaming1440p.orEmpty(),
                bn?.gaming4k.orEmpty(),
                bn?.reason.orEmpty(),
                (bn?.withinLimit ?: true).toString()
            ).joinToString(SAVED_BUILD_SEPARATOR)
            val rangeMeta = listOf(
                item.isRangeBuild.toString(),
                item.budgetAmount.toString(),
                item.budgetTierLabel,
                item.usageLabel
            ).joinToString(SUB_FIELD_SEPARATOR)
            return base + SAVED_BUILD_SEPARATOR + rangeMeta
        }

        private fun deserializeSavedBuild(value: String): SavedBuildItem? {
            val allParts = value.split(SAVED_BUILD_SEPARATOR)
            // New format appends a range-metadata part (16 or 26 parts total).
            val hasRangeMeta = allParts.size == 16 || allParts.size == 26
            val parts = if (hasRangeMeta) allParts.dropLast(1) else allParts
            if (parts.size != 15 && parts.size != 25) return null

            val rangeMeta = if (hasRangeMeta) allParts.last().split(SUB_FIELD_SEPARATOR) else emptyList()
            val isRangeBuild = rangeMeta.getOrNull(0)?.toBooleanStrictOrNull() ?: false
            val budgetAmount = rangeMeta.getOrNull(1)?.toLongOrNull() ?: 0L
            val budgetTierLabel = rangeMeta.getOrNull(2).orEmpty()
            val usageLabel = rangeMeta.getOrNull(3).orEmpty()

            return runCatching {
                val base = BuildRecommendation(
                    label = parts[3],
                    name = parts[4],
                    cpu = parts[5],
                    gpu = parts[6],
                    motherboard = parts[7],
                    ram = parts[8],
                    storage = parts[9],
                    psu = parts[10],
                    wattage = parts[11],
                    wattageNote = parts[12],
                    bottleneck = parts[13],
                    bottleneckNote = parts[14]
                )
                val bn: BottleneckAnalysis = if (parts.size == 25) {
                    BottleneckAnalysis(
                        percentage = parts[15].toIntOrNull()?.coerceIn(0, 20) ?: 0,
                        status = parts[16].ifBlank {
                            val pct = parts[15].toIntOrNull()?.coerceIn(0, 20) ?: 0
                            when {
                                pct <= 10 -> "EXCELLENT"
                                pct <= 15 -> "GOOD"
                                else -> "ACCEPTABLE"
                            }
                        },
                        limitingComponent = parts[17].ifBlank { "Balanced" },
                        summary = parts[18].ifBlank { "Estimated bottleneck: approximately ${parts[15].toIntOrNull()?.coerceIn(0, 20) ?: 0}%." },
                        detailedAnalysis = parts[19].ifBlank { base.bottleneckNote.ifBlank { "Estimated bottleneck; actual result varies by workload." } },
                        gaming1080p = parts[20],
                        gaming1440p = parts[21],
                        gaming4k = parts[22],
                        reason = parts[23],
                        withinLimit = parts[24].toBooleanStrictOrNull() ?: true
                    )
                } else {
                    val pct = Regex("(\\d+)").find(base.bottleneck)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 20) ?: 0
                    val limiting = when {
                        base.bottleneck.contains("CPU", true) -> "CPU"
                        base.bottleneck.contains("GPU", true) -> "GPU"
                        else -> "Balanced"
                    }
                    val status = when {
                        pct <= 10 -> "EXCELLENT"
                        pct <= 15 -> "GOOD"
                        else -> "ACCEPTABLE"
                    }
                    BottleneckAnalysis(
                        percentage = pct,
                        status = status,
                        limitingComponent = limiting,
                        summary = "Estimated bottleneck: approximately ${pct}%. (Status: $status.)",
                        detailedAnalysis = base.bottleneckNote.ifBlank { "Estimated bottleneck; actual result varies by workload." },
                        gaming1080p = "",
                        gaming1440p = "",
                        gaming4k = "",
                        reason = "",
                        withinLimit = true
                    )
                }
                SavedBuildItem(
                    id = parts[0],
                    component = parts[1],
                    tier = BuildTier.valueOf(parts[2]),
                    build = base.copy(bottleneckAnalysis = bn),
                    isRangeBuild = isRangeBuild,
                    budgetAmount = budgetAmount,
                    budgetTierLabel = budgetTierLabel,
                    usageLabel = usageLabel
                )
            }.getOrNull()
        }

        private fun buildSavedUpgradeId(component: String, hasExistingBuild: Boolean, title: String): String {
            return listOf(component, hasExistingBuild.toString(), title)
                .joinToString("|")
                .lowercase()
        }

        private fun normalizeSavedUpgradeItem(item: SavedUpgradeItem): SavedUpgradeItem {
            val effectivePaths = if (item.paths.isNotEmpty()) {
                item.paths
            } else {
                listOf(item.path)
            }
            val totalComponents = effectivePaths.sumOf { it.components.size }
            val effectiveTitle = if (item.displayTitle.isNotBlank()) {
                item.displayTitle
            } else {
                effectivePaths.firstOrNull()?.title ?: "Upgrade Path"
            }
            val primaryPath = effectivePaths.firstOrNull() ?: item.path
            return item.copy(
                path = primaryPath,
                paths = effectivePaths,
                displayTitle = effectiveTitle,
                tierCount = effectivePaths.size,
                totalComponentCount = totalComponents
            )
        }

        private fun serializeUpgradeComponent(comp: UpgradeComponent): String {
            val scoresStr = comp.scores?.let { s ->
                listOf(
                    s.compatibility.toString(),
                    s.performanceGain,
                    s.value,
                    s.upgradePriority
                ).joinToString(",")
            }.orEmpty()
            val parts = listOf(
                comp.category,
                comp.name,
                comp.description,
                comp.compatibility.toString(),
                comp.progressPercent.toString(),
                scoresStr
            )
            return parts.joinToString(SUB_FIELD_SEPARATOR)
        }

        private fun deserializeUpgradeComponent(value: String): UpgradeComponent? {
            if (value.isBlank()) return null
            val parts = value.split(SUB_FIELD_SEPARATOR)
            if (parts.size < 5) return null
            return runCatching {
                val scoresRaw = parts.getOrNull(5).orEmpty()
                val scores: ComponentScores? = if (scoresRaw.isNotBlank()) {
                    val fields = scoresRaw.split(",")
                    if (fields.size >= 4) ComponentScores(
                        compatibility = fields[0].toIntOrNull() ?: 100,
                        performanceGain = fields[1],
                        value = fields[2],
                        upgradePriority = fields[3]
                    ) else null
                } else null
                UpgradeComponent(
                    category = parts[0],
                    name = parts[1],
                    description = parts[2],
                    compatibility = parts[3].toIntOrNull() ?: 0,
                    progressPercent = parts[4].toFloatOrNull() ?: 0f,
                    scores = scores
                )
            }.getOrNull()
        }

        private fun serializeUpgradePath(path: UpgradePath): String {
            val header = listOf(
                path.title,
                path.description,
                path.tier,
                path.stepOne,
                path.stepTwo,
                path.stepThree,
                path.components.size.toString()
            ).joinToString(SUB_FIELD_SEPARATOR)
            val components = path.components.joinToString(SUB_COMPONENT_SEPARATOR) {
                serializeUpgradeComponent(it)
            }
            return header + SUB_COMPONENT_SEPARATOR + components
        }

        private fun deserializeUpgradePath(value: String): UpgradePath? {
            if (value.isBlank()) return null
            val idxSep = value.indexOf(SUB_COMPONENT_SEPARATOR)
            val header = if (idxSep == -1) value else value.substring(0, idxSep)
            val compsStr = if (idxSep == -1) "" else value.substring(idxSep + 1)
            val headerParts = header.split(SUB_FIELD_SEPARATOR)
            if (headerParts.size < 7) return null
            return runCatching {
                val compCount = headerParts[6].toIntOrNull() ?: 0
                val components = if (compCount > 0 && compsStr.isNotBlank()) {
                    compsStr.split(SUB_COMPONENT_SEPARATOR).take(compCount).mapNotNull {
                        deserializeUpgradeComponent(it)
                    }
                } else emptyList()
                UpgradePath(
                    title = headerParts[0],
                    description = headerParts[1],
                    tier = headerParts[2],
                    components = components,
                    stepOne = headerParts[3],
                    stepTwo = headerParts[4],
                    stepThree = headerParts[5]
                )
            }.getOrNull()
        }

        private fun serializeSavedUpgrade(itemRaw: SavedUpgradeItem): String {
            val item = normalizeSavedUpgradeItem(itemRaw)
            val header = listOf(
                UPGRADE_V2_MARKER,
                item.id,
                item.component,
                item.hasExistingBuild.toString(),
                item.displayTitle,
                item.tierCount.toString(),
                item.totalComponentCount.toString(),
                item.basedOnText,
                item.currentBuildTierLabel
            ).joinToString(SAVED_UPGRADE_SEPARATOR)
            val pathsStr = item.paths.joinToString(SUB_PATH_SEPARATOR) { serializeUpgradePath(it) }
            return header + SAVED_UPGRADE_SEPARATOR + pathsStr
        }

        private fun deserializeSavedUpgrade(value: String): SavedUpgradeItem? {
            if (value.startsWith(UPGRADE_V2_MARKER)) {
                val minHeaderParts = 7
                val currentHeaderParts = 9
                val parts = value.split(SAVED_UPGRADE_SEPARATOR, limit = currentHeaderParts + 1)
                if (parts.size < minHeaderParts + 1) return null
                val header = parts.take(parts.size - 1)
                val pathsStr = parts.last()
                if (header.isEmpty() || header[0] != UPGRADE_V2_MARKER) return null
                return runCatching {
                    val id = header[1]
                    val component = header[2]
                    val hasExistingBuild = header[3].toBooleanStrict()
                    val displayTitle = header[4]
                    val tierCount = header[5].toIntOrNull() ?: 0
                    val totalComponentCount = header[6].toIntOrNull() ?: 0
                    val basedOnText = header.getOrNull(7).orEmpty()
                    val currentBuildTierLabel = header.getOrNull(8).orEmpty()
                    val paths = if (tierCount > 0 && pathsStr.isNotBlank()) {
                        pathsStr.split(SUB_PATH_SEPARATOR).take(tierCount).mapNotNull {
                            deserializeUpgradePath(it)
                        }
                    } else emptyList()
                    val primary = paths.firstOrNull() ?: UpgradePath(
                        title = displayTitle,
                        description = "",
                        tier = "",
                        components = emptyList(),
                        stepOne = "",
                        stepTwo = "",
                        stepThree = ""
                    )
                    SavedUpgradeItem(
                        id = id,
                        component = component,
                        hasExistingBuild = hasExistingBuild,
                        path = primary,
                        paths = paths,
                        displayTitle = displayTitle,
                        tierCount = tierCount,
                        totalComponentCount = totalComponentCount,
                        basedOnText = basedOnText,
                        currentBuildTierLabel = currentBuildTierLabel
                    )
                }.getOrNull()
            } else {
                val parts = value.split(SAVED_UPGRADE_SEPARATOR)
                if (parts.size != 8) return null
                return runCatching {
                    val id = parts[0]
                    val component = parts[1]
                    val hasExistingBuild = parts[2].toBooleanStrict()
                    val title = parts[3]
                    val description = parts[4]
                    val stepOne = parts[5]
                    val stepTwo = parts[6]
                    val stepThree = parts[7]
                    val single = UpgradePath(
                        title = title,
                        description = description,
                        tier = "",
                        components = emptyList(),
                        stepOne = stepOne,
                        stepTwo = stepTwo,
                        stepThree = stepThree
                    )
                    normalizeSavedUpgradeItem(
                        SavedUpgradeItem(
                            id = id,
                            component = component,
                            hasExistingBuild = hasExistingBuild,
                            path = single,
                            paths = listOf(single),
                            displayTitle = title,
                            tierCount = 1,
                            totalComponentCount = 0
                        )
                    )
                }.getOrNull()
            }
        }

        private fun serializeCurrentBuild(build: CurrentBuild?): String {
            val b = build ?: CurrentBuild()
            return listOf(
                b.cpu,
                b.gpu,
                b.motherboard,
                b.ram,
                b.storage,
                b.psu
            ).joinToString(CURRENT_BUILD_SEPARATOR)
        }

        private fun deserializeCurrentBuild(value: String?): CurrentBuild {
            val v = value ?: return CurrentBuild()
            val parts = v.split(CURRENT_BUILD_SEPARATOR)
            if (parts.size != 6) return CurrentBuild()
            return CurrentBuild(
                cpu = parts[0],
                gpu = parts[1],
                motherboard = parts[2],
                ram = parts[3],
                storage = parts[4],
                psu = parts[5]
            )
        }
    }
}
