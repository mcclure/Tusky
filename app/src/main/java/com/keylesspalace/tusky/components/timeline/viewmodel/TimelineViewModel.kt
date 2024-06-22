/* Copyright 2021 Tusky Contributors
 *
 * This file is a part of Tusky.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * Tusky is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Tusky; if not,
 * see <http://www.gnu.org/licenses>. */

package com.keylesspalace.tusky.components.timeline.viewmodel

import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import at.connyduck.calladapter.networkresult.NetworkResult
import at.connyduck.calladapter.networkresult.fold
import at.connyduck.calladapter.networkresult.getOrElse
import at.connyduck.calladapter.networkresult.getOrThrow
import com.keylesspalace.tusky.appstore.Event
import com.keylesspalace.tusky.appstore.EventHub
import com.keylesspalace.tusky.appstore.FilterUpdatedEvent
import com.keylesspalace.tusky.appstore.PreferenceChangedEvent
import com.keylesspalace.tusky.components.preference.PreferencesFragment.ReadingOrder
import com.keylesspalace.tusky.components.timeline.util.ifExpected
import com.keylesspalace.tusky.db.AccountManager
import com.keylesspalace.tusky.entity.Filter
import com.keylesspalace.tusky.entity.FilterV1
import com.keylesspalace.tusky.entity.Poll
import com.keylesspalace.tusky.network.FilterModel
import com.keylesspalace.tusky.network.MastodonApi
import com.keylesspalace.tusky.settings.PrefKeys
import com.keylesspalace.tusky.usecase.TimelineCases
import com.keylesspalace.tusky.util.isHttpNotFound
import com.keylesspalace.tusky.viewdata.StatusViewData
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

abstract class TimelineViewModel(
    protected val timelineCases: TimelineCases,
    private val api: MastodonApi,
    private val eventHub: EventHub,
    protected val accountManager: AccountManager,
    private val sharedPreferences: SharedPreferences,
    private val filterModel: FilterModel
) : ViewModel() {

    abstract val statuses: Flow<PagingData<StatusViewData>>

    var kind: Kind = Kind.HOME
        private set
    var id: String? = null
        private set
    var tags: List<String> = emptyList()
        private set

    protected var alwaysShowSensitiveMedia = false
    private var alwaysOpenSpoilers = false
    private var filterRemoveReplies = false
    private var filterRemoveReblogs = false
    private var filterRemoveSelfReblogs = false
    protected var readingOrder: ReadingOrder = ReadingOrder.OLDEST_FIRST

    fun init(kind: Kind, id: String?, tags: List<String>) {
        this.kind = kind
        this.id = id
        this.tags = tags
        filterModel.kind = kind.toFilterKind()

        if (kind == Kind.HOME) {
            // Note the variable is "true if filter" but the underlying preference/settings text is "true if show"
            filterRemoveReplies =
                !(accountManager.activeAccount?.isShowHomeReplies ?: true)
            filterRemoveReblogs =
                !(accountManager.activeAccount?.isShowHomeBoosts ?: true)
            filterRemoveSelfReblogs =
                !(accountManager.activeAccount?.isShowHomeSelfBoosts ?: true)
        }
        readingOrder = ReadingOrder.from(sharedPreferences.getString(PrefKeys.READING_ORDER, null))

        this.alwaysShowSensitiveMedia = accountManager.activeAccount!!.alwaysShowSensitiveMedia
        this.alwaysOpenSpoilers = accountManager.activeAccount!!.alwaysOpenSpoiler

        viewModelScope.launch {
            eventHub.events
                .collect { event -> handleEvent(event) }
        }

        reloadFilters()
    }

    fun reblog(reblog: Boolean, status: StatusViewData.Concrete): Job = viewModelScope.launch {
        try {
            timelineCases.reblog(status.actionableId, reblog).getOrThrow()
        } catch (t: Exception) {
            ifExpected(t) {
                Log.d(TAG, "Failed to reblog status " + status.actionableId, t)
            }
        }
    }

    fun favorite(favorite: Boolean, status: StatusViewData.Concrete): Job = viewModelScope.launch {
        try {
            timelineCases.favourite(status.actionableId, favorite).getOrThrow()
        } catch (t: Exception) {
            ifExpected(t) {
                Log.d(TAG, "Failed to favourite status " + status.actionableId, t)
            }
        }
    }

    fun bookmark(bookmark: Boolean, status: StatusViewData.Concrete): Job = viewModelScope.launch {
        try {
            timelineCases.bookmark(status.actionableId, bookmark).getOrThrow()
        } catch (t: Exception) {
            ifExpected(t) {
                Log.d(TAG, "Failed to bookmark status " + status.actionableId, t)
            }
        }
    }

    fun voteInPoll(choices: List<Int>, status: StatusViewData.Concrete): Job =
        viewModelScope.launch {
            val poll = status.status.actionableStatus.poll ?: run {
                Log.w(TAG, "No poll on status ${status.id}")
                return@launch
            }

            val votedPoll = poll.votedCopy(choices)
            updatePoll(votedPoll, status)

            try {
                timelineCases.voteInPoll(status.actionableId, poll.id, choices).getOrThrow()
            } catch (t: Exception) {
                ifExpected(t) {
                    Log.d(TAG, "Failed to vote in poll: " + status.actionableId, t)
                }
            }
        }

    abstract fun updatePoll(newPoll: Poll, status: StatusViewData.Concrete)

    abstract fun changeExpanded(expanded: Boolean, status: StatusViewData.Concrete)

    abstract fun changeContentShowing(isShowing: Boolean, status: StatusViewData.Concrete)

    abstract fun changeContentCollapsed(isCollapsed: Boolean, status: StatusViewData.Concrete)

    abstract fun removeStatusWithId(id: String)

    abstract fun loadMore(placeholderId: String)

    abstract fun fullReload()

    abstract fun clearWarning(status: StatusViewData.Concrete)

    /** Saves the user's reading position so it can be restored later */
    abstract fun saveReadingPosition(statusId: String)

    /** Triggered when currently displayed data must be reloaded. */
    protected abstract suspend fun invalidate()

    protected fun shouldFilterStatus(statusViewData: StatusViewData): Filter.Action {
        val status = statusViewData.asStatusOrNull()?.status ?: return Filter.Action.NONE
        return if (
            (status.inReplyToId != null && filterRemoveReplies) ||
            (status.reblog != null && filterRemoveReblogs) ||
            ((status.account.id == status.reblog?.account?.id) && filterRemoveSelfReblogs)
        ) {
            return Filter.Action.HIDE
        } else {
            statusViewData.filterAction = filterModel.shouldFilterStatus(status.actionableStatus)
            statusViewData.filterAction
        }
    }

    private fun onPreferenceChanged(key: String) {
        when (key) {
            PrefKeys.TAB_FILTER_HOME_REPLIES -> {
                val filter = accountManager.activeAccount?.isShowHomeReplies ?: true
                val oldRemoveReplies = filterRemoveReplies
                filterRemoveReplies = kind == Kind.HOME && !filter
                if (oldRemoveReplies != filterRemoveReplies) {
                    fullReload()
                }
            }
            PrefKeys.TAB_FILTER_HOME_BOOSTS -> {
                val filter = accountManager.activeAccount?.isShowHomeBoosts ?: true
                val oldRemoveReblogs = filterRemoveReblogs
                filterRemoveReblogs = kind == Kind.HOME && !filter
                if (oldRemoveReblogs != filterRemoveReblogs) {
                    fullReload()
                }
            }
            PrefKeys.TAB_SHOW_HOME_SELF_BOOSTS -> {
                val filter = accountManager.activeAccount?.isShowHomeSelfBoosts ?: true
                val oldRemoveSelfReblogs = filterRemoveSelfReblogs
                filterRemoveSelfReblogs = kind == Kind.HOME && !filter
                if (oldRemoveSelfReblogs != filterRemoveSelfReblogs) {
                    fullReload()
                }
            }
            FilterV1.HOME, FilterV1.NOTIFICATIONS, FilterV1.THREAD, FilterV1.PUBLIC, FilterV1.ACCOUNT -> {
                if (filterContextMatchesKind(kind, listOf(key))) {
                    reloadFilters()
                }
            }
            PrefKeys.ALWAYS_SHOW_SENSITIVE_MEDIA -> {
                // it is ok if only newly loaded statuses are affected, no need to fully refresh
                alwaysShowSensitiveMedia =
                    accountManager.activeAccount!!.alwaysShowSensitiveMedia
            }
            PrefKeys.READING_ORDER -> {
                readingOrder = ReadingOrder.from(sharedPreferences.getString(PrefKeys.READING_ORDER, null))
            }
        }
    }

    private fun handleEvent(event: Event) {
        when (event) {
            is PreferenceChangedEvent -> {
                onPreferenceChanged(event.preferenceKey)
            }
            is FilterUpdatedEvent -> {
                if (filterContextMatchesKind(kind, event.filterContext)) {
                    fullReload()
                }
            }
        }
    }

    private fun reloadFilters() {
        viewModelScope.launch {
            api.getFilters().fold(
                {
                    // After the filters are loaded we need to reload displayed content to apply them.
                    // It can happen during the usage or at startup, when we get statuses before filters.
                    invalidate()
                },
                { throwable ->
                    if (throwable.isHttpNotFound()) {
                        // Fallback to client-side filter code
                        val filters = api.getFiltersV1().getOrElse {
                            Log.e(TAG, "Failed to fetch filters", it)
                            return@launch
                        }
                        filterModel.initWithFilters(
                            filters.filter {
                                filterContextMatchesKind(kind, it.context)
                            }
                        )
                        // After the filters are loaded we need to reload displayed content to apply them.
                        // It can happen during the usage or at startup, when we get statuses before filters.
                        invalidate()
                    } else {
                        Log.e(TAG, "Error getting filters", throwable)
                    }
                }
            )
        }
    }

    abstract suspend fun translate(status: StatusViewData.Concrete): NetworkResult<Unit>
    abstract fun untranslate(status: StatusViewData.Concrete)

    companion object {
        private const val TAG = "TimelineVM"
        internal const val LOAD_AT_ONCE = 30

        fun filterContextMatchesKind(kind: Kind, filterContext: List<String>): Boolean {
            return filterContext.contains(kind.toFilterKind().kind)
        }
    }

    enum class Kind {
        HOME,
        PUBLIC_LOCAL,
        PUBLIC_FEDERATED,
        TAG,
        USER,
        USER_PINNED,
        USER_WITH_REPLIES,
        FAVOURITES,
        LIST,
        BOOKMARKS,
        PUBLIC_TRENDING_STATUSES;

        fun toFilterKind(): Filter.Kind {
            return when (valueOf(name)) {
                HOME, LIST -> Filter.Kind.HOME
                PUBLIC_FEDERATED, PUBLIC_LOCAL, TAG, FAVOURITES, PUBLIC_TRENDING_STATUSES -> Filter.Kind.PUBLIC
                USER, USER_WITH_REPLIES, USER_PINNED -> Filter.Kind.ACCOUNT
                else -> Filter.Kind.PUBLIC
            }
        }
    }
}
