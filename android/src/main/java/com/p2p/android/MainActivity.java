package com.p2p.android;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.p2p.android.ui.DownloadsFragment;
import com.p2p.android.ui.SearchFragment;
import com.p2p.android.ui.SharedFragment;
import com.p2p.android.ui.SettingsFragment;
import com.p2p.android.service.TransferService;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Start background transfer service
        Intent serviceIntent = new Intent(this, TransferService.class);
        startForegroundService(serviceIntent);

        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(item -> {
            Fragment selected = null;
            int id = item.getItemId();
            if (id == R.id.nav_search)     selected = new SearchFragment();
            else if (id == R.id.nav_downloads) selected = new DownloadsFragment();
            else if (id == R.id.nav_shared)    selected = new SharedFragment();
            else if (id == R.id.nav_settings)  selected = new SettingsFragment();

            if (selected != null) {
                getSupportFragmentManager().beginTransaction()
                        .replace(R.id.fragment_container, selected)
                        .commit();
                return true;
            }
            return false;
        });

        // Load default screen
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, new SearchFragment())
                    .commit();
            nav.setSelectedItemId(R.id.nav_search);
        }
    }
}
