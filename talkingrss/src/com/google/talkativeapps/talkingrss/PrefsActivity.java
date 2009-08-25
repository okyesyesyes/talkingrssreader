/*
 * Copyright (C) 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

/**
 * Talking RSS Reader.
 *
 * @author sdoyon@google.com (Stephane Doyon)
 */

package com.google.talkativeapps.talkingrss;

import android.content.Intent;
import android.app.Activity;
import android.os.Bundle;
import android.content.Context;
import android.util.Config;
import android.util.Log;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.preference.ListPreference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;

/* Activity to configure settings, currently only the speech rate.
 *
 * TODO: Speech language would belong here too, but: A) the french
 * accents are mishandled by this espeak for some reason, and B) need
 * to either change language only for article body or use
 * appropriately localized spoken messages.
 * Ideally I'd want to configure a different language per feed.
 */

public class PrefsActivity extends PreferenceActivity {
  private static final String TAG = "talkingrss-prefs";

  @Override
  public void onCreate(Bundle savedInstanceState) {
    if (Config.LOGD) Log.d(TAG, "onCreate");
    super.onCreate(savedInstanceState);
    addPreferencesFromResource(R.xml.prefs);
  }

  @Override
  protected void onResume() {
    if (Config.LOGD) Log.d(TAG, "onResume");
    super.onResume();
    Core.announceKeyguard();
  }

  @Override
  protected void onPause() {
    if (Config.LOGD) Log.d(TAG, "onPause");
    super.onPause();
    // Apply setting change.
    SharedPreferences prefs
        = PreferenceManager.getDefaultSharedPreferences(this);
    int rate = Integer.parseInt(prefs.getString("rate_pref", "140"));
    Core.tts.setSpeechRate(rate);
  }
}
