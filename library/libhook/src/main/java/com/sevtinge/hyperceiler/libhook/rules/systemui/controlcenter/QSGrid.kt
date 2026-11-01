/*
  * This file is part of HyperCeiler.

  * HyperCeiler is free software: you can redistribute it and/or modify
  * it under the terms of the GNU Affero General Public License as
  * published by the Free Software Foundation, either version 3 of the
  * License.

  * This program is distributed in the hope that it will be useful,
  * but WITHOUT ANY WARRANTY; without even the implied warranty of
  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  * GNU Affero General Public License for more details.

  * You should have received a copy of the GNU Affero General Public License
  * along with this program.  If not, see <https://www.gnu.org/licenses/>.

  * Copyright (C) 2023-2026 HyperCeiler Contributions
*/
package com.sevtinge.hyperceiler.libhook.rules.systemui.controlcenter

import android.content.res.Configuration
import android.view.ViewGroup
import com.sevtinge.hyperceiler.common.utils.PrefsBridge
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.loadClass
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.getObjectFieldOrNullAs
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.setObjectField
import java.util.ArrayList

class QSGrid : BaseHook() {
    private val pagedTileClass by lazy {
        loadClass("com.android.systemui.qs.MiuiPagedTileLayout")
    }

    override fun init() {
        val cols = PrefsBridge.getInt("system_control_center_old_qs_columns", 4)
        val colsHorizontal = PrefsBridge.getInt("system_control_center_old_qs_columns_horizontal", 5)
        val rows = PrefsBridge.getInt("system_control_center_old_qs_rows", 3)
        val rowsHorizontal = PrefsBridge.getInt("system_control_center_old_qs_rows_horizontal", 2)

        // MiuiPagedTileLayout distributes tiles using the first TilePage's
        // mColumns/mRows before calling ViewPager.onMeasure. Set custom values
        // before this pass; layoutTileRecords() is far too late because child
        // widths and page distribution already used the old column count.
        pagedTileClass.findMethod { name("onMeasure"); paramCount(2) }.createBeforeHook {
            applyGrid(it.thisObject, cols, colsHorizontal, rows, rowsHorizontal)
        }

        // updateResources() resets each TilePage to ROM resource columns.
        // Reapply preferences after that reset for the next measurement.
        pagedTileClass.findMethod { name("updateResources"); paramCount(0) }.createAfterHook {
            applyGrid(it.thisObject, cols, colsHorizontal, rows, rowsHorizontal)
        }

        // Pages created during onMeasure are not in mPages when this method
        // returns, so configure them before the parent distributes records.
        pagedTileClass.findMethod { name("createTilePage"); paramCount(0) }.createAfterHook { param ->
            val page = param.result ?: return@createAfterHook
            val view = param.thisObject as? ViewGroup ?: return@createAfterHook
            val portrait = view.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            val targetColumns = (if (portrait) cols else colsHorizontal).coerceAtLeast(1)
            val targetRows = (if (portrait) rows else rowsHorizontal).coerceAtLeast(1)
            configurePage(page, targetColumns, targetRows)
        }
    }

    private fun applyGrid(
        pagedLayout: Any,
        cols: Int,
        colsHorizontal: Int,
        rows: Int,
        rowsHorizontal: Int
    ) {
        val view = pagedLayout as? ViewGroup ?: return
        val pages = pagedLayout.getObjectFieldOrNullAs<ArrayList<*>>("mPages") ?: return
        if (pages.isEmpty()) return

        val portrait = view.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val targetColumns = (if (portrait) cols else colsHorizontal).coerceAtLeast(1)
        val targetRows = (if (portrait) rows else rowsHorizontal).coerceAtLeast(1)
        var dimensionsChanged = false
        pages.forEach { page ->
            if (page != null) dimensionsChanged = configurePage(page, targetColumns, targetRows) || dimensionsChanged
        }

        if (dimensionsChanged) {
            // onMeasure uses this flag to recalculate both rows and page
            // distribution with the updated page capacity.
            pagedLayout.setObjectField("mDistributeTiles", true)
            view.requestLayout()
        }
    }

    private fun configurePage(page: Any, columns: Int, maxRows: Int): Boolean {
        val oldColumns = page.getObjectFieldOrNullAs<Int>("mColumns") ?: return false
        val oldMaxRows = page.getObjectFieldOrNullAs<Int>("mMaxAllowedRows")
        if (oldColumns != columns) page.setObjectField("mColumns", columns)
        if (oldMaxRows != maxRows) page.setObjectField("mMaxAllowedRows", maxRows)
        return oldColumns != columns || oldMaxRows != maxRows
    }
}
