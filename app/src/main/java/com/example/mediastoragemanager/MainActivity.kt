package com.example.mediastoragemanager

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.mediastoragemanager.databinding.ActivityMainBinding
import com.example.mediastoragemanager.ui.main.MainFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(binding.fragmentContainer.id, MainFragment())
                .commit()
        }
    }
}
