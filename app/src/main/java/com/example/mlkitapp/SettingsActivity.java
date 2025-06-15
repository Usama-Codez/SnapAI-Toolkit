package com.example.mlkitapp;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import android.widget.CompoundButton;
import android.widget.Toast;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public class SettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setTitle("Settings");
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        SwitchCompat switchDarkMode = findViewById(R.id.switch_dark_mode);
        SwitchCompat switchNotifications = findViewById(R.id.switch_notifications);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        switchDarkMode.setChecked(prefs.getBoolean("dark_mode", false));
        switchNotifications.setChecked(prefs.getBoolean("notifications", true));

        switchDarkMode.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("dark_mode", isChecked).apply();
                Toast.makeText(SettingsActivity.this, isChecked ? "Dark mode enabled" : "Dark mode disabled", Toast.LENGTH_SHORT).show();
                // You can add code to apply dark mode here
            }
        });
        switchNotifications.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("notifications", isChecked).apply();
                Toast.makeText(SettingsActivity.this, isChecked ? "Notifications enabled" : "Notifications disabled", Toast.LENGTH_SHORT).show();
            }
        });
    }
    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}