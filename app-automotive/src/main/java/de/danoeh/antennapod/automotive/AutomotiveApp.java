package de.danoeh.antennapod.automotive;

import android.app.Application;
import android.content.pm.PackageManager;
import android.util.Log;
import androidx.preference.PreferenceManager;
import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import de.danoeh.antennapod.net.common.NetworkUtils;
import de.danoeh.antennapod.net.common.UserAgentInterceptor;
import de.danoeh.antennapod.net.download.service.episode.autodownload.AutoDownloadManagerImpl;
import de.danoeh.antennapod.net.download.service.feed.DownloadServiceInterfaceImpl;
import de.danoeh.antennapod.net.download.service.feed.FeedUpdateManagerImpl;
import de.danoeh.antennapod.net.download.serviceinterface.AutoDownloadManager;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterface;
import de.danoeh.antennapod.net.download.serviceinterface.FeedUpdateManager;
import de.danoeh.antennapod.net.ssl.SslProviderInstaller;
import de.danoeh.antennapod.net.sync.service.SynchronizationQueueImpl;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueue;
import de.danoeh.antennapod.storage.database.PodDBAdapter;
import de.danoeh.antennapod.storage.databasemaintenanceservice.DatabaseMaintenanceWorker;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.SleepTimerPreferences;
import de.danoeh.antennapod.storage.preferences.SynchronizationCredentials;
import de.danoeh.antennapod.storage.preferences.SynchronizationSettings;
import de.danoeh.antennapod.storage.preferences.UsageStatistics;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.notifications.NotificationUtils;
import io.reactivex.rxjava3.exceptions.UndeliverableException;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import java.io.File;
import org.greenrobot.eventbus.EventBus;

public class AutomotiveApp extends Application {
    private static final String TAG = "AutomotiveApp";

    @Override
    public void onCreate() {
        super.onCreate();
        RxJavaPlugins.setErrorHandler(exception -> {
            if (exception instanceof UndeliverableException) {
                Log.d(TAG, "Ignored exception: " + Log.getStackTraceString(exception));
                return;
            }
            Log.e(TAG, Log.getStackTraceString(exception));
        });
        EventBus.builder()
                .logNoSubscriberMessages(false)
                .sendNoSubscriberEvent(false)
                .installDefaultEventBus();

        try {
            UserAgentInterceptor.USER_AGENT = "AntennaPod/"
                    + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
        PodDBAdapter.init(this);
        UserPreferences.init(this);
        SynchronizationCredentials.init(this);
        SynchronizationSettings.init(this);
        UsageStatistics.init(this);
        PlaybackPreferences.init(this);
        SslProviderInstaller.install(this);
        NetworkUtils.init(this);
        DownloadServiceInterface.setImpl(new DownloadServiceInterfaceImpl());
        FeedUpdateManager.setInstance(new FeedUpdateManagerImpl());
        AutoDownloadManager.setInstance(new AutoDownloadManagerImpl());
        SynchronizationQueue.setInstance(new SynchronizationQueueImpl(this));
        AntennapodHttpClient.setCacheDirectory(new File(getCacheDir(), "okhttp"));
        AntennapodHttpClient.setProxyConfig(UserPreferences.getProxyConfig());
        SleepTimerPreferences.init(this);
        NotificationUtils.createChannels(this);

        if (!PreferenceManager.getDefaultSharedPreferences(this).contains(UserPreferences.PREF_MOBILE_UPDATE)) {
            UserPreferences.setAllowMobileFeedRefresh(true);
            UserPreferences.setAllowMobileStreaming(true);
            UserPreferences.setAllowMobileSync(true);
        }

        FeedUpdateManager.getInstance().restartUpdateAlarm(this, false);
        SynchronizationQueue.getInstance().syncIfNotSyncedRecently();
        DatabaseMaintenanceWorker.enqueueIfNeeded(this);
    }
}
