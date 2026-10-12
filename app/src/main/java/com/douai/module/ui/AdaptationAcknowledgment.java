package com.douai.module.ui;

import android.content.Context;
import android.content.SharedPreferences;

/** Keeps scan confirmation separate from user feature preferences. */
final class AdaptationAcknowledgment {
    static final String PREFERENCES = "douai_adaptation_acknowledgment";
    private static final String CONFIRMED_IDENTITY = "confirmed_identity";
    private final SharedPreferences preferences;

    AdaptationAcknowledgment(Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    AdaptationAcknowledgment(SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("Preferences are required");
        this.preferences = preferences;
    }

    boolean isAcknowledged(String identity) {
        return valid(identity) && identity.equals(preferences.getString(CONFIRMED_IDENTITY, ""));
    }

    boolean acknowledge(String identity) {
        if (!valid(identity)) return false;
        String previous = preferences.getString(CONFIRMED_IDENTITY, "");
        if (preferences.edit().putString(CONFIRMED_IDENTITY, identity).commit()) return true;
        // SharedPreferences changes memory even when its disk commit fails.
        SharedPreferences.Editor restore = preferences.edit();
        if (valid(previous)) restore.putString(CONFIRMED_IDENTITY, previous);
        else restore.remove(CONFIRMED_IDENTITY);
        restore.commit();
        return false;
    }

    void forget(String identity) {
        if (isAcknowledged(identity)) preferences.edit().remove(CONFIRMED_IDENTITY).commit();
    }

    private static boolean valid(String identity) {
        return identity != null && !identity.isEmpty();
    }
}
