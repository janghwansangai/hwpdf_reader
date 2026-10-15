package com.fastreader.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fastreader.app.data.RecentDocument
import com.fastreader.app.data.RecentDocuments
import com.fastreader.app.databinding.ActivityMainBinding
import com.fastreader.app.databinding.ItemRecentBinding
import com.fastreader.app.viewer.DocumentOpener
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 홈 화면 (디자인 B안): 최근 문서 카드 + 하단 '파일 열기' 버튼 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var recents: RecentDocuments
    private val adapter = RecentAdapter()

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                // 앱을 다시 켜도 최근 문서에서 바로 열 수 있도록 영구 권한을 받는다
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
            openViewer(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        applySystemBarInsets(b.root)
        recents = RecentDocuments(this)
        if (savedInstanceState == null) DocumentOpener.cleanCache(this)

        val span = if (resources.configuration.screenWidthDp >= 600) 4 else 2
        b.recentList.layoutManager = GridLayoutManager(this, span)
        b.recentList.adapter = adapter
        b.openButton.setOnClickListener { launchPicker() }
        b.clearRecent.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.clear_recent_q)
                .setPositiveButton(R.string.clear_recent) { _, _ ->
                    recents.list().forEach { releasePermission(it.uri) }
                    recents.clear()
                    refresh()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val items = recents.list()
        adapter.submit(items)
        b.emptyRecent.isVisible = items.isEmpty()
        b.clearRecent.isVisible = items.isNotEmpty()
        b.recentSummary.text = if (items.isEmpty()) getString(R.string.recent_summary_empty)
        else getString(R.string.recent_summary, items.size)
    }

    private fun launchPicker() {
        try {
            openDocument.launch(PICKER_MIME_TYPES)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_LONG).show()
        }
    }

    private fun openViewer(uri: Uri) {
        startActivity(ViewerActivity.intent(this, uri))
    }

    private fun confirmRemove(doc: RecentDocument) {
        MaterialAlertDialogBuilder(this)
            .setTitle(doc.name)
            .setMessage(R.string.remove_recent_q)
            .setPositiveButton(R.string.remove) { _, _ ->
                releasePermission(doc.uri)
                recents.remove(doc.uri)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun releasePermission(uri: Uri) {
        try {
            contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
    }

    private inner class RecentAdapter : RecyclerView.Adapter<RecentAdapter.Holder>() {
        private var items: List<RecentDocument> = emptyList()

        inner class Holder(val v: ItemRecentBinding) : RecyclerView.ViewHolder(v.root)

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<RecentDocument>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemRecentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val doc = items[position]
            val v = holder.v
            v.name.text = doc.name.substringBeforeLast('.').ifBlank { doc.name }
            v.subtitle.text = DateUtils.getRelativeTimeSpanString(
                doc.openedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            )
            v.badge.text = doc.type
            val color = when (doc.type) {
                "PDF" -> R.color.badge_pdf
                "HWPX" -> R.color.badge_hwpx
                else -> R.color.badge_hwp
            }
            v.badge.background.mutate().setTint(ContextCompat.getColor(this@MainActivity, color))
            v.root.setOnClickListener { openViewer(doc.uri) }
            v.root.setOnLongClickListener {
                confirmRemove(doc)
                true
            }
        }
    }

    companion object {
        /** 파일 선택기에 표시할 형식. 한글 파일은 기기마다 MIME 이 제각각이라 octet-stream 도 포함 */
        val PICKER_MIME_TYPES = arrayOf(
            "application/pdf",
            "application/x-hwp",
            "application/hwp",
            "application/haansofthwp",
            "application/vnd.hancom.hwp",
            "application/x-hwpx",
            "application/hwp+zip",
            "application/haansofthwpx",
            "application/vnd.hancom.hwpx",
            "application/octet-stream",
            "application/zip",
        )
    }
}
