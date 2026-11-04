package androidx.preference;

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.HashSet;
import java.util.Set;

public class MultiSelectListPreference extends DialogPreference {
    // reference: https://android.googlesource.com/platform/frameworks/support/+/996971f962fcd554339a7cb2859cef9ca89dbcb7/preference/preference/src/main/java/androidx/preference/MultiSelectListPreference.java
    // Note: remove @JsonIgnore and implement methods if any extension ever uses these methods or the variables behind them

    private CharSequence[] entries;
    private CharSequence[] entryValues;

    public MultiSelectListPreference(Context context) {
        super(context);
    }

    public void setEntries(CharSequence[] entries) {
        this.entries = entries;
    }

    public CharSequence[] getEntries() {
        return entries;
    }

    public void setEntryValues(CharSequence[] entryValues) {
        this.entryValues = entryValues;
    }

    public CharSequence[] getEntryValues() {
        return entryValues;
    }

    // Madoyomi: the value accessors are backed by the screen's SharedPreferences, as on Android (upstream: "Stub!").
    // Before the preference is added to a screen, the values are kept in this object.
    private Set<String> values;

    @JsonIgnore
    public void setValues(Set<String> values) {
        // Madoyomi: implemented (upstream: "Stub!"); persists like Android's persistStringSet
        this.values = values == null ? null : new HashSet<>(values);
        SharedPreferences prefs = getSharedPreferences();
        if (prefs != null && getKey() != null) prefs.edit().putStringSet(getKey(), this.values).apply();
    }

    @JsonIgnore
    @SuppressWarnings("unchecked")
    public Set<String> getValues() {
        // Madoyomi: implemented (upstream: "Stub!")
        Set<String> fallback = values != null ? values
                : getDefaultValue() instanceof Set ? (Set<String>) getDefaultValue() : new HashSet<>();
        SharedPreferences prefs = getSharedPreferences();
        if (prefs == null || getKey() == null) return new HashSet<>(fallback);
        Set<String> stored = prefs.getStringSet(getKey(), fallback);
        return stored == null ? new HashSet<>() : new HashSet<>(stored);
    }

    public int findIndexOfValue(String value) {
        // Madoyomi: implemented like ListPreference.findIndexOfValue (upstream: "Stub!")
        if (value != null && entryValues != null) {
            for (int i = entryValues.length - 1; i >= 0; i--) {
                if (TextUtils.equals(entryValues[i].toString(), value)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Tachidesk specific API */
    @Override
    public String getDefaultValueType() {
        return "Set<String>";
    }
}