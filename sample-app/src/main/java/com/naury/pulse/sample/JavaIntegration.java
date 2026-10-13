package com.naury.pulse.sample;

import android.content.Context;

import com.naury.pulse.api.CaptureLimits;
import com.naury.pulse.api.CaptureMode;
import com.naury.pulse.api.Pulse;
import com.naury.pulse.api.PulseConfig;
import com.naury.pulse.api.PulseHandle;
import com.naury.pulse.api.RedactionPolicy;
import com.naury.pulse.api.RetentionPolicy;

import okhttp3.OkHttpClient;

/** Java 호스트 앱에서 쓰는 방법. debug(pulse-bundle)와 release(pulse-noop) 모두 같은 코드로 컴파일된다. */
final class JavaIntegration {
    private JavaIntegration() {
    }

    static OkHttpClient createClient(Context context) {
        PulseConfig config = new PulseConfig.Builder()
                .captureMode(CaptureMode.METADATA_AND_SAFE_BODIES)
                .limits(new CaptureLimits(64 * 1024, 256 * 1024))
                .retention(new RetentionPolicy(5_000))
                .redaction(RedactionPolicy.strict().withHeaders("X-Tenant-Id"))
                .build();
        PulseHandle pulse = Pulse.initialize(context, config);
        return new OkHttpClient.Builder()
                .addInterceptor(pulse.interceptor())
                .eventListenerFactory(pulse.eventListenerFactory())
                .build();
    }

    static boolean openInspector(Context context) {
        return Pulse.open(context);
    }
}
