package de.danoeh.antennapod.automotive;

import android.content.Context;
import android.util.Log;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.model.download.DownloadError;
import de.danoeh.antennapod.model.download.DownloadRequest;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.net.common.UrlChecker;
import de.danoeh.antennapod.net.discovery.PodcastSearcherRegistry;
import de.danoeh.antennapod.net.download.service.feed.remote.FeedParserTask;
import de.danoeh.antennapod.net.download.service.feed.remote.HttpDownloader;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadRequestCreator;
import de.danoeh.antennapod.parser.feed.FeedHandlerResult;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.FeedDatabaseWriter;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.greenrobot.eventbus.EventBus;

public class FeedSubscriber {
    private static final String TAG = "FeedSubscriber";
    private static final Set<String> subscribing = ConcurrentHashMap.newKeySet();

    private FeedSubscriber() {
    }

    public static void subscribe(Context context, String url) {
        if (!subscribing.add(url)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        Single.fromCallable(() -> subscribeBlocking(appContext, url))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> subscribing.remove(url))
                .subscribe(title -> EventBus.getDefault().post(new MessageEvent(
                        appContext.getString(R.string.automotive_subscribed_to_podcast, title))),
                        error -> {
                            Log.e(TAG, Log.getStackTraceString(error));
                            EventBus.getDefault().post(new MessageEvent(errorMessage(appContext, error, url)));
                        });
    }

    private static String subscribeBlocking(Context context, String url) throws SubscribeException {
        String feedUrl = UrlChecker.prepareUrl(PodcastSearcherRegistry.lookupUrl(url).blockingGet());
        for (Feed feed : DBReader.getFeedList()) {
            if (feedUrl.equals(feed.getDownloadUrl())) {
                return feed.getHumanReadableIdentifier();
            }
        }

        DownloadRequest request = DownloadRequestCreator.create(new Feed(feedUrl, null))
                .withInitiatedByUser(true)
                .build();
        HttpDownloader downloader = new HttpDownloader(request);
        downloader.call();
        if (!downloader.getResult().isSuccessful()) {
            throw new SubscribeException(downloader.getResult().getReason());
        }
        FeedParserTask parserTask = new FeedParserTask(request);
        FeedHandlerResult result = parserTask.call();
        if (result == null) {
            throw new SubscribeException(parserTask.getDownloadStatus().getReason());
        }
        result.feed.setLastRefreshAttempt(System.currentTimeMillis());
        FeedDatabaseWriter.updateFeed(context, result.feed, false);
        return result.feed.getHumanReadableIdentifier();
    }

    private static String errorMessage(Context context, Throwable error, String url) {
        if (error instanceof SubscribeException) {
            switch (((SubscribeException) error).reason) {
                case ERROR_UNSUPPORTED_TYPE_HTML:
                    return context.getString(R.string.download_error_unsupported_type_html_manual);
                case ERROR_UNSUPPORTED_TYPE:
                    return context.getString(R.string.download_error_unsupported_type);
                case ERROR_PARSER_EXCEPTION:
                    return context.getString(R.string.download_error_parser_exception);
                case ERROR_UNAUTHORIZED:
                    return context.getString(R.string.download_error_unauthorized);
                case ERROR_UNKNOWN_HOST:
                    return context.getString(R.string.download_error_unknown_host);
                default:
                    break;
            }
        }
        return context.getString(R.string.download_error_not_retrying, url);
    }

    private static class SubscribeException extends Exception {
        private final DownloadError reason;

        SubscribeException(DownloadError reason) {
            super(reason.name());
            this.reason = reason;
        }
    }
}
