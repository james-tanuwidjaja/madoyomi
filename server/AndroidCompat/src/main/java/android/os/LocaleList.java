package android.os;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.icu.util.ULocale;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Madoyomi: android.os.LocaleList on top of java.util.Locale (android-jar only has "Stub!" bodies, and every
 * Keiyoushi extension of the Phase 2 compat scan calls getDefault/get/size, e.g. to format dates). Replaces the
 * android-jar class: AndroidCompat's build leaves it out of the android-jar it uses (see server/UPSTREAM.md).
 * The public API is android-jar's.
 */
public final class LocaleList implements Parcelable {
    private static final LocaleList EMPTY = new LocaleList();

    public static final Parcelable.Creator<LocaleList> CREATOR = new Parcelable.Creator<LocaleList>() {
        @Override
        public LocaleList createFromParcel(Parcel source) {
            return forLanguageTags(source.readString());
        }

        @Override
        public LocaleList[] newArray(int size) {
            return new LocaleList[size];
        }
    };

    private static volatile LocaleList defaultList;

    private final Locale[] list;

    public LocaleList(Locale... list) {
        if (list == null) throw new NullPointerException("list is null");
        LinkedHashSet<Locale> seen = new LinkedHashSet<>();
        for (Locale locale : list) {
            if (locale == null) throw new NullPointerException("list contains a null locale");
            if (!seen.add(locale)) throw new IllegalArgumentException("list contains a duplicate locale: " + locale);
        }
        this.list = list.clone();
    }

    public Locale get(int index) {
        return index >= 0 && index < list.length ? list[index] : null;
    }

    public boolean isEmpty() {
        return list.length == 0;
    }

    public int size() {
        return list.length;
    }

    public int indexOf(Locale locale) {
        for (int i = 0; i < list.length; i++) {
            if (list[i].equals(locale)) return i;
        }
        return -1;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LocaleList && Arrays.equals(list, ((LocaleList) other).list);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(list);
    }

    @Override
    public String toString() {
        return "[" + toLanguageTags() + "]";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int parcelableFlags) {
        dest.writeString(toLanguageTags());
    }

    public String toLanguageTags() {
        StringBuilder tags = new StringBuilder();
        for (int i = 0; i < list.length; i++) {
            if (i > 0) tags.append(',');
            tags.append(list[i].toLanguageTag());
        }
        return tags.toString();
    }

    public static LocaleList getEmptyLocaleList() {
        return EMPTY;
    }

    public static LocaleList forLanguageTags(String list) {
        if (list == null || list.isEmpty()) return EMPTY;
        List<Locale> locales = new ArrayList<>();
        for (String tag : list.split(",")) {
            Locale locale = Locale.forLanguageTag(tag.trim());
            if (!locales.contains(locale)) locales.add(locale);
        }
        return new LocaleList(locales.toArray(new Locale[0]));
    }

    /** The pseudo-locales en-XA and ar-XB (Android's rule). */
    public static boolean isPseudoLocale(ULocale locale) {
        if (locale == null) return false;
        String tag = locale.toLanguageTag();
        return "en-XA".equals(tag) || "ar-XB".equals(tag);
    }

    /** The first of this list's locales whose language is in supportedLocales, else the first locale. */
    public Locale getFirstMatch(String[] supportedLocales) {
        if (list.length == 0) return null;
        if (supportedLocales != null) {
            for (Locale locale : list) {
                for (String supported : supportedLocales) {
                    Locale candidate = Locale.forLanguageTag(supported);
                    if (candidate.getLanguage().equals(locale.getLanguage())) return candidate;
                }
            }
        }
        return list[0];
    }

    /** The JVM's default locale (or what {@link #setDefault} set). */
    public static LocaleList getDefault() {
        LocaleList set = defaultList;
        if (set != null) return set;
        return new LocaleList(Locale.getDefault());
    }

    public static LocaleList getAdjustedDefault() {
        return getDefault();
    }

    /** Sets the default list; its first locale becomes the JVM's default locale, as on Android. */
    public static void setDefault(LocaleList locales) {
        if (locales == null) throw new NullPointerException("locales is null");
        if (locales.isEmpty()) throw new IllegalArgumentException("locales is empty");
        defaultList = locales;
        Locale.setDefault(locales.get(0));
    }
}
