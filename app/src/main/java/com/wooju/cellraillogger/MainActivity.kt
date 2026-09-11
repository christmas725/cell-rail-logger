package com.wooju.cellraillogger

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val PERMISSION_REQUEST = 1001
        private const val EXPORT_REQUEST = 1002
        private const val SAMPLE_INTERVAL_MS = 3_000L
        private const val PREFS_NAME = "cell_rail_logger_state"
        private const val KEY_RECORDING = "recording"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_SESSION_STARTED_ELAPSED = "session_started_elapsed"
        private const val KEY_SESSION_STARTED_WALL = "session_started_wall"
        private const val KEY_LINE = "line"
        private const val KEY_DIRECTION = "direction"
        private const val KEY_START_STATION = "start_station"
        private const val KEY_CURRENT_INDEX = "current_station_index"
        private const val KEY_ACTIVE_FILE = "active_file"
        private const val KEY_LAST_FILE = "last_file"

        private val DAEGYEONG = listOf(
            "구미", "사곡", "북삼", "왜관", "서대구", "대구", "동대구", "경산"
        )

        private val LINE_2 = listOf(
            "문양", "다사", "대실", "강창", "계명대", "성서산업단지", "이곡", "용산", "죽전",
            "감삼", "두류", "내당", "반고개", "청라언덕", "반월당", "경대병원", "대구은행",
            "범어", "수성구청", "만촌", "담티", "연호", "대공원", "고산", "신매", "사월",
            "정평", "임당", "영남대"
        )
    }

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var lineSpinner: Spinner
    private lateinit var directionSpinner: Spinner
    private lateinit var startStationSpinner: Spinner
    private lateinit var currentSegmentText: TextView
    private lateinit var statusText: TextView
    private lateinit var cellText: TextView
    private lateinit var eventText: TextView
    private lateinit var startStopButton: Button
    private lateinit var departureButton: Button
    private lateinit var arrivalButton: Button
    private lateinit var exportButton: Button

    private var isRecording = false
    private var callbackRegistered = false
    private var sessionId = ""
    private var sessionStartedElapsedMs = 0L
    private var sessionStartedWallMs = 0L
    private var currentStationIndex = 0
    private var lastSavedFile: File? = null
    private var writer: FileOutputStream? = null
    private val eventLines = ArrayDeque<String>()
    private var lastCellSnapshot = "아직 셀 정보가 없습니다."
    private var restoringState = false

    private val localFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withLocale(Locale.KOREA)
        .withZone(ZoneId.systemDefault())

    private val cellCallback = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
            handleCellSnapshot(cellInfo, "callback")
        }
    }

    private val sampleRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return
            requestFreshCellInfo()
            handler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        buildUi()
        updateRouteControls()
        restorePersistentState()
        updateButtons()
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (hasRequiredPermissions()) {
            registerTelephonyCallbackIfNeeded()
            requestFreshCellInfo()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isRecording) {
            // Split-screen can move focus without making the logger invisible.
            persistSessionState()
        } else {
            unregisterTelephonyCallbackIfNeeded()
        }
    }

    override fun onDestroy() {
        if (isRecording) persistSessionState()
        handler.removeCallbacksAndMessages(null)
        unregisterTelephonyCallbackIfNeeded()
        closeWriter()
        super.onDestroy()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(36))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "Cell Rail Logger"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "v0.1.1 · GPS 좌표를 읽지 않는 철도 셀룰러 로거"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(18))
        })

        lineSpinner = addLabeledSpinner(root, "노선", listOf("대경선", "대구 도시철도 2호선"))
        directionSpinner = addLabeledSpinner(root, "방향", listOf("경산 방면", "구미 방면"))
        startStationSpinner = addLabeledSpinner(root, "기록 시작역", DAEGYEONG)

        val routeListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!restoringState) updateRouteControls()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        lineSpinner.onItemSelectedListener = routeListener
        directionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!restoringState) {
                    refreshStartStations()
                    updateSegmentLabel()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        startStationSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isRecording && !restoringState) {
                    currentStationIndex = position
                    updateSegmentLabel()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        currentSegmentText = sectionText("현재 구간: -")
        root.addView(currentSegmentText)

        startStopButton = Button(this).apply {
            text = "기록 시작"
            setOnClickListener { if (isRecording) stopRecording() else startRecording() }
        }
        root.addView(startStopButton, fullWidthParams())

        val markerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        departureButton = Button(this).apply {
            text = "현재 역 출발"
            setOnClickListener { markDeparture() }
        }
        arrivalButton = Button(this).apply {
            text = "다음 역 도착"
            setOnClickListener { markArrival() }
        }
        markerRow.addView(departureButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        markerRow.addView(arrivalButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
        })
        root.addView(markerRow)

        exportButton = Button(this).apply {
            text = "마지막 CSV 내보내기"
            setOnClickListener { exportLastCsv() }
        }
        root.addView(exportButton, fullWidthParams().apply { topMargin = dp(8) })

        statusText = sectionText("상태: 권한 확인 중")
        root.addView(statusText)

        root.addView(headerText("현재 셀 정보"))
        cellText = bodyBox("아직 셀 정보가 없습니다.")
        root.addView(cellText)

        root.addView(headerText("최근 이벤트"))
        eventText = bodyBox("기록을 시작하면 이벤트가 표시됩니다.")
        root.addView(eventText)

        root.addView(TextView(this).apply {
            text = "※ 이 앱은 Location/GPS API를 호출하지 않습니다. Android가 셀 식별자 접근에 정밀 위치 권한을 요구하기 때문에 해당 권한만 요청합니다. v0.1.1은 분할화면 포커스 변경·화면 구성 재생성에도 진행 중 세션을 자동 복구합니다."
            textSize = 12f
            setPadding(0, dp(18), 0, 0)
        })

        setContentView(scroll)
    }

    private fun addLabeledSpinner(root: LinearLayout, label: String, values: List<String>): Spinner {
        root.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        })
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)
        root.addView(spinner, fullWidthParams())
        return spinner
    }

    private fun headerText(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(8))
    }

    private fun sectionText(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 16f
        setPadding(0, dp(14), 0, dp(10))
    }

    private fun bodyBox(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 13f
        setTextIsSelectable(true)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setBackgroundColor(0xFFF1F3F4.toInt())
        typeface = Typeface.MONOSPACE
    }

    private fun fullWidthParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun updateRouteControls() {
        val isDaegyeong = lineSpinner.selectedItemPosition == 0
        val dirs = if (isDaegyeong) listOf("경산 방면", "구미 방면") else listOf("영남대 방면", "문양 방면")
        val currentDirection = directionSpinner.selectedItem?.toString()
        directionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, dirs)
        val idx = dirs.indexOf(currentDirection)
        if (idx >= 0) directionSpinner.setSelection(idx)
        refreshStartStations()
        updateSegmentLabel()
    }

    private fun routeStations(): List<String> {
        val base = if (lineSpinner.selectedItemPosition == 0) DAEGYEONG else LINE_2
        val forward = directionSpinner.selectedItemPosition == 0
        return if (forward) base else base.asReversed()
    }

    private fun refreshStartStations() {
        if (!::startStationSpinner.isInitialized || isRecording) return
        val stations = routeStations()
        val previous = startStationSpinner.selectedItem?.toString()
        startStationSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, stations)
        val idx = stations.indexOf(previous)
        if (idx >= 0) startStationSpinner.setSelection(idx)
        currentStationIndex = startStationSpinner.selectedItemPosition.coerceAtLeast(0)
    }

    private fun updateSegmentLabel() {
        if (!::currentSegmentText.isInitialized) return
        val stations = routeStations()
        currentStationIndex = currentStationIndex.coerceIn(0, stations.lastIndex)
        val current = stations[currentStationIndex]
        val next = stations.getOrNull(currentStationIndex + 1)
        currentSegmentText.text = if (next != null) {
            "현재 구간: $current → $next"
        } else {
            "현재 구간: $current (종착역)"
        }
        if (::arrivalButton.isInitialized) {
            arrivalButton.text = if (next != null) "$next 도착" else "종착역"
        }
        if (::departureButton.isInitialized) {
            departureButton.text = "$current 출발"
        }
    }

    private fun setRouteControlsEnabled(enabled: Boolean) {
        lineSpinner.isEnabled = enabled
        directionSpinner.isEnabled = enabled
        startStationSpinner.isEnabled = enabled
    }

    private fun persistSessionState() {
        if (!::prefs.isInitialized) return
        prefs.edit()
            .putBoolean(KEY_RECORDING, isRecording)
            .putString(KEY_SESSION_ID, sessionId)
            .putLong(KEY_SESSION_STARTED_ELAPSED, sessionStartedElapsedMs)
            .putLong(KEY_SESSION_STARTED_WALL, sessionStartedWallMs)
            .putString(KEY_LINE, selectedLine())
            .putString(KEY_DIRECTION, selectedDirection())
            .putString(KEY_START_STATION, startStationSpinner.selectedItem?.toString() ?: "")
            .putInt(KEY_CURRENT_INDEX, currentStationIndex)
            .putString(KEY_ACTIVE_FILE, if (isRecording) lastSavedFile?.absolutePath else null)
            .putString(KEY_LAST_FILE, lastSavedFile?.absolutePath)
            .apply()
    }

    private fun clearActiveSessionState() {
        if (!::prefs.isInitialized) return
        prefs.edit()
            .putBoolean(KEY_RECORDING, false)
            .remove(KEY_ACTIVE_FILE)
            .remove(KEY_SESSION_ID)
            .remove(KEY_SESSION_STARTED_ELAPSED)
            .remove(KEY_SESSION_STARTED_WALL)
            .remove(KEY_LINE)
            .remove(KEY_DIRECTION)
            .remove(KEY_START_STATION)
            .remove(KEY_CURRENT_INDEX)
            .putString(KEY_LAST_FILE, lastSavedFile?.absolutePath)
            .apply()
    }

    private fun restorePersistentState() {
        if (!::prefs.isInitialized) return

        prefs.getString(KEY_LAST_FILE, null)?.let { path ->
            val file = File(path)
            if (file.exists()) lastSavedFile = file
        }

        if (!prefs.getBoolean(KEY_RECORDING, false)) return

        val savedLine = prefs.getString(KEY_LINE, "") ?: ""
        val savedDirection = prefs.getString(KEY_DIRECTION, "") ?: ""
        val savedStartStation = prefs.getString(KEY_START_STATION, "") ?: ""
        val savedFile = prefs.getString(KEY_ACTIVE_FILE, null)?.let(::File)

        if (savedFile == null || !savedFile.exists()) {
            clearActiveSessionState()
            statusText.text = "상태: 이전 기록 세션 파일을 찾지 못해 복구하지 못했습니다."
            return
        }

        restoringState = true
        try {
            lineSpinner.setSelection(if (savedLine == "대구 도시철도 2호선") 1 else 0, false)
            updateRouteControls()
            val directionValues = if (lineSpinner.selectedItemPosition == 0) {
                listOf("경산 방면", "구미 방면")
            } else {
                listOf("영남대 방면", "문양 방면")
            }
            val directionIndex = directionValues.indexOf(savedDirection).takeIf { it >= 0 } ?: 0
            directionSpinner.setSelection(directionIndex, false)
            refreshStartStations()
            val stations = routeStations()
            val startIndex = stations.indexOf(savedStartStation).takeIf { it >= 0 } ?: 0
            startStationSpinner.setSelection(startIndex, false)
            currentStationIndex = prefs.getInt(KEY_CURRENT_INDEX, startIndex).coerceIn(0, stations.lastIndex)
        } finally {
            restoringState = false
        }

        sessionId = prefs.getString(KEY_SESSION_ID, "") ?: ""
        sessionStartedElapsedMs = prefs.getLong(KEY_SESSION_STARTED_ELAPSED, 0L)
        sessionStartedWallMs = prefs.getLong(KEY_SESSION_STARTED_WALL, System.currentTimeMillis())
        lastSavedFile = savedFile

        try {
            writer = FileOutputStream(savedFile, true)
            isRecording = true
            setRouteControlsEnabled(false)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            updateSegmentLabel()
            addEvent("이전 기록 세션 자동 복구")
            writeMarker("SESSION_RESUME", currentStationName())
            handler.removeCallbacks(sampleRunnable)
            handler.post(sampleRunnable)
        } catch (e: Exception) {
            isRecording = false
            closeWriter()
            clearActiveSessionState()
            statusText.text = "상태: 세션 복구 실패 · ${e.message}"
        }
    }

    private fun sessionElapsedMs(): Long {
        val monotonic = SystemClock.elapsedRealtime() - sessionStartedElapsedMs
        if (sessionStartedElapsedMs > 0L && monotonic >= 0L) return monotonic
        return (System.currentTimeMillis() - sessionStartedWallMs).coerceAtLeast(0L)
    }

    private fun requestPermissionsIfNeeded() {
        if (hasRequiredPermissions()) {
            registerTelephonyCallbackIfNeeded()
            requestFreshCellInfo()
            if (isRecording) updateButtons() else statusText.text = "상태: 준비됨"
            return
        }
        requestPermissions(
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_PHONE_STATE
            ),
            PERMISSION_REQUEST
        )
    }

    private fun hasRequiredPermissions(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            if (hasRequiredPermissions()) {
                registerTelephonyCallbackIfNeeded()
                requestFreshCellInfo()
                if (isRecording) updateButtons() else statusText.text = "상태: 준비됨"
            } else {
                statusText.text = "상태: 정밀 위치 + 전화 권한이 필요합니다. GPS 좌표는 사용하지 않습니다."
                Toast.makeText(this, "셀 식별자를 읽으려면 정밀 위치 및 전화 권한이 필요합니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun registerTelephonyCallbackIfNeeded() {
        if (callbackRegistered || !hasRequiredPermissions()) return
        try {
            telephonyManager.registerTelephonyCallback(mainExecutor, cellCallback)
            callbackRegistered = true
        } catch (e: SecurityException) {
            statusText.text = "상태: TelephonyCallback 권한 오류"
        }
    }

    private fun unregisterTelephonyCallbackIfNeeded() {
        if (!callbackRegistered) return
        try {
            telephonyManager.unregisterTelephonyCallback(cellCallback)
        } catch (_: Exception) {
        } finally {
            callbackRegistered = false
        }
    }

    private fun requestFreshCellInfo() {
        if (!hasRequiredPermissions()) return
        try {
            telephonyManager.requestCellInfoUpdate(mainExecutor, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                    handleCellSnapshot(cellInfo, "poll")
                }

                override fun onError(errorCode: Int, detail: Throwable?) {
                    if (isRecording) addEvent("셀 갱신 요청 오류 code=$errorCode")
                }
            })
        } catch (e: SecurityException) {
            statusText.text = "상태: 셀 정보 권한 오류"
        } catch (e: UnsupportedOperationException) {
            statusText.text = "상태: 이 기기에서 셀 정보 조회를 지원하지 않습니다."
        }
    }

    private fun startRecording() {
        if (!hasRequiredPermissions()) {
            requestPermissionsIfNeeded()
            return
        }
        val stations = routeStations()
        currentStationIndex = startStationSpinner.selectedItemPosition.coerceIn(0, stations.lastIndex)
        sessionId = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        sessionStartedElapsedMs = SystemClock.elapsedRealtime()
        sessionStartedWallMs = System.currentTimeMillis()

        val dir = filesDir.resolve("logs")
        dir.mkdirs()
        val safeLine = selectedLine().replace(" ", "_")
        val file = dir.resolve("${sessionId}_${safeLine}.csv")
        writer = FileOutputStream(file, false)
        lastSavedFile = file
        writeRaw("timestamp_iso,elapsed_ms,session_id,event,source,line,direction,current_station,next_station,rat,registered,cell_id,tac_lac,pci_psc,channel,rsrp_dbm,rsrq_db,sinr_db,signal_dbm,mcc,mnc\n")

        isRecording = true
        setRouteControlsEnabled(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateSegmentLabel()
        addEvent("기록 시작 · ${selectedLine()} · ${selectedDirection()}")
        writeMarker("SESSION_START", stations[currentStationIndex])
        persistSessionState()
        handler.removeCallbacks(sampleRunnable)
        handler.post(sampleRunnable)
        updateButtons()
        requestFreshCellInfo()
    }

    private fun stopRecording() {
        if (!isRecording) return
        writeMarker("SESSION_STOP", currentStationName())
        addEvent("기록 종료")
        isRecording = false
        handler.removeCallbacks(sampleRunnable)
        closeWriter()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setRouteControlsEnabled(true)
        clearActiveSessionState()
        updateButtons()
        statusText.text = "상태: 기록 저장 완료 · ${lastSavedFile?.name ?: "-"}"
    }

    private fun closeWriter() {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
    }

    private fun markDeparture() {
        if (!isRecording) return
        val station = currentStationName()
        writeMarker("DEPARTURE", station)
        addEvent("$station 출발 마커")
        persistSessionState()
    }

    private fun markArrival() {
        if (!isRecording) return
        val stations = routeStations()
        val nextIndex = currentStationIndex + 1
        if (nextIndex > stations.lastIndex) return
        val next = stations[nextIndex]
        writeMarker("ARRIVAL", next)
        addEvent("$next 도착 마커")
        currentStationIndex = nextIndex
        persistSessionState()
        updateSegmentLabel()
    }

    private fun handleCellSnapshot(cells: List<CellInfo>, source: String) {
        if (cells.isEmpty()) {
            lastCellSnapshot = "셀 정보가 비어 있습니다.\n위치 서비스가 켜져 있는지 확인해 주세요.\n(GPS 좌표 자체는 사용하지 않습니다.)"
            cellText.text = lastCellSnapshot
            return
        }

        val sorted = cells.sortedWith(
            compareByDescending<CellInfo> { it.isRegistered }
                .thenByDescending { signalDbm(it) }
        )
        val operator = telephonyManager.networkOperatorName.ifBlank { "알 수 없음" }
        lastCellSnapshot = buildString {
            append("통신사: ").append(operator).append('\n')
            append("관측 셀: ").append(sorted.size).append("개\n\n")
            sorted.take(8).forEachIndexed { index, cell ->
                val row = extractCell(cell)
                append(if (index == 0) "▶ " else "  ")
                append(row.rat)
                append(if (row.registered) " [REGISTERED]" else "")
                append('\n')
                append("  ID ").append(row.cellId)
                    .append(" / TAC ").append(row.tacLac)
                    .append(" / PCI ").append(row.pciPsc).append('\n')
                append("  CH ").append(row.channel)
                    .append(" / RSRP ").append(row.rsrp)
                    .append(" / RSRQ ").append(row.rsrq)
                    .append(" / SINR ").append(row.sinr)
                    .append(" / dBm ").append(row.dbm).append("\n\n")
            }
        }
        cellText.text = lastCellSnapshot

        if (isRecording) {
            sorted.forEach { cell -> writeCellRow(cell, source) }
        }
    }

    private fun signalDbm(cell: CellInfo): Int = when (cell) {
        is CellInfoNr -> cell.cellSignalStrength.dbm
        is CellInfoLte -> cell.cellSignalStrength.dbm
        is CellInfoWcdma -> cell.cellSignalStrength.dbm
        else -> Int.MIN_VALUE
    }

    private data class CellRow(
        val rat: String,
        val registered: Boolean,
        val cellId: String,
        val tacLac: String,
        val pciPsc: String,
        val channel: String,
        val rsrp: String,
        val rsrq: String,
        val sinr: String,
        val dbm: String,
        val mcc: String,
        val mnc: String
    )

    private fun extractCell(cell: CellInfo): CellRow {
        return when (cell) {
            is CellInfoNr -> {
                val id = cell.cellIdentity as CellIdentityNr
                val ss = cell.cellSignalStrength as CellSignalStrengthNr
                CellRow(
                    rat = "NR5G",
                    registered = cell.isRegistered,
                    cellId = normalizeLong(id.nci),
                    tacLac = normalizeInt(id.tac),
                    pciPsc = normalizeInt(id.pci),
                    channel = normalizeInt(id.nrarfcn),
                    rsrp = normalizeInt(ss.ssRsrp),
                    rsrq = normalizeInt(ss.ssRsrq),
                    sinr = normalizeInt(ss.ssSinr),
                    dbm = normalizeInt(ss.dbm),
                    mcc = id.mccString ?: "",
                    mnc = id.mncString ?: ""
                )
            }
            is CellInfoLte -> {
                val id = cell.cellIdentity as CellIdentityLte
                val ss = cell.cellSignalStrength as CellSignalStrengthLte
                CellRow(
                    rat = "LTE",
                    registered = cell.isRegistered,
                    cellId = normalizeInt(id.ci),
                    tacLac = normalizeInt(id.tac),
                    pciPsc = normalizeInt(id.pci),
                    channel = normalizeInt(id.earfcn),
                    rsrp = normalizeInt(ss.rsrp),
                    rsrq = normalizeInt(ss.rsrq),
                    sinr = normalizeInt(ss.rssnr),
                    dbm = normalizeInt(ss.dbm),
                    mcc = id.mccString ?: "",
                    mnc = id.mncString ?: ""
                )
            }
            is CellInfoWcdma -> {
                val id = cell.cellIdentity
                val ss = cell.cellSignalStrength as CellSignalStrengthWcdma
                CellRow(
                    rat = "WCDMA",
                    registered = cell.isRegistered,
                    cellId = normalizeInt(id.cid),
                    tacLac = normalizeInt(id.lac),
                    pciPsc = normalizeInt(id.psc),
                    channel = normalizeInt(id.uarfcn),
                    rsrp = "",
                    rsrq = "",
                    sinr = "",
                    dbm = normalizeInt(ss.dbm),
                    mcc = id.mccString ?: "",
                    mnc = id.mncString ?: ""
                )
            }
            else -> CellRow(
                rat = cell.javaClass.simpleName,
                registered = cell.isRegistered,
                cellId = "",
                tacLac = "",
                pciPsc = "",
                channel = "",
                rsrp = "",
                rsrq = "",
                sinr = "",
                dbm = signalDbm(cell).takeIf { it != Int.MIN_VALUE }?.toString() ?: "",
                mcc = "",
                mnc = ""
            )
        }
    }

    private fun normalizeInt(value: Int): String = if (value == CellInfo.UNAVAILABLE || value == Int.MAX_VALUE) "" else value.toString()
    private fun normalizeLong(value: Long): String = if (value == CellInfo.UNAVAILABLE_LONG || value == Long.MAX_VALUE) "" else value.toString()

    private fun writeCellRow(cell: CellInfo, source: String) {
        val row = extractCell(cell)
        writeCsvRow(
            event = "CELL",
            source = source,
            rat = row.rat,
            registered = row.registered.toString(),
            cellId = row.cellId,
            tacLac = row.tacLac,
            pciPsc = row.pciPsc,
            channel = row.channel,
            rsrp = row.rsrp,
            rsrq = row.rsrq,
            sinr = row.sinr,
            dbm = row.dbm,
            mcc = row.mcc,
            mnc = row.mnc
        )
    }

    private fun writeMarker(event: String, station: String) {
        writeCsvRow(
            event = "$event:$station",
            source = "manual",
            rat = "",
            registered = "",
            cellId = "",
            tacLac = "",
            pciPsc = "",
            channel = "",
            rsrp = "",
            rsrq = "",
            sinr = "",
            dbm = "",
            mcc = "",
            mnc = ""
        )
    }

    private fun writeCsvRow(
        event: String,
        source: String,
        rat: String,
        registered: String,
        cellId: String,
        tacLac: String,
        pciPsc: String,
        channel: String,
        rsrp: String,
        rsrq: String,
        sinr: String,
        dbm: String,
        mcc: String,
        mnc: String
    ) {
        if (!isRecording && !event.startsWith("SESSION_START")) return
        val now = Instant.now()
        val stations = routeStations()
        val current = stations.getOrNull(currentStationIndex) ?: ""
        val next = stations.getOrNull(currentStationIndex + 1) ?: ""
        val values = listOf(
            localFormatter.format(now),
            sessionElapsedMs().toString(),
            sessionId,
            event,
            source,
            selectedLine(),
            selectedDirection(),
            current,
            next,
            rat,
            registered,
            cellId,
            tacLac,
            pciPsc,
            channel,
            rsrp,
            rsrq,
            sinr,
            dbm,
            mcc,
            mnc
        )
        writeRaw(values.joinToString(",") { csvEscape(it) } + "\n")
    }

    private fun writeRaw(text: String) {
        try {
            writer?.write(text.toByteArray(Charsets.UTF_8))
            writer?.flush()
        } catch (e: Exception) {
            statusText.text = "상태: 파일 기록 오류 · ${e.message}"
        }
    }

    private fun csvEscape(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun selectedLine(): String = lineSpinner.selectedItem?.toString() ?: ""
    private fun selectedDirection(): String = directionSpinner.selectedItem?.toString() ?: ""
    private fun currentStationName(): String = routeStations().getOrNull(currentStationIndex) ?: ""

    private fun addEvent(message: String) {
        val time = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        eventLines.addFirst("$time  $message")
        while (eventLines.size > 12) eventLines.removeLast()
        eventText.text = eventLines.joinToString("\n")
    }

    private fun updateButtons() {
        if (!::startStopButton.isInitialized) return
        startStopButton.text = if (isRecording) "기록 종료" else "기록 시작"
        departureButton.isEnabled = isRecording
        arrivalButton.isEnabled = isRecording && currentStationIndex < routeStations().lastIndex
        exportButton.isEnabled = !isRecording && lastSavedFile?.exists() == true
        statusText.text = when {
            isRecording -> "상태: 기록 중 · ${lastSavedFile?.name ?: ""} · ${SAMPLE_INTERVAL_MS / 1000}초 갱신 · 세션 자동복구 ON"
            hasRequiredPermissions() -> "상태: 준비됨"
            else -> "상태: 권한 필요"
        }
    }

    private fun exportLastCsv() {
        val file = lastSavedFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, "내보낼 기록이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, file.name)
        }
        startActivityForResult(intent, EXPORT_REQUEST)
    }

    @Deprecated("Deprecated in Android SDK; retained for the minimal v0.1 without AndroidX.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == EXPORT_REQUEST && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            val file = lastSavedFile ?: return
            try {
                contentResolver.openOutputStream(uri, "w")?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                }
                Toast.makeText(this, "CSV를 내보냈습니다.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "내보내기 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
