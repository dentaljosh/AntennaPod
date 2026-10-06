package de.danoeh.antennapod.automotive;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceFragmentCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.danoeh.antennapod.net.common.UrlChecker;
import de.danoeh.antennapod.net.download.serviceinterface.FeedUpdateManager;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.common.databinding.EditTextDialogBinding;
import de.danoeh.antennapod.ui.preferences.screen.synchronization.SynchronizationPreferencesFragment;

public class AutomotiveSettingsFragment extends PreferenceFragmentCompat
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String PREF_SEARCH = "prefAutomotiveSearch";
    private static final String PREF_ADD_URL = "prefAutomotiveAddUrl";
    private static final String PREF_REFRESH = "prefAutomotiveRefresh";
    private static final String PREF_SCREEN_SYNCHRONIZATION = "prefScreenSynchronization";
    private Context sharedPreferencesContext;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.preferences_automotive);
        findPreference(PREF_SEARCH).setOnPreferenceClickListener(preference -> {
            ((AutomotiveSettingsActivity) requireActivity()).openScreen(new PodcastSearchFragment());
            return true;
        });
        findPreference(PREF_ADD_URL).setOnPreferenceClickListener(preference -> {
            showAddUrlDialog();
            return true;
        });
        findPreference(PREF_REFRESH).setOnPreferenceClickListener(preference -> {
            FeedUpdateManager.getInstance().runOnceOrAsk(requireActivity());
            return true;
        });
        findPreference(PREF_SCREEN_SYNCHRONIZATION).setOnPreferenceClickListener(preference -> {
            ((AutomotiveSettingsActivity) requireActivity()).openScreen(new SynchronizationPreferencesFragment());
            return true;
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        ((AppCompatActivity) requireActivity()).getSupportActionBar().setTitle(R.string.settings_label);
        sharedPreferencesContext = requireContext().getApplicationContext();
        getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (UserPreferences.PREF_MOBILE_UPDATE.equals(key)) {
            FeedUpdateManager.getInstance().restartUpdateAlarm(sharedPreferencesContext, true);
        }
    }

    private void showAddUrlDialog() {
        Context context = requireContext();
        EditTextDialogBinding dialogBinding = EditTextDialogBinding.inflate(getLayoutInflater());
        dialogBinding.textInput.setHint(R.string.rss_address);
        dialogBinding.textInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.add_podcast_by_url)
                .setView(dialogBinding.getRoot())
                .setPositiveButton(R.string.subscribe_label, null)
                .setNegativeButton(R.string.cancel_label, null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String url = dialogBinding.textInput.getText().toString().trim();
            if (!UrlChecker.isValidUrl(url)) {
                dialogBinding.textInputLayout.setError(getText(R.string.rss_address_invalid));
                return;
            }
            FeedSubscriber.subscribe(context, url);
            dialog.dismiss();
        });
    }
}
