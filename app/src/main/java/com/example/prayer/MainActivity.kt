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
    private lateinit var etSinglePrayer: EditText
    private lateinit var btnAdd: Button
    private lateinit var tvPrayerList: TextView
    private lateinit var btnGenerate: Button
    private lateinit var btnDownloadZip: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    
    private val prayerList = mutableListOf<String>()
    private val generatedFiles = mutableListOf<File>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        etSinglePrayer = findViewById(R.id.etSinglePrayer)
        btnAdd = findViewById(R.id.btnAdd)
        tvPrayerList = findViewById(R.id.tvPrayerList)
        btnGenerate = findViewById(R.id.btnGenerate)
        btnDownloadZip = findViewById(R.id.btnDownloadZip)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)

        btnAdd.setOnClickListener { addPrayer() }
        btnGenerate.setOnClickListener { startGeneration() }
        btnDownloadZip.setOnClickListener { downloadZip() }
    }

    private fun addPrayer() {
        val text = etSinglePrayer.text.toString().trim()
        if (text.isNotBlank()) {
            prayerList.add(text)
            etSinglePrayer.text.clear()
            updatePrayerListDisplay()
            tvStatus.text = "Added. Ready for the next one."
        }
    }

    private fun updatePrayerListDisplay() {
        if (prayerList.isEmpty()) {
            tvPrayerList.text = "No prayers added yet."
            btnGenerate.isEnabled = false
        } else {
            tvPrayerList.text = prayerList.mapIndexed { index, text -> "${index + 1}. $text" }.joinToString("\n")
            btnGenerate.isEnabled = true
        }
    }

    private fun startGeneration() {
        if (prayerList.isEmpty()) return

        btnAdd.isEnabled = false
        btnGenerate.isEnabled = false
        btnDownloadZip.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.max = prayerList.size
        progressBar.progress = 0
        generatedFiles.clear()

        scope.launch {
            for ((index, line) in prayerList.withIndex()) {
                val currentVideo = index + 1
                val totalVideos = prayerList.size
                val percentage = ((currentVideo.toFloat() / totalVideos) * 100).toInt()
                
                withContext(Dispatchers.Main) { 
                    tvStatus.text = "Generating video $currentVideo of $totalVideos ($percentage%)"
                    progressBar.progress = currentVideo
                }
                
                // STRICT NAMING: prayer1.mp4, prayer2.mp4, prayer3.mp4, etc.
                val outFile = File(filesDir, "prayer${currentVideo}.mp4")
                try {
                    VideoGenerator.generate(this@MainActivity, line, outFile)
                    generatedFiles.add(outFile)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { 
                        tvStatus.text = "Error on video $currentVideo: ${e.message}"
                    }
                }
            }
            
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                btnAdd.isEnabled = true
                btnGenerate.isEnabled = true
                
                if (generatedFiles.isNotEmpty()) {
                    btnDownloadZip.isEnabled = true
                    tvStatus.text = "✅ Successfully generated ${generatedFiles.size} separate videos!"
                }
            }
        }
    }

    private fun downloadZip() {
        if (generatedFiles.isEmpty()) return
        
        val zipFile = File(filesDir, "all_prayer_videos.zip")
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
        startActivity(Intent.createChooser(intent, "Save or Share ZIP File"))
    }

    override fun onDestroy() { 
        super.onDestroy()
        scope.cancel() 
    }
}
