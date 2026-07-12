package com.rfsat.vtb.log

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import androidx.core.content.FileProvider
import com.rfsat.vtb.databinding.ActivityLogBinding
import com.rfsat.vtb.ui.BaseActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogActivity : BaseActivity() {

    private lateinit var binding: ActivityLogBinding
    private var filter: LogLevel? = null // null = show all
    private val listener: () -> Unit = { runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupBottomNav(com.rfsat.vtb.R.id.nav_log)

        binding.btnFilterAll.setOnClickListener { filter = null; refresh() }
        binding.btnFilterInfo.setOnClickListener { filter = LogLevel.INFO; refresh() }
        binding.btnFilterWarn.setOnClickListener { filter = LogLevel.WARNING; refresh() }
        binding.btnFilterError.setOnClickListener { filter = LogLevel.ERROR; refresh() }

        refresh()
        Logger.addListener(listener)

        binding.btnSaveLog.setOnClickListener { saveLogToFile()?.let {
            Toast.makeText(this, "Saved: ${it.name}", Toast.LENGTH_SHORT).show()
        } }
        binding.btnShareLog.setOnClickListener { shareLog() }
        binding.btnClearLog.setOnClickListener { Logger.clear() }
    }

    override fun onDestroy() {
        Logger.removeListener(listener)
        super.onDestroy()
    }

    private fun colorFor(level: LogLevel): Int = when (level) {
        LogLevel.ERROR -> Color.parseColor("#FF5252")
        LogLevel.WARNING -> Color.parseColor("#FFB74D")
        LogLevel.INFO -> Color.parseColor("#9ECFA0")
    }

    private fun refresh() {
        val entries = Logger.snapshotOfLevel(filter)
        if (entries.isEmpty()) {
            binding.tvLog.text = "(no ${filter?.name?.lowercase() ?: ""} entries)"
            return
        }
        val sb = SpannableStringBuilder()
        for (e in entries) {
            val start = sb.length
            sb.append(e.toString()).append("\n")
            sb.setSpan(ForegroundColorSpan(colorFor(e.level)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        binding.tvLog.text = sb
        binding.scrollLog.post { binding.scrollLog.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun saveLogToFile(): File? {
        return try {
            val dir = File(getExternalFilesDir(null), "logs").apply { mkdirs() }
            val name = "vtb_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
            val file = File(dir, name)
            file.writeText(Logger.asText(filter))
            file
        } catch (t: Throwable) {
            Logger.e("LogActivity", "Failed to save log", t)
            null
        }
    }

    private fun shareLog() {
        val file = saveLogToFile() ?: run {
            Toast.makeText(this, "Could not save log for sharing.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share VTB log"))
    }
}
