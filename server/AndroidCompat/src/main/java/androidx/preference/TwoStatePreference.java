package androidx.preference;

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.content.Context;
import android.content.SharedPreferences;
import com.fasterxml.jackson.annotation.JsonIgnore;

public class TwoStatePreference extends Preference {
    private CharSequence mSummaryOn;
    private CharSequence mSummaryOff;

    public TwoStatePreference(Context context) {
        super(context);
        setDefaultValue(false);
    }

    // Madoyomi: isChecked/setChecked are backed by the screen's SharedPreferences, as on Android (upstream: "Stub!").
    // Before the preference is added to a screen, the state is kept in this object.
    private Boolean checked;

    @JsonIgnore
    public boolean isChecked() {
        // Madoyomi: implemented (upstream: "Stub!")
        boolean fallback = checked != null ? checked
                : getDefaultValue() instanceof Boolean ? (Boolean) getDefaultValue() : false;
        SharedPreferences prefs = getSharedPreferences();
        if (prefs == null || getKey() == null) return fallback;
        return prefs.getBoolean(getKey(), fallback);
    }

    @JsonIgnore
    public void setChecked(boolean checked) {
        // Madoyomi: implemented (upstream: "Stub!"); persists like Android's persistBoolean
        this.checked = checked;
        SharedPreferences prefs = getSharedPreferences();
        if (prefs != null && getKey() != null) prefs.edit().putBoolean(getKey(), checked).apply();
    }

    @JsonIgnore
    public CharSequence getSummaryOn() {
        return mSummaryOn;
    }

    @JsonIgnore
    public void setSummaryOn(CharSequence summary) {
        this.mSummaryOn = summary;
    }

    @JsonIgnore
    public CharSequence getSummaryOff() {
        return mSummaryOff;
    }

    @JsonIgnore
    public void setSummaryOff(CharSequence summary) {
        this.mSummaryOff = summary;
    }

    @Override
    public CharSequence getSummary() {
        final CharSequence summary = super.getSummary();
        if (summary != null) {
            return summary;
        }

        final boolean checked = (Boolean) getCurrentValue();
        if (checked && mSummaryOn != null) {
            return mSummaryOn;
        } else if (!checked && mSummaryOff != null) {
            return mSummaryOff;
        }

        return null;
    }

    @JsonIgnore
    public boolean getDisableDependentsState() { throw new RuntimeException("Stub!"); }

    @JsonIgnore
    public void setDisableDependentsState(boolean disableDependentsState) { throw new RuntimeException("Stub!"); }

    /** Tachidesk specific API */
    @Override
    public String getDefaultValueType() {
        return "Boolean";
    }
}