package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

import de.danoeh.antennapod.storage.preferences.UserPreferences;

import org.shiurcleanup.denoiser.GtcrnAudioProcessor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Shiur Player fork — {@link DefaultRenderersFactory} subclass that inserts a
 * {@link GtcrnAudioProcessor} into the ExoPlayer audio sink chain.
 *
 * <p>The denoiser only accepts 16 kHz mono 16-bit PCM. For sources that don't match that format
 * (stereo music podcasts, 44.1 / 48 kHz lecture recordings, etc.), the processor's
 * {@code onConfigure} throws and Media3 omits it from the chain — audio plays untouched.
 *
 * <p>TODO (v1.1): add a resampler-plus-downmix processor in front of the denoiser so cleanup
 * works on non-16 kHz sources too. For now, v1 expects podcast sources to already be voice-format
 * (which most shiur feeds on YUTorah are).
 */
@UnstableApi
public final class ShiurRenderersFactory extends DefaultRenderersFactory {
    private static final String TAG = "ShiurRenderersFactory";
    private static final String MODEL_ASSET = "gtcrn_simple.onnx";

    @Nullable
    private byte[] modelBytes;

    public ShiurRenderersFactory(Context context) {
        super(context);
    }

    @Nullable
    @Override
    protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput,
                                       boolean enableAudioTrackPlaybackParams) {
        byte[] bytes = getOrLoadModelBytes(context);
        if (bytes == null) {
            // Asset missing or IO error — fall back to the default sink so playback still works.
            Log.w(TAG, "GTCRN model asset not available; falling back to default audio sink");
            return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams);
        }
        GtcrnAudioProcessor denoiser = new GtcrnAudioProcessor(
                bytes, UserPreferences::isShiurCleanupEnabled);
        return new DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(new AudioProcessor[]{denoiser})
                .build();
    }

    @Nullable
    private synchronized byte[] getOrLoadModelBytes(Context context) {
        if (modelBytes != null) {
            return modelBytes;
        }
        try (
                InputStream in = context.getAssets().open(MODEL_ASSET);
                ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 19)
        ) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            modelBytes = out.toByteArray();
            Log.i(TAG, "Loaded GTCRN model, " + modelBytes.length + " bytes");
            return modelBytes;
        } catch (IOException e) {
            Log.e(TAG, "Failed to load GTCRN model from assets", e);
            return null;
        }
    }
}
