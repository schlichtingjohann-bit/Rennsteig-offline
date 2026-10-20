package de.rennsteig.offline

import android.content.Context
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * Die Kartendateien liegen komprimiert in der APK. MapLibre braucht sie als normale Dateien
 * (SQLite/MBTiles), daher werden sie beim ersten Start bzw. nach einem Update einmalig kopiert.
 */
object AssetInstaller {
    private const val MAP_ASSET = "tiles/rennsteig.mbtiles"
    private const val DEM_ASSET = "tiles/dem.mbtiles"
    private const val CONTOUR_ASSET = "tiles/contours.mbtiles"

    private fun dir(context: Context) = File(context.filesDir, "maps").apply { mkdirs() }
    fun mapFile(context: Context) = File(dir(context), "rennsteig.mbtiles")
    fun demFile(context: Context) = File(dir(context), "dem.mbtiles")
    fun contourFile(context: Context) = File(dir(context), "contours.mbtiles")

    fun install(context: Context, progress: (String) -> Unit) {
        val stampFile = File(dir(context), "installed.stamp")
        @Suppress("DEPRECATION")
        val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        val current = if (stampFile.exists()) stampFile.readText() else ""
        if (current == stamp && mapFile(context).exists()) return
        progress("Offline-Karte wird eingerichtet …\n(nur beim ersten Start)")
        copy(context, MAP_ASSET, mapFile(context))
        progress("Höhenmodell wird eingerichtet …")
        copy(context, DEM_ASSET, demFile(context))
        copy(context, CONTOUR_ASSET, contourFile(context))
        stampFile.writeText(stamp)
    }

    private fun copy(context: Context, asset: String, target: File) {
        val tmp = File(target.path + ".tmp")
        try {
            context.assets.open(asset).use { input ->
                FileOutputStream(tmp).use { out -> input.copyTo(out, 256 * 1024) }
            }
        } catch (e: FileNotFoundException) {
            tmp.delete()
            return
        }
        for (suffix in listOf("-journal", "-wal", "-shm")) File(target.path + suffix).delete()
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}
