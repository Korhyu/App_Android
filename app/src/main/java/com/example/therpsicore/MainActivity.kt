package com.example.therpsicore

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val button_prueba = findViewById<Button>(R.id.button_prueba)

        button_prueba.setOnClickListener{
            val intent = Intent(this, AudioActivity::class.java)
            startActivity(intent)
        }


    }
}