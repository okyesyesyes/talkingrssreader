/*
 * Copyright (C) 2008 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.tts;

import android.util.Log;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;


/**
 * The SpeechSynthesis class provides a high-level api to create and play
 * synthesized speech.
 * 
 * @author dmazzoni@gmail.com (Dominic Mazzoni)
 */
@SuppressWarnings("unused")
public class SpeechSynthesis {

  //
  // External API
  //

  /**
   * Constructor; pass a language code such as "en" for English.
   */
  public SpeechSynthesis(String nativeSoLib) {
    int sdk_version = 0;
    Log.i("SpeechSynthesis setup", "sdk: " + android.os.Build.VERSION.SDK);
    try {
      sdk_version = Integer.parseInt(android.os.Build.VERSION.SDK);
    } catch (NumberFormatException e) { }
    native_setup(new WeakReference<SpeechSynthesis>(this), nativeSoLib, sdk_version);
  }

  /**
   * Stops and clears the AudioTrack.
   */
  public native final void stop();

  /**
   * Synthesize speech and speak it directly using AudioTrack.
   */
  public native final void speak(String text, boolean isSsml);

  /**
   * Synthesize speech to a file. The current implementation writes a valid WAV
   * file to the given path, assuming it is writable. Something like
   * "/sdcard/???.wav" is recommended.
   */
  public native final void synthesizeToFile(String text, String filename);

  /**
   * Sets the language
   */
  public native final void setLanguage(String language);

  /**
   * Sets the speech rate
   */
  public native final void setSpeechRate(int speechRate);
  

  /**
   * Gets the currently set language
   */
  public native final String getLanguage();

  /**
   * Gets the currently set rate
   */
  public native final int getRate();

  /**
   * Shutsdown the native synthesizer
   */
  public native final void shutdown();

  //
  // Internal
  //

  static {
    System.loadLibrary("speechsynthesis");
  }

  private final static String TAG = "SpeechSynthesis";

  private int mNativeContext; // accessed by native methods

  private native final void native_setup(Object weak_this, String nativeSoLib, int sdk_version);

  private native final void native_finalize();

  protected void finalize() {
    native_finalize();
  }
  
  /**
   * Callback from the C layer
   */
  @SuppressWarnings("unused")
  private static void synthesisCompleted(Object tts_ref) {
	  //Log.i("TTS plugin debug", "java synthesisCompleted");
    // Is the weak ref really needed?
	  SpeechSynthesis nativeTTS = (SpeechSynthesis)((WeakReference)tts_ref).get();
    nativeTTS.onSynthesisCompleted();
  }

  protected void onSynthesisCompleted() {
  }
}
