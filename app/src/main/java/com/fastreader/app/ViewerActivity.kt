package com.fastreader.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fastreader.app.core.DocumentType
import com.fastreader.app.data.RecentDocument
import com.fastreader.app.data.RecentDocuments
import com.fastreader.app.data.ViewerPrefs
import com.fastreader.app.databinding.ActivityViewerBinding
import com.fastreader.app.databinding.DialogSliderBinding
import com.fastreader.app.viewer.DocumentInfo
import com.fastreader.app.viewer.PdfPageAdapter
import com.fastreader.app.viewer.SearchMatch
import com.fastreader.app.viewer.TextBlockAdapter
import com.fastreader.app.viewer.ViewerState
import com.fastreader.app.viewer.ViewerViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class ViewerActivity : AppCompatActivity() {

    private lateinit var b: ActivityViewerBinding
    private val vm: ViewerViewModel by viewModels()
    private lateinit var recents: RecentDocuments
    private lateinit var prefs: ViewerPrefs

    private var pdfAdapter: PdfPageAdapter? = null
    private var textAdapter: TextBlockAdapter? = null
    private var shownState: ViewerState? = null
    private var restoredUri: Uri? = null

    private var matches: List<SearchMatch> = emptyList()
    private var matchIndex = -1
    private var searchJob: Job? = null

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            persistPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            savePosition()
            vm.open(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(b.root)
        applySystemBarInsets(b.root)
        recents = RecentDocuments(this)
        prefs = ViewerPrefs(this)

        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.errorOpenOther.setOnClickListener { launchPicker() }
        setupSearchBar()
        setupTextList()
        setupPdfList()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (b.searchBar.isVisible) closeSearch() else finish()
            }
        })

        val uri = extractUri(intent)
        if (uri == null) {
            if (vm.currentUri == null) showError(getString(R.string.open_file_desc))
        } else {
            persistPermission(uri, intent.flags)
            vm.open(uri)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { render(it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractUri(intent)?.let {
            savePosition()
            persistPermission(it, intent.flags)
            vm.open(it)
        }
    }

    override fun onPause() {
        super.onPause()
        savePosition()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) vm.trimMemory()
    }

    // ---------------------------------------------------------------- 상태 표시

    private fun render(state: ViewerState) {
        if (state === shownState) return
        shownState = state
        b.loading.isVisible = state is ViewerState.Loading
        b.errorView.isVisible = state is ViewerState.Failed
        b.pdfList.isVisible = state is ViewerState.Pdf
        b.textList.isVisible = state is ViewerState.Text
        b.bottomBar.isVisible = state is ViewerState.Pdf || state is ViewerState.Text
        b.readProgress.isVisible = b.bottomBar.isVisible
        b.notice.isVisible = false
        closeSearch()
        invalidateOptionsMenu()

        when (state) {
            ViewerState.Loading -> {
                b.toolbar.title = ""
                pdfAdapter = null
                textAdapter = null
                b.pdfList.adapter = null
                b.textList.adapter = null
            }
            is ViewerState.Failed -> {
                b.toolbar.title = state.info?.displayName ?: getString(R.string.app_name)
                showError(state.message)
            }
            is ViewerState.Pdf -> showPdf(state)
            is ViewerState.Text -> showText(state)
        }
    }

    private fun showError(message: String) {
        b.loading.isVisible = false
        b.errorView.isVisible = true
        b.errorText.text = message
    }

    private fun showPdf(state: ViewerState.Pdf) {
        b.toolbar.title = state.info.displayName
        addToRecents(state.info, "PDF")
        val adapter = PdfPageAdapter(state.pdf, lifecycleScope)
        adapter.nightMode = prefs.pdfNightMode
        pdfAdapter = adapter
        textAdapter = null
        b.textList.adapter = null
        b.pdfList.resetZoom()
        b.pdfList.adapter = adapter
        if (b.pdfList.width > 0) adapter.setViewWidth(b.pdfList.width)
        lifecycleScope.launch {
            try {
                state.pdf.preloadSizes(30)
                if (pdfAdapter === adapter) adapter.notifyItemRangeChanged(0, minOf(30, adapter.itemCount))
            } catch (_: Exception) {
            }
        }

        configureBottomBar(isPdf = true)
        restorePosition(state.info.uri, b.pdfList)
        updateProgress()
    }

    private fun showText(state: ViewerState.Text) {
        b.toolbar.title = state.info.displayName
        addToRecents(state.info, if (state.type == DocumentType.HWPX) "HWPX" else "HWP")
        pdfAdapter = null
        b.pdfList.adapter = null
        val adapter = TextBlockAdapter(state.doc.blocks)
        adapter.textSizeSp = prefs.textSizeSp
        textAdapter = adapter
        b.textList.adapter = adapter

        val notice = state.doc.notice ?: if (state.doc.isEmpty) getString(R.string.empty_document) else null
        b.notice.isVisible = notice != null
        b.notice.text = notice

        configureBottomBar(isPdf = false)
        restorePosition(state.info.uri, b.textList)
        updateProgress()
    }

    // ---------------------------------------------------------------- 하단 도구 막대 (디자인 B안)

    private fun configureBottomBar(isPdf: Boolean) {
        if (isPdf) {
            b.btnFirst.setIconResource(R.drawable.ic_zoom)
            b.btnFirst.setOnClickListener {
                val list = b.pdfList
                if (list.scale > 1.05f) list.zoomTo(1f, list.width / 2f, 0f) else list.zoomTo(2f, list.width / 2f, 0f)
                pdfAdapter?.setRenderScale(list.scale)
                updateZoomButton()
            }
            updateZoomButton()
            b.btnSecond.setIconResource(R.drawable.ic_night)
            b.btnSecond.setOnClickListener {
                prefs.pdfNightMode = !prefs.pdfNightMode
                pdfAdapter?.nightMode = prefs.pdfNightMode
                updateNightButton()
            }
            updateNightButton()
        } else {
            b.btnFirst.setIconResource(R.drawable.ic_search)
            b.btnFirst.setText(R.string.search)
            b.btnFirst.setOnClickListener { if (b.searchBar.isVisible) closeSearch() else openSearch() }
            b.btnSecond.setIconResource(R.drawable.ic_text_size)
            b.btnSecond.setText(R.string.text_size)
            b.btnSecond.setOnClickListener { showTextSizeDialog() }
        }
        b.btnPage.setOnClickListener { showGoToDialog() }
        b.btnShare.setOnClickListener { shareDocument() }
    }

    private fun updateZoomButton() {
        b.btnFirst.setText(if (b.pdfList.scale > 1.05f) R.string.zoom_reset else R.string.zoom)
    }

    private fun updateNightButton() {
        b.btnSecond.setText(if (prefs.pdfNightMode) R.string.day_short else R.string.night_short)
    }

    private fun updateProgress() {
        val list = when (shownState) {
            is ViewerState.Pdf -> b.pdfList
            is ViewerState.Text -> b.textList
            else -> return
        }
        val count = list.adapter?.itemCount ?: 0
        if (count == 0) return
        val lm = list.layoutManager as LinearLayoutManager
        val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        if (shownState is ViewerState.Pdf) {
            b.btnPage.text = getString(R.string.page_format, first + 1, count)
            b.readProgress.setProgressCompat(((first + 1) * 1000L / count).toInt(), false)
        } else {
            val range = list.computeVerticalScrollRange() - list.computeVerticalScrollExtent()
            val ratio = if (range <= 0) 1f else list.computeVerticalScrollOffset().toFloat() / range
            val percent = (ratio * 100).roundToInt().coerceIn(0, 100)
            b.btnPage.text = getString(R.string.percent_format, percent)
            b.readProgress.setProgressCompat((ratio * 1000).roundToInt().coerceIn(0, 1000), false)
        }
    }

    // ---------------------------------------------------------------- 목록 설정

    private fun setupPdfList() {
        b.pdfList.layoutManager = LinearLayoutManager(this)
        b.pdfList.setHasFixedSize(true)
        b.pdfList.setItemViewCacheSize(2)
        b.pdfList.addOnLayoutChangeListener { v, l, _, r, _, oldL, _, oldR, _ ->
            if (r - l != oldR - oldL) pdfAdapter?.setViewWidth(v.width)
        }
        b.pdfList.onZoomChanged = { scale ->
            pdfAdapter?.setRenderScale(scale)
            updateZoomButton()
        }
        b.pdfList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updateProgress()
        })
    }

    private fun setupTextList() {
        b.textList.layoutManager = LinearLayoutManager(this)
        b.textList.setItemViewCacheSize(20)
        b.textList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updateProgress()
        })
        // 두 손가락으로 글자 크기 조절
        val detector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val adapter = textAdapter ?: return false
                val newSize = (adapter.textSizeSp * detector.scaleFactor).coerceIn(MIN_TEXT, MAX_TEXT)
                if (kotlin.math.abs(newSize - adapter.textSizeSp) >= 0.5f) {
                    adapter.textSizeSp = newSize
                    return true
                }
                return false
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                textAdapter?.let { prefs.textSizeSp = it.textSizeSp }
            }
        })
        b.textList.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                detector.onTouchEvent(e)
                return e.pointerCount > 1
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                detector.onTouchEvent(e)
            }
        })
    }

    // ---------------------------------------------------------------- 위치 저장/복원

    private fun savePosition() {
        val uri = vm.currentUri ?: return
        val list = when (shownState) {
            is ViewerState.Pdf -> b.pdfList
            is ViewerState.Text -> b.textList
            else -> return
        }
        val lm = list.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        if (first < 0) return
        val top = lm.findViewByPosition(first)?.top ?: 0
        recents.savePosition(uri, first, top - list.paddingTop)
    }

    private fun restorePosition(uri: Uri, list: RecyclerView) {
        val lm = list.layoutManager as LinearLayoutManager
        if (restoredUri == uri) return
        restoredUri = uri
        val (index, offset) = recents.position(uri) ?: return
        val count = list.adapter?.itemCount ?: 0
        if (index in 1 until count) lm.scrollToPositionWithOffset(index, offset)
    }

    // ---------------------------------------------------------------- 검색 (한글 문서)

    private fun setupSearchBar() {
        b.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = scheduleSearch(s?.toString().orEmpty())
        })
        b.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                moveMatch(+1)
                hideKeyboard()
                true
            } else false
        }
        b.searchNext.setOnClickListener { moveMatch(+1) }
        b.searchPrev.setOnClickListener { moveMatch(-1) }
        b.searchClose.setOnClickListener { closeSearch() }
    }

    private fun openSearch() {
        if (textAdapter == null) return
        b.searchBar.isVisible = true
        b.searchInput.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(b.searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        if (!b.searchBar.isVisible) return
        searchJob?.cancel()
        hideKeyboard()
        b.searchBar.isVisible = false
        b.searchInput.setText("")
        matches = emptyList()
        matchIndex = -1
        textAdapter?.setSearch("", null)
    }

    private fun scheduleSearch(query: String) {
        searchJob?.cancel()
        val adapter = textAdapter ?: return
        val state = shownState as? ViewerState.Text ?: return
        searchJob = lifecycleScope.launch {
            delay(250)
            val q = query.trim()
            val found = withContext(Dispatchers.Default) { TextBlockAdapter.findMatches(state.doc.blocks, q, SEARCH_LIMIT) }
            matches = found
            // 현재 보고 있는 위치 이후의 첫 결과로 이동
            val first = (b.textList.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
            matchIndex = found.indexOfFirst { it.block >= first }.let { if (it < 0 && found.isNotEmpty()) 0 else it }
            adapter.setSearch(q, found.getOrNull(matchIndex))
            updateSearchCount(q)
            found.getOrNull(matchIndex)?.let { scrollToBlock(it.block) }
        }
    }

    private fun moveMatch(delta: Int) {
        if (matches.isEmpty()) return
        matchIndex = (matchIndex + delta).mod(matches.size)
        val m = matches[matchIndex]
        textAdapter?.setSearch(b.searchInput.text.toString().trim(), m)
        updateSearchCount(b.searchInput.text.toString().trim())
        scrollToBlock(m.block)
    }

    private fun updateSearchCount(q: String) {
        b.searchCount.text = when {
            q.isEmpty() -> ""
            matches.isEmpty() -> getString(R.string.no_results)
            matches.size >= SEARCH_LIMIT -> getString(R.string.search_count_more, matchIndex + 1, matches.size)
            else -> getString(R.string.search_count, matchIndex + 1, matches.size)
        }
    }

    private fun scrollToBlock(block: Int) {
        val lm = b.textList.layoutManager as LinearLayoutManager
        lm.scrollToPositionWithOffset(block, b.textList.height / 4)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(b.searchInput.windowToken, 0)
    }

    // ---------------------------------------------------------------- 다이얼로그

    private fun showTextSizeDialog() {
        val adapter = textAdapter ?: return
        val d = DialogSliderBinding.inflate(layoutInflater)
        d.dialogSlider.valueFrom = MIN_TEXT
        d.dialogSlider.valueTo = MAX_TEXT
        d.dialogSlider.stepSize = 1f
        d.dialogSlider.value = adapter.textSizeSp.roundToInt().toFloat().coerceIn(MIN_TEXT, MAX_TEXT)
        d.dialogValue.text = "가 ${d.dialogSlider.value.toInt()}"
        d.dialogValue.textSize = d.dialogSlider.value
        d.dialogSlider.addOnChangeListener { _, value, _ ->
            d.dialogValue.text = "가 ${value.toInt()}"
            d.dialogValue.textSize = value
            adapter.textSizeSp = value
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.text_size)
            .setView(d.root)
            .setPositiveButton(R.string.ok) { _, _ -> prefs.textSizeSp = adapter.textSizeSp }
            .setOnCancelListener { prefs.textSizeSp = adapter.textSizeSp }
            .show()
    }

    private fun showGoToDialog() {
        val isPdf = shownState is ViewerState.Pdf
        val list = if (isPdf) b.pdfList else b.textList
        val count = list.adapter?.itemCount ?: return
        if (count <= 1) return
        val lm = list.layoutManager as LinearLayoutManager
        val d = DialogSliderBinding.inflate(layoutInflater)
        val current = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        if (isPdf) {
            d.dialogSlider.valueFrom = 1f
            d.dialogSlider.valueTo = count.toFloat()
            d.dialogSlider.stepSize = 1f
            d.dialogSlider.value = (current + 1).toFloat().coerceIn(1f, count.toFloat())
            d.dialogInput.isVisible = true
            d.dialogInput.hint = getString(R.string.page_input_hint, count)
            d.dialogValue.text = getString(R.string.page_format, current + 1, count)
            d.dialogSlider.addOnChangeListener { _, v, fromUser ->
                d.dialogValue.text = getString(R.string.page_format, v.toInt(), count)
                if (fromUser) d.dialogInput.setText("")
            }
        } else {
            d.dialogSlider.valueFrom = 0f
            d.dialogSlider.valueTo = 100f
            d.dialogSlider.stepSize = 1f
            d.dialogSlider.value = (current * 100f / (count - 1)).roundToInt().toFloat().coerceIn(0f, 100f)
            d.dialogValue.text = getString(R.string.percent_format, d.dialogSlider.value.toInt())
            d.dialogSlider.addOnChangeListener { _, v, _ ->
                d.dialogValue.text = getString(R.string.percent_format, v.toInt())
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.go_to_page)
            .setView(d.root)
            .setPositiveButton(R.string.ok) { _, _ ->
                val target = if (isPdf) {
                    val typed = d.dialogInput.text.toString().toIntOrNull()
                    ((typed ?: d.dialogSlider.value.toInt()) - 1).coerceIn(0, count - 1)
                } else {
                    (d.dialogSlider.value / 100f * (count - 1)).roundToInt().coerceIn(0, count - 1)
                }
                lm.scrollToPositionWithOffset(target, 0)
                list.post { updateProgress() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showInfo() {
        val (info, detail) = when (val s = shownState) {
            is ViewerState.Pdf -> s.info to "PDF · ${s.pdf.pageCount}쪽"
            is ViewerState.Text -> s.info to "${if (s.type == DocumentType.HWPX) "HWPX" else "HWP"} · 문단 ${s.doc.blocks.size}개"
            is ViewerState.Failed -> (s.info ?: return) to "열 수 없음"
            else -> return
        }
        val size = if (info.size >= 0) Formatter.formatFileSize(this, info.size) else "알 수 없음"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.doc_info)
            .setMessage("이름: ${info.displayName}\n형식: $detail\n크기: $size")
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // ---------------------------------------------------------------- 메뉴

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_viewer, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_copy_all)?.isVisible = shownState is ViewerState.Text
        menu.findItem(R.id.action_info)?.isVisible = shownState !is ViewerState.Loading
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_copy_all -> copyAllText()
            R.id.action_open_other -> launchPicker()
            R.id.action_info -> showInfo()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun copyAllText() {
        val state = shownState as? ViewerState.Text ?: return
        val sb = StringBuilder()
        for (block in state.doc.blocks) {
            if (sb.length + block.text.length > CLIPBOARD_LIMIT) break
            sb.append(block.text).append('\n')
        }
        try {
            getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText(state.info.displayName, sb))
            // Android 13+ 는 시스템이 복사 알림을 띄운다
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "복사하지 못했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareDocument() {
        val uri = vm.currentUri ?: return
        val info: DocumentInfo? = when (val s = shownState) {
            is ViewerState.Pdf -> s.info
            is ViewerState.Text -> s.info
            else -> null
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = info?.mimeType?.takeIf { it != "application/octet-stream" } ?: when (shownState) {
                is ViewerState.Pdf -> "application/pdf"
                is ViewerState.Text -> "application/x-hwp"
                else -> "*/*"
            }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share)))
        } catch (e: Exception) {
            // file:// URI 는 다른 앱에 넘길 수 없다(FileUriExposedException) 등
            Toast.makeText(this, "이 파일은 공유할 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchPicker() {
        try {
            openDocument.launch(MainActivity.PICKER_MIME_TYPES)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- 기타

    private fun addToRecents(info: DocumentInfo, type: String) {
        if (info.uri.scheme != "content" && info.uri.scheme != "file") return
        recents.add(RecentDocument(info.uri, info.displayName, type, System.currentTimeMillis()))
    }

    private fun persistPermission(uri: Uri, flags: Int) {
        if (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
    }

    private fun extractUri(intent: Intent?): Uri? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> intent.data
        }
    }

    companion object {
        private const val MIN_TEXT = 12f
        private const val MAX_TEXT = 32f
        private const val SEARCH_LIMIT = 5000
        private const val CLIPBOARD_LIMIT = 300_000

        fun intent(context: android.content.Context, uri: Uri): Intent =
            Intent(context, ViewerActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
