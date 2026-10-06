package de.danoeh.antennapod.playback.base;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.bumptech.glide.Glide;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class ArtworkProvider extends ContentProvider {
    private static final String TAG = "ArtworkProvider";
    private static final String AUTHORITY_SUFFIX = ".artwork";
    private static final String PARAM_URL = "url";
    private static final Set<String> allowedUrls = ConcurrentHashMap.newKeySet();

    public static Uri toContentUri(Context context, String url) {
        allowedUrls.add(url);
        return new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(context.getPackageName() + AUTHORITY_SUFFIX)
                .appendQueryParameter(PARAM_URL, url)
                .build();
    }

    public static Uri toRemoteUri(Uri uri) {
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                && uri.getAuthority() != null && uri.getAuthority().endsWith(AUTHORITY_SUFFIX)) {
            String url = uri.getQueryParameter(PARAM_URL);
            if (url != null) {
                return Uri.parse(url);
            }
        }
        return uri;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        String url = uri.getQueryParameter(PARAM_URL);
        if (url == null || !allowedUrls.contains(url)) {
            throw new FileNotFoundException(uri.toString());
        }
        try {
            File file = Glide.with(getContext()).downloadOnly().load(url).submit().get(10, TimeUnit.SECONDS);
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (Exception e) {
            Log.e(TAG, "Unable to load artwork: " + e.getMessage());
            throw new FileNotFoundException(uri.toString());
        }
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return "image/*";
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        return 0;
    }
}
