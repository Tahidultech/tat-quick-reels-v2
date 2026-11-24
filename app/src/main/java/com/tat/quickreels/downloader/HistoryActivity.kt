package com.tat.quickreels.downloader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Date

class HistoryActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(0xFF10131A.toInt())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Download History"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val clear = Button(this).apply {
            text = "CLEAR"
            setOnClickListener {
                AlertDialog.Builder(this@HistoryActivity)
                    .setTitle("Clear history?")
                    .setMessage("This removes the history list only. Downloaded files will remain on your device.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear") { _, _ ->
                        HistoryStore.clear(this@HistoryActivity)
                        render()
                    }.show()
            }
        }
        header.addView(title)
        header.addView(clear)
        root.addView(header)
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    private fun render() {
        list.removeAllViews()
        val entries = HistoryStore.getAll(this)
        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No downloads yet"
                textSize = 15f
                setTextColor(0xFF9AA4B2.toInt())
                setPadding(0, 28, 0, 0)
            })
            return
        }
        entries.forEach { entry ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 14, 14, 14)
                setBackgroundColor(0xFF202633.toInt())
            }
            card.addView(TextView(this).apply {
                text = entry.title
                textSize = 15f
                setTextColor(0xFFFFFFFF.toInt())
                maxLines = 2
            })
            card.addView(TextView(this).apply {
                val size = if (entry.sizeBytes > 0) " · ${entry.sizeBytes / 1024 / 1024} MB" else ""
                val date = if (entry.createdAt > 0) DateFormat.getDateTimeInstance().format(Date(entry.createdAt)) else ""
                text = "${entry.mediaType}$size · $date"
                textSize = 12f
                setTextColor(0xFF9AA4B2.toInt())
                setPadding(0, 5, 0, 8)
            })
            card.setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(entry.uri), if (entry.mediaType == "Audio") "audio/*" else "video/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } catch (_: Exception) {
                    Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show()
                }
            }
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 })
        }
    }
}
