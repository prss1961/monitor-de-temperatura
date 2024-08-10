package com.tcc.leitordesensoresdetemperatura.activities;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;

import com.tcc.leitordesensoresdetemperatura.R;
import com.tcc.leitordesensoresdetemperatura.fragments.TelaPrincipal;
import com.tcc.leitordesensoresdetemperatura.fragments.TelaTestes;

import java.util.Objects;

public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getSupportFragmentManager().addOnBackStackChangedListener(this);
       if (savedInstanceState == null)
            getSupportFragmentManager().beginTransaction().add(R.id.container,  new TelaPrincipal()).commit();
        else
            onBackStackChanged();
    }

    @Override
    public void onBackStackChanged() {
        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(getSupportFragmentManager().getBackStackEntryCount()>0);
    }

    @Override
    public boolean onSupportNavigateUp() {
        //noinspection deprecation
        onBackPressed();
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction())) {
            TelaTestes terminal = (TelaTestes)getSupportFragmentManager().findFragmentByTag("terminal");
            if (terminal != null)
                terminal.status("Dispositivo USB detectado");
        }
        super.onNewIntent(intent);
    }

}