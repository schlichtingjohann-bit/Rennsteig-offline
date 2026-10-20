package de.rennsteig.offline

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.RotateGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : Activity() {

    /** FREE: Karte frei verschiebbar. FOLLOW: folgt dem Standort, Norden oben.
     *  HEADING: folgt dem Standort und dreht sich mit der Blickrichtung (Einnorden). */
    private enum class Mode { FREE, FOLLOW, HEADING }

    private lateinit var prefs: SharedPreferences
    private lateinit var mapView: MapView
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusWarn: TextView
    private lateinit var btnCompass: ImageButton
    private lateinit var btnLocate: ImageButton
    private lateinit var btnTrack: ImageButton
    private lateinit var btnProfile: ImageButton
    private lateinit var poiCard: View
    private lateinit var poiCardScroll: MaxHeightScrollView
    private lateinit var poiCardContent: LinearLayout
    private lateinit var profilePanel: FrameLayout
    private lateinit var profileView: ProfileView
    private lateinit var loadingPanel: View
    private lateinit var loadingText: TextView
    private lateinit var listPanel: PoiListPanel

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var data: AppData? = null
    private lateinit var locationTracker: LocationTracker
    private lateinit var headingProvider: HeadingProvider
    private val icons by lazy { Icons.all(resources.displayMetrics.density) }

    private var mode = Mode.FREE
    private var preferHeadingUp = true
    private var resumed = false
    private var lastFix: Location? = null
    private var routePos: Route.Position? = null
    private var sensorHeading: Double? = null
    private var shownHeading: Double? = null
    private var shownLat = Double.NaN
    private var shownLon = Double.NaN
    private var animFromLat = 0.0
    private var animFromLon = 0.0
    private var animToLat = Double.NaN
    private var animToLon = Double.NaN
    private var animStart = 0L
    private var animDuration = 900L
    private var frameScheduled = false
    private var cameraOnTarget = true
    private var zoomTarget: Double? = null
    private var topPadding = 0.0
    private var lastStatusUpdate = 0L
    private var firstFixHandled = false
    private var gpsDialogShown = false
    private var selectedPoi: Poi? = null
    private var poiLiveLine: TextView? = null
    private var poiLiveArrow: ImageView? = null
    private val enabledCats = HashSet<PoiCategory>()
    private var onlyTop = false
    private var keepScreenOn = false
    /** Gehrichtung: false = Hörschel → Blankenstein, true = Blankenstein → Hörschel */
    private var reverse = false

    private val frameCallback = Choreographer.FrameCallback { onFrame() }
    private val trackListener: () -> Unit = { updateTrackSource(); updateStatus(force = true) }

    // ------------------------------------------------------------------ Lebenszyklus

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindow()
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        loadPrefs()
        bindViews()
        applyInsets()
        mapView.onCreate(savedInstanceState)
        locationTracker = LocationTracker(this) { onLocation(it) }
        headingProvider = HeadingProvider(this) { onHeading(it) }
        updateStatus(force = true)
        loadDataAndMap()
        if (!LocationTracker.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
        }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        if (LocationTracker.hasPermission(this)) {
            locationTracker.start()
            checkGpsEnabled()
        }
        TrackStore.addListener(trackListener)
        updateTrackSource()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        resumed = true
        if (headingProvider.available) headingProvider.start()
        updateTrackButton()
        ensureFrameLoop()
    }

    override fun onPause() {
        resumed = false
        headingProvider.stop()
        saveCamera()
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        locationTracker.stop()
        TrackStore.removeListener(trackListener)
        mapView.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        mapView.onDestroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            listPanel.isOpen -> listPanel.close()
            selectedPoi != null -> clearSelection()
            profilePanel.visibility == View.VISIBLE -> toggleProfile()
            else -> super.onBackPressed()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (LocationTracker.hasPermission(this)) {
                locationTracker.start()
                checkGpsEnabled()
            }
            updateStatus(force = true)
        }
    }

    // ------------------------------------------------------------------ Aufbau

    @Suppress("DEPRECATION")
    private fun setupWindow() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = 0x33000000
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    private fun bindViews() {
        mapView = view(R.id.mapView)
        statusTitle = view(R.id.statusTitle)
        statusDetail = view(R.id.statusDetail)
        statusWarn = view(R.id.statusWarn)
        btnCompass = view(R.id.btnCompass)
        btnLocate = view(R.id.btnLocate)
        btnTrack = view(R.id.btnTrack)
        btnProfile = view(R.id.btnProfile)
        poiCard = view(R.id.poiCard)
        poiCardScroll = view(R.id.poiCardScroll)
        poiCardContent = view(R.id.poiCardContent)
        profilePanel = view(R.id.profilePanel)
        loadingPanel = view(R.id.loadingPanel)
        loadingText = view(R.id.loadingText)

        profileView = ProfileView(this)
        profilePanel.addView(profileView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        profileView.onKmTapped = { km -> showRoutePoint(km) }

        listPanel = PoiListPanel(
            view(R.id.listPanel), icons,
            onPick = { p -> listPanel.close(); selectPoi(p, moveCamera = true) },
            onCategoriesClicked = { showCategoryDialog() },
            onOnlyTopChanged = { top -> onlyTop = top; savePrefs(); applyFilter() },
        )
        view<View>(R.id.btnListClose).setOnClickListener { listPanel.close() }

        btnCompass.setOnClickListener { onCompassClicked() }
        btnLocate.setOnClickListener { onLocateClicked() }
        btnTrack.setOnClickListener { toggleTracking() }
        btnProfile.setOnClickListener { toggleProfile() }
        view<View>(R.id.btnList).setOnClickListener { listPanel.open() }
        view<View>(R.id.btnMenu).setOnClickListener { showMenu(it) }
        view<View>(R.id.statusCard).setOnClickListener { if (lastFix != null) setMode(if (preferHeadingUp) Mode.HEADING else Mode.FOLLOW) }
        updateLocateButton()
    }

    private fun applyInsets() {
        val root = view<View>(R.id.root)
        val topBar = view<View>(R.id.topBar)
        val bottomArea = view<View>(R.id.bottomArea)
        val list = view<View>(R.id.listPanel)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val (l, t, r, b) = systemBarInsets(insets)
            topBar.setPadding(l + dp(10), t + dp(8), r + dp(10), 0)
            bottomArea.setPadding(l, 0, r, b)
            list.setPadding(l, t, r, b)
            insets
        }
    }

    @Suppress("DEPRECATION")
    private fun systemBarInsets(insets: WindowInsets): IntArray =
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            intArrayOf(i.left, i.top, i.right, i.bottom)
        } else {
            intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }

    private fun loadDataAndMap() {
        loadingPanel.visibility = View.VISIBLE
        Thread {
            try {
                AssetInstaller.install(this) { msg -> runOnUiThread { loadingText.text = msg } }
                runOnUiThread { loadingText.text = "Route und Orte werden geladen …" }
                val d = DataLoader.load(this)
                runOnUiThread { if (!isFinishing && !isDestroyed) onDataReady(d) }
            } catch (e: Exception) {
                runOnUiThread {
                    loadingText.text = "Die Offline-Daten konnten nicht geladen werden:\n${e.message}"
                }
            }
        }.start()
    }

    private fun onDataReady(d: AppData) {
        data = d
        listPanel.setData(d.pois)
        listPanel.setFilter(enabledCats, onlyTop)
        profileView.profile = d.profile
        profileView.lengthKm = d.route.lengthM / 1000.0
        if (d.profile == null) btnProfile.visibility = View.GONE
        mapView.getMapAsync { m -> onMapReady(m, d) }
    }

    private fun onMapReady(m: MapLibreMap, d: AppData) {
        map = m
        m.uiSettings.setCompassEnabled(false)
        m.uiSettings.setLogoEnabled(false)
        m.uiSettings.setAttributionEnabled(false)
        m.uiSettings.setTiltGesturesEnabled(false)
        m.uiSettings.setRotateGesturesEnabled(true)
        m.setMinZoomPreference(7.0)
        m.setMaxZoomPreference(18.5)

        val b = d.route.bounds()
        m.setLatLngBoundsForCameraTarget(
            LatLngBounds.Builder()
                .include(LatLng(b[0] - 0.2, b[1] - 0.3))
                .include(LatLng(b[2] + 0.2, b[3] + 0.3))
                .build()
        )
        if (!restoreCamera(m)) showWholeRoute(animate = false)

        val styleJson = DataLoader.readAsset(this, "style/rennsteig.json")
            .replace("{MAP_PATH}", AssetInstaller.mapFile(this).absolutePath)
            .replace("{DEM_PATH}", AssetInstaller.demFile(this).absolutePath)
            .replace("{CONTOUR_PATH}", AssetInstaller.contourFile(this).absolutePath)
        val builder = Style.Builder().fromJson(styleJson)
        for ((name, bmp) in icons) builder.withImage(name, bmp)
        m.setStyle(builder) { s -> onStyleLoaded(s, d) }

        // Diagnose: Fehler beim Laden von Schriften, Symbolen und Shadern ins Log schreiben
        mapView.addOnStyleImageMissingListener { id ->
            Log.w(TAG, "Kartensymbol fehlt: $id")
            icons[id]?.let { bmp -> style?.addImage(id, bmp) }
        }
        mapView.addOnGlyphsErrorListener { stack, start, end ->
            Log.w(TAG, "Schrift fehlt: ${stack.joinToString()} $start-$end")
        }
        mapView.addOnSymbolErrorListener { msg -> Log.w(TAG, "Symbolfehler: $msg") }
        mapView.addOnShaderCompileFailedListener { id, backend, defines -> Log.e(TAG, "Shader $id/$backend: $defines") }
        mapView.addOnRenderErrorListener { Log.e(TAG, "Renderfehler") }
        mapView.addOnDidFailLoadingMapListener { msg -> Log.e(TAG, "Karte konnte nicht geladen werden: $msg") }

        m.addOnMapClickListener { point -> onMapClick(point) }
        m.addOnMoveListener(object : MapLibreMap.OnMoveListener {
            override fun onMoveBegin(detector: MoveGestureDetector) {
                // Verschieben mit einem Finger beendet das Folgen, Zoomen mit zwei Fingern nicht
                if (mode != Mode.FREE && detector.pointersCount <= 1) setMode(Mode.FREE)
            }
            override fun onMove(detector: MoveGestureDetector) {}
            override fun onMoveEnd(detector: MoveGestureDetector) {}
        })
        m.addOnRotateListener(object : MapLibreMap.OnRotateListener {
            override fun onRotateBegin(detector: RotateGestureDetector) {
                if (mode == Mode.HEADING) {
                    preferHeadingUp = false
                    setMode(Mode.FREE)
                }
            }
            override fun onRotate(detector: RotateGestureDetector) {}
            override fun onRotateEnd(detector: RotateGestureDetector) {}
        })
        m.addOnCameraMoveListener { onCameraMoved() }
        mapView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateFocalPoint() }
        onCameraMoved()
    }

    private fun onStyleLoaded(s: Style, d: AppData) {
        style = s
        Log.i(TAG, "Kartenstil geladen: ${s.layers.size} Ebenen, ${s.sources.size} Quellen")
        // Symbole zusätzlich direkt hinzufügen (falls der Builder sie noch nicht übernommen hat)
        for ((name, bmp) in icons) if (s.getImage(name) == null) s.addImage(name, bmp)
        s.getSourceAs<GeoJsonSource>("route")?.setGeoJson(d.routeGeoJson)
        applyFilter()
        updateTrackSource()
        updateMeSource()
        updateSelectedSource()
        loadingPanel.visibility = View.GONE
        // Bereits vorhandenen Fix übernehmen
        lastFix?.let { handleFirstFix(it) }
    }

    // ------------------------------------------------------------------ Standort & Kompass

    private fun onLocation(loc: Location) {
        val prev = lastFix
        if (prev != null && loc.provider != prev.provider && LocationTracker.ageMs(prev) < 15_000 &&
            loc.hasAccuracy() && prev.hasAccuracy() && loc.accuracy > prev.accuracy * 2
        ) return
        lastFix = loc
        headingProvider.updateDeclination(loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else 600.0)
        if (!headingProvider.available && loc.hasBearing() && loc.speed > 0.8f) sensorHeading = loc.bearing.toDouble()

        val now = SystemClock.uptimeMillis()
        if (shownLat.isNaN() || prev == null) {
            shownLat = loc.latitude
            shownLon = loc.longitude
            animFromLat = loc.latitude
            animFromLon = loc.longitude
        } else {
            animFromLat = shownLat
            animFromLon = shownLon
            animDuration = (now - animStart).coerceIn(300L, 1500L)
        }
        animToLat = loc.latitude
        animToLon = loc.longitude
        animStart = now

        val d = data
        if (d != null) {
            routePos = d.route.locate(loc.latitude, loc.longitude)
            profileView.currentKm = routePos?.takeIf { it.offM < 3000 }?.km
        }
        listPanel.setLocation(loc)
        handleFirstFix(loc)
        updateMeSource()
        updatePoiLive()
        updateStatus(force = prev == null)
        ensureFrameLoop()
    }

    private fun handleFirstFix(loc: Location) {
        if (firstFixHandled || style == null) return
        val d = data ?: return
        firstFixHandled = true
        val pos = d.route.locate(loc.latitude, loc.longitude)
        // Folgen nur, wenn man in der Nähe des Rennsteigs ist (sonst liegt man außerhalb der Karte)
        if (pos.offM < 15_000 && prefs.getBoolean("autoFollow", true)) {
            setMode(if (preferHeadingUp && headingProvider.available) Mode.HEADING else Mode.FOLLOW)
        }
    }

    private fun onHeading(deg: Double) {
        sensorHeading = deg
        ensureFrameLoop()
        updateStatus(force = false)
    }

    private fun checkGpsEnabled() {
        if (locationTracker.gpsEnabled || gpsDialogShown) return
        gpsDialogShown = true
        AlertDialog.Builder(this)
            .setTitle("GPS ist ausgeschaltet")
            .setMessage("Für die Standortanzeige wird GPS benötigt. GPS funktioniert auch ohne Internet.")
            .setPositiveButton("Einstellungen") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (e: ActivityNotFoundException) {
                    toast("Einstellungen nicht verfügbar")
                }
            }
            .setNegativeButton("Später", null)
            .show()
    }

    // ------------------------------------------------------------------ Kamera

    private fun ensureFrameLoop() {
        if (!frameScheduled && resumed) {
            frameScheduled = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun onFrame() {
        frameScheduled = false
        val m = map ?: return
        if (!resumed) return
        val now = SystemClock.uptimeMillis()
        var changed = false

        if (!animToLat.isNaN()) {
            val t = ((now - animStart).toDouble() / animDuration).coerceIn(0.0, 1.0)
            val lat = animFromLat + (animToLat - animFromLat) * t
            val lon = animFromLon + (animToLon - animFromLon) * t
            if (lat != shownLat || lon != shownLon) {
                shownLat = lat
                shownLon = lon
                changed = true
            }
        }
        val target = sensorHeading
        var headingSettled = true
        if (target != null) {
            val cur = shownHeading
            val next = if (cur == null) target else Geo.normalize(cur + Geo.angleDiff(cur, target) * 0.22)
            if (cur == null || abs(Geo.angleDiff(cur, next)) > 0.08) {
                shownHeading = next
                changed = true
                headingSettled = false
            }
        }

        if (changed) {
            updateMeSource()
            if (poiLiveArrow != null) updatePoiLive()
        }
        var cameraSettled = true
        if (mode != Mode.FREE && !shownLat.isNaN() && (changed || !cameraOnTarget)) {
            cameraSettled = followCamera(m)
        }
        cameraOnTarget = cameraSettled
        val animating = !animToLat.isNaN() && now - animStart < animDuration
        if (animating || !headingSettled || !cameraSettled) ensureFrameLoop()
    }

    /**
     * Führt die Kamera der Position (und im Einnorden-Modus der Blickrichtung) nach.
     * Zoom, Drehung, Position und Bildversatz nähern sich weich ihrem Ziel.
     * @return true, wenn die Kamera ihr Ziel erreicht hat
     */
    private fun followCamera(m: MapLibreMap): Boolean {
        val cam = m.cameraPosition
        val k = 0.25
        val target = cam.target
        val curLat = target?.latitude ?: shownLat
        val curLon = target?.longitude ?: shownLon
        var lat = curLat + (shownLat - curLat) * k
        var lon = curLon + (shownLon - curLon) * k
        val posDone = abs(shownLat - lat) < 1e-7 && abs(shownLon - lon) < 1e-7
        if (posDone) {
            lat = shownLat
            lon = shownLon
        }

        val wantBearing = if (mode == Mode.HEADING) shownHeading ?: cam.bearing else 0.0
        val db = Geo.angleDiff(cam.bearing, wantBearing)
        val bearingDone = abs(db) < 0.3
        val bearing = if (bearingDone) wantBearing else Geo.normalize(cam.bearing + db * 0.3)

        var zoom = cam.zoom
        val zt = zoomTarget
        var zoomDone = true
        if (zt != null) {
            zoom += (zt - zoom) * 0.2
            if (abs(zt - zoom) < 0.01) {
                zoom = zt
                zoomTarget = null
            } else {
                zoomDone = false
            }
        }

        val wantPad = if (mode == Mode.HEADING) mapView.height * HEADING_TOP_PAD else 0.0
        val padDone = abs(wantPad - topPadding) < 1.0
        topPadding = if (padDone) wantPad else topPadding + (wantPad - topPadding) * 0.25

        m.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(lat, lon))
                    .zoom(zoom)
                    .bearing(bearing)
                    .tilt(0.0)
                    .padding(0.0, topPadding, 0.0, 0.0)
                    .build()
            )
        )
        return posDone && bearingDone && zoomDone && padDone
    }

    private fun setMode(newMode: Mode) {
        val m = map
        val oldMode = mode
        mode = newMode
        if (newMode == Mode.HEADING) preferHeadingUp = true
        if (newMode == Mode.FOLLOW && oldMode == Mode.HEADING) preferHeadingUp = false
        savePrefs()
        updateLocateButton()
        updateFocalPoint()
        if (m == null || newMode == Mode.FREE) return
        if (lastFix == null || shownLat.isNaN()) {
            toast("Warte auf GPS-Signal …")
            return
        }
        m.cancelTransitions()
        if (oldMode == Mode.FREE) {
            zoomTarget = max(m.cameraPosition.zoom, 14.5)
            topPadding = m.cameraPosition.padding?.getOrNull(1) ?: 0.0
        }
        cameraOnTarget = false
        ensureFrameLoop()
    }

    /** Beim Folgen wird um die eigene Position gezoomt statt um die Fingermitte. */
    private fun updateFocalPoint() {
        val m = map ?: return
        m.uiSettings.focalPoint = when (mode) {
            Mode.FREE -> null
            Mode.FOLLOW -> PointF(mapView.width / 2f, mapView.height / 2f)
            Mode.HEADING -> PointF(mapView.width / 2f, (mapView.height * (1 + HEADING_TOP_PAD) / 2).toFloat())
        }
    }

    private fun onLocateClicked() {
        if (!LocationTracker.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            return
        }
        when (mode) {
            Mode.FREE -> setMode(if (preferHeadingUp && headingProvider.available) Mode.HEADING else Mode.FOLLOW)
            Mode.FOLLOW -> if (headingProvider.available) setMode(Mode.HEADING) else setMode(Mode.FOLLOW)
            Mode.HEADING -> setMode(Mode.FOLLOW)
        }
        val d = data
        val fix = lastFix
        if (d != null && fix != null) {
            val pos = d.route.locate(fix.latitude, fix.longitude)
            if (pos.offM > 15_000) toast("Du bist ${Geo.formatDistance(pos.offM)} vom Rennsteig entfernt – dort enthält die Offline-Karte keine Details.")
        }
    }

    private fun onCompassClicked() {
        val m = map ?: return
        when (mode) {
            Mode.HEADING -> setMode(Mode.FOLLOW)
            Mode.FOLLOW -> if (headingProvider.available) setMode(Mode.HEADING) else toast("Dieses Gerät hat keinen Kompass")
            Mode.FREE -> {
                if (abs(m.cameraPosition.bearing) > 0.5) {
                    m.animateCamera(CameraUpdateFactory.bearingTo(0.0), 400)
                } else if (lastFix != null && headingProvider.available) {
                    setMode(Mode.HEADING)
                }
            }
        }
    }

    private fun onCameraMoved() {
        val m = map ?: return
        btnCompass.rotation = (-m.cameraPosition.bearing).toFloat()
    }

    private fun updateLocateButton() {
        val active = 0xFF1F63D6.toInt()
        when (mode) {
            Mode.FREE -> {
                btnLocate.setImageResource(R.drawable.ic_my_location)
                btnLocate.setColorFilter(0xFF555555.toInt(), PorterDuff.Mode.SRC_IN)
                btnLocate.setBackgroundResource(R.drawable.round_button)
            }
            Mode.FOLLOW -> {
                btnLocate.setImageResource(R.drawable.ic_my_location)
                btnLocate.setColorFilter(active, PorterDuff.Mode.SRC_IN)
                btnLocate.setBackgroundResource(R.drawable.round_button)
            }
            Mode.HEADING -> {
                btnLocate.setImageResource(R.drawable.ic_navigation)
                btnLocate.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                btnLocate.setBackgroundResource(R.drawable.round_button_active)
            }
        }
        btnCompass.setBackgroundResource(if (mode == Mode.HEADING) R.drawable.round_button_active_light else R.drawable.round_button)
        btnLocate.contentDescription = when (mode) {
            Mode.FREE -> "Standort folgen"
            Mode.FOLLOW -> "Karte nach Blickrichtung drehen"
            Mode.HEADING -> "Norden oben"
        }
    }

    private fun showWholeRoute(animate: Boolean) {
        val m = map ?: return
        val d = data ?: return
        val b = d.route.bounds()
        val bounds = LatLngBounds.Builder().include(LatLng(b[0], b[1])).include(LatLng(b[2], b[3])).build()
        val update = CameraUpdateFactory.newLatLngBounds(bounds, dp(40))
        if (mode != Mode.FREE) setMode(Mode.FREE)
        if (animate) m.animateCamera(update, 800) else m.moveCamera(update)
    }

    private fun showRoutePoint(km: Double) {
        val m = map ?: return
        val d = data ?: return
        val (lat, lon) = d.route.pointAt(km)
        if (mode != Mode.FREE) setMode(Mode.FREE)
        focusOn(m, lat, lon, max(m.cameraPosition.zoom, 13.0))
        style?.getSourceAs<GeoJsonSource>("selected")?.setGeoJson(pointJson(lon, lat))
    }

    private fun saveCamera() {
        val cam = map?.cameraPosition ?: return
        val t = cam.target ?: return
        prefs.edit()
            .putFloat("camLat", t.latitude.toFloat()).putFloat("camLon", t.longitude.toFloat())
            .putFloat("camZoom", cam.zoom.toFloat()).putFloat("camBearing", cam.bearing.toFloat())
            .apply()
    }

    private fun restoreCamera(m: MapLibreMap): Boolean {
        if (!prefs.contains("camLat")) return false
        m.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(prefs.getFloat("camLat", 0f).toDouble(), prefs.getFloat("camLon", 0f).toDouble()))
                    .zoom(prefs.getFloat("camZoom", 10f).toDouble())
                    .bearing(prefs.getFloat("camBearing", 0f).toDouble())
                    .build()
            )
        )
        return true
    }

    // ------------------------------------------------------------------ Kartenquellen

    private fun updateMeSource() {
        val src = style?.getSourceAs<GeoJsonSource>("me") ?: return
        val fix = lastFix
        if (fix == null || shownLat.isNaN()) {
            src.setGeoJson(EMPTY)
            return
        }
        val acc = if (fix.hasAccuracy()) fix.accuracy.toDouble() else 30.0
        val r0 = acc / (Geo.METERS_PER_PIXEL_Z0 * cos(Math.toRadians(shownLat)))
        val props = JSONObject()
            .put("kind", "me")
            .put("r0", r0)
            .put("stale", LocationTracker.ageMs(fix) > 30_000)
        shownHeading?.let { props.put("bearing", it) }
        src.setGeoJson("""{"type":"Feature","geometry":{"type":"Point","coordinates":[$shownLon,$shownLat]},"properties":$props}""")
    }

    private fun applyFilter() {
        listPanel.setFilter(enabledCats, onlyTop)
        val d = data ?: return
        val src = style?.getSourceAs<GeoJsonSource>("pois") ?: return
        val sb = StringBuilder(d.pois.size * 160)
        sb.append("""{"type":"FeatureCollection","features":[""")
        var first = true
        for (p in d.pois) {
            if (p.id == "start" || p.id == "end") continue
            if (p.cat !in enabledCats || (onlyTop && !p.top)) continue
            if (!first) sb.append(',')
            first = false
            sb.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":[${p.lon},${p.lat}]},""")
            sb.append(""""properties":{"id":${JSONObject.quote(p.id)},"name":${JSONObject.quote(p.displayName)},""")
            sb.append(""""top":${p.top},"score":${p.score},"icon":"${p.cat.iconName(p.top)}"}}""")
        }
        sb.append("]}")
        src.setGeoJson(sb.toString())
    }

    private fun updateTrackSource() {
        style?.getSourceAs<GeoJsonSource>("track")?.setGeoJson(TrackStore.toGeoJson())
    }

    private fun updateSelectedSource() {
        val src = style?.getSourceAs<GeoJsonSource>("selected") ?: return
        val p = selectedPoi
        src.setGeoJson(if (p == null) EMPTY else pointJson(p.lon, p.lat))
    }

    private fun pointJson(lon: Double, lat: Double) =
        """{"type":"Feature","geometry":{"type":"Point","coordinates":[$lon,$lat]},"properties":{}}"""

    // ------------------------------------------------------------------ POIs

    private fun onMapClick(point: LatLng): Boolean {
        val m = map ?: return false
        val d = data ?: return false
        val p = m.projection.toScreenLocation(point)
        val r = dp(20).toFloat()
        val features = m.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), "poi-top", "poi-other", "route-ends")
        for (f in features) {
            val id = if (f.hasProperty("id")) f.getStringProperty("id") else f.getStringProperty("kind")
            val poi = d.pois.firstOrNull { it.id == id } ?: continue
            selectPoi(poi, moveCamera = false)
            return true
        }
        if (selectedPoi != null) {
            clearSelection()
            return true
        }
        return false
    }

    private fun selectPoi(p: Poi, moveCamera: Boolean) {
        selectedPoi = p
        updateSelectedSource()
        profileView.selectedKm = p.km
        buildPoiCard(p)
        if (moveCamera) {
            val m = map ?: return
            if (mode != Mode.FREE) setMode(Mode.FREE)
            focusOn(m, p.lat, p.lon, max(m.cameraPosition.zoom, 14.5))
        }
    }

    /** Zentriert die Karte auf einen Punkt (ohne den Versatz des Einnorden-Modus). */
    private fun focusOn(m: MapLibreMap, lat: Double, lon: Double, zoom: Double) {
        m.cancelTransitions()
        topPadding = 0.0
        val target = CameraPosition.Builder()
            .target(LatLng(lat, lon))
            .zoom(zoom)
            .bearing(m.cameraPosition.bearing)
            .tilt(0.0)
            .padding(0.0, 0.0, 0.0, 0.0)
            .build()
        m.animateCamera(CameraUpdateFactory.newCameraPosition(target), 500)
    }

    private fun clearSelection() {
        selectedPoi = null
        poiLiveLine = null
        poiLiveArrow = null
        poiCard.visibility = View.GONE
        profileView.selectedKm = null
        updateSelectedSource()
    }

    private fun buildPoiCard(p: Poi) {
        val c = poiCardContent
        c.removeAllViews()
        poiCardScroll.maxHeightPx = (resources.displayMetrics.heightPixels * 0.42).roundToInt()

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageBitmap(icons[p.cat.iconName(p.top)]) }, LinearLayout.LayoutParams(dp(36), dp(36)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(4), 0) }
        titles.addView(text(p.displayName, 18f, Color.BLACK, bold = true))
        val sub = StringBuilder(p.kindLabel)
        if (p.top) sub.append(" · ★ Empfehlung")
        titles.addView(text(sub.toString(), 13.5f, if (p.top) 0xFF9A6B00.toInt() else 0xFF666666.toInt()))
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val close = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Schließen"
            setOnClickListener { clearSelection() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(40), dp(40)))
        c.addView(header)

        val routeInfo = when {
            p.id == "start" -> "Rennsteig-km 0"
            p.id == "end" -> "Rennsteig-km ${Geo.formatKm(p.km)}"
            p.off < 30 -> "Rennsteig-km ${Geo.formatKm(p.km)} · direkt am Weg"
            else -> "Rennsteig-km ${Geo.formatKm(p.km)} · ${Geo.formatDistance(p.off.toDouble())} abseits"
        }
        c.addView(text(routeInfo, 14f, 0xFFA3163A.toInt(), bold = true).apply { setPadding(0, dp(8), 0, 0) })

        val live = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val arrow = ImageView(this).apply {
            setImageResource(R.drawable.ic_navigation)
            setColorFilter(0xFF1F63D6.toInt(), PorterDuff.Mode.SRC_IN)
        }
        live.addView(arrow, LinearLayout.LayoutParams(dp(20), dp(20)))
        val liveText = text("", 14f, 0xFF1F63D6.toInt()).apply { setPadding(dp(6), 0, 0, 0) }
        live.addView(liveText)
        live.setPadding(0, dp(4), 0, 0)
        c.addView(live)
        poiLiveLine = liveText
        poiLiveArrow = arrow
        updatePoiLive()

        p.desc?.let { c.addView(text(it, 14.5f, 0xFF222222.toInt()).apply { setPadding(0, dp(8), 0, 0) }) }
        (p.tags["description:de"] ?: p.tags["description"])?.let {
            c.addView(text(it, 13.5f, 0xFF444444.toInt()).apply { setTypeface(typeface, Typeface.ITALIC); setPadding(0, dp(6), 0, 0) })
        }

        val facts = PoiFacts.facts(p)
        if (facts.isNotEmpty()) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
            for (f in facts) box.addView(factRow(f))
            c.addView(box)
        }
        if (p.id != "start" && p.id != "end") {
            c.addView(text("Daten: OpenStreetMap (${p.id})", 11f, 0xFF888888.toInt()).apply { setPadding(0, dp(8), 0, 0) })
        }
        poiCard.visibility = View.VISIBLE
        poiCardScroll.scrollTo(0, 0)
    }

    private fun factRow(f: PoiFacts.Fact): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(2)) }
        row.addView(text(f.label, 13.5f, 0xFF777777.toInt()), LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT))
        val value = text(f.value, 13.5f, if (f.action != null) 0xFF1F63D6.toInt() else 0xFF222222.toInt())
        if (f.action != null) {
            value.paintFlags = value.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            value.setOnClickListener {
                try {
                    startActivity(f.action)
                } catch (e: ActivityNotFoundException) {
                    toast("Keine passende App gefunden")
                }
            }
        }
        row.addView(value, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun updatePoiLive() {
        val p = selectedPoi ?: return
        val lineView = poiLiveLine ?: return
        val arrow = poiLiveArrow ?: return
        val fix = lastFix
        if (fix == null) {
            setText(lineView, "Entfernung erscheint, sobald GPS bereit ist")
            arrow.visibility = View.INVISIBLE
            return
        }
        val lat = if (shownLat.isNaN()) fix.latitude else shownLat
        val lon = if (shownLon.isNaN()) fix.longitude else shownLon
        val dist = Geo.distance(lat, lon, p.lat, p.lon)
        val brg = Geo.bearing(lat, lon, p.lat, p.lon)
        val mapBearing = map?.cameraPosition?.bearing ?: 0.0
        arrow.visibility = View.VISIBLE
        arrow.rotation = (brg - mapBearing).toFloat()
        val along = routePos?.let { rp ->
            if (rp.offM < 2000) {
                val dk = if (reverse) rp.km - p.km else p.km - rp.km
                when {
                    abs(dk) < 0.05 -> ""
                    dk > 0 -> " · ${Geo.formatKm(dk)} km voraus"
                    else -> " · ${Geo.formatKm(-dk)} km zurück"
                }
            } else ""
        } ?: ""
        setText(lineView, "${Geo.formatDistance(dist)} Luftlinie, Richtung ${Geo.cardinal(brg)}$along")
    }

    // ------------------------------------------------------------------ Status

    private fun setText(view: TextView, value: CharSequence) {
        if (view.text.toString() != value.toString()) view.text = value
    }

    private fun updateStatus(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastStatusUpdate < 500) return
        lastStatusUpdate = now
        val fix = lastFix
        val d = data
        val warn = ArrayList<String>()
        var title = "Rennsteig Offline"
        var detail = ""
        if (!LocationTracker.hasPermission(this)) {
            title = "Standort nicht freigegeben"
            detail = "Tippe auf ◎, um die Standortfreigabe zu erteilen."
        } else if (fix == null) {
            title = "Suche GPS-Signal …"
            detail = "GPS funktioniert ohne Internet. Unter freiem Himmel dauert der erste Fix meist unter einer Minute."
        } else {
            val pos = routePos
            val parts = ArrayList<String>()
            if (pos != null && d != null && pos.offM < 3000) {
                val total = d.route.lengthM / 1000.0
                val remaining = if (reverse) pos.km else total - pos.km
                val goal = if (reverse) "Hörschel" else "Blankenstein"
                title = "Rennsteig-km ${Geo.formatKm(pos.km)} · noch ${Geo.formatKm(remaining)} km bis $goal"
                val ele = d.profile?.eleAt(pos.km)
                if (ele != null && pos.offM < 300) parts.add("Höhe ca. ${ele.roundToInt()} m")
                else if (fix.hasAltitude()) parts.add("Höhe ca. ${(fix.altitude - GEOID_HEIGHT).roundToInt()} m")
                parts.add(if (pos.offM < 25) "auf dem Weg" else "${Geo.formatDistance(pos.offM)} neben dem Weg")
                if (pos.offM > 150) warn.add("Du bist ${Geo.formatDistance(pos.offM)} vom Rennsteig entfernt.")
            } else if (pos != null) {
                title = "${Geo.formatDistance(pos.offM)} bis zum Rennsteig"
                parts.add("nächster Punkt: km ${Geo.formatKm(pos.km)}")
            }
            if (fix.hasAccuracy()) parts.add("GPS ±${fix.accuracy.roundToInt()} m")
            if (LocationTracker.ageMs(fix) > 30_000) warn.add("Letzter Standort ist ${LocationTracker.ageMs(fix) / 60_000 + 1} min alt – kein GPS-Empfang?")
            detail = parts.joinToString(" · ")
        }
        sensorHeading?.let {
            val h = "Blick ${Geo.cardinal(it)} ${it.roundToInt()}°"
            detail = if (detail.isEmpty() || fix == null) h else "$detail · $h"
        }
        if (headingProvider.available && (headingProvider.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
                headingProvider.accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW) && sensorHeading != null
        ) {
            warn.add("Kompass ungenau: Handy einige Male in Form einer Acht schwenken.")
        }
        if (TrackingService.running || TrackStore.size() > 1) {
            val rec = if (TrackingService.running) "● Aufzeichnung" else "Aufgezeichnet"
            detail += "\n$rec: ${Geo.formatDistance(TrackStore.lengthM)}"
        }
        setText(statusTitle, title)
        setText(statusDetail, detail)
        setText(statusWarn, warn.joinToString("\n"))
        val warnVisibility = if (warn.isEmpty()) View.GONE else View.VISIBLE
        if (statusWarn.visibility != warnVisibility) statusWarn.visibility = warnVisibility
    }

    // ------------------------------------------------------------------ Aufzeichnung, Profil, Menü

    private fun toggleTracking() {
        if (TrackingService.running) {
            TrackingService.stop(this)
            toast("Aufzeichnung beendet (${Geo.formatDistance(TrackStore.lengthM)})")
        } else {
            if (!LocationTracker.hasPermission(this)) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
                return
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            }
            TrackingService.start(this)
            toast("Dein Weg wird aufgezeichnet – auch bei ausgeschaltetem Bildschirm.")
        }
        btnTrack.postDelayed({ updateTrackButton(); updateStatus(force = true) }, 400)
    }

    private fun updateTrackButton() {
        if (TrackingService.running) {
            btnTrack.setImageResource(R.drawable.ic_stop)
            btnTrack.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            btnTrack.setBackgroundResource(R.drawable.round_button_record)
        } else {
            btnTrack.setImageResource(R.drawable.ic_record)
            btnTrack.setColorFilter(0xFFD1294D.toInt(), PorterDuff.Mode.SRC_IN)
            btnTrack.setBackgroundResource(R.drawable.round_button)
        }
    }

    private fun toggleProfile() {
        val show = profilePanel.visibility != View.VISIBLE
        profilePanel.visibility = if (show) View.VISIBLE else View.GONE
        btnProfile.setColorFilter(if (show) 0xFF1F63D6.toInt() else 0xFF333333.toInt(), PorterDuff.Mode.SRC_IN)
    }

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Kategorien auswählen …")
        pm.menu.add(0, 2, 1, "Nur Empfehlungen zeigen").setCheckable(true).setChecked(onlyTop)
        pm.menu.add(0, 3, 2, "Ganze Route zeigen")
        pm.menu.add(0, 4, 3, "Bildschirm anlassen").setCheckable(true).setChecked(keepScreenOn)
        pm.menu.add(0, 5, 4, "Aufgezeichneten Weg löschen")
        pm.menu.add(0, 7, 5, "Gehrichtung: Blankenstein → Hörschel").setCheckable(true).setChecked(reverse)
        pm.menu.add(0, 6, 6, "Über die App & Datenquellen")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showCategoryDialog()
                2 -> { onlyTop = !onlyTop; savePrefs(); applyFilter() }
                3 -> showWholeRoute(animate = true)
                4 -> { keepScreenOn = !keepScreenOn; applyKeepScreenOn(); savePrefs() }
                5 -> confirmClearTrack()
                6 -> showAbout()
                7 -> { reverse = !reverse; savePrefs(); updateStatus(force = true); updatePoiLive() }
            }
            true
        }
        pm.show()
    }

    private fun showCategoryDialog() {
        val cats = PoiCategory.entries
        val counts = data?.pois?.groupingBy { it.cat }?.eachCount() ?: emptyMap()
        val labels = cats.map { "${it.label} (${counts[it] ?: 0})" }.toTypedArray()
        val checked = BooleanArray(cats.size) { cats[it] in enabledCats }
        AlertDialog.Builder(this)
            .setTitle("Was soll angezeigt werden?")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Übernehmen") { _, _ ->
                enabledCats.clear()
                cats.forEachIndexed { i, c -> if (checked[i]) enabledCats.add(c) }
                savePrefs()
                applyFilter()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun confirmClearTrack() {
        AlertDialog.Builder(this)
            .setTitle("Aufzeichnung löschen?")
            .setMessage("Der aufgezeichnete Weg (${Geo.formatDistance(TrackStore.lengthM)}) wird gelöscht.")
            .setPositiveButton("Löschen") { _, _ -> TrackStore.clear() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showAbout() {
        val d = data
        val routeInfo = if (d != null) {
            val p = d.profile
            "Route: ${Geo.formatKm(d.route.lengthM / 1000)} km von Hörschel nach Blankenstein" +
                (if (p != null) ", ↑ ${p.ascent} m ↓ ${p.descent} m" else "") + ".\n" +
                "${d.pois.size} Orte entlang des Weges, Stand der Daten: ${d.dataDate}.\n\n"
        } else ""
        val msg = TextView(this).apply {
            setPadding(dp(22), dp(12), dp(22), dp(4))
            textSize = 14f
            movementMethod = LinkMovementMethod.getInstance()
            text = routeInfo +
                "Alles funktioniert ohne Internet: Karte, Route, Orte, Höhenprofil, GPS und Kompass.\n\n" +
                "Empfehlungen (★): OpenStreetMap enthält keine Bewertungen. Ausgewählt werden kuratierte " +
                "Höhepunkte sowie je Abschnitt die Orte mit den vollständigsten Angaben (Website, " +
                "Öffnungszeiten, Wikipedia-Eintrag, Sterne) und der größten Nähe zum Weg. " +
                "Öffnungszeiten können sich ändern – im Zweifel vorher anrufen.\n\n" +
                "Kartendaten © OpenStreetMap-Mitwirkende (ODbL), Kartenkacheln im OpenMapTiles-Schema, " +
                "Höhendaten: Mapzen Terrain Tiles (SRTM, EU-DEM/Copernicus u. a.).\n\nGut Runst!"
        }
        AlertDialog.Builder(this)
            .setTitle("Rennsteig Offline ${versionName()}")
            .setView(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun applyKeepScreenOn() {
        if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ------------------------------------------------------------------ Einstellungen & Helfer

    private fun loadPrefs() {
        val cats = prefs.getStringSet("cats", null)
        enabledCats.clear()
        if (cats == null) enabledCats.addAll(PoiCategory.entries)
        else cats.mapNotNullTo(enabledCats) { PoiCategory.of(it) }
        onlyTop = prefs.getBoolean("onlyTop", false)
        keepScreenOn = prefs.getBoolean("keepScreenOn", false)
        reverse = prefs.getBoolean("reverse", false)
        preferHeadingUp = prefs.getBoolean("headingUp", true)
        applyKeepScreenOn()
    }

    private fun savePrefs() {
        prefs.edit()
            .putStringSet("cats", enabledCats.map { it.key }.toSet())
            .putBoolean("onlyTop", onlyTop)
            .putBoolean("keepScreenOn", keepScreenOn)
            .putBoolean("reverse", reverse)
            .putBoolean("headingUp", preferHeadingUp)
            .apply()
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        private const val TAG = "Rennsteig"
        private const val REQ_LOCATION = 1
        private const val REQ_NOTIFY = 2
        private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
        /** Geoidundulation in Thüringen: GPS-Höhe (Ellipsoid) liegt ca. 47 m über NHN. */
        private const val GEOID_HEIGHT = 47.0
        /** Im Einnorden-Modus sitzt die eigene Position im unteren Drittel, damit man mehr voraus sieht. */
        private const val HEADING_TOP_PAD = 0.32
    }
}
