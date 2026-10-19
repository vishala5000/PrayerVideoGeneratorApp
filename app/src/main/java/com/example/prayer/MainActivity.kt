package com.example.prayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    private lateinit var etPrayers: EditText
    private lateinit var btnGenerate: Button
    private lateinit var btnDownloadZip: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private val generatedFiles = mutableListOf<File>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        etPrayers = findViewById(R.id.etPrayers)
        btnGenerate = findViewById(R.id.btnGenerate)
        btnDownloadZip = findViewById(R.id.btnDownloadZip)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)

        btnGenerate.setOnClickListener { startGeneration() }
        btnDownloadZip.setOnClickListener { downloadZip() }
    }

    private fun startGeneration() {
        val lines = etPrayers.text.toString().split("\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) { tvStatus.text = "Please enter at least one prayer line."; return }

        btnGenerate.isEnabled = false
        btnDownloadZip.isEnabled = false
        progressBar.visibility = View.VISIBLE
        generatedFiles.clear()

        scope.launch {
            for ((index, line) in lines.withIndex()) {
                withContext(Dispatchers.Main) { tvStatus.text = "Generating video ${index + 1} of ${lines.size}..." }
                val outFile = File(filesDir, "prayer_${index + 1}.mp4")
                try {
                    VideoGenerator.generate(this@MainActivity, line, outFile)
                    generatedFiles.add(outFile)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { tvStatus.text = "Error on video ${index + 1}: ${e.message}" }
                }
            }
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                btnGenerate.isEnabled = true
                if (generatedFiles.isNotEmpty()) {
                    btnDownloadZip.isEnabled = true
                    tvStatus.text = "Successfully generated ${generatedFiles.size} videos!"
                }
            }
        }
    }

    private fun downloadZip() {
        if (generatedFiles.isEmpty()) return
        val zipFile = File(filesDir, "prayers_videos.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            for (file in generatedFiles) {
                zos.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", zipFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Download ZIP via"))
    }

    override fun onDestroy() { super.onDestroy(); scope.cancel() }
}
