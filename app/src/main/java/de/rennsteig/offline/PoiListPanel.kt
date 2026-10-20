package de.rennsteig.offline

import android.graphics.Bitmap
import android.location.Location
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import java.text.Normalizer
import java.util.Locale

/** Liste aller Orte entlang des Rennsteigs – sortiert nach Routen-km oder Entfernung, durchsuchbar. */
class PoiListPanel(
    private val panel: View,
    private val icons: Map<String, Bitmap>,
    private val onPick: (Poi) -> Unit,
    private val onCategoriesClicked: () -> Unit,
    private val onOnlyTopChanged: (Boolean) -> Unit,
) {
    private val list: ListView = panel.view(R.id.poiList)
    private val search: EditText = panel.view(R.id.searchField)
    private val sortButton: Button = panel.view(R.id.btnSort)
    private val topCheck: CheckBox = panel.view(R.id.cbTop)
    private val countText: TextView = panel.view(R.id.listCount)

    private var all: List<Poi> = emptyList()
    private var shown: List<Poi> = emptyList()
    private var sortByDistance = false
    private var location: Location? = null
    private var enabledCats: Set<PoiCategory> = emptySet()
    private var onlyTop = false

    val isOpen: Boolean get() = panel.visibility == View.VISIBLE

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_poi, parent, false)
            val p = shown[position]
            v.view<ImageView>(R.id.itemIcon).setImageBitmap(icons[p.cat.iconName(p.top)])
            v.view<TextView>(R.id.itemName).text = p.displayName
            val parts = ArrayList<String>()
            parts.add("km ${Geo.formatKm(p.km)}")
            parts.add(p.kindLabel)
            if (p.off >= 30) parts.add("${Geo.formatDistance(p.off.toDouble())} abseits")
            if (p.top) parts.add("★ Empfehlung")
            v.view<TextView>(R.id.itemSub).text = parts.joinToString(" · ")
            val loc = location
            v.view<TextView>(R.id.itemDist).text =
                if (loc != null) Geo.formatDistance(Geo.distance(loc.latitude, loc.longitude, p.lat, p.lon)) else ""
            return v
        }
    }

    init {
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> onPick(shown[position]) }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        sortButton.setOnClickListener {
            sortByDistance = !sortByDistance
            refresh()
        }
        panel.view<Button>(R.id.btnCategories).setOnClickListener { onCategoriesClicked() }
        topCheck.setOnCheckedChangeListener { _, checked ->
            if (checked != onlyTop) onOnlyTopChanged(checked)
        }
    }

    fun setData(pois: List<Poi>) {
        all = pois
        refresh()
    }

    fun setFilter(cats: Set<PoiCategory>, top: Boolean) {
        enabledCats = cats
        onlyTop = top
        topCheck.isChecked = top
        if (isOpen) refresh()
    }

    fun setLocation(loc: Location?) {
        location = loc
        if (isOpen && sortByDistance) refresh() else if (isOpen) adapter.notifyDataSetChanged()
    }

    fun open() {
        panel.visibility = View.VISIBLE
        refresh()
    }

    fun close() {
        panel.visibility = View.GONE
        search.clearFocus()
    }

    private fun refresh() {
        val q = fold(search.text?.toString() ?: "")
        var items = all.filter { (it.cat in enabledCats || it.id == "start" || it.id == "end") && (!onlyTop || it.top) }
        if (q.isNotBlank()) {
            items = all.filter { fold(it.displayName).contains(q) || fold(it.kindLabel).contains(q) }
        }
        val loc = location
        items = if (sortByDistance && loc != null) {
            items.sortedBy { Geo.distance(loc.latitude, loc.longitude, it.lat, it.lon) }
        } else {
            items.sortedBy { it.km }
        }
        shown = items
        sortButton.text = if (sortByDistance && loc != null) "Sortiert: Nähe" else "Sortiert: Rennsteig-km"
        countText.text = "${shown.size} Orte" + if (q.isNotBlank()) " (Suche in allen Kategorien)" else ""
        adapter.notifyDataSetChanged()
    }

    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.GERMAN), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace("ß", "ss")
            .trim()
}
