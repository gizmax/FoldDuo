package cz.pflanzer.foldduo.test;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.os.Bundle;
import android.util.Log;

public final class ProfileFixtureActivity extends android.app.Activity {
    public static final String ENABLE_WIDGETS = "cz.pflanzer.foldduo.test.ENABLE_CROSS_PROFILE_WIDGETS";
    public static final String WIDGET_PACKAGE = "widgetPackage";
    public static final String RESULT_TOKEN = "resultToken";
    private static final String TAG = "DuoProfilePolicy";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String token = getIntent().getStringExtra(RESULT_TOKEN);
        String widgetPackage = getIntent().getStringExtra(WIDGET_PACKAGE);
        try {
            if (!ENABLE_WIDGETS.equals(getIntent().getAction())) {
                throw new IllegalArgumentException("Unexpected action " + getIntent().getAction());
            }
            DevicePolicyManager policy = getSystemService(DevicePolicyManager.class);
            ComponentName admin = new ComponentName(this, ProfileAdminReceiver.class);
            if (widgetPackage == null || widgetPackage.isEmpty()) {
                throw new IllegalArgumentException("Missing managed-profile widget package");
            }
            java.util.List<String> before = policy.getCrossProfileWidgetProviders(admin);
            boolean owner = policy.isProfileOwnerApp(getPackageName());
            boolean added = before.contains(widgetPackage) ||
                    policy.addCrossProfileWidgetProvider(admin, widgetPackage);
            java.util.List<String> after = policy.getCrossProfileWidgetProviders(admin);
            Log.i(TAG, "token=" + token + " package=" + getPackageName() +
                    " action=" + getIntent().getAction() + " owner=" + owner +
                    " requested=" + widgetPackage + " before=" + before +
                    " addResult=" + added + " after=" + after);
            if (!owner || !added || !after.contains(widgetPackage)) {
                throw new IllegalStateException("Could not allow the managed-profile widget fixture");
            }
        } catch (Throwable error) {
            Log.e(TAG, "token=" + token + " package=" + getPackageName() +
                    " action=" + getIntent().getAction() + " requested=" + widgetPackage +
                    " error=" + error, error);
        } finally {
            finish();
        }
    }
}
