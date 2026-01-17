package com.crosscheck.app

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.about_title)

        val asciiArt = findViewById<TextView>(R.id.asciiArtText)
        asciiArt.text = """

                   _.-'''''-._
                 .'  _     _  '.
                /   (_)   (_)   \
               |                 |
               |      \._./.     |
               |    Claude AI    |
                \      '-'      /
                 '.           .'
                   '-._____.-'

        """.trimIndent()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
