package com.splitstak.app.wear.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.TypeBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.Wearable
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.splitstak.app.wear.MainActivity
import com.splitstak.app.wear.data.Snapshot
import com.splitstak.app.wear.data.WatchState

/**
 * Tile that surfaces the current exercise in the watch's tile carousel.
 * Non-scrollable, non-interactive beyond a single tap-to-open. The button
 * area drops into the MainActivity for full ± controls — that lets us
 * keep the tile dead-simple while the Compose UI handles the heavy work.
 */
class ActiveExerciseTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        val snapshot = WatchState.currentSnapshot(applicationContext)
        val layout = LayoutElementBuilders.Layout.Builder()
            .setRoot(buildRoot(applicationContext, snapshot))
            .build()
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(layout)
                            .build()
                    )
                    .build()
            )
            // Refresh every 30 s — the Data Layer listener triggers an
            // immediate refresh on actual snapshot changes, so this is just
            // a slow-tick safety net.
            .setFreshnessIntervalMillis(30_000L)
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build()
        )
    }

    companion object {
        private const val RESOURCES_VERSION = "1"
    }
}

private fun buildRoot(
    context: Context,
    snapshot: Snapshot?
): LayoutElementBuilders.LayoutElement {
    val openAppClickable = ModifiersBuilders.Clickable.Builder()
        .setId("open_app")
        .setOnClick(
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(context.packageName)
                        .setClassName(MainActivity::class.java.name)
                        .build()
                )
                .build()
        )
        .build()

    val rootModifiers = ModifiersBuilders.Modifiers.Builder()
        .setClickable(openAppClickable)
        .build()

    val column = LayoutElementBuilders.Column.Builder()
        .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
        .setModifiers(rootModifiers)

    when {
        snapshot == null -> {
            column
                .addContent(textLine("SPLITSTAK", 16, SplitstakColors.ACCENT, bold = true))
                .addContent(spacer(8))
                .addContent(textLine("Open phone app to sync", 11, SplitstakColors.TEXT_DIM))
        }
        snapshot.isRestDay -> {
            column
                .addContent(textLine("REST", 32, SplitstakColors.ACCENT, bold = true))
                .addContent(spacer(6))
                .addContent(textLine(snapshot.currentDayLabel ?: "Today", 11, SplitstakColors.TEXT_DIM))
        }
        snapshot.dayAllComplete -> {
            column
                .addContent(textLine("DAY DONE", 18, SplitstakColors.ACCENT, bold = true))
                .addContent(spacer(4))
                .addContent(textLine("Tap to save", 11, SplitstakColors.TEXT_DIM))
        }
        else -> {
            // Prefer the exercise the user last interacted with on the
            // watch; fall back to the PWA's selection or the first one.
            val ex = WatchState.currentExercise(context) ?: snapshot.currentExercise()
            if (ex == null) {
                column.addContent(textLine("No exercise selected", 12, SplitstakColors.TEXT_DIM))
            } else {
                val completedSets = ex.sets.count { it.d }
                val totalSets = ex.sets.size
                column
                    .addContent(
                        textLine(
                            ex.name.uppercase(),
                            13,
                            SplitstakColors.TEXT,
                            bold = true,
                            maxLines = 2
                        )
                    )
                    .addContent(spacer(4))
                    .addContent(textLine(ex.target.ifEmpty { "—" }, 10, SplitstakColors.ACCENT))
                    .addContent(spacer(8))
                    .addContent(
                        textLine(
                            "SET $completedSets / $totalSets",
                            11,
                            SplitstakColors.TEXT_DIM,
                            bold = true
                        )
                    )
                    .addContent(spacer(4))
                    .addContent(
                        textLine(
                            ex.lastTop?.takeIf { it.isNotEmpty() && it != "null" }
                                ?: "—",
                            14,
                            SplitstakColors.TEXT
                        )
                    )
                    .addContent(spacer(10))
                    .addContent(textLine("TAP TO LOG", 9, SplitstakColors.ACCENT, bold = true))
            }
        }
    }
    return column.build()
}

private fun textLine(
    text: String,
    sizeSp: Int,
    color: Int,
    bold: Boolean = false,
    maxLines: Int = 1
): LayoutElementBuilders.LayoutElement {
    val builder = LayoutElementBuilders.Text.Builder()
        .setText(text)
        .setMaxLines(maxLines)
        .setFontStyle(
            LayoutElementBuilders.FontStyle.Builder()
                .setSize(sp(sizeSp.toFloat()))
                .setWeight(
                    LayoutElementBuilders.FontWeightProp.Builder()
                        .setValue(
                            if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD
                            else LayoutElementBuilders.FONT_WEIGHT_NORMAL
                        )
                        .build()
                )
                .setColor(ColorBuilders.ColorProp.Builder(color).build())
                .build()
        )
    return builder.build()
}

private fun spacer(heightDp: Int): LayoutElementBuilders.LayoutElement {
    return LayoutElementBuilders.Spacer.Builder()
        .setHeight(dp(heightDp.toFloat()))
        .build()
}

/** Color hex shortcuts (ProtoLayout uses raw ARGB ints, not Compose Color). */
private object SplitstakColors {
    const val ACCENT = 0xFFFF5722.toInt()
    const val TEXT = 0xFFF5F5F5.toInt()
    const val TEXT_DIM = 0xFF888888.toInt()
}

/**
 * Helper that the Data Layer listener calls to request the system re-render
 * any active tiles after a snapshot update. Wear OS handles the actual
 * onTileRequest re-invocation in response.
 */
object RefreshTiles {
    fun requestUpdate(context: Context) {
        try {
            val updater = androidx.wear.tiles.TileService.getUpdater(context)
            updater.requestUpdate(ActiveExerciseTileService::class.java)
        } catch (_: Exception) {
            // No active tile installations; safe to ignore.
        }
    }
}
