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

import com.google.talkativeapps.talkingrss.R;

import com.google.tts.ITTS.Stub;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.TypedValue;
import android.os.Handler;
import android.os.Message;
import android.os.Bundle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;

import javax.xml.parsers.FactoryConfigurationError;

/**
 * Synthesizes speech from text. This is implemented as a service so that other
 * applications can call the TTS without needing to bundle the TTS in the build.
 * 
 * @author clchen@google.com (Charles L. Chen)
 */
public class TTSService extends Service implements OnCompletionListener {
	private class SpeechItem {
		public String text;
		public ArrayList<String> params;
		public boolean isEarcon;
    public ITTSUserCallback callback;  // user callback to call.
    public int callbackUserArg;  // argument to callback method.

    // For simplicity, there is either speech or a callback, not both.

		public SpeechItem(String text, ArrayList<String> params,
				boolean isEarcon) {
			this.text = text;
			this.params = params;
			this.isEarcon = isEarcon;
		}
		public SpeechItem(ITTSUserCallback callback, int user_arg) {
      this.callback = callback;
      this.callbackUserArg = user_arg;
    }
	}

	private static final String ACTION = "com.google.talkativeapps.talkingrss.intent.action.USE_SNAPPYTTS";
	private static final String CATEGORY = "com.google.talkativeapps.talkingrss.intent.category.SNAPPYTTS";
	private static final String PKGNAME = "com.google.talkativeapps.talkingrss";

	final RemoteCallbackList<ITTSCallback> mCallbacks = new RemoteCallbackList<ITTSCallback>();

	private TTSEngine engine;

	private boolean isSpeaking;
	private ArrayList<SpeechItem> speechQueue = new ArrayList<SpeechItem>();
	private Map<String, SoundResource> earcons
      = Collections.synchronizedMap(new HashMap<String, SoundResource>());
	private Map<String, SoundResource> utterances
      = Collections.synchronizedMap(new HashMap<String, SoundResource>());
	private MediaPlayer player;
	private TTSService self;

	private SharedPreferences prefs;
	private int speechRate = 140;
	private String language = "en-rUS";


	private SpeechSynthesis nativeSynth;

  private static final int MSG_NEW_ITEM = 1;
  private static final int MSG_RUN_QUEUE_NEXT = 2;
  private static final int MSG_STOP = 3;
  private static final int MSG_SET_ENGINE = 10;
  private static final int MSG_SET_SPEECH_RATE = 11;
  private static final int MSG_SET_LANGUAGE = 12;
  private static final int MSG_SYNTH_TO_FILE = 20;
  private volatile int stopsCount;
  private Object stopLock = new Object();
  private volatile boolean isSynthesizing;

	@Override
	public void onCreate() {
		super.onCreate();
		Log.i("TTS", "TTS starting");
		
		// This should be changed to work using preferences
		nativeSynth = new MySpeechSynthesis(
        "/data/data/com.google.talkativeapps.talkingrss/lib/libespeakengine.so");

		// android.os.Debug.waitForDebugger();
		self = this;
		isSpeaking = false;

		prefs = PreferenceManager.getDefaultSharedPreferences(this);

		player = null;

		if (espeakIsUsable()) {
			setEngine(TTSEngine.PRERECORDED_WITH_TTS);
		} else {
			setEngine(TTSEngine.PRERECORDED_ONLY);
		}

		setLanguage(prefs.getString("lang_pref", "en-rUS"));
		setSpeechRate(Integer.parseInt(prefs.getString("rate_pref", "140")));

    Log.i("TTS", "onCreate done.");
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
    Log.i("TTS", "onDestroy");

    forceStop();

		// Don't hog the media player
		cleanUpPlayer();

		// Unregister all callbacks.
		mCallbacks.kill();

    // espeak_Terminate() will hang because of the commented out
    // pthread_cancel calls. There doesn't seem to be much point in
    // fixing up the cleanup sequence though. Secondly, if threads are
    // left behind for any reason, Android will not cleanup and kill
    // the process, and it seems like strange things can happen
    // then. So use a heavy-handed approach and just exit(), since
    // there's nothing else we really want to do here.

		//nativeSynth.shutdown();
    System.exit(0);

    Log.d("TTS", "onDestroy done");
	}

  // Following methods are called on the service's main thread.

	private void setSpeechRate(int rate) {
		if (prefs.getBoolean("override_pref", false)) {
			// This is set to the default here so that the preview in the prefs
			// activity will show the change without a restart, even if apps are
			// not allowed to change the defaults.
			rate = Integer.parseInt(prefs.getString("rate_pref", "140"));
		}
		speechRate = rate;
		nativeSynth.setSpeechRate(rate);
		Log.i("get test", "rate: " + nativeSynth.getRate());
	}

	private void setLanguage(String lang) {
		if (prefs.getBoolean("override_pref", false)) {
			// This is set to the default here so that the preview in the prefs
			// activity will show the change without a restart, even if apps are
			// not allowed to change the defaults.
			lang = prefs.getString("lang_pref", "en-rUS");
		}
		language = lang;
		nativeSynth.setLanguage(lang);
		Log.i("get test", nativeSynth.getLanguage());
	}

	private void setEngine(TTSEngine selectedEngine) {
    Log.i("TTS", "setEngine: " + selectedEngine.toString());
    utterances.clear();
		boolean fallbackToPrerecordedOnly = false;
		if (selectedEngine == TTSEngine.TTS_ONLY) {
			if (!espeakIsUsable()) {
				fallbackToPrerecordedOnly = true;
			}
			engine = selectedEngine;
		} else if (selectedEngine == TTSEngine.PRERECORDED_WITH_TTS) {
			if (!espeakIsUsable()) {
				fallbackToPrerecordedOnly = true;
			}
			//loadUtterancesFromPropertiesFile();
			engine = selectedEngine;
		} else {
			fallbackToPrerecordedOnly = true;
		}
		if (fallbackToPrerecordedOnly) {
			//loadUtterancesFromPropertiesFile();
			engine = TTSEngine.PRERECORDED_ONLY;
		}

		// Load earcons
		earcons.put(TTSEarcon.CANCEL.name(), new SoundResource(PKGNAME,
				R.raw.cancel_snd));
		earcons.put(TTSEarcon.SILENCE.name(), new SoundResource(PKGNAME,
				R.raw.slnc_snd));
		earcons.put(TTSEarcon.TICK.name(), new SoundResource(PKGNAME,
				R.raw.tick_snd));
		earcons.put(TTSEarcon.TOCK.name(), new SoundResource(PKGNAME,
				R.raw.tock_snd));
    // For backwards compatibility:
		utterances.put("[slnc]", new SoundResource(PKGNAME,
				R.raw.slnc_snd));
		utterances.put("[tick]", new SoundResource(PKGNAME,
				R.raw.tick_snd));
		utterances.put("[tock]", new SoundResource(PKGNAME,
				R.raw.tock_snd));
	}

  /*
	private void loadUtterancesFromPropertiesFile() {
		Resources res = getResources();
		InputStream fis = res.openRawResource(R.raw.soundsamples);

		try {
			Properties soundsamples = new Properties();
			soundsamples.load(fis);
			Enumeration<Object> textKeys = soundsamples.keys();
			while (textKeys.hasMoreElements()) {
				String text = textKeys.nextElement().toString();
				String name = "com.google.tts:raw/"
						+ soundsamples.getProperty(text);
				TypedValue value = new TypedValue();
				getResources().getValue(name, value, false);
				utterances.put(text, new SoundResource(PKGNAME,
						value.resourceId));
			}
		} catch (FactoryConfigurationError e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		} catch (SecurityException e) {
			e.printStackTrace();
		}
	}
  */

	// TODO: Make this generic for internal TTS
	private boolean espeakIsUsable() {
		if (!ConfigurationManager.allFilesExist()) {
			// This should have been taken care of when the TTS is launched
			// by the check in the TTS.java wrapper.
      Log.w("TTS", "espeakIsUsable returns false");
			return false;
		}
		return true;
	}

	// TODO: Make this generic for internal TTS
	/**
	 * Adds a sound resource to the TTS.
	 * 
	 * @param text
	 *            The text that should be associated with the sound resource
	 * @param packageName
	 *            The name of the package which has the sound resource
	 * @param resId
	 *            The resource ID of the sound within its package
	 */
	private void addSpeech(String text, String packageName, int resId) {
		utterances.put(text, new SoundResource(packageName, resId));
	}

	/**
	 * Adds a sound resource to the TTS.
	 * 
	 * @param text
	 *            The text that should be associated with the sound resource
	 * @param filename
	 *            The filename of the sound resource. This must be a complete
	 *            path like: (/sdcard/mysounds/mysoundbite.mp3).
	 */
	private void addSpeech(String text, String filename) {
		utterances.put(text, new SoundResource(filename));
	}

	/**
	 * Adds a sound resource to the TTS as an earcon.
	 * 
	 * @param earcon
	 *            The text that should be associated with the sound resource
	 * @param packageName
	 *            The name of the package which has the sound resource
	 * @param resId
	 *            The resource ID of the sound within its package
	 */
	private void addEarcon(String earcon, String packageName, int resId) {
    // Called from the IBinder's thread pool.
		earcons.put(earcon, new SoundResource(packageName, resId));
	}

	/**
	 * Adds a sound resource to the TTS as an earcon.
	 * 
	 * @param earcon
	 *            The text that should be associated with the sound resource
	 * @param filename
	 *            The filename of the sound resource. This must be a complete
	 *            path like: (/sdcard/mysounds/mysoundbite.mp3).
	 */
	private void addEarcon(String earcon, String filename) {
    // Called from the IBinder's thread pool.
		earcons.put(earcon, new SoundResource(filename));
	}

	/**
	 * Speaks the given text using the specified queueing mode and parameters.
	 * 
	 * @param text
	 *            The text that should be spoken
	 * @param queueMode
	 *            0 for no queue (interrupts all previous utterances), 1 for
	 *            queued
	 * @param params
	 *            An ArrayList of parameters. This is not implemented for all
	 *            engines.
	 */
	private void speak(String text, int queueMode, ArrayList<String> params) {
    // Called from the IBinder's thread pool.
    Log.i("TTS", "speak: "+text);
		if (queueMode == 0) {
			stop();
		}
    handler.sendMessage(
        handler.obtainMessage(MSG_NEW_ITEM,
                              new SpeechItem(text, params, false)));
	}

	/**
	 * Plays the earcon using the specified queueing mode and parameters.
	 * 
	 * @param earcon
	 *            The earcon that should be played
	 * @param queueMode
	 *            0 for no queue (interrupts all previous utterances), 1 for
	 *            queued
	 * @param params
	 *            An ArrayList of parameters. This is not implemented for all
	 *            engines.
	 */
	private void playEarcon(String earcon, int queueMode,
			ArrayList<String> params) {
    // Called from the IBinder's thread pool.
    Log.i("TTS", "earcon: "+earcon);
		if (queueMode == 0) {
			stop();
		}
    handler.sendMessage(
        handler.obtainMessage(MSG_NEW_ITEM,
                              new SpeechItem(earcon, params, true)));
	}

	private void enqueueCallback(ITTSUserCallback callback, int user_arg) {
    // Called from the IBinder's thread pool.
    handler.sendMessage(
        handler.obtainMessage(MSG_NEW_ITEM,
                              new SpeechItem(callback, user_arg)));
	}

	/**
	 * Stops all speech output and removes any utterances still in the queue.
	 */
	private void stop() {
    // Called from the IBinder's thread pool.
    synchronized (stopLock) {
      // Count how many pending stop messages are in the handler's queue.
      ++stopsCount;
      Log.i("TTS", String.format("Stopping: count %d", stopsCount));
    }
    handler.sendMessage(handler.obtainMessage(MSG_STOP));
  }

	public void onCompletion(MediaPlayer arg0) {
    Log.i("TTS", "player onCompletion");
    handler.sendMessage(handler.obtainMessage(MSG_RUN_QUEUE_NEXT));
	}

  private class MySpeechSynthesis extends SpeechSynthesis {
    public MySpeechSynthesis(String nativeSoLib) {
      super(nativeSoLib);
    }
    @Override
    protected void onSynthesisCompleted() {
      Log.i("TTS", "onSynthesisCompleted");
      isSynthesizing = false;
      handler.sendMessage(handler.obtainMessage(MSG_RUN_QUEUE_NEXT));
    }
  }

  private Handler handler = new Handler() {
      @Override
      public void handleMessage(Message msg) {
        switch (msg.what) {
          case MSG_NEW_ITEM:
            SpeechItem item = (SpeechItem)msg.obj;
            Log.i("TTS", "MSG_NEW_ITEM: " + item.text);
            speechQueue.add(item);
            if (!isSpeaking)
              // Start the queue.
              processSpeechQueue();
            break;
          case MSG_RUN_QUEUE_NEXT:
            //Log.i("TTS", "MSG_RUN_QUEUE_NEXT");
            // When an utterance is finished playing/speaking, start
            // on the next enqueued element.
            processSpeechQueue();
            break;
          case MSG_STOP:
            handleStop();
            break;
          case MSG_SET_ENGINE:
            forceStop();
            TTSEngine engine = (TTSEngine)msg.obj;
            setEngine(engine);
            break;
          case MSG_SET_SPEECH_RATE:
            forceStop();
            int rate = msg.arg1;
            setSpeechRate(rate);
            break;
          case MSG_SET_LANGUAGE:
            forceStop();
            String language = (String)msg.obj;
            setLanguage(language);
            break;
          case MSG_SYNTH_TO_FILE:
            forceStop();
            Bundle b = msg.getData();
            String text = b.getString("text");
            ArrayList<String> params = b.getStringArrayList("params");
            String filename = b.getString("filename");
            synthesizeToFile(text, params, filename);
            break;
        }
      }
    };

  private void forceStop() {
    synchronized (stopLock) {
      ++stopsCount;
    }
    handleStop();
  }
  private void handleStop() {
    //Log.i("TTS", String.format("MSG_STOP: count %d", stopsCount));
    if (stopsCount < 1)
      throw new RuntimeException("Inconsistent stopsCount");
    if (isSynthesizing) {
      Log.i("TTS", "Stopping native");
      nativeSynth.stop();
      // On return from nativeSynth.stop(), onSynthesisCompleted may
      // or may not have been called, but we are guaranteed that it
      // will not be called later.
      //Log.i("TTS", "native stopped");
      if (isSynthesizing) {
        // We interrupted it before the completion callback fired.
        isSynthesizing = false;
      }
    } else if (isSpeaking) {
      if (player != null) {
        //Log.i("TTS", "Stopping player");
        try {
          player.stop();
        } catch (IllegalStateException e) {
          // Do nothing, the player is already stopped.
        }
      } //else Log.e("TTS", "No player to stop");
    }
    handler.removeMessages(MSG_RUN_QUEUE_NEXT);
    processSpeechQueue();  // with stopsCount still > 0
    // The above sets isSpeaking to false and empties the queue.
    synchronized (stopLock) {
      --stopsCount;
    }
  }

	// TODO: Add commented out speech methods
	private void speakWithChosenEngine(SpeechItem speechItem) {
		//Log.i("Selected engine: ", engine.toString());
		if (engine == TTSEngine.PRERECORDED_WITH_TTS) {
			speakPrerecordedWithInternal(speechItem.text, speechItem.params);
		} else if (engine == TTSEngine.TTS_ONLY) {
			speakInternalOnly(speechItem.text, speechItem.params);
		} else {
			speakPrerecordedOnly(speechItem.text, speechItem.params);
		}
	}

	private void speakPrerecordedOnly(String text, ArrayList<String> params) {
		if (!utterances.containsKey(text)) {
			if (text.length() > 1) {
				decomposedToNumbers(text, params);
			}
		}
		processSpeechQueue();
	}

	private void speakPrerecordedWithInternal(String text,
			ArrayList<String> params) {
		if (!utterances.containsKey(text)) {
			if ((text.length() > 1) && decomposedToNumbers(text, params)) {
				processSpeechQueue();
			} else {
				speakInternalOnly(text, params);
			}
		}
	}

	private void speakInternalOnly(final String text,
			final ArrayList<String> params) {
    if (isSynthesizing)
      throw new RuntimeException("Already synthesizing");
    isSynthesizing = true;
    Log.i("TTS", "synthesizing: " +text);
    boolean ssml = params != null
        && params.contains(TTSParams.USE_SSML.toString());
    nativeSynth.speak(text, ssml); 
    // Returns immediately.
    //Log.i("TTS", "nativeSynth.speak() has returned");
  }

	private SoundResource getSoundResource(SpeechItem speechItem) {
		SoundResource sr = null;
		String text = speechItem.text;
		ArrayList<String> params = speechItem.params;
		// If this is an earcon, just load that sound resource
		if (speechItem.isEarcon) {
			sr = earcons.get(text);
			if (sr == null) {
				// Invalid earcon requested; play the default [tock] sound.
				sr = new SoundResource(PKGNAME, R.raw.tock_snd);
			}
		}

		// TODO: Cleanup special params system
		if ((sr == null) && (engine != TTSEngine.TTS_ONLY)) {
			if ((params != null) && (params.size() > 0)) {
				String textWithVoice = text;
				if (params.get(0).equals(TTSParams.VOICE_ROBOT.toString())) {
					textWithVoice = textWithVoice + "[robot]";
				} else if (params.get(0).equals(
						TTSParams.VOICE_FEMALE.toString())) {
					textWithVoice = textWithVoice + "[fem]";
				}
				if (utterances.containsKey(textWithVoice)) {
					text = textWithVoice;
				}
			}
			sr = utterances.get(text);
		}
		return sr;
	}

	// Special algorithm to decompose numbers into speakable parts.
	// This will handle positive numbers up to 999.
	private boolean decomposedToNumbers(String text, ArrayList<String> params) {
    try {
			int number = Integer.parseInt(text);
			ArrayList<SpeechItem> decomposedNumber = new ArrayList<SpeechItem>();
			// Handle cases that are between 100 and 999, inclusive
			if ((number > 99) && (number < 1000)) {
				int remainder = number % 100;
				number = number / 100;
				decomposedNumber.add(new SpeechItem(Integer.toString(number),
						params, false));
				decomposedNumber.add(new SpeechItem(TTSEarcon.SILENCE.name(), params, true));
				decomposedNumber.add(new SpeechItem("hundred", params, false));
				decomposedNumber.add(new SpeechItem(TTSEarcon.SILENCE.name(), params, true));
				if (remainder > 0) {
					decomposedNumber.add(new SpeechItem(Integer
							.toString(remainder), params, false));
				}
				speechQueue.addAll(0, decomposedNumber);
				return true;
			}

			// Handle cases that are less than 100
			int digit = 0;
			if ((number > 20) && (number < 100)) {
				if ((number > 20) && (number < 30)) {
					decomposedNumber.add(new SpeechItem(Integer.toString(20),
							params, false));
					decomposedNumber
							.add(new SpeechItem(TTSEarcon.SILENCE.name(), params, true));
					digit = number - 20;
				} else if ((number > 30) && (number < 40)) {
					decomposedNumber.add(new SpeechItem(Integer.toString(30),
							params, false));
					decomposedNumber
							.add(new SpeechItem(TTSEarcon.SILENCE.name(), params, true));
					digit = number - 30;
				} else if ((number > 40) && (number < 50)) {
					decomposedNumber.add(new SpeechItem(Integer.toString(40),
							params, false));
					decomposedNumber
							.add(new SpeechItem(TTSEarcon.SILENCE.name(), params, true));
					digit = number - 40;
				} else if ((number > 50) && (number < 60)) {
					decomposedNumber.add(new SpeechItem(Integer.toString(50),
							params, false));
					decomposedNumber
							.add(new SpeechItem(TTSEarcon.SILENCE.name(), params, true));
					digit = number - 50;
				} else if ((number > 60) && (number < 70)) {
					decomposedNumber.add(new SpeechItem(Integer.toString(60),
							params, false));
					decomposedNumber
							.add(new SpeechItem(TTSEarcon.SILENCE.name(), params, true));
					digit = number - 60;
				} else if ((number > 70) && (number < 80)) {
					decomposedNumber.add(new SpeechItem(Integer.toString(70),
							params, false));
					decomposedNumber
							.add(new SpeechItem(TTSEarcon.SILENCE.name(), params, true));
					digit = number - 70;
				} else if ((number > 80) && (number < 90)) {
					decomposedNumber.add(new SpeechItem(Integer.toString(80),
							params, false));
					decomposedNumber
							.add(new SpeechItem(TTSEarcon.SILENCE.name(), params, true));
					digit = number - 80;
				} else if ((number > 90) && (number < 100)) {
					decomposedNumber.add(new SpeechItem(Integer.toString(90),
							params, false));
					decomposedNumber
							.add(new SpeechItem(TTSEarcon.SILENCE.name(), params, true));
					digit = number - 90;
				}
				if (digit > 0) {
					decomposedNumber.add(new SpeechItem(
							Integer.toString(digit), params, false));
				}
				speechQueue.addAll(0, decomposedNumber);
				return true;
			}
			// Any other cases are either too large to handle
			// or have an utterance that is directly mapped.
			return false;
		} catch (NumberFormatException nfe) {
			return false;
    }
  }

	private void dispatchSpeechCompletedCallbacks(String mark) {
		Log.i("TTS callback", "dispatch started");
		// Broadcast to all clients the new value.
		final int N = mCallbacks.beginBroadcast();
		for (int i = 0; i < N; i++) {
			try {
				mCallbacks.getBroadcastItem(i).markReached(mark);
			} catch (RemoteException e) {
				// The RemoteCallbackList will take care of removing
				// the dead object for us.
			}
		}
		mCallbacks.finishBroadcast();
		//Log.i("TTS callback", "dispatch completed to " + N);
	}

  private void doUserCallback(SpeechItem item, boolean interrupted) {
    if (item.callback != null) {
      Log.i("TTS", "user callback");
      try {
        item.callback.onCompletion(interrupted, item.callbackUserArg);
      } catch (RemoteException e) {
      }
    }
  }

	private void processSpeechQueue() {
    synchronized (stopLock) {
      int stopsCount = this.stopsCount;
    }
    if (stopsCount > 0) {
      // There are pending MSG_STOP messages, so we flush out the
      // queue instead of speaking it.
      Log.i("TTS", "processSpeechQueue while stopping");
      for (SpeechItem item : speechQueue)
        doUserCallback(item, true);
      speechQueue.clear();
      isSpeaking = false;
      return;
    }
    while(true) {
			if (speechQueue.isEmpty()) {
        Log.i("TTS", "speechQueue empty");
				isSpeaking = false;
				// Dispatch a completion here as this is the
				// only place where speech completes normally.
				// Nothing left to say in the queue is a special case
				// that is always a "mark" - associated text is null.
				dispatchSpeechCompletedCallbacks("");
				return;
			}

			SpeechItem currentSpeechItem = speechQueue.remove(0);
      if (currentSpeechItem.callback != null) {
        doUserCallback(currentSpeechItem, false);
        continue;  // process next queue item.
      }
			isSpeaking = true;
			SoundResource sr = getSoundResource(currentSpeechItem);
			// Synth speech as needed - synthesizer should call
			// processSpeechQueue to continue running the queue
			Log.i("TTS processing: ", currentSpeechItem.text);
			if (sr == null) {
				// TODO: Split text up into smaller chunks before accepting them
				// for processing.
				speakWithChosenEngine(currentSpeechItem);
			} else {
				cleanUpPlayer();
				if (sr.sourcePackageName == PKGNAME) {
					// Utterance is part of the TTS library
					player = MediaPlayer.create(this, sr.resId);
				} else if (sr.sourcePackageName != null) {
					// Utterance is part of the app calling the library
					Context ctx;
					try {
						ctx = this
								.createPackageContext(sr.sourcePackageName, 0);
					} catch (NameNotFoundException e) {
            Log.e("TTS", "name not found playing sound resource");
						e.printStackTrace();
						// move on
            speechQueue.clear();
						isSpeaking = false;
						return;
					}
					player = MediaPlayer.create(ctx, sr.resId);
				} else {
					// Utterance is coming from a file
					player = MediaPlayer.create(this, Uri.parse(sr.filename));
				}

				// Check if Media Server is dead; if it is, clear the queue and
				// give up for now - hopefully, it will recover itself.
				if (player == null) {
          Log.e("TTS", "dead player");
					speechQueue.clear();
					isSpeaking = false;
					return;
				}
				player.setOnCompletionListener(this);
				try {
					player.start();
				} catch (IllegalStateException e) {
          Log.e("TTS", "Illegal state starting player");
          e.printStackTrace();
					speechQueue.clear();
					isSpeaking = false;
					cleanUpPlayer();
					return;
				}
			}
      break;
    }
	}

	private void cleanUpPlayer() {
		if (player != null) {
			player.release();
			player = null;
		}
	}

	/**
	 * Synthesizes the given text using the specified queuing mode and
	 * parameters.
	 * 
	 * @param text
	 *            The String of text that should be synthesized
	 * @param params
	 *            An ArrayList of parameters. The first element of this array
	 *            controls the type of voice to use.
	 * @param filename
	 *            The string that gives the full output filename; it should be
	 *            something like "/sdcard/myappsounds/mysound.wav".
	 * @return A boolean that indicates if the synthesis succeeded
	 */
	private boolean synthesizeToFile(String text, ArrayList<String> params,
			String filename) {
    // Don't allow a filename that is too long
    if (filename.length() > 250) {
      return false;
    }
    // Is this still needed? Not really tested since latest
    // changes. It's still synchronous and now on the main thread...
		Log.i("TTS", "Synthesizing " + filename);
    nativeSynth.synthesizeToFile(text, filename);
		Log.i("TTS", "Completed synthesis for " + filename);
		return true;
	}

	@Override
	public IBinder onBind(Intent intent) {
		if (ACTION.equals(intent.getAction())) {
			for (String category : intent.getCategories()) {
				if (category.equals(CATEGORY)) {
					return mBinder;
				}
			}
		}
		return null;
	}

	private final ITTS.Stub mBinder = new Stub() {

		@SuppressWarnings("unused")
		public void registerCallback(ITTSCallback cb) {
			if (cb != null)
				mCallbacks.register(cb);
		}

		@SuppressWarnings("unused")
		public void unregisterCallback(ITTSCallback cb) {
			if (cb != null)
				mCallbacks.unregister(cb);
		}

		/**
		 * Speaks the given text using the specified queueing mode and
		 * parameters.
		 * 
		 * @param selectedEngine
		 *            The TTS engine that should be used
		 */
    public void setEngine(String selectedEngine) {
			TTSEngine theEngine;
			if (selectedEngine.equals(TTSEngine.TTS_ONLY.toString())) {
				theEngine = TTSEngine.TTS_ONLY;
			} else if (selectedEngine.equals(TTSEngine.PRERECORDED_WITH_TTS
					.toString())) {
				theEngine = TTSEngine.PRERECORDED_WITH_TTS;
			} else if (selectedEngine.equals(TTSEngine.PRERECORDED_ONLY
					.toString())) {
				theEngine = TTSEngine.PRERECORDED_ONLY;
			} else {
        Log.w("TTS", String.format("setEngine(): unrecognized engine '%s', defaulting to PRERECORDED_WITH_TTS.", selectedEngine));
				theEngine = TTSEngine.PRERECORDED_WITH_TTS;
			}
      handler.sendMessage(handler.obtainMessage(MSG_SET_ENGINE, theEngine));
		}

		/**
		 * Speaks the given text using the specified queueing mode and
		 * parameters.
		 * 
		 * @param text
		 *            The text that should be spoken
		 * @param queueMode
		 *            0 for no queue (interrupts all previous utterances), 1 for
		 *            queued
		 * @param params
		 *            An ArrayList of parameters. The first element of this
		 *            array controls the type of voice to use.
		 */
		public void speak(String text, int queueMode, String[] params) {
			ArrayList<String> speakingParams = new ArrayList<String>();
			if (params != null) {
				speakingParams = new ArrayList<String>(Arrays.asList(params));
			}
			self.speak(text, queueMode, speakingParams);
		}

		/**
		 * Plays the earcon using the specified queueing mode and parameters.
		 * 
		 * @param earcon
		 *            The earcon that should be played
		 * @param queueMode
		 *            0 for no queue (interrupts all previous utterances), 1 for
		 *            queued
		 * @param params
		 *            An ArrayList of parameters.
		 */
		public void playEarcon(String earcon, int queueMode, String[] params) {
			ArrayList<String> speakingParams = new ArrayList<String>();
			if (params != null) {
				speakingParams = new ArrayList<String>(Arrays.asList(params));
			}
			self.playEarcon(earcon, queueMode, speakingParams);
		}

		/**
		 * Stops all speech output and removes any utterances still in the
		 * queue.
		 */
		public void stop() {
			self.stop();
		}

		/**
		 * Returns whether or not the TTS is speaking.
		 * 
		 * @return Boolean to indicate whether or not the TTS is speaking
		 */
		public boolean isSpeaking() {
			return self.isSpeaking;
		}

		/**
		 * Adds a sound resource to the TTS.
		 * 
		 * @param text
		 *            The text that should be associated with the sound resource
		 * @param packageName
		 *            The name of the package which has the sound resource
		 * @param resId
		 *            The resource ID of the sound within its package
		 */
		public void addSpeech(final String text,
                          final String packageName, final int resId) {
      // The reason this needs to be synchronized is that setEngine
      // clears utterances. Not sure if that's by design...
      handler.post(new Runnable() {
          public void run() {
            self.addSpeech(text, packageName, resId);
          }
        });
		}

		/**
		 * Adds a sound resource to the TTS.
		 * 
		 * @param text
		 *            The text that should be associated with the sound resource
		 * @param filename
		 *            The filename of the sound resource. This must be a
		 *            complete path like: (/sdcard/mysounds/mysoundbite.mp3).
		 */
		public void addSpeechFile(final String text, final String filename) {
      // The reason this needs to be synchronized is that setEngine
      // clears utterances. Not sure if that's by design...
      handler.post(new Runnable() {
          public void run() {
            self.addSpeech(text, filename);
          }
        });
		}

		/**
		 * Adds a sound resource to the TTS as an earcon.
		 * 
		 * @param earcon
		 *            The text that should be associated with the sound resource
		 * @param packageName
		 *            The name of the package which has the sound resource
		 * @param resId
		 *            The resource ID of the sound within its package
		 */
		public void addEarcon(String earcon, String packageName, int resId) {
			self.addEarcon(earcon, packageName, resId);
		}

		/**
		 * Adds a sound resource to the TTS as an earcon.
		 * 
		 * @param earcon
		 *            The text that should be associated with the sound resource
		 * @param filename
		 *            The filename of the sound resource. This must be a
		 *            complete path like: (/sdcard/mysounds/mysoundbite.mp3).
		 */
		public void addEarconFile(String earcon, String filename) {
			self.addEarcon(earcon, filename);
		}

		/**
		 * Sets the speech rate for the TTS. Note that this will only have an
		 * effect on synthesized speech; it will not affect pre-recorded speech.
		 * 
		 * @param speechRate
		 *            The speech rate that should be used
		 */
		public void setSpeechRate(int speechRate) {
      handler.sendMessage(handler.obtainMessage(
                              MSG_SET_SPEECH_RATE, speechRate, 0));
		}

		/**
		 * Sets the speech rate for the TTS. Note that this will only have an
		 * effect on synthesized speech; it will not affect pre-recorded speech.
		 * 
		 * @param language
		 *            The language to be used. The languages are specified by
		 *            their IETF language tags as defined by BCP 47. This is the
		 *            same standard used for the lang attribute in HTML. See:
		 *            http://en.wikipedia.org/wiki/IETF_language_tag
		 */
		public void setLanguage(String language) {
      handler.sendMessage(handler.obtainMessage(MSG_SET_LANGUAGE, language));
		}

		/**
		 * Returns the version number of the TTS This version number is the
		 * versionCode in the AndroidManifest.xml
		 * 
		 * @return The version number of the TTS
		 */
		public int getVersion() {
      /*
			PackageInfo pInfo = new PackageInfo();
			try {
				PackageManager pm = self.getPackageManager();
				pInfo = pm.getPackageInfo(self.getPackageName(), 0);
			} catch (NameNotFoundException e) {
				// Ignore this exception - the packagename is itself, can't fail
				// here
				e.printStackTrace();
			}
			return pInfo.versionCode;
      */
      return 9;
		}

		/**
		 * Speaks the given text using the specified queueing mode and
		 * parameters.
		 * 
		 * @param text
		 *            The String of text that should be synthesized
		 * @param params
		 *            An ArrayList of parameters. The first element of this
		 *            array controls the type of voice to use.
		 * @param filename
		 *            The string that gives the full output filename; it should
		 *            be something like "/sdcard/myappsounds/mysound.wav".
		 * @return A boolean that indicates if the synthesis succeeded
		 */
		public boolean synthesizeToFile(String text, String[] params,
				String filename) {
			ArrayList<String> speakingParams = new ArrayList<String>();
			if (params != null) {
				speakingParams = new ArrayList<String>(Arrays.asList(params));
			}
      Bundle b = new Bundle();
      b.putString("text", text);
      b.putStringArrayList("params", speakingParams);
      b.putString("filename", filename);
      Message msg = handler.obtainMessage(MSG_SYNTH_TO_FILE);
      msg.setData(b);
      handler.sendMessage(msg);
      return true;
		}

    public void enqueueCallback(ITTSUserCallback callback, int user_arg) {
      self.enqueueCallback(callback, user_arg);
    }
	};

  private static int instanceCount;
  public TTSService() {
    // Only one instance is supposed to be created for a
    // service. Somehow though it happened that the OS got confused
    // and ran multiple instances of this class (in the same process)
    // and that took me a while to debug. So warn about this.
    int n = ++instanceCount;
    if (n > 1)
      Log.e("TTS", String.format("Instance count %d", n));
  }
}
