package com.pcscan.pcscan.ui.dashboard

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    SAVED_UPGRADE
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
    val build: BuildRecommendation
)

data class SavedUpgradeItem(
    val id: String,
    val component: String,
    val hasExistingBuild: Boolean,
    val path: UpgradePath
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
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val repository: RecommendationRepository = RecommendationRepositoryProvider.current
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()

    private val buildCache: MutableMap<Pair<String, BuildTier>, BuildRecommendation> =
        mutableMapOf()
    private var buildCacheComponentKey: String? = null

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

        viewModelScope.launch {
            runCatching {
                _searchProgress.postValue(18)
                when (val exact = repository.getExactSearchResult(query)) {
                    is ExactSearchResult.Success -> {
                        _searchProgress.postValue(55)
                        val component = exact.component.name
                        _selectedComponent.value = component
                        addRecentActivity(
                            type = RecentActivityType.SEARCH,
                            name = component,
                            category = exact.component.category.ifBlank { "Component" }
                        )
                        persistState()

                        // Stage 2: CACHE BUILD (longest step on Android side - mock data + RAM DB lookups)
                        _searchProgress.postValue(68)
                        val category = RecommendationMockData.resolveCategory(component)
                        ensureAllBuildsCached(component, category)

                        // Stage 3: PARALLEL STATE PUBLISH (all 4 screens updated concurrently - no 4x sequential wait!)
                        _searchProgress.postValue(92)
                        val singleList = listOf(component)
                        coroutineScope {
                            val tasks: List<Deferred<Unit>> = listOf(
                                async { publishSearchResultsState(singleList) },
                                async { publishBuildRecommendationState() },
                                async { publishUpgradePathState() },
                                async { publishUpgradeRecoState() },
                            )
                            tasks.awaitAll()
                        }
                        // NO MORE delay(50L) fake pause - set progress 100 immediately after work finishes
                        _searchProgress.postValue(100)
                        _searchResultsReady.postValue(true)
                    }
                    is ExactSearchResult.NotFound -> {
                        _searchProgress.postValue(100)
                        _searchResultsReady.postValue(true)
                        _searchResultsScreenState.value = ScreenState.Error(exact.reason)
                    }
                    is ExactSearchResult.InvalidQuery -> {
                        _searchProgress.postValue(100)
                        _searchResultsReady.postValue(true)
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
                        _searchResultsScreenState.value = ScreenState.Error(
                            if (exact.isNetworkError) "Network error. Start backend or check WIFI. ${exact.reason}"
                            else exact.reason
                        )
                    }
                }
            }.onFailure { error ->
                _searchProgress.postValue(100)
                _searchResultsReady.postValue(true)
                _searchResultsScreenState.value = ScreenState.Error(
                    error.message ?: "Unable to load search results."
                )
            }
        }
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

        if (!selected.isNullOrBlank()) {
            _searchResultsScreenState.value = ScreenState.Loading
            viewModelScope.launch {
                runCatching {
                    // VALIDATE selected component FIRST (catches "Monitor" from nav args / saved history)
                    _searchProgress.postValue(15)
                    when (val exact = repository.getExactSearchResult(selected)) {
                        is ExactSearchResult.Success -> {
                            _searchProgress.postValue(20)
                            val validatedName = exact.component.name
                            _selectedComponent.value = validatedName
                            persistState()
                            _searchProgress.postValue(45)

                            val category = RecommendationMockData.resolveCategory(validatedName)
                            ensureAllBuildsCached(validatedName, category)

                            _searchProgress.postValue(90)
                            refreshAllStatesSuspend() // Already parallel internally (4x async/awaitAll)

                            // NO MORE delay(50L) fake pause - 100% immediately after all parallel publish completes
                            _searchProgress.postValue(100)
                            _searchResultsReady.postValue(true)
                        }
                        is ExactSearchResult.InvalidQuery -> {
                            _searchProgress.postValue(100)
                            _searchResultsReady.postValue(true)
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
                            _searchResultsScreenState.value = ScreenState.Error(exact.reason)
                        }
                        is ExactSearchResult.ApiOrNetworkError -> {
                            _searchProgress.postValue(100)
                            _searchResultsReady.postValue(true)
                            _searchResultsScreenState.value = ScreenState.Error(
                                if (exact.isNetworkError) "Network error. Start backend or check WIFI. ${exact.reason}" else exact.reason
                            )
                        }
                    }
                }.onFailure { error ->
                    _searchProgress.postValue(100)
                    _searchResultsReady.postValue(true)
                    _searchResultsScreenState.value = ScreenState.Error(
                        error.message ?: "Unable to restore search results."
                    )
                }
            }
            return
        }

        _selectedComponent.value = null
        _searchResultsScreenState.value = ScreenState.Loading
        viewModelScope.launch {
            runCatching {
                _searchProgress.postValue(15)
                when (val exact = repository.getExactSearchResult(query)) {
                    is ExactSearchResult.Success -> {
                        _searchProgress.postValue(40)
                        val component = exact.component.name
                        _selectedComponent.value = component
                        persistState()
                        _searchProgress.postValue(55)
                        val category = RecommendationMockData.resolveCategory(component)
                        ensureAllBuildsCached(component, category)
                        _searchProgress.postValue(85)
                        val singleList = listOf(component)
                        publishSearchResultsState(singleList)
                        publishBuildRecommendationState()
                        publishUpgradePathState()
                        _searchProgress.postValue(95)
                        kotlinx.coroutines.delay(50L)
                        _searchProgress.postValue(100)
                        _searchResultsReady.postValue(true)
                    }
                    is ExactSearchResult.NotFound -> {
                        _searchProgress.postValue(100)
                        _searchResultsReady.postValue(true)
                        _searchResultsScreenState.value = ScreenState.Error(exact.reason)
                    }
                    is ExactSearchResult.InvalidQuery -> {
                        _searchProgress.postValue(100)
                        _searchResultsReady.postValue(true)
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
                        _searchResultsScreenState.value = ScreenState.Error(
                            if (exact.isNetworkError) "Network error. Start backend or check WIFI. ${exact.reason}"
                            else exact.reason
                        )
                    }
                }
            }.onFailure { error ->
                _searchProgress.postValue(100)
                _searchResultsReady.postValue(true)
                _searchResultsScreenState.value = ScreenState.Error(
                    error.message ?: "Unable to restore search results."
                )
            }
        }
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
        }
    }

    private fun clearBuildCache() {
        buildCache.clear()
        buildCacheComponentKey = null
    }

    fun resetAllSearchAndBuildState() {
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
        _allBuildsCacheProgress.value = 0
        _allBuildsReady.value = false
        _searchProgress.value = 0
        _searchResultsReady.value = false
        persistState()
    }

    fun preloadAllBuildsFor(component: String) {
        if (component.isBlank()) return
        val cacheValid = buildCacheComponentKey == component && buildCache.size >= 3
        if (cacheValid) {
            _allBuildsCacheProgress.value = 100
            _allBuildsReady.value = true
            return
        }
        _allBuildsCacheProgress.value = 0
        _allBuildsReady.value = false
        val category = RecommendationMockData.resolveCategory(component)
        viewModelScope.launch {
            runCatching {
                ensureAllBuildsCached(component, category)
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

    fun saveCurrentBuild(): Boolean {
        val state = _buildRecommendationScreenState.value as? ScreenState.Success ?: return false
        val component = _selectedComponent.value?.takeIf { it.isNotBlank() } ?: return false
        val tier = _selectedBuildTier.value ?: BuildTier.ENTRY
        val build = state.data.build ?: return false
        val currentSaved = _savedBuilds.value.orEmpty()
        val itemId = buildSavedBuildId(component, tier, build.name)

        if (currentSaved.any { it.id == itemId }) {
            return false
        }

        val newItem = SavedBuildItem(
            id = itemId,
            component = component,
            tier = tier,
            build = build
        )
        val updated = listOf(
            newItem
        ) + currentSaved

        _savedBuilds.value = updated
        persistSavedBuilds(updated)
        addRecentActivity(
            type = RecentActivityType.SAVED_BUILD,
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

    fun restoreSavedBuild(item: SavedBuildItem) {
        clearBuildCache()
        _searchQuery.value = item.component
        _selectedComponent.value = item.component
        _selectedBuildTier.value = item.tier
        persistState()
        refreshAllStates()
    }

    fun saveCurrentUpgrade(): Boolean {
        val state = _upgradePathScreenState.value as? ScreenState.Success ?: return false
        val component = _selectedComponent.value?.takeIf { it.isNotBlank() } ?: return false
        val hasExistingBuild = _hasExistingBuild.value ?: return false
        val path = state.data.path ?: return false
        val currentSaved = _savedUpgrades.value.orEmpty()
        val itemId = buildSavedUpgradeId(component, hasExistingBuild, path.title)

        if (currentSaved.any { it.id == itemId }) {
            return false
        }

        val newItem = SavedUpgradeItem(
            id = itemId,
            component = component,
            hasExistingBuild = hasExistingBuild,
            path = path
        )
        val updated = listOf(
            newItem
        ) + currentSaved

        _savedUpgrades.value = updated
        persistSavedUpgrades(updated)
        addRecentActivity(
            type = RecentActivityType.SAVED_UPGRADE,
            name = path.title,
            category = "Upgrade"
        )
        viewModelScope.launch {
            persistSavedUpgradeToFirestore(newItem)
        }
        return true
    }

    fun removeSavedUpgrade(id: String) {
        val updated = _savedUpgrades.value.orEmpty().filterNot { it.id == id }
        _savedUpgrades.value = updated
        persistSavedUpgrades(updated)
        viewModelScope.launch {
            deleteSavedUpgradeFromFirestore(id)
        }
    }

    fun saveUpgradeItem(
        component: String,
        hasExistingBuild: Boolean,
        path: UpgradePath
    ): Boolean {
        val safeComponent = component.takeIf { it.isNotBlank() } ?: return false
        val currentSaved = _savedUpgrades.value.orEmpty()
        val itemId = buildSavedUpgradeId(safeComponent, hasExistingBuild, path.title)

        if (currentSaved.any { it.id == itemId }) {
            return false
        }

        val newItem = SavedUpgradeItem(
            id = itemId,
            component = safeComponent,
            hasExistingBuild = hasExistingBuild,
            path = path
        )
        val updated = listOf(newItem) + currentSaved
        _savedUpgrades.value = updated
        persistSavedUpgrades(updated)
        addRecentActivity(
            type = RecentActivityType.SAVED_UPGRADE,
            name = path.title,
            category = "Upgrade"
        )
        viewModelScope.launch {
            persistSavedUpgradeToFirestore(newItem)
        }
        return true
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

    private fun persistState() {
        savedStateHandle[KEY_SEARCH_QUERY] = _searchQuery.value
        savedStateHandle[KEY_SELECTED_COMPONENT] = _selectedComponent.value
        savedStateHandle[KEY_SELECTED_BUILD_TIER] = _selectedBuildTier.value?.name
        savedStateHandle[KEY_HAS_EXISTING_BUILD] = _hasExistingBuild.value
        savedStateHandle[KEY_CURRENT_BUILD] = serializeCurrentBuild(_currentBuild.value)
    }

    private fun persistSavedBuilds(items: List<SavedBuildItem>) {
        savedStateHandle[KEY_SAVED_BUILDS] = ArrayList(items.map(::serializeSavedBuild))
    }

    private fun loadSavedBuilds(): List<SavedBuildItem> {
        return savedStateHandle.get<ArrayList<String>>(KEY_SAVED_BUILDS)
            ?.mapNotNull(::deserializeSavedBuild)
            .orEmpty()
    }

    private fun persistSavedUpgrades(items: List<SavedUpgradeItem>) {
        savedStateHandle[KEY_SAVED_UPGRADES] = ArrayList(items.map(::serializeSavedUpgrade))
    }

    private fun loadSavedUpgrades(): List<SavedUpgradeItem> {
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
        val updatedActivities = (listOf(newItem) + currentActivities).take(10) // Keep last 10
        _recentActivities.value = updatedActivities
        persistRecentActivities(updatedActivities)
    }

    private fun persistRecentActivities(items: List<RecentActivityItem>) {
        savedStateHandle[KEY_RECENT_ACTIVITIES] = ArrayList(items.map(::serializeRecentActivity))
    }

    private fun loadRecentActivities(): List<RecentActivityItem> {
        return savedStateHandle.get<ArrayList<String>>(KEY_RECENT_ACTIVITIES)
            ?.mapNotNull(::deserializeRecentActivity)
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
                        bottleneckNote = bottleneckNote
                    )
                ) to savedAt
            }
                .sortedByDescending { it.second }
                .map { it.first }

            if (remote.isNotEmpty()) {
                _savedBuilds.value = remote
                persistSavedBuilds(remote)
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

            if (remote.isNotEmpty()) {
                _savedUpgrades.value = remote
                persistSavedUpgrades(remote)
            }
        }
    }

    private suspend fun persistSavedBuildToFirestore(item: SavedBuildItem) {
        val userId = currentUserId() ?: return
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
            publishSearchResultsState()
            publishBuildRecommendationState()
            publishUpgradePathState()
            publishUpgradeRecoState()
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
        hasExistingBuild: Boolean = true
    ) {
        resetBuildCompatibilityResult()
        clearBuildCache()
        _upgradeRecoScreenState.value = ScreenState.Idle
        _upgradePathScreenState.value = ScreenState.Idle

        viewModelScope.launch {
            runCatching {
                _upgradePrepareProgress.postValue(10)
                kotlinx.coroutines.delay(250L)

                val compatResult = RecommendationMockData.checkBuildCompatibility(build)
                _buildCompatibilityResult.postValue(compatResult)
                _upgradePrepareProgress.postValue(40)

                if (!compatResult.isCompatible) {
                    return@runCatching
                }

                kotlinx.coroutines.delay(200L)
                _upgradePrepareProgress.postValue(55)

                val safeComponent = primaryComponent.ifBlank {
                    listOf(build.gpu, build.cpu, build.motherboard, build.ram, build.storage, build.psu)
                        .firstOrNull { it.isNotBlank() } ?: "Component"
                }

                _selectedComponent.value = safeComponent
                _currentBuild.value = build
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

    fun saveAllUpgradeRecommendations(): Int {
        val state = _upgradeRecoScreenState.value as? ScreenState.Success ?: return 0
        val component = _selectedComponent.value?.takeIf { it.isNotBlank() } ?: return 0
        val hasExistingBuild = _hasExistingBuild.value ?: true
        val currentSaved = _savedUpgrades.value.orEmpty()
        var savedCount = 0
        val newItems = mutableListOf<SavedUpgradeItem>()

        listOfNotNull(state.data.entryPath, state.data.midPath, state.data.highPath).forEach { path ->
            val itemId = buildSavedUpgradeId(component, hasExistingBuild, path.title)
            if (currentSaved.none { it.id == itemId }) {
                newItems.add(
                    SavedUpgradeItem(
                        id = itemId,
                        component = component,
                        hasExistingBuild = hasExistingBuild,
                        path = path
                    )
                )
                savedCount++
            }
        }

        if (newItems.isNotEmpty()) {
            val updated = newItems + currentSaved
            _savedUpgrades.value = updated
            persistSavedUpgrades(updated)
            newItems.forEach { item ->
                addRecentActivity(
                    type = RecentActivityType.SAVED_UPGRADE,
                    name = item.path.title,
                    category = "Upgrade"
                )
            }
            viewModelScope.launch {
                newItems.forEach { persistSavedUpgradeToFirestore(it) }
            }
        }

        return savedCount
    }

    private suspend fun publishSearchResultsState(resultsOverride: List<String>? = null) {
        val query = _searchQuery.value ?: "Results"
        runCatching {
            val results = resultsOverride ?: repository.getSearchResults(query)
            val selected = _selectedComponent.value ?: results.firstOrNull()
            val componentDetail = selected?.let { repository.getComponentDetails(it) }
            ScreenState.Success(
                SearchResultsUiState(
                    title = "Results for: $query",
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

    private suspend fun ensureAllBuildsCached(component: String, category: String) {
        if (buildCacheComponentKey == component && buildCache.size >= 3) {
            _allBuildsCacheProgress.postValue(100)
            return
        }

        buildCache.clear()
        buildCacheComponentKey = component
        _allBuildsCacheProgress.postValue(5)

        val recommendations = repository.getBuildRecommendations(component)
        _allBuildsCacheProgress.postValue(20)

        val tiers = listOf(
            Triple(BuildTier.ENTRY, recommendations.getOrNull(0), "ENTRY BUILD"),
            Triple(BuildTier.MID, recommendations.getOrNull(1), "MIDRANGE BUILD"),
            Triple(BuildTier.HIGH, recommendations.getOrNull(2), "HIGH-END BUILD")
        )

        tiers.forEachIndexed { idx, (tier, rawBuild, label) ->
            val build = rawBuild ?: recommendations.firstOrNull()
            val enforcedBuild = build?.let { enforceComponentInBuild(it, component, category) }
            val cleanedBuild = enforcedBuild?.copy(
                name = removePriceFromBuildName(enforcedBuild.name),
                label = label
            )
            if (cleanedBuild != null) {
                buildCache[component to tier] = cleanedBuild
            }
            when (idx) {
                0 -> _allBuildsCacheProgress.postValue(45)
                1 -> _allBuildsCacheProgress.postValue(72)
                2 -> _allBuildsCacheProgress.postValue(95)
            }
        }
    }

    private fun publishBuildFromCache(component: String, tier: BuildTier): ScreenState<BuildRecommendationUiState> {
        val cachedBuild = buildCache[component to tier]
        return ScreenState.Success(
            BuildRecommendationUiState(
                basedOnText = "Based on: $component",
                selectedTier = tier,
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
                ensureAllBuildsCached(component, category)
                publishBuildFromCache(component, selectedTier)
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
            _buildRecommendationScreenState.value =
                publishBuildFromCache(component, selectedTier)
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

            val detectedTier = if (hasExistingBuild && !build.isEmpty) {
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

            val entryPath: UpgradePath?
            val midPath: UpgradePath?
            val highPath: UpgradePath?

            when {
                detectedTier == BuildTier.ENTRY -> {
                    entryPath = null
                    midPath = repository.getUpgradePath(searchedComponent, build, BuildTier.MID)
                    highPath = repository.getUpgradePath(searchedComponent, build, BuildTier.HIGH)
                }
                detectedTier == BuildTier.MID -> {
                    entryPath = null
                    midPath = null
                    highPath = repository.getUpgradePath(searchedComponent, build, BuildTier.HIGH)
                }
                detectedTier == BuildTier.HIGH -> {
                    entryPath = null
                    midPath = null
                    highPath = repository.getUpgradePath(searchedComponent, build, BuildTier.HIGH)
                }
                else -> {
                    entryPath = repository.getUpgradePath(searchedComponent, hasExistingBuild, BuildTier.ENTRY)
                    midPath = repository.getUpgradePath(searchedComponent, hasExistingBuild, BuildTier.MID)
                    highPath = repository.getUpgradePath(searchedComponent, hasExistingBuild, BuildTier.HIGH)
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

    companion object {
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
            return listOf(
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
                item.build.bottleneckNote
            ).joinToString(SAVED_BUILD_SEPARATOR)
        }

        private fun deserializeSavedBuild(value: String): SavedBuildItem? {
            val parts = value.split(SAVED_BUILD_SEPARATOR)
            if (parts.size != 15) return null

            return runCatching {
                SavedBuildItem(
                    id = parts[0],
                    component = parts[1],
                    tier = BuildTier.valueOf(parts[2]),
                    build = BuildRecommendation(
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
                )
            }.getOrNull()
        }

        private fun buildSavedUpgradeId(component: String, hasExistingBuild: Boolean, title: String): String {
            return listOf(component, hasExistingBuild.toString(), title)
                .joinToString("|")
                .lowercase()
        }

        private fun serializeSavedUpgrade(item: SavedUpgradeItem): String {
            return listOf(
                item.id,
                item.component,
                item.hasExistingBuild.toString(),
                item.path.title,
                item.path.description,
                item.path.stepOne,
                item.path.stepTwo,
                item.path.stepThree
            ).joinToString(SAVED_UPGRADE_SEPARATOR)
        }

        private fun deserializeSavedUpgrade(value: String): SavedUpgradeItem? {
            val parts = value.split(SAVED_UPGRADE_SEPARATOR)
            if (parts.size != 8) return null

            return runCatching {
                SavedUpgradeItem(
                    id = parts[0],
                    component = parts[1],
                    hasExistingBuild = parts[2].toBooleanStrict(),
                    path = UpgradePath(
                        title = parts[3],
                        description = parts[4],
                        stepOne = parts[5],
                        stepTwo = parts[6],
                        stepThree = parts[7]
                    )
                )
            }.getOrNull()
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
