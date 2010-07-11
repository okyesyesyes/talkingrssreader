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
import android.media.AudioManager;
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

import com.googlecode.talkingrssreader.talkingrss.ReaderHttp;
import com.googlecode.talkingrssreader.talkingrss.ReaderClientData;
import com.googlecode.talkingrssreader.talkingrss.ReaderClientData.ArticleEntry;
import com.googlecode.talkingrssreader.talkingrss.ReaderClientData.ReaderAtomFeed;
import com.googlecode.talkingrssreader.talkingrss.ReaderClientData.RssFeed;
import com.googlecode.talkingrssreader.talkingrss.ReaderClientData.UserLabel;

/* Activity to subscribe to a new feed: user inputs URL. */

public class SubscribeFeedActivity extends Activity {
  private static final String TAG = "talkingrss-subs";

  private static final int SUBSCRIBING_DIALOG = 1;

  private boolean canceled;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    if (Config.LOGD) Log.d(TAG, "onCreate");
    super.onCreate(savedInstanceState);
    setVolumeControlStream(AudioManager.STREAM_MUSIC);
    subscribeNewFeed();
  }

  @Override
  protected void onResume() {
    if (Config.LOGD) Log.d(TAG, "onResume");
    super.onResume();
    Core.announceKeyguard();
  }

  private void subscribeNewFeed() {
    if (Config.LOGD) Log.d(TAG, "subscribeNewFeed");
    setContentView(R.layout.subscribe);
    final EditText urlEdit = (EditText)findViewById(R.id.subscribe_url);
    final Button button = (Button)findViewById(R.id.subscribe);
    button.setOnClickListener(new Button.OnClickListener() {
        public void onClick(View v) {
          String url = urlEdit.getText().toString();
          if (url.length() == 0) {
            setResult(RESULT_CANCELED);
            finish();
          }
          doSubscribe(url);
        }
      });

    button.setOnFocusChangeListener(Core.focusAnnouncer);
    urlEdit.setOnFocusChangeListener(Core.focusAnnouncer);
    urlEdit.addTextChangedListener(Core.talkingTextWatcher);
  }

  @Override
  public Dialog onCreateDialog(int id) {
    switch (id) {
      case SUBSCRIBING_DIALOG:
        return Core.makeProgressDialog(
            this, getString(R.string.subscribing),
            new Core.OnProgressCancelListener() {
              public void onCancel() {
                canceled = true;
                // User does not want to wait for network request.
                setResult(RESULT_CANCELED);
                finish();
              }
            });
    }
    return null;
  }

  private String newFeedId;
  private void doSubscribe(String url) {
    newFeedId = Core.http.FEED_PREFIX + url;
    showDialog(SUBSCRIBING_DIALOG);
    Core.subscribe(newFeedId, true, handler);
  }

  private Handler handler = new Handler() {
      @Override
      public void handleMessage(Message msg) {
        if (canceled)
          return;
        // Dispatch to common error handling code.
        if (Core.handleErrorMessage(
                SubscribeFeedActivity.this, msg,
                new Core.OnErrorDismissListener() {
                  public void onErrorDismissed() {
                    setResult(RESULT_CANCELED);
                    finish();
                  }
                }))
          return;
        switch (msg.what) {
          case Core.MSG_SUBSCRIBED_OK:
            dismissDialog(SUBSCRIBING_DIALOG);
            Core.showToast(SubscribeFeedActivity.this,
                           getString(R.string.subscribed));
            Intent intent = new Intent();
            intent.putExtra(FeedsListActivity.EXTRA_NEW_FEED_ID, newFeedId);
            setResult(Activity.RESULT_OK, intent);
            finish();
            break;
        }
      }
    };

  @Override
  protected void onActivityResult(int requestCode, int resultCode,
                                  Intent data) {
    switch(requestCode) {
      case Core.REQUEST_LOGIN:
        // Dispatch to common handling code.
        Core.handleLoginActivityResult(this, resultCode, data);
        break;
    }
  }
}
