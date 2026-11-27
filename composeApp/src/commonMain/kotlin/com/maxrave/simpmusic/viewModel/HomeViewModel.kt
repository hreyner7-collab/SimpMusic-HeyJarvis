package com.maxrave.simpmusic.viewModel

import androidx.lifecycle.viewModelScope
import com.maxrave.common.SELECTED_LANGUAGE
import com.maxrave.common.SUPPORTED_LANGUAGE
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.home.HomeDataCombine
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.home.chart.Chart
import com.maxrave.domain.data.model.mood.Mood
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import com.maxrave.domain.repository.HomeRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.utils.FastPageCache
import com.maxrave.simpmusic.viewModel.base.BaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.music_video
import simpmusic.composeapp.generated.resources.new_release
import simpmusic.composeapp.generated.resources.song
import simpmusic.composeapp.generated.resources.view_count

class HomeViewModel(
    private val dataStoreManager: DataStoreManager,
    private val homeRepository: HomeRepository,
) : BaseViewModel() {
    private val _homeItemList: MutableStateFlow<List<HomeItem>> =
        MutableStateFlow(arrayListOf())
    val homeItemList: StateFlow<List<HomeItem>> = _homeItemList

    private var _homeListState = MutableStateFlow<ListState>(ListState.IDLE)
    val homeListState: StateFlow<ListState> = _homeListState

    private var _continuation = MutableStateFlow<String?>(null)
    val continuation: StateFlow<String?> = _continuation

    private val _exploreMoodItem: MutableStateFlow<Mood?> = MutableStateFlow(null)
    val exploreMoodItem: StateFlow<Mood?> = _exploreMoodItem
    private val _accountInfo: MutableStateFlow<Pair<String?, String?>?> = MutableStateFlow(null)
    val accountInfo: StateFlow<Pair<String?, String?>?> = _accountInfo

    private var homeJob: Job? = null

    val showSnackBarErrorState = MutableSharedFlow<String>()

    private val _chart: MutableStateFlow<Chart?> = MutableStateFlow(null)
    val chart: StateFlow<Chart?> = _chart
    private val _newRelease: MutableStateFlow<List<HomeItem>> = MutableStateFlow(arrayListOf())
    val newRelease: StateFlow<List<HomeItem>> = _newRelease
    var regionCodeChart: MutableStateFlow<String?> = MutableStateFlow(null)

    val loading = MutableStateFlow<Boolean>(true)
    val loadingChart = MutableStateFlow<Boolean>(true)
    private var regionCode: String = ""
    private var language: String = ""

    private val _songEntity: MutableStateFlow<SongEntity?> = MutableStateFlow(null)
    val songEntity: StateFlow<SongEntity?> = _songEntity

    private var _params: MutableStateFlow<String?> = MutableStateFlow(null)
    val params: StateFlow<String?> = _params

    // For showing alert that should log in to YouTube
    private val _showLogInAlert: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val showLogInAlert: StateFlow<Boolean> = _showLogInAlert

    val dataSyncId =
        dataStoreManager
            .dataSyncId
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _mainHomeThumbnail: MutableStateFlow<String?> = MutableStateFlow(null)
    val mainHomeThumbnail: StateFlow<String?> = _mainHomeThumbnail

    // The page background image YouTube sends with Home, null when it sends none.
    private val _homeBackground: MutableStateFlow<String?> = MutableStateFlow(null)
    val homeBackground: StateFlow<String?> = _homeBackground

    private data class HomeTrigger(
        val location: String,
        val language: String,
        val cookie: String,
        val params: String?,
    )

    init {
        homeJob = Job()
        viewModelScope.launch {
            if (dataStoreManager.cookie.first().isEmpty() &&
                dataStoreManager.shouldShowLogInRequiredAlert.first() == TRUE
            ) {
                _showLogInAlert.update { true }
            }
            regionCodeChart.value = dataStoreManager.chartKey.first()
            launch {
                homeItemList.collectLatest { list ->
                    _mainHomeThumbnail.value =
                        list
                            .firstOrNull()
                            ?.contents
                            ?.firstOrNull()
                            ?.thumbnails
                            ?.lastOrNull()
                            ?.url
                }
            }
            // Single coalesced trigger replaces 5 separate collectors + 1s cookie delay, preventing
            // duplicate initial Home network requests on startup.
            combine(
                dataStoreManager.location.distinctUntilChanged(),
                dataStoreManager.language.distinctUntilChanged(),
                dataStoreManager.cookie.distinctUntilChanged(),
                params,
            ) { loc, lang, cookie, p ->
                HomeTrigger(
                    location = loc,
                    language = lang.ifBlank { SUPPORTED_LANGUAGE.codes.first() },
                    cookie = cookie,
                    params = p,
                )
            }.distinctUntilChanged().collectLatest { trigger ->
                regionCode = trigger.location
                language = trigger.language
                if (trigger.cookie.isNotEmpty()) {
                    _accountInfo.emit(
                        Pair(
                            dataStoreManager.getString("AccountName").first(),
                            dataStoreManager.getString("AccountThumbUrl").first(),
                        ),
                    )
                } else {
                    _accountInfo.emit(null)
                }
                getHomeItemList(trigger.params)
            }
        }
    }

    fun doneShowLogInAlert(neverShowAgain: Boolean = false) {
        viewModelScope.launch {
            _showLogInAlert.update { false }
            if (neverShowAgain) {
                dataStoreManager.setShouldShowLogInRequiredAlert(false)
            }
        }
    }

    fun getHomeItemList(
        params: String? = null,
        forceRefresh: Boolean = false,
    ) {
        homeJob?.cancel()
        homeJob =
            viewModelScope.launch {
                val resolvedLang =
                    language.ifBlank {
                        dataStoreManager.getString(SELECTED_LANGUAGE).first()
                            ?: SUPPORTED_LANGUAGE.codes.first()
                    }
                val resolvedRegion = regionCode.ifBlank { dataStoreManager.location.first() }
                language = resolvedLang
                regionCode = resolvedRegion
                val cookieValue = dataStoreManager.cookie.first()
                val cacheKey = "${params.orEmpty()}|$resolvedRegion|$resolvedLang|${cookieValue.hashCode()}"
                var servedFromDisk = false
                if (!forceRefresh) {
                    FastPageCache.getHome(cacheKey)?.let { cached ->
                        _continuation.value = cached.continuation
                        _homeItemList.value = cached.homeItems
                        _homeBackground.value = cached.homeBackground
                        _homeListState.value =
                            if (cached.continuation.isNullOrEmpty()) {
                                ListState.PAGINATION_EXHAUST
                            } else {
                                ListState.IDLE
                            }
                        _chart.value = cached.chart
                        _newRelease.value = cached.newRelease
                        _exploreMoodItem.value = cached.mood
                        loading.value = false
                        return@launch
                    }

                    // Stale-while-revalidate disk cache: render cached Home immediately on cold start,
                    // then fall through to silently refresh live data from network in the background.
                    FastPageCache.loadHomeFromDisk(cacheKey)?.let { diskResult ->
                        val cached = diskResult.snapshot
                        _continuation.value = cached.continuation
                        _homeItemList.value = cached.homeItems
                        _homeBackground.value = cached.homeBackground
                        _homeListState.value =
                            if (cached.continuation.isNullOrEmpty()) {
                                ListState.PAGINATION_EXHAUST
                            } else {
                                ListState.IDLE
                            }
                        if (cached.newRelease.isNotEmpty()) {
                            _newRelease.value = cached.newRelease
                        }
                        loading.value = false
                        servedFromDisk = true
                    }
                }
                if (!servedFromDisk) {
                    loading.value = true
                    _homeListState.value = ListState.LOADING
                }
                // If user only switched a mood chip on Home and secondary shelves are already loaded,
                // only fetch getHomeData(params) instead of re-fetching Moods, Charts, and NewRelease.
                if (!params.isNullOrEmpty() && !forceRefresh && _exploreMoodItem.value != null) {
                    homeRepository
                        .getHomeData(
                            params,
                            getString(Res.string.view_count),
                            getString(Res.string.song),
                        ).collect { home ->
                            when (home) {
                                is Resource.Success -> {
                                    _continuation.value = home.data?.first
                                    _homeItemList.value = home.data?.second ?: listOf()
                                    _homeBackground.value = home.data?.third
                                    FastPageCache.putHome(
                                        cacheKey,
                                        FastPageCache.HomeSnapshot(
                                            homeItems = _homeItemList.value,
                                            continuation = _continuation.value,
                                            homeBackground = _homeBackground.value,
                                            mood = _exploreMoodItem.value,
                                            chart = _chart.value,
                                            newRelease = _newRelease.value,
                                        ),
                                    )
                                }

                                is Resource.Error -> {
                                    if (!servedFromDisk) {
                                        _continuation.value = null
                                        _homeItemList.value = listOf()
                                        home.message?.let { showSnackBarErrorState.emit(it) }
                                    }
                                }
                            }
                            _homeListState.value =
                                if (continuation.value.isNullOrEmpty()) ListState.PAGINATION_EXHAUST else ListState.IDLE
                            loading.value = false
                        }
                    return@launch
                }
                combine(
                    homeRepository.getHomeData(
                        params,
                        getString(Res.string.view_count),
                        getString(Res.string.song),
                    ),
                    homeRepository.getMoodAndMomentsData(),
                    homeRepository.getChartData(dataStoreManager.chartKey.first()),
                    homeRepository.getNewRelease(
                        getString(Res.string.new_release),
                        getString(Res.string.music_video),
                    ),
                ) { home, exploreMood, exploreChart, newRelease ->
                    HomeDataCombine(home, exploreMood, exploreChart, newRelease)
                }.collect { result ->
                    val home = result.home
                    val exploreMoodItem = result.mood
                    val chart = result.chart
                    val newRelease = result.newRelease
                    when (home) {
                        is Resource.Success -> {
                            _continuation.value = home.data?.first
                            _homeItemList.value = home.data?.second ?: listOf()
                            _homeBackground.value = home.data?.third
                        }

                        else -> {
                            if (!servedFromDisk) {
                                _continuation.value = null
                                _homeItemList.value = listOf()
                            }
                        }
                    }
                    if (continuation.value.isNullOrEmpty()) {
                        _homeListState.value = ListState.PAGINATION_EXHAUST
                    } else {
                        _homeListState.value = ListState.IDLE
                    }
                    when (chart) {
                        is Resource.Success -> {
                            _chart.value = chart.data
                        }

                        else -> {
                            if (!servedFromDisk) {
                                _chart.value = null
                            }
                        }
                    }
                    when (newRelease) {
                        is Resource.Success -> {
                            _newRelease.value = newRelease.data ?: arrayListOf()
                        }

                        else -> {
                            if (!servedFromDisk) {
                                _newRelease.value = arrayListOf()
                            }
                        }
                    }
                    when (exploreMoodItem) {
                        is Resource.Success -> {
                            _exploreMoodItem.value = exploreMoodItem.data
                        }

                        else -> {
                            if (!servedFromDisk) {
                                _exploreMoodItem.value = null
                            }
                        }
                    }
                    regionCodeChart.value = dataStoreManager.chartKey.first()
                    if (home is Resource.Success) {
                        val snapshot =
                            FastPageCache.HomeSnapshot(
                                homeItems = _homeItemList.value,
                                continuation = _continuation.value,
                                homeBackground = _homeBackground.value,
                                mood = _exploreMoodItem.value,
                                chart = _chart.value,
                                newRelease = _newRelease.value,
                            )
                        FastPageCache.putHome(cacheKey, snapshot)
                        if (params.isNullOrEmpty() && snapshot.homeItems.isNotEmpty()) {
                            launch(Dispatchers.IO) {
                                FastPageCache.saveHomeToDisk(cacheKey, snapshot)
                            }
                        }
                    }
                    if (cookieValue.isNotEmpty()) {
                        _accountInfo.emit(
                            Pair(
                                dataStoreManager.getString("AccountName").first(),
                                dataStoreManager.getString("AccountThumbUrl").first(),
                            ),
                        )
                    }
                    when {
                        home is Resource.Error -> home.message
                        exploreMoodItem is Resource.Error -> exploreMoodItem.message
                        chart is Resource.Error -> chart.message
                        else -> null
                    }?.let {
                        if (!servedFromDisk) {
                            showSnackBarErrorState.emit(it)
                        }
                        Logger.w("Error", "getHomeItemList: ${home.message}")
                        Logger.w("Error", "getHomeItemList: ${exploreMoodItem.message}")
                        Logger.w("Error", "getHomeItemList: ${chart.message}")
                    }
                    loading.value = false
                }
            }
    }

    /** A shelf's "Play all": YouTube's own finite queue of that shelf's songs. */
    fun playAll(item: HomeItem) {
        item.playAllEndpoint?.let { playFromEndpoint(it, item.title) }
    }

    fun getContinueHomeItem(continuation: String?) {
        viewModelScope.launch {
            if (continuation.isNullOrEmpty()) {
                _homeListState.value = ListState.PAGINATION_EXHAUST
                return@launch
            } else {
                log("Get more home item with continuation: $continuation")
                _homeListState.value = ListState.PAGINATING
                homeRepository
                    .getHomeDataContinue(
                        continuation,
                        getString(Res.string.view_count),
                        getString(Res.string.song),
                    ).collect { home ->
                        when (home) {
                            is Resource.Success -> {
                                _continuation.value = home.data?.first
                                val newItems = home.data?.second ?: listOf()
                                _homeItemList.update { it + newItems }
                                if (home.data?.first.isNullOrEmpty()) {
                                    _homeListState.value = ListState.PAGINATION_EXHAUST
                                } else {
                                    _homeListState.value = ListState.IDLE
                                }
                            }

                            is Resource.Error -> {
                                _continuation.value = null
                                Logger.w(tag, "getContinueHomeItem: ${home.message}")
                                showSnackBarErrorState.emit(home.message ?: "Unknown error")
                                _homeListState.value = ListState.PAGINATION_EXHAUST
                            }
                        }
                    }
            }
        }
    }

    fun exploreChart(region: String) {
        viewModelScope.launch {
            loadingChart.value = true
            homeRepository
                .getChartData(
                    region,
                ).collect { values ->
                    regionCodeChart.value = region
                    dataStoreManager.setChartKey(region)
                    when (values) {
                        is Resource.Success -> {
                            _chart.value = values.data
                        }

                        else -> {
                            _chart.value = null
                        }
                    }
                    loadingChart.value = false
                }
        }
    }

    fun setParams(params: String?) {
        _params.value = params
    }

    override fun onCleared() {
        super.onCleared()
        homeJob?.cancel()
    }

    companion object {
        // Home params
        const val HOME_PARAMS_RELAX = "ggM8SgQIBxADSgQIBRABSgQICRABSgQIChABSgQIDRABSgQICBABSgQIBBABSgQIDhABSgQIAxABSgQIBhAB"
        const val HOME_PARAMS_SLEEP = "ggM8SgQIBxABSgQIBRADSgQICRABSgQIChABSgQIDRABSgQICBABSgQIBBABSgQIDhABSgQIAxABSgQIBhAB"
        const val HOME_PARAMS_ENERGIZE = "ggM8SgQIBxABSgQIBRABSgQICRADSgQIChABSgQIDRABSgQICBABSgQIBBABSgQIDhABSgQIAxABSgQIBhAB"
        const val HOME_PARAMS_SAD = "ggM8SgQIBxABSgQIBRABSgQICRABSgQIChADSgQIDRABSgQICBABSgQIBBABSgQIDhABSgQIAxABSgQIBhAB"
        const val HOME_PARAMS_ROMANCE = "ggM8SgQIBxABSgQIBRABSgQICRABSgQIChABSgQIDRADSgQICBABSgQIBBABSgQIDhABSgQIAxABSgQIBhAB"
        const val HOME_PARAMS_FEEL_GOOD = "ggM8SgQIBxABSgQIBRABSgQICRABSgQIChABSgQIDRABSgQICBADSgQIBBABSgQIDhABSgQIAxABSgQIBhAB"
        const val HOME_PARAMS_WORKOUT = "ggM8SgQIBxABSgQIBRABSgQICRABSgQIChABSgQIDRABSgQICBABSgQIBBADSgQIDhABSgQIAxABSgQIBhAB"
        const val HOME_PARAMS_PARTY = "ggM8SgQIBxABSgQIBRABSgQICRABSgQIChABSgQIDRABSgQICBABSgQIBBABSgQIDhADSgQIAxABSgQIBhAB"
        const val HOME_PARAMS_COMMUTE = "ggM8SgQIBxABSgQIBRABSgQICRABSgQIChABSgQIDRABSgQICBABSgQIBBABSgQIDhABSgQIAxADSgQIBhAB"
        const val HOME_PARAMS_FOCUS = "ggM8SgQIBxABSgQIBRABSgQICRABSgQIChABSgQIDRABSgQICBABSgQIBBABSgQIDhABSgQIAxABSgQIBhAD"
    }
}