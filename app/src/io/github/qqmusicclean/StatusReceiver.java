package io.github.qqmusicclean;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;

/** Receives one-way scan progress; no target-side provider lookup or startup wait. */
public final class StatusReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !"io.github.qqmusicclean.REPORT".equals(intent.getAction())) return;
        if (Build.VERSION.SDK_INT >= 34) {
            int uid = getSentFromUid();
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            boolean target = false;
            if (packages != null) for (String name : packages)
                if (Config.PACKAGE.equals(name)) { target = true; break; }
            if (!target && uid != Process.INVALID_UID) return;
        }
        if (intent.getExtras() != null) StatusProvider.record(context, intent.getExtras());
    }
}
