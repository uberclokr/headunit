package com.xterra.helm.nav

import android.content.Context
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionStatus

/**
 * Thin wrapper over MapLibre's callback-soup offline-region API so the UI can
 * list/delete cached regions. Regions are created by NavMap's CACHE THIS VIEW
 * with a human-readable name in their metadata JSON ({"name": "..."}) —
 * legacy regions from before naming show as "helm".
 */
object OfflineRegions {
    data class Info(
        val region: OfflineRegion,
        val name: String,
        val tiles: Long,
        val mb: Double,
        val complete: Boolean,
    )

    /** List all regions with their sizes. [cb] runs on the main thread. */
    fun list(ctx: Context, cb: (List<Info>) -> Unit) {
        OfflineManager.getInstance(ctx).listOfflineRegions(
            object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    val regions = offlineRegions.orEmpty()
                    if (regions.isEmpty()) { cb(emptyList()); return }
                    val out = arrayOfNulls<Info>(regions.size)
                    var done = 0
                    regions.forEachIndexed { i, r ->
                        r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                            override fun onStatus(status: OfflineRegionStatus?) {
                                out[i] = Info(
                                    r, nameOf(r),
                                    status?.completedResourceCount ?: 0,
                                    (status?.completedResourceSize ?: 0) / 1_048_576.0,
                                    status?.isComplete ?: false,
                                )
                                if (++done == regions.size) cb(out.filterNotNull())
                            }
                            override fun onError(error: String?) {
                                out[i] = Info(r, nameOf(r), 0, 0.0, false)
                                if (++done == regions.size) cb(out.filterNotNull())
                            }
                        })
                    }
                }
                override fun onError(error: String) = cb(emptyList())
            })
    }

    fun delete(info: Info, cb: (String?) -> Unit) {
        info.region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() = cb(null)
            override fun onError(error: String) = cb(error)
        })
    }

    private fun nameOf(r: OfflineRegion): String = runCatching {
        Regex(""""name"\s*:\s*"([^"]*)"""")
            .find(String(r.metadata))?.groupValues?.get(1)
    }.getOrNull().takeUnless { it.isNullOrBlank() } ?: "unnamed"
}
