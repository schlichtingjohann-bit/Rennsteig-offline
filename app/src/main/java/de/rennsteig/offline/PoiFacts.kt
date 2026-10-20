package de.rennsteig.offline

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AttributeSet
import android.widget.ScrollView

/** Aufbereitung der OSM-Angaben eines Ortes für die Detailkarte. */
object PoiFacts {
    class Fact(val label: String, val value: String, val action: Intent? = null)

    private val CUISINE = mapOf(
        "regional" to "regional", "german" to "deutsch", "thuringian" to "thüringisch",
        "italian" to "italienisch", "pizza" to "Pizza", "greek" to "griechisch", "asian" to "asiatisch",
        "chinese" to "chinesisch", "vietnamese" to "vietnamesisch", "burger" to "Burger",
        "kebab" to "Döner", "coffee_shop" to "Kaffee", "cake" to "Kuchen", "ice_cream" to "Eis",
        "steak_house" to "Steakhaus", "barbecue" to "Grill", "bratwurst" to "Bratwurst",
        "sausage" to "Wurst", "international" to "international", "fish" to "Fisch",
        "vegetarian" to "vegetarisch", "vegan" to "vegan", "local" to "regional", "breakfast" to "Frühstück",
        "sandwich" to "Belegte Brötchen", "friture" to "Imbiss", "balkan" to "Balkan",
        "turkish" to "türkisch", "indian" to "indisch", "thai" to "thailändisch", "mexican" to "mexikanisch",
    )

    private val DAYS = listOf("Mo" to "Mo", "Tu" to "Di", "We" to "Mi", "Th" to "Do", "Fr" to "Fr",
        "Sa" to "Sa", "Su" to "So", "PH" to "Feiertag", "SH" to "Ferien")

    fun facts(p: Poi): List<Fact> {
        val t = p.tags
        val out = ArrayList<Fact>()
        val ele = t["ele"]?.let { e -> e.replace(',', '.').toDoubleOrNull()?.let { "${it.toInt()} m" } }
            ?: p.demEle?.let { "ca. $it m" }
        ele?.let { out.add(Fact("Höhe", it)) }
        t["stars"]?.let { s ->
            val n = s.filter { it.isDigit() }.toIntOrNull()
            out.add(Fact("Sterne", if (n != null && n in 1..5) "★".repeat(n) + (if (s.contains("S")) " Superior" else "") else s))
        }
        t["cuisine"]?.let { c -> out.add(Fact("Küche", c.split(';').joinToString(", ") { CUISINE[it.trim()] ?: it.trim() })) }
        t["opening_hours"]?.let { out.add(Fact("Geöffnet", openingHours(it))) }
        val phone = t["phone"] ?: t["contact:phone"]
        phone?.let { ph ->
            val first = ph.split(';').first().trim()
            out.add(Fact("Telefon", first, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + first.replace(" ", "")))))
        }
        val web = t["website"] ?: t["contact:website"] ?: t["url"]
        web?.let { w ->
            val url = if (w.startsWith("http")) w else "https://$w"
            out.add(Fact("Website", w.removePrefix("https://").removePrefix("http://").removeSuffix("/") + " (online)",
                Intent(Intent.ACTION_VIEW, Uri.parse(url))))
        }
        val mail = t["email"] ?: t["contact:email"]
        mail?.let { out.add(Fact("E-Mail", it, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$it")))) }
        address(t)?.let { out.add(Fact("Adresse", it)) }
        t["wikipedia"]?.let { w ->
            val lang = w.substringBefore(':', "de")
            val title = w.substringAfter(':')
            out.add(Fact("Wikipedia", "$title (online)",
                Intent(Intent.ACTION_VIEW, Uri.parse("https://$lang.wikipedia.org/wiki/" + Uri.encode(title.replace(' ', '_'))))))
        }
        t["operator"]?.let { out.add(Fact("Betreiber", it)) }
        t["beds"]?.let { out.add(Fact("Betten", it)) }
        t["rooms"]?.let { out.add(Fact("Zimmer", it)) }
        t["capacity"]?.let { if (p.cat == PoiCategory.LODGING) out.add(Fact("Plätze", it)) }
        t["outdoor_seating"]?.let { if (it == "yes") out.add(Fact("Draußen", "Sitzplätze im Freien")) }
        t["diet:vegetarian"]?.let { if (it == "yes" || it == "only") out.add(Fact("Vegetarisch", if (it == "only") "nur vegetarisch" else "ja")) }
        t["wheelchair"]?.let { out.add(Fact("Rollstuhl", yesNo(it))) }
        t["dog"]?.let { out.add(Fact("Hunde", yesNo(it))) }
        t["internet_access"]?.let { if (it != "no") out.add(Fact("Internet", if (it == "wlan") "WLAN" else yesNo(it))) }
        if (p.cat == PoiCategory.SHELTER) {
            t["bench"]?.let { out.add(Fact("Sitzbank", yesNo(it))) }
            t["fireplace"]?.let { out.add(Fact("Feuerstelle", yesNo(it))) }
        }
        t["drinking_water"]?.let { if (p.cat != PoiCategory.WATER) out.add(Fact("Trinkwasser", yesNo(it))) }
        t["fee"]?.let { out.add(Fact("Eintritt", if (it == "no") "frei" else if (it == "yes") "kostenpflichtig" else it)) }
        t["height"]?.let { if (p.kind == "tower") out.add(Fact("Turmhöhe", "$it m")) }
        t["inscription"]?.let { out.add(Fact("Inschrift", it)) }
        t["start_date"]?.let { out.add(Fact("Errichtet", it)) }
        return out
    }

    private fun yesNo(v: String) = when (v) {
        "yes" -> "ja"
        "no" -> "nein"
        "limited" -> "eingeschränkt"
        "leashed" -> "angeleint"
        "designated" -> "ja"
        else -> v
    }

    private fun address(t: Map<String, String>): String? {
        val street = listOfNotNull(t["addr:street"] ?: t["addr:place"], t["addr:housenumber"]).joinToString(" ")
        val city = listOfNotNull(t["addr:postcode"], t["addr:city"]).joinToString(" ")
        val s = listOf(street, city).filter { it.isNotBlank() }.joinToString(", ")
        return s.ifBlank { null }
    }

    fun openingHours(raw: String): String {
        var s = raw
        for ((en, de) in DAYS) s = s.replace(Regex("\\b$en\\b"), de)
        s = s.replace(Regex("\\boff\\b"), "geschlossen")
            .replace(Regex("\\bclosed\\b"), "geschlossen")
            .replace("24/7", "rund um die Uhr")
            .replace(Regex(";\\s*"), "\n")
        return s
    }
}

/** ScrollView mit Maximalhöhe für die Detailkarte. */
class MaxHeightScrollView(context: Context, attrs: AttributeSet?) : ScrollView(context, attrs) {
    var maxHeightPx = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = if (maxHeightPx > 0) MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST) else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, spec)
    }
}
