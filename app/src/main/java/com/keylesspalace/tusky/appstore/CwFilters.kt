/* Copyright 2023 Andi McClure
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

package com.keylesspalace.tusky.appstore

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

typealias CwRule = Pair<Set<String>, Boolean>

object CwFilterKeys {
    const final val ERASE = 0
    const final val EXPAND = 1
}

// This class's values update only once, at MainActivity bootup, so flows are not needed.
@Singleton
class CwFilters @Inject constructor() {
    private val chaosToken = "!!!"
    private val tokenRegex = Regex("""\p{L}+""")

    private var data:Array<CwRule> = Array<CwRule>(2, {Pair(emptySet(),false)})

    private fun tokenize(s:String): Sequence<String> {
        return tokenRegex.findAll(s, 0).map { x -> x.value }
    }

    private fun decode(s: String): Pair<Set<String>, Boolean> {
        val anti = s.contains(chaosToken);
        return Pair(tokenize(s).toSet(), anti);
    }

    fun reset(erase:String, expand:String) {
        data[CwFilterKeys.ERASE] = decode(erase);
        data[CwFilterKeys.EXPAND] = decode(expand);

        // If EXPAND and ERASE differ in "anti"-ness, treat them as two separate lists.
        // Otherwise, merge the EXPAND list into the ERASE list.
        if (data[CwFilterKeys.EXPAND].first.size > 0 &&  data[CwFilterKeys.ERASE].second == data[CwFilterKeys.EXPAND].second)
            data[CwFilterKeys.ERASE] = Pair(data[CwFilterKeys.ERASE].first.union(data[CwFilterKeys.EXPAND].first), data[CwFilterKeys.ERASE].second)
    }

    fun should(cwFilterKey: Int, target:String): Boolean {
        val (set, anti) = data[cwFilterKey]

        if (set.size == 0) // No match keywords given
            return anti    // So we know ahead of time we should not do it (regular case) or do it (anti case)

        val seq = tokenize(target)

        // Search for the first instance of a nonmatching (or, for anti, matching) word
        for (word in seq) {
            val contains = set.contains(word);
            if (contains == anti) // Condition met: nonmatching (regular case) or matching (anti case)
                return anti // Return "don't do it" (regular case) or "do it" (anti case)
        }
        return !anti // Return "do it" (regular case) or "don't do it" (anti case)
    }
}
