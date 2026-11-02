package ru.nastya.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class DetailsActivity : AppCompatActivity() {

    companion object {
        fun intent(ctx: Context, m: Movie): Intent = Intent(ctx, DetailsActivity::class.java)
            .putExtra("title", m.title)
            .putExtra("meta", m.metaLine)
            .putExtra("note", m.note)
            .putExtra("poster", m.posterUrl)
            .putExtra("posterFile", m.posterFileName)
            .putExtra("query", m.searchQuery)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)

        findViewById<TextView>(R.id.title).text = intent.getStringExtra("title")
        findViewById<TextView>(R.id.meta).text = intent.getStringExtra("meta")
        findViewById<TextView>(R.id.note).text = intent.getStringExtra("note")
        Posters.load(findViewById(R.id.poster), intent.getStringExtra("poster"), intent.getStringExtra("posterFile"))

        val query = intent.getStringExtra("query") ?: intent.getStringExtra("title") ?: ""
        val btnLampa = findViewById<Button>(R.id.btnLampa)
        btnLampa.setOnClickListener {
            if (!Launcher.searchInLampa(this, query) && !Launcher.launchPackage(this, Launcher.LAMPA)) {
                Toast.makeText(this, "LAMPA не установлена", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        btnLampa.requestFocus()
    }
}
