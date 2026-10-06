package de.danoeh.antennapod.automotive;

import android.os.Bundle;
import android.view.MenuItem;
import androidx.appcompat.app.ActionBar;
import androidx.fragment.app.Fragment;
import com.google.android.material.snackbar.Snackbar;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.ui.common.Keyboard;
import de.danoeh.antennapod.ui.common.ToolbarActivity;
import de.danoeh.antennapod.ui.preferences.databinding.SettingsActivityBinding;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class AutomotiveSettingsActivity extends ToolbarActivity {
    private SettingsActivityBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
        }
        binding = SettingsActivityBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(binding.settingsContainer.getId(), new AutomotiveSettingsFragment())
                    .commit();
        }
    }

    public void openScreen(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(binding.settingsContainer.getId(), fragment)
                .addToBackStack(null)
                .commit();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
                finish();
            } else {
                Keyboard.hide(this);
                getSupportFragmentManager().popBackStack();
            }
            return true;
        }
        return false;
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

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventMainThread(MessageEvent event) {
        Snackbar.make(binding.getRoot(), event.message, Snackbar.LENGTH_LONG).show();
    }
}
