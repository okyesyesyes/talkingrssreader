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

package com.googlecode.talkingrssreader.talkingrss;

import android.content.Intent;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.os.Bundle;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.util.Config;
import android.util.Log;
import android.widget.TextView;
import android.os.Handler;
import android.os.Message;
import android.widget.Toast;
import android.widget.Button;
import android.widget.EditText;
import android.os.Vibrator;
import android.os.PowerManager;
import android.webkit.WebView;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.media.AudioManager;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.io.IOException;

import com.googlecode.talkingrssreader.talkingrss.ReaderHttp;
import com.googlecode.talkingrssreader.talkingrss.ReaderClientData;
import com.googlecode.talkingrssreader.talkingrss.ReaderClientData.ArticleEntry;
import com.googlecode.talkingrssreader.talkingrss.ReaderClientData.ReaderAtomFeed;
import com.googlecode.talkingrssreader.talkingrss.ReaderClientData.RssFeed;
import com.googlecode.talkingrssreader.talkingrss.ReaderClientData.UserLabel;

import com.googlecode.talkingrssreader.talkingrss.ReaderExceptions.UnexpectedException;

import com.googlecode.talkingrssreader.talkingrss.TalkingWebView;

import com.googlecode.talkingrssreader.talkingrss.HtmlTalker;
import com.googlecode.talkingrssreader.talkingrss.HtmlTalker.HtmlParseException;

import com.googlecode.talkingrssreader.talkingrss.KeyHandling;
import com.googlecode.talkingrssreader.talkingrss.KeyHandling.ContentActionHandler;

/* Activity that shows a help screen, in a talking webView. */

public class HelpActivity extends Activity
  implements KeyHandling.ContentActionHandler {
  private static final String TAG = "talkingrss-hlp";

  private static final int READYING_DIALOG = 1;

  private TalkingWebView talkingWebView;

  private Vibrator vibrator;
  private PowerManager powerManager;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    if (Config.LOGD) Log.d(TAG, "onCreate");
    super.onCreate(savedInstanceState);
    setVolumeControlStream(AudioManager.STREAM_MUSIC);
    vibrator = (Vibrator)getSystemService(Context.VIBRATOR_SERVICE);
    powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
  }

  @Override
  public void onStart() {
    if (Config.LOGD) Log.d(TAG, "onStart");
    super.onStart();
    int htmlResource
      = getIntent().getIntExtra(ArticleViewActivity.EXTRA_HTML_RESOURCE, -1);
    showHelp(htmlResource);
  }

  @Override
  protected void onStop() {
    if (Config.LOGD) Log.d(TAG, "onStop");
    super.onStop();
    if (talkingWebView != null) {
      talkingWebView.kill();
      talkingWebView = null;
    }
  }

  @Override
  protected void onResume() {
    if (Config.LOGD) Log.d(TAG, "onResume");
    super.onResume();
    Core.announceKeyguard();
  }

  private String readRawResource(int resourceId) {
    // Read the whole raw resource file into a string.
    InputStream stream = getResources().openRawResource(resourceId);
    BufferedReader reader = new BufferedReader(
        new InputStreamReader(stream), 16*1024);
    StringWriter sw = new StringWriter();
    char[] buf = new char[32*1024];
    try {
      while (true) {
        int len = reader.read(buf);
        if (len == -1)
          break;
        sw.write(buf, 0, len);
      }
    } catch (IOException e) {
      throw new UnexpectedException(e);
    } finally {
      try {
        reader.close();
      } catch(IOException e) {}
    }
    return sw.toString();
  }

  @Override
  public Dialog onCreateDialog(int id) {
    switch (id) {
      case READYING_DIALOG:
        // Dialog to keep the user company while preparing the
        // page. It takes a while to setup especially in the first run
        // case.
        return Core.makeProgressDialog(
            this, getString(R.string.welcome), null);
    }
    return null;
  }
            
  private void showHelp(int htmlResource) {
    if (Config.LOGD) Log.d(TAG, "help");
    String html = readRawResource(htmlResource);
    setContentView(R.layout.help);
    showDialog(READYING_DIALOG);

    WebView webView = (WebView)findViewById(R.id.body);
    TalkingWebView.SpokenMessages msgs = new TalkingWebView.SpokenMessages();
    msgs.endOfPage = getString(R.string.end_of_page);
    msgs.topOfPage = getString(R.string.top_of_article);
    msgs.speakParseError = getString(R.string.nothing_to_speak);
    msgs.emptyArticle = getString(R.string.empty_article);

    TalkingWebView.Callback callback = new TalkingWebView.Callback() {
        @Override
        public void onParseError(HtmlParseException e) {
          Core.showErrorDialog(HelpActivity.this, e.getMessage(), null);
        }
        @Override
        public boolean onViewReady() {
          dismissDialog(READYING_DIALOG);
          return true;
        }
        @Override
        public void onUserInteraction() {
        }
        public void onTalking(boolean started) {
        }
        @Override
        public void onReadToBottom() {
        }
      };

    talkingWebView = new TalkingWebView(
        this,
        webView, Core.tts, vibrator, powerManager,
        msgs, callback,
        html, "", null);

    Button closeBtn = (Button)findViewById(R.id.help_dismiss);
    closeBtn.setOnClickListener(new Button.OnClickListener() {
        public void onClick(View v) {
          if (talkingWebView != null) {
            talkingWebView.kill();
            talkingWebView = null;
          }
          finish();
        }
      });
  }

  public void showNextArticle(boolean isMediaCommand) {
  }
  public void showPreviousArticle(boolean isMediaCommand) {
  }

  private boolean isTalking() {
    return talkingWebView != null && talkingWebView.isTalking();
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    boolean handled = KeyHandling.onKey(
        event, isTalking(),
        talkingWebView, this);
    if (handled)
      return true;
    return super.onKeyDown(keyCode, event);
  }
  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event) {
    boolean handled = KeyHandling.onKey(
        event, isTalking(),
        talkingWebView, this);
    if (handled)
      return true;
    return super.onKeyUp(keyCode, event);
  }

  @Override
  public boolean onTrackballEvent(MotionEvent event) {
    return KeyHandling.onTrackballEvent(event, isTalking(),
                                        talkingWebView, this);
  }
}
