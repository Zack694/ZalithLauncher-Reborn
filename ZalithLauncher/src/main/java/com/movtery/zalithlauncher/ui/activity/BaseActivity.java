package com.movtery.zalithlauncher.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.movtery.zalithlauncher.context.ContextExecutor;
import com.movtery.zalithlauncher.context.LocaleHelper;
import com.movtery.zalithlauncher.event.single.LauncherIgnoreNotchEvent;
import com.movtery.zalithlauncher.feature.accounts.AccountsManager;
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathManager;
import com.movtery.zalithlauncher.plugins.PluginLoader;
import com.movtery.zalithlauncher.renderer.Renderers;
import com.movtery.zalithlauncher.setting.AllSettings;
import com.movtery.zalithlauncher.utils.StoragePermissionsUtils;

import net.kdt.pojavlaunch.MissingStorageActivity;
import net.kdt.pojavlaunch.Tools;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

public abstract class BaseActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.Companion.setLocale(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LocaleHelper.Companion.setLocale(this);
        Tools.setFullscreen(this);
        Tools.updateWindowSize(this);

        refreshStoragePermissions();
        // Load built-in renderers.
        Renderers.INSTANCE.init(false);
        // Load plugins.
        PluginLoader.loadAllPlugins(this, false);
        // Refresh the game path list.
        ProfilePathManager.INSTANCE.refreshPath();
    }

    @Override
    protected void onResume() {
        super.onResume();

        ContextExecutor.setActivity(this);

        if (!Tools.checkStorageRoot()) {
            startActivity(new Intent(this, MissingStorageActivity.class));
            finish();
            return;
        }

        refreshStoragePermissions();

        if (!(this instanceof net.kdt.pojavlaunch.MainActivity)) {
            Renderers.INSTANCE.init(true);
            PluginLoader.loadAllPlugins(this, true);
            ProfilePathManager.INSTANCE.refreshPath();
            AccountsManager.INSTANCE.reload();
        }
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        Tools.setFullscreen(this);
        Tools.ignoreNotch(shouldIgnoreNotch(), this);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Tools.getDisplayMetrics(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }

    @Subscribe
    public void event(LauncherIgnoreNotchEvent event) {
        Tools.ignoreNotch(shouldIgnoreNotch(), this);
    }

    /**
     * @return whether the notch should be ignored
     */
    public boolean shouldIgnoreNotch() {
        return AllSettings.getIgnoreNotchLauncher().getValue();
    }

    private void refreshStoragePermissions() {
        StoragePermissionsUtils.refreshPermissions(this);
    }
}