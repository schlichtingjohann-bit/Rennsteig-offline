package de.rennsteig.offline

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sqrt

enum class PoiCategory(val key: String, val label: String, val color: Int) {
    LODGING("lodging", "Unterkünfte", 0xFF3949AB.toInt()),
    FOOD("food", "Essen & Trinken", 0xFFE8650A.toInt()),
    SIGHT("sight", "Sehenswürdigkeiten", 0xFF8E24AA.toInt()),
    VIEW("view", "Aussicht & Gipfel", 0xFF2E7D32.toInt()),
    SHELTER("shelter", "Schutzhütten", 0xFF795548.toInt()),
    WATER("water", "Trinkwasser", 0xFF0288D1.toInt()),
    STATION("station", "Bahnhöfe", 0xFF546E7A.toInt());

    fun iconName(top: Boolean) = if (top) "poi-$key-top" else "poi-$key"

    companion object {
        fun of(key: String): PoiCategory? = entries.firstOrNull { it.key == key }
    }
}

class Poi(
    val id: String,
    val cat: PoiCategory,
    val kind: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val km: Double,
    val off: Int,
    val score: Double,
    val top: Boolean,
    val desc: String?,
    val demEle: Int?,
    val tags: Map<String, String>,
) {
    val displayName: String get() = name.ifEmpty { kindLabel }

    val kindLabel: String
        get() = KIND_LABELS[kind] ?: when (cat) {
            PoiCategory.SHELTER -> "Schutzhütte"
            else -> cat.label
        }

    companion object {
        val KIND_LABELS = mapOf(
            "hotel" to "Hotel", "guest_house" to "Pension / Gasthof", "alpine_hut" to "Berghütte / Baude",
            "hostel" to "Herberge", "motel" to "Motel", "chalet" to "Ferienhaus",
            "apartment" to "Ferienwohnung", "camp_site" to "Zeltplatz", "wilderness_hut" to "Wanderhütte",
            "restaurant" to "Gaststätte", "biergarten" to "Biergarten", "cafe" to "Café", "pub" to "Kneipe",
            "fast_food" to "Imbiss", "ice_cream" to "Eiscafé",
            "castle" to "Burg / Schloss", "museum" to "Museum", "attraction" to "Sehenswürdigkeit",
            "ruins" to "Ruine", "monument" to "Denkmal", "archaeological_site" to "Bodendenkmal",
            "boundary_stone" to "Grenzstein", "memorial" to "Gedenkstätte", "waterfall" to "Wasserfall",
            "cave" to "Höhle", "rock" to "Felsen", "spring" to "Quelle", "mine" to "Bergwerk",
            "church" to "Kirche", "tower" to "Aussichtsturm", "viewpoint" to "Aussichtspunkt",
            "peak" to "Gipfel", "weather_shelter" to "Schutzhütte", "basic_hut" to "Hütte",
            "lean_to" to "Unterstand", "picnic_shelter" to "Rasthütte", "rock_shelter" to "Felsdach",
            "drinking_water" to "Trinkwasser", "station" to "Bahnhof", "halt" to "Haltepunkt",
            "start" to "Start des Rennsteigs", "end" to "Ziel des Rennsteigs",
        )
    }
}

/** Die Rennsteig-Linie mit kumulierten Distanzen für km-Angaben. */
class Route(val lats: DoubleArray, val lons: DoubleArray, val name: String) {
    val cum = DoubleArray(lats.size)
    val lengthM: Double

    init {
        for (i in 1 until lats.size) {
            cum[i] = cum[i - 1] + Geo.distance(lats[i - 1], lons[i - 1], lats[i], lons[i])
        }
        lengthM = cum.last()
    }

    class Position(val km: Double, val offM: Double, val lat: Double, val lon: Double)

    /** Nächster Punkt auf der Route (lokale ebene Näherung, genau genug für < 50 km). */
    fun locate(lat: Double, lon: Double): Position {
        val kx = 111320.0 * cos(Math.toRadians(lat))
        val ky = 110574.0
        var best = Double.MAX_VALUE
        var bestAlong = 0.0
        var bestLat = lats[0]
        var bestLon = lons[0]
        for (i in 0 until lats.size - 1) {
            val ax = (lons[i] - lon) * kx
            val ay = (lats[i] - lat) * ky
            val dx = (lons[i + 1] - lon) * kx - ax
            val dy = (lats[i + 1] - lat) * ky - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val px = ax + t * dx
            val py = ay + t * dy
            val d2 = px * px + py * py
            if (d2 < best) {
                best = d2
                bestAlong = cum[i] + t * (cum[i + 1] - cum[i])
                bestLat = lats[i] + t * (lats[i + 1] - lats[i])
                bestLon = lons[i] + t * (lons[i + 1] - lons[i])
            }
        }
        return Position(bestAlong / 1000.0, sqrt(best), bestLat, bestLon)
    }

    fun pointAt(km: Double): Pair<Double, Double> {
        val d = (km * 1000.0).coerceIn(0.0, lengthM)
        var lo = 0
        var hi = cum.size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) / 2
            if (cum[mid] <= d) lo = mid else hi = mid
        }
        val seg = cum[hi] - cum[lo]
        val f = if (seg > 0) (d - cum[lo]) / seg else 0.0
        return Pair(lats[lo] + f * (lats[hi] - lats[lo]), lons[lo] + f * (lons[hi] - lons[lo]))
    }

    fun bounds(): DoubleArray {
        return doubleArrayOf(lats.min(), lons.min(), lats.max(), lons.max())
    }
}

class Profile(
    val stepM: Double,
    val ele: IntArray,
    val ascent: Int,
    val descent: Int,
    val minM: Int,
    val maxM: Int,
) {
    fun eleAt(km: Double): Double {
        if (ele.isEmpty()) return 0.0
        val x = (km * 1000.0 / stepM).coerceIn(0.0, (ele.size - 1).toDouble())
        val i = x.toInt()
        val j = minOf(i + 1, ele.size - 1)
        val f = x - i
        return ele[i] * (1 - f) + ele[j] * f
    }

    /** Auf- und Abstieg zwischen zwei Routen-km (für "noch bis zum Ziel"). */
    fun climbBetween(fromKm: Double, toKm: Double): Pair<Int, Int> {
        if (ele.isEmpty()) return Pair(0, 0)
        val a = (minOf(fromKm, toKm) * 1000 / stepM).toInt().coerceIn(0, ele.size - 1)
        val b = (maxOf(fromKm, toKm) * 1000 / stepM).toInt().coerceIn(0, ele.size - 1)
        var up = 0
        var down = 0
        var ref = ele[a]
        for (i in a + 1..b) {
            val e = ele[i]
            if (e - ref >= 4) { up += e - ref; ref = e } else if (ref - e >= 4) { down += ref - e; ref = e }
        }
        return if (toKm >= fromKm) Pair(up, down) else Pair(down, up)
    }
}

class AppData(
    val route: Route,
    val routeGeoJson: String,
    val pois: List<Poi>,
    val profile: Profile?,
    val dataDate: String,
)

object DataLoader {
    private const val START_DESC =
        "Hier an der Werra in Hörschel beginnt der Rennsteig. Alter Brauch: Nimm einen Kiesel aus " +
            "der Werra mit und wirf ihn am Ziel in Blankenstein in die Selbitz. " +
            "Der Gruß der Rennsteigwanderer lautet „Gut Runst!“"
    private const val END_DESC =
        "Ziel in Blankenstein an der Selbitz. Hier wird der in Hörschel aufgelesene Werra-Kiesel " +
            "in die Selbitz geworfen – Glückwunsch zur Rennsteig-Wanderung!"

    fun load(context: Context): AppData {
        val routeText = readAsset(context, "data/route.geojson")
        val routeJson = JSONObject(routeText)
        val features = routeJson.getJSONArray("features")
        var route: Route? = null
        var dataDate = ""
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val props = f.getJSONObject("properties")
            if (props.optString("kind") == "route") {
                val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
                val lats = DoubleArray(coords.length())
                val lons = DoubleArray(coords.length())
                for (k in 0 until coords.length()) {
                    val c = coords.getJSONArray(k)
                    lons[k] = c.getDouble(0)
                    lats[k] = c.getDouble(1)
                }
                route = Route(lats, lons, props.optString("name", "Rennsteig"))
                dataDate = props.optString("generated")
            }
        }
        requireNotNull(route) { "route.geojson enthält keine Route" }

        val pois = ArrayList<Poi>()
        val poiFeatures = JSONObject(readAsset(context, "data/pois.geojson")).getJSONArray("features")
        for (i in 0 until poiFeatures.length()) {
            parsePoi(poiFeatures.getJSONObject(i))?.let { pois.add(it) }
        }
        pois.add(Poi("start", PoiCategory.SIGHT, "start", "Start in Hörschel", route.lats.first(),
            route.lons.first(), 0.0, 0, 99.0, true, START_DESC, null, emptyMap()))
        pois.add(Poi("end", PoiCategory.SIGHT, "end", "Ziel in Blankenstein", route.lats.last(),
            route.lons.last(), route.lengthM / 1000.0, 0, 99.0, true, END_DESC, null, emptyMap()))
        pois.sortBy { it.km }

        val profile = try {
            val p = JSONObject(readAsset(context, "data/profile.json"))
            val arr: JSONArray = p.getJSONArray("ele")
            Profile(p.getDouble("step_m"), IntArray(arr.length()) { arr.getInt(it) },
                p.optInt("ascent_m"), p.optInt("descent_m"), p.optInt("min_m"), p.optInt("max_m"))
        } catch (e: Exception) {
            null
        }
        return AppData(route, routeText, pois, profile, dataDate)
    }

    private fun parsePoi(f: JSONObject): Poi? {
        val props = f.optJSONObject("properties") ?: return null
        val cat = PoiCategory.of(props.optString("cat")) ?: return null
        val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
        val tags = HashMap<String, String>()
        val keys = props.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.startsWith("osm:")) tags[k.removePrefix("osm:")] = props.optString(k)
        }
        return Poi(
            id = props.optString("id"),
            cat = cat,
            kind = props.optString("kind"),
            name = props.optString("name"),
            lat = coords.getDouble(1),
            lon = coords.getDouble(0),
            km = props.optDouble("km", 0.0),
            off = props.optInt("off", 0),
            score = props.optDouble("score", 0.0),
            top = props.optBoolean("top", false),
            desc = props.optString("desc").ifEmpty { null },
            demEle = if (props.has("dem_ele")) props.optInt("dem_ele") else null,
            tags = tags,
        )
    }

    fun readAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
