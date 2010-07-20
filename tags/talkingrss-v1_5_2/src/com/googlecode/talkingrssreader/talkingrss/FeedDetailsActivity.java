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
import android.text.Html;
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

/* Activity that shows the URL of a feed and let's the user
   unsubscribe. */

public class FeedDetailsActivity extends Activity {
  private static final String TAG = "talkingrss-feed";

  private static final int UNSUBSCRIBING_DIALOG = 1;

  private boolean canceled;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    if (Config.LOGD) Log.d(TAG, "onCreate");
    super.onCreate(savedInstanceState);
    setVolumeControlStream(AudioManager.STREAM_MUSIC);
    String feedId
        = getIntent().getStringExtra(FeedsListActivity.EXTRA_FEED_ID);
    showFeedDetails(feedId);
  }

  @Override
  protected void onResume() {
    if (Config.LOGD) Log.d(TAG, "onResume");
    super.onResume();
    Core.announceKeyguard();
  }

  private void showFeedDetails(final String feedId) {
    if (Config.LOGD) Log.d(TAG, "feed details: " +feedId);
    RssFeed feed = Core.client.rssFeeds.get(feedId);
    if (feed == null) {
      Log.w(TAG, "Cannot find feed id " +feedId);
      setResult(RESULT_CANCELED);
      finish();
      return;
    }
    setContentView(R.layout.feed_details);
    final TextView title_text = (TextView)findViewById(R.id.details_feed_title);
    TextView url_text = (TextView)findViewById(R.id.details_feed_url);
    title_text.setText(Html.fromHtml(feed.title));
    final String url = feed.id.substring(Core.http.FEED_PREFIX.length());
    url_text.setText(url);

    Core.tts.speak(title_text.getText().toString(), 1, null);
    Core.tts.speak(url, 1, null);

    final Button back_btn = (Button)findViewById(R.id.back);
    final Button unsub_btn = (Button)findViewById(R.id.unsubscribe);
    back_btn.setOnClickListener(new Button.OnClickListener() {
        public void onClick(View v) {
          setResult(RESULT_CANCELED);
          finish();
        }
      });
    unsub_btn.setOnClickListener(new Button.OnClickListener() {
        public void onClick(View v) {
          showDialog(UNSUBSCRIBING_DIALOG);
          Core.subscribe(feedId, false, handler);
        }
      });
    back_btn.requestFocus();

    // Spoken indications.
    back_btn.setOnFocusChangeListener(Core.focusAnnouncer);
    unsub_btn.setOnFocusChangeListener(Core.focusAnnouncer);
  }

  @Override
  public Dialog onCreateDialog(int id) {
    switch (id) {
      case UNSUBSCRIBING_DIALOG:
        return Core.makeProgressDialog(
            this, getString(R.string.unsubscribing),
            new Core.OnProgressCancelListener() {
              public void onCancel() {
                canceled = true;
                setResult(RESULT_CANCELED);
                finish();
              }
            });
    }
    return null;
  }

  private Handler handler = new Handler() {
      @Override
      public void handleMessage(Message msg) {
        if (canceled)
          return;
        // Dispatch to common error handling code.
        if (Core.handleErrorMessage(
                FeedDetailsActivity.this, msg,
                new Core.OnErrorDismissListener() {
                  public void onErrorDismissed() {
                    setResult(RESULT_CANCELED);
                    finish();
                  }
                }))
          return;
        switch (msg.what) {
          case Core.MSG_SUBSCRIBED_OK:
            dismissDialog(UNSUBSCRIBING_DIALOG);
            Core.showToast(FeedDetailsActivity.this,
                           getString(R.string.unsubscribed));
            setResult(Activity.RESULT_OK);
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
