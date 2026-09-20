package io.nannyu.voicesatellite.r1.wakeword;

import android.content.Context;
import android.util.Log;

/**
 * Default factory: offline Snowboy when {@code common.res} + model are present,
 * otherwise a no-op engine (button / Simulate Wake still work).
 *
 * No AccessKey / account path. Place a Chinese {@code *.pmdl} under
 * {@code assets/snowboy/} or push into the app files dir — see docs/09-wake-word.md.
 */
public final class WakeWordEngines {
    private static final String TAG = "WakeWordEngines";

    private WakeWordEngines() {}

    public static WakeWordEngine create(Context context) {
        try {
            SnowboyAssets.Bundle bundle = SnowboyAssets.prepare(context);
            if (bundle == null) {
                Log.w(TAG, "Snowboy assets incomplete; wake engine=none");
                return new NoOpWakeWordEngine();
            }
            return new SnowboyWakeWordEngine(context, bundle);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Snowboy native load failed", e);
            return new NoOpWakeWordEngine();
        } catch (Exception e) {
            Log.e(TAG, "Snowboy init failed", e);
            return new NoOpWakeWordEngine();
        }
    }
}
