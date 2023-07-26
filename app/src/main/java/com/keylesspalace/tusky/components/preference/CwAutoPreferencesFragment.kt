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

package com.keylesspalace.tusky.components.preference

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.keylesspalace.tusky.R
import com.keylesspalace.tusky.settings.PrefKeys
import com.keylesspalace.tusky.settings.checkBoxPreference
import com.keylesspalace.tusky.settings.makePreferenceScreen
import com.keylesspalace.tusky.settings.preferenceCategory
import com.keylesspalace.tusky.settings.validatedEditTextPreference

class CwAutoPreferencesFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        makePreferenceScreen {
            preferenceCategory(R.string.pref_title_cw_auto_prelude) {
                category ->
                    category.isIconSpaceReserved = false
            }
            preferenceCategory(R.string.pref_title_cw_auto_1) {
                category ->
                    category.isIconSpaceReserved = false

                validatedEditTextPreference(null, {_ -> true}) {
                    setTitle(R.string.pref_title_cw_auto_tap_here)
                    key = PrefKeys.CW_AUTO_ERASE_WORDS
                    setDefaultValue("")
                    isIconSpaceReserved = false
                }
            }
            preferenceCategory(R.string.pref_title_cw_auto_2) {
                    category ->
                category.isIconSpaceReserved = false

                validatedEditTextPreference(null, {_ -> true}) {
                    setTitle(R.string.pref_title_cw_auto_tap_here)
                    key = PrefKeys.CW_AUTO_EXPAND_WORDS
                    setDefaultValue("")
                    isIconSpaceReserved = false
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requireActivity().setTitle(R.string.pref_title_post_cw_auto)
    }

    companion object {
        fun newInstance(): TabFilterPreferencesFragment {
            return TabFilterPreferencesFragment()
        }
    }
}
