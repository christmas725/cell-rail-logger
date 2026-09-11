from pathlib import Path

MAIN = Path("app/src/main/java/com/wooju/cellraillogger/MainActivity.kt")
MANIFEST = Path("app/src/main/AndroidManifest.xml")
BUILD = Path("app/build.gradle.kts")

s = MAIN.read_text(encoding="utf-8")

if 'PREFS_NAME = "cell_rail_logger_state"' not in s:
    s = s.replace("import android.content.Intent\n", "import android.content.Intent\nimport android.content.SharedPreferences\n")
    s = s.replace(
        "        private const val SAMPLE_INTERVAL_MS = 3_000L\n",
        '''        private const val SAMPLE_INTERVAL_MS = 3_000L
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
''')
    s = s.replace(
        "    private lateinit var telephonyManager: TelephonyManager\n",
        "    private lateinit var telephonyManager: TelephonyManager\n    private lateinit var prefs: SharedPreferences\n",
    )
    s = s.replace(
        "    private var sessionStartedElapsedMs = 0L\n",
        "    private var sessionStartedElapsedMs = 0L\n    private var sessionStartedWallMs = 0L\n",
    )
    s = s.replace(
        '    private var lastCellSnapshot = "아직 셀 정보가 없습니다."\n',
        '    private var lastCellSnapshot = "아직 셀 정보가 없습니다."\n    private var restoringState = false\n',
    )
    s = s.replace(
        '''        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        buildUi()
        updateRouteControls()
        updateButtons()
        requestPermissionsIfNeeded()
''',
        '''        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        buildUi()
        updateRouteControls()
        restorePersistentState()
        updateButtons()
        requestPermissionsIfNeeded()
''',
    )
    s = s.replace(
        '''    override fun onPause() {
        super.onPause()
        // Keep callback active only while the app UI is foregrounded in v0.1.
        unregisterTelephonyCallbackIfNeeded()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterTelephonyCallbackIfNeeded()
        closeWriter()
        super.onDestroy()
    }
''',
        '''    override fun onPause() {
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
''',
    )
    s = s.replace(
        '''            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateRouteControls()
            }
''',
        '''            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!restoringState) updateRouteControls()
            }
''',
        1,
    )
    s = s.replace(
        '''            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshStartStations()
                updateSegmentLabel()
            }
''',
        '''            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!restoringState) {
                    refreshStartStations()
                    updateSegmentLabel()
                }
            }
''',
        1,
    )
    s = s.replace(
        '''            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isRecording) {
                    currentStationIndex = position
                    updateSegmentLabel()
                }
            }
''',
        '''            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isRecording && !restoringState) {
                    currentStationIndex = position
                    updateSegmentLabel()
                }
            }
''',
        1,
    )
    s = s.replace(
        'text = "v0.1 · GPS 좌표를 읽지 않는 철도 셀룰러 로거"',
        'text = "v0.1.1 · GPS 좌표를 읽지 않는 철도 셀룰러 로거"',
    )
    s = s.replace(
        'text = "※ 이 앱은 Location/GPS API를 호출하지 않습니다. Android가 셀 식별자 접근에 정밀 위치 권한을 요구하기 때문에 해당 권한만 요청합니다. v0.1은 화면이 켜지고 앱이 전면에 있을 때 기록합니다."',
        'text = "※ 이 앱은 Location/GPS API를 호출하지 않습니다. Android가 셀 식별자 접근에 정밀 위치 권한을 요구하기 때문에 해당 권한만 요청합니다. v0.1.1은 분할화면 포커스 변경·화면 구성 재생성에도 진행 중 세션을 자동 복구합니다."',
    )

    helpers = '''    private fun setRouteControlsEnabled(enabled: Boolean) {
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

'''
    s = s.replace("    private fun requestPermissionsIfNeeded() {\n", helpers + "    private fun requestPermissionsIfNeeded() {\n")
    s = s.replace(
        "        sessionStartedElapsedMs = SystemClock.elapsedRealtime()\n",
        "        sessionStartedElapsedMs = SystemClock.elapsedRealtime()\n        sessionStartedWallMs = System.currentTimeMillis()\n",
        1,
    )
    s = s.replace(
        '''        isRecording = true
        lineSpinner.isEnabled = false
        directionSpinner.isEnabled = false
        startStationSpinner.isEnabled = false
''',
        '''        isRecording = true
        setRouteControlsEnabled(false)
''',
        1,
    )
    s = s.replace(
        '''        writeMarker("SESSION_START", stations[currentStationIndex])
        handler.post(sampleRunnable)
''',
        '''        writeMarker("SESSION_START", stations[currentStationIndex])
        persistSessionState()
        handler.removeCallbacks(sampleRunnable)
        handler.post(sampleRunnable)
''',
        1,
    )
    s = s.replace(
        '''        lineSpinner.isEnabled = true
        directionSpinner.isEnabled = true
        startStationSpinner.isEnabled = true
        updateButtons()
''',
        '''        setRouteControlsEnabled(true)
        clearActiveSessionState()
        updateButtons()
''',
        1,
    )
    s = s.replace(
        '''        writeMarker("DEPARTURE", station)
        addEvent("$station 출발 마커")
''',
        '''        writeMarker("DEPARTURE", station)
        addEvent("$station 출발 마커")
        persistSessionState()
''',
        1,
    )
    s = s.replace(
        '''        currentStationIndex = nextIndex
        updateSegmentLabel()
''',
        '''        currentStationIndex = nextIndex
        persistSessionState()
        updateSegmentLabel()
''',
        1,
    )
    s = s.replace(
        "            (SystemClock.elapsedRealtime() - sessionStartedElapsedMs).toString(),\n",
        "            sessionElapsedMs().toString(),\n",
        1,
    )
    s = s.replace(
        '            isRecording -> "상태: 기록 중 · ${lastSavedFile?.name ?: ""} · ${SAMPLE_INTERVAL_MS / 1000}초 갱신 요청"\n',
        '            isRecording -> "상태: 기록 중 · ${lastSavedFile?.name ?: ""} · ${SAMPLE_INTERVAL_MS / 1000}초 갱신 · 세션 자동복구 ON"\n',
        1,
    )
    s = s.replace(
        '''            statusText.text = "상태: 준비됨"
            return
''',
        '''            if (isRecording) updateButtons() else statusText.text = "상태: 준비됨"
            return
''',
        1,
    )
    s = s.replace(
        '''                statusText.text = "상태: 준비됨"
''',
        '''                if (isRecording) updateButtons() else statusText.text = "상태: 준비됨"
''',
        1,
    )

MAIN.write_text(s, encoding="utf-8")

m = MANIFEST.read_text(encoding="utf-8")
if 'android:configChanges=' not in m:
    m = m.replace(
        '''            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="unspecified">''',
        '''            android:name=".MainActivity"
            android:exported="true"
            android:resizeableActivity="true"
            android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|density|uiMode|keyboardHidden"
            android:screenOrientation="unspecified">''',
    )
MANIFEST.write_text(m, encoding="utf-8")

b = BUILD.read_text(encoding="utf-8")
b = b.replace('versionCode = 1', 'versionCode = 2')
b = b.replace('versionName = "0.1.0"', 'versionName = "0.1.1"')
BUILD.write_text(b, encoding="utf-8")

print("v0.1.1 migration applied")
