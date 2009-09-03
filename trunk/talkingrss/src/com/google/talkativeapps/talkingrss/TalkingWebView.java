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
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.widget.ProgressBar;
import android.app.KeyguardManager;
import android.content.DialogInterface.OnClickListener;
import android.os.Bundle;
import android.os.Vibrator;
import android.os.SystemClock;
import android.os.PowerManager;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.util.Config;
import android.util.Log;
import android.text.Html;
import android.widget.TextView;
import android.webkit.WebView;
import android.view.Window;
import android.media.AudioManager;
import android.content.DialogInterface;
import android.view.KeyEvent;
import android.text.method.LinkMovementMethod;
import android.os.Handler;
import android.os.Message;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.ListView;
import android.widget.BaseAdapter;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.AdapterView;
import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.net.ConnectivityManager;
import android.os.AsyncTask;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.FileWriter;
import java.io.BufferedWriter;
import java.text.DateFormat;
import java.util.Date;
import java.util.Calendar;
import java.util.Comparator;
import java.text.Collator;

import com.google.tts.TTS;

import com.google.talkativeapps.talkingrss.HtmlTalker;
import com.google.talkativeapps.talkingrss.HtmlTalker.HtmlParseException;
import com.google.talkativeapps.talkingrss.HtmlTalker.Utterance;
import com.google.talkativeapps.talkingrss.HtmlTalker.SpokenText;
import com.google.talkativeapps.talkingrss.HtmlTalker.SpeechElement;
import com.google.talkativeapps.talkingrss.HtmlTalker.SpokenIndication;
import com.google.talkativeapps.talkingrss.HtmlTalker.EarconIndication;

import com.google.talkativeapps.talkingrss.KeyHandling;
import com.google.talkativeapps.talkingrss.KeyHandling.TalkActionHandler;

/* Wraps a webView and makes it talk. Handles html parsing and
 * splitting into utterances, tracking speech progress, and
 * synchronizing scrolling by communicating with javascript in the
 * webView. */

public class TalkingWebView implements KeyHandling.TalkActionHandler {
  private static final String TAG = "talkingrss-webv";

  private static final long[] SCROLLED_VIBR_PATTERN = {0, 30};
  private static final long[] MOVED_VIBR_PATTERN = SCROLLED_VIBR_PATTERN;

  public interface SetupCallback {
    void onParseError(HtmlParseException e);
    void onViewReady();
  }

  public static class SpokenMessages {
    // What to say in a couple of corner cases.
    public String endOfPage;
    public String topOfPage;
    public String speakParseError;
    public String emptyArticle;
  }

  private WebView webView;
  private TTS tts;
  private Vibrator vibrator;
  private PowerManager powerManager;
  private SpokenMessages messages;
  private SetupCallback setupCallback;
  private String htmlInput;
  private String htmlFooter;  // Shown but not spoken.
  private String baseUrl;

  public TalkingWebView(WebView webView, TTS tts,
                        Vibrator vibrator, PowerManager powerManager,
                        SpokenMessages messages,
                        SetupCallback setupCallback,
                        String htmlInput, String htmlFooter, String baseUrl) {
    this.webView = webView;
    this.tts = tts;
    this.vibrator = vibrator;
    this.powerManager = powerManager;
    this.messages = messages;
    this.setupCallback = setupCallback;
    this.htmlInput = htmlInput;
    this.htmlFooter = htmlFooter;
    this.baseUrl = baseUrl;

    setup();
  }

  private static final int MSG_WEBVIEW_ONLOAD = 20;
  private static final int MSG_WEBVIEW_ONSCROLL = 21;

  private HtmlTalker htmlTalker;
  // Background html parsing task.
  AsyncTask<String, Void, HtmlTalker> htmlParseTask;
  // Exception returned from background thread.
  private volatile HtmlParseException pendingException;
  private int currentUtterance = -1;
  private boolean isTalking;
  private int ttsCallbackUtteranceId;  // unique id per speak() request.
  private boolean continueTalking;  // Auto-forrward to next utterance.
  private boolean skippingBackwards;  // Speaking previous sentence.
  // Set to true when we get the onload callback from javascript.
  private boolean webViewLoaded;
  // Set to drop pending handler messages on a discarded view.
  private boolean isDead;

  // Called when we are discarded.
  public void kill() {
    stopTalking();
    isDead = true;
    if (htmlParseTask != null) {
      htmlParseTask.cancel(true);
      htmlParseTask = null;
    }
    webView.stopLoading();
    webView = null;
  }

  public boolean isTalking() {
    return this.isTalking;
  }

  private Handler handler = new Handler() {
      @Override
      public void handleMessage(Message msg) {
        switch (msg.what) {
          case MSG_WEBVIEW_ONLOAD:
            if (!isDead) {
              if (Config.LOGD) Log.d(TAG, "WebView loaded");
              webViewLoaded = true;
              showSpoken();
            }
            break;
          case MSG_WEBVIEW_ONSCROLL:
            if (!isDead) {
              int utterance = msg.arg1;
              if (Config.LOGD) Log.d(TAG, String.format("Scrolled to utterance %d", utterance));
              if (currentUtterance != utterance) {
                callShowSpoken(-1);  // hide visual indication.
                currentUtterance = utterance;
                vibrator.vibrate(SCROLLED_VIBR_PATTERN, -1);
                if (isTalking) {
                  stopTalking();
                  startTalking(continueTalking);
                }
              }
            }
            break;
        }
      }
    };

  // Interface for callbacks from javascript in the webView.
  private class MyJSInterface {
    public void reportLoaded() {
      handler.sendMessage(handler.obtainMessage(
          MSG_WEBVIEW_ONLOAD));
    }
    public void scrolledToUtterance(int utterance) {
      handler.removeMessages(MSG_WEBVIEW_ONSCROLL);
      handler.sendMessage(handler.obtainMessage(
          MSG_WEBVIEW_ONSCROLL, utterance, 0));
    }
    // For debugging.
    public void report(String x) {
      Log.d(TAG, String.format("JS report: <%s>", x));
    }
  }
  private MyJSInterface jsInterface = new MyJSInterface();

  private static final String myJSCode =
      "<head>" +
      "<style type=\"text/css\">\n" +
      "  .spoken { color: green }\n" +
      "</style>\n" +
      "<script type=\"text/javascript\">\n" +
      "    var gNumberOfSpans = [%s];\n" +
      "    var gShownUtterance = null;\n" +
      "    var gScrollTarget = 0;\n" +
      "    var gScrollTimeout = null;\n" +
      "    function getVerticalPosition(node) {\n" +
      "      offset = 0;\n" +
      "      do {\n" +
      "        offset += node.offsetTop;\n" +
      "      } while (node = node.offsetParent);\n" +
      "      return offset;\n" +
      "    }\n" +
      "    function showSpoken(utterance) {\n" +
      "      if (utterance >= gNumberOfSpans.length) return;\n" +
      "      var numSpans;\n" +
      "      if (gShownUtterance != null) {\n" +
      "        var idPrefix = \"utt\" + gShownUtterance + \"_\";\n" +
      "        numSpans = gNumberOfSpans[gShownUtterance];\n" +
      "        for (i = 0; i < numSpans; ++i) {\n" +
      "          document.getElementById(idPrefix + i).className = \"\";\n" +
      "        }\n" + 
      "      }\n" +
      "      if (utterance >= 0 && (numSpans = gNumberOfSpans[utterance]) > 0) {\n" +
      "        var idPrefix = \"utt\" + utterance + \"_\";\n" +
      "        tag = document.getElementById(idPrefix + \"0\");\n" +
      "        scrollTarget = getVerticalPosition(tag);\n" +
      "        var scrollMax = document.body.scrollHeight - document.body.clientHeight;\n" +
      "        if (scrollMax < 0) scrollMax = 0;\n" +
      "        if (scrollTarget > scrollMax) scrollTarget = scrollMax;\n" +
      "        window.scrollTo(0, scrollTarget);\n" +
      "        gScrollTarget = scrollTarget;\n" +
      "        for (i = 0; i < numSpans; ++i) {\n" +
      "          document.getElementById(idPrefix + i).className = \"spoken\";\n" +
      "        }\n" + 
      "        gShownUtterance = utterance;\n" +
      "      }\n" +
      "    }\n" +
      "    function handleScroll() {\n" +
      "      var scrollTop = document.body.scrollTop;\n" +
      "      var midScreen = document.body.scrollTop + screen.height/2;\n" +
      "      for (i = 0; i < gNumberOfSpans.length; ++i) {\n" +
      "        var tag = document.getElementById(\"utt\" + i + \"_0\");\n" +
      "        var pos = getVerticalPosition(tag);\n" +
      "        if (pos > midScreen) return;\n" +
      "        if (pos >= scrollTop) {\n" +
      "          window.mycb.scrolledToUtterance(i);\n" +
      "          return;\n" +
      "        }\n" +
      "      }\n" +
      "    }\n" +
      "    function myOnScroll() {\n" +
      "      //window.mycb.report(\"myOnScroll: \" + document.body.scrollTop + \" target \" +gScrollTarget);\n" +
      "      if (gScrollTimeout != null) clearTimeout(gScrollTimeout);\n" +
      "      if (document.body.scrollTop == gScrollTarget) return;\n" +
      "      gScrollTimeout = window.setTimeout(handleScroll, 150);\n" +
      "    }\n" +
      "</script>\n" +
      "</head>\n" +
      "<body onload=\"javascript:window.mycb.reportLoaded();\" onscroll=\"javascript:myOnScroll();\" >\n";

  private void setup() {
    // Have the actual parsing done in a background thread, for the
    // odd long article that might cause an ANR.

    htmlParseTask = new AsyncTask<String, Void, HtmlTalker>() {
      protected HtmlTalker doInBackground(String... htmlInput) {
        try {
          long startTime = SystemClock.uptimeMillis();
          HtmlTalker htmlTalker = HtmlTalker.parse(htmlInput[0]);
          long now = SystemClock.uptimeMillis();
          if (Config.LOGD) Log.d(TAG, String.format("Parsed html in %dms", now-startTime));
          // Turn this on to dump the html for this article to sdcard
          // for debugging.
          if (false) {
            try {
              FileWriter fw = new FileWriter("/sdcard/talkingrss.out0");
              BufferedWriter bw = new BufferedWriter(fw, 16384);
              bw.write(htmlInput[0]);
              bw.close();
              String outHtml = htmlTalker.fullHtml.toString();
              fw = new FileWriter("/sdcard/talkingrss.out");
              bw = new BufferedWriter(fw, 16384);
              bw.write(outHtml);
              bw.close();
            } catch(IOException e) {
              e.printStackTrace();
            }
          }
          return htmlTalker;
        } catch(HtmlParseException e) {
          Log.w(TAG, "HtmlParseError");
          pendingException = e;
          return null;
        }
      }
      protected void onPostExecute(HtmlTalker htmlTalker) {
        htmlParseTask = null;
        if (isDead)
          return;
        if (htmlTalker == null) {
          setupCallback.onParseError(pendingException);
        } else {
          TalkingWebView.this.htmlTalker = htmlTalker;
          String outHtml = htmlTalker.fullHtml.toString();

          // Do a string join of htmlTalker.numberOfSpansPerUtterance.
          StringBuilder builder = new StringBuilder();
          boolean first = true;
          for (int spans : htmlTalker.numberOfSpansPerUtterance) {
            if (first) {
              first = false;
            } else {
              builder.append(",");
            }
            builder.append(String.valueOf(spans));
          }

          // Put the numberOfSpans array into the JS code.
          String jsCode = String.format(
              myJSCode, builder.toString());
          
          webView.getSettings().setJavaScriptEnabled(true);
          webView.addJavascriptInterface(jsInterface, "mycb");

          // Concatenate the JS code, article and footer.  Note the footer
          // is added at this late stage because we don't want it spoken.
          String wvHtml = String.format("%s %s %s</body>",
                                      jsCode, outHtml, htmlFooter);
          webView.loadDataWithBaseURL(
              baseUrl, wvHtml, "text/html", "utf-8", null);
          webView.requestFocus();

          currentUtterance = 0;
          setupCallback.onViewReady();
        }
      }
    }.execute(htmlInput);
  }

  private boolean speakChecks() {
    if (htmlTalker == null) {
      tts.speak(messages.speakParseError, 1, null);
      return false;
    }
    if (htmlTalker.utterances.isEmpty()) {
      tts.speak(messages.emptyArticle, 1, null);
      return false;
    }
    return true;
  }

  // Starts speaking at the currentUtterance.
  public void startTalking(boolean continueTalking) {
    this.continueTalking = continueTalking;
    if (!speakChecks())
      return;
    if (currentUtterance >= htmlTalker.utterances.size()) {
      // Wrap to top.
      currentUtterance = 0;
    }
    isTalking = true;
    if (Config.LOGD) Log.d(TAG, String.format("StartTalking utterance %d", currentUtterance));
    speakCompoundUtterance(currentUtterance);
    // Show visual indication of this utterance.
    showSpoken();
  }
  // When we were speaking just one sentence, this command lets it
  // continue on.
  public void continueTalking() {
    continueTalking = true;
  }

  // Tells javascript to scroll to and color the current utterance.
  private void showSpoken() {
    if (!webViewLoaded || webView == null)
      return;
    if (htmlTalker == null
        || currentUtterance < 0
        || currentUtterance >= htmlTalker.utterances.size())
      return;
    callShowSpoken(currentUtterance);
  }
  private void callShowSpoken(int utterance) {
    webView.loadUrl(String.format("javascript:showSpoken(%d);", utterance));
  }

  public void stopTalking() {
    if (isTalking) {
      tts.stop();
      isTalking = false;
      if (Config.LOGD) Log.d(TAG, String.format("Stopped talking during utterance %d", currentUtterance));
    }
  }

  // Speak the current utterance from htmlTalker.
  private void speakCompoundUtterance(int utterance_index) {
    Utterance utt = htmlTalker.utterances.get(utterance_index);
    for (int i= 0; i < utt.size(); ++i) {
      SpeechElement e = utt.get(i);
      if (e instanceof EarconIndication) {
        String name = e.toString();
        if (Config.LOGD) Log.d(TAG, "earcon " + name);
        tts.playEarcon(name, 1, null);
      } else {
        String toSpeak = e.toString();
        if (Config.LOGD) Log.d(TAG, "toSpeak: "+toSpeak);
        tts.speak(toSpeak, 1, null);
      }
    }
    tts.enqueueCallback(speechCallback, ++ttsCallbackUtteranceId);
    // and we could continue enqueuing...
    keepUnlocked();
  }

  private TTS.TTSUserCallback speechCallback = new TTS.TTSUserCallback() {
      public void onCompletion(final boolean interrupted, final int user_arg) {
        handler.post(new Runnable() {
            public void run() {
              if (user_arg != ttsCallbackUtteranceId)
                return;  // an older utterance.
              if (!isTalking)
                return;
              if (Config.LOGD) Log.d(TAG, String.format("speechCallback %d, interrupted %s", user_arg, String.valueOf(interrupted)));
              if (interrupted) {
                isTalking = false;
                if (Config.LOGD) Log.d(TAG, String.format("Interrupted talking during utterance %d", currentUtterance));
              } else {
                speechProgress();
              }
            }
          });
      }
    };

  private void speechProgress() {
    if (!isTalking)
      return;
    if (currentUtterance >= 0) {
      // Next utterance.
      if (!continueTalking && skippingBackwards)
        ;  // don't increment
      else
        ++currentUtterance;
      if (currentUtterance >= htmlTalker.utterances.size()) {
        // End of article.
        if (Config.LOGD) Log.d(TAG, "Finished talking");
        currentUtterance = htmlTalker.utterances.size();
        tts.speak(messages.endOfPage, 1, null);
        isTalking = false;
      } else {
        if (!continueTalking) {
          if (Config.LOGD) Log.d(TAG, String.format("Done just one utterance, now at %d", currentUtterance));
          isTalking = false;
        } else {
          if (Config.LOGD) Log.d(TAG, String.format("Speaking next utterance: %d", currentUtterance));
          startTalking(true);
        }
      }
    }
  }

  // Keep the phone from locking while we're talking.
  private void keepUnlocked() {
    handler.removeCallbacks(powerPokerRunner);
    keepUnlockedStartTime = SystemClock.uptimeMillis();
    powerPokerRunner.run();
  }
  private long keepUnlockedStartTime;
  private Runnable powerPokerRunner = new Runnable() {
      public void run() {
        if (!isTalking)
          return;
        long now = SystemClock.uptimeMillis();
        if (now - keepUnlockedStartTime > 90*1000)
          // too long, give up.
          return;
        // Don't lock the screen until we're done talking.
        powerManager.userActivity(SystemClock.uptimeMillis(), false);
        // Check again later.
        handler.postDelayed(powerPokerRunner, 3000);
      }
    };

  // User command to move to next/previous utterance and speak it.
  public void nextUtterance(boolean doSkip, boolean continueTalking) {
    vibrator.vibrate(MOVED_VIBR_PATTERN, -1);
    if (!speakChecks())
      return;
    boolean wasTalking = isTalking;
    stopTalking();
    // If we weren't talking then start with the current sentence. If
    // we were talking then skip to the next. If doSkip is true then
    // we had been talking recently (until the button was
    // pressed, and it has not been released since).
    if (wasTalking || doSkip)
      ++currentUtterance;
    if (currentUtterance >= htmlTalker.utterances.size()) {
      currentUtterance = htmlTalker.utterances.size();
      tts.speak(messages.endOfPage, 1, null);
      return;
    }
    if (Config.LOGD) Log.d(TAG, String.format("nextUtterance %d", currentUtterance));
    skippingBackwards = false;
    startTalking(continueTalking);
  }
  public void previousUtterance(boolean continueTalking) {
    vibrator.vibrate(MOVED_VIBR_PATTERN, -1);
    if (!speakChecks())
      return;
    stopTalking();
    --currentUtterance;
    if (currentUtterance <= 0) {
      currentUtterance = 0;
      tts.speak(messages.topOfPage, 1, null);
    }
    if (Config.LOGD) Log.d(TAG, String.format("previousUtterance %d", currentUtterance));
    // Prevent incrementing currentUtterance when done.
    skippingBackwards = true;
    startTalking(continueTalking);
  }
}
