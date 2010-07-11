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
import android.media.AudioManager;
import android.content.DialogInterface;
import android.view.KeyEvent;
import android.text.method.LinkMovementMethod;
import android.os.Handler;
import android.os.Message;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.ListView;
import android.widget.BaseAdapter;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.AdapterView;
import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.EditText;
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


import com.googlecode.talkingrssreader.talkingrss.ReaderHttp;
import com.googlecode.talkingrssreader.talkingrss.ReaderClientData;
import com.googlecode.talkingrssreader.talkingrss.ReaderClientData.ArticleEntry;
import com.googlecode.talkingrssreader.talkingrss.ReaderClientData.ReaderAtomFeed;
import com.googlecode.talkingrssreader.talkingrss.ReaderClientData.RssFeed;
import com.googlecode.talkingrssreader.talkingrss.ReaderClientData.UserLabel;

import com.googlecode.talkingrssreader.talkingrss.ReaderExceptions.ReaderException;
import com.googlecode.talkingrssreader.talkingrss.ReaderExceptions.HttpUnauthorizedException;
import com.googlecode.talkingrssreader.talkingrss.HtmlTalker;
import com.googlecode.talkingrssreader.talkingrss.HtmlTalker.HtmlParseException;
import com.googlecode.talkingrssreader.talkingrss.HtmlTalker.Utterance;
import com.googlecode.talkingrssreader.talkingrss.HtmlTalker.SpokenText;
import com.googlecode.talkingrssreader.talkingrss.HtmlTalker.SpeechElement;
import com.googlecode.talkingrssreader.talkingrss.HtmlTalker.SpokenIndication;
import com.googlecode.talkingrssreader.talkingrss.HtmlTalker.EarconIndication;

/* Activity that lists your feeds, select one to view by clicking or
   details/unsubscribe with long click. */

public class FeedsListActivity extends Activity {
  private static final String TAG = "talkingrss-feeds";

  // Subactivity codes:
  private static final int REQUEST_SUBSCRIBE_FEED = 1;
  static final String EXTRA_NEW_FEED_ID = "new_feed_id";
  private static final int REQUEST_FEED_DETAILS = 2;
  static final String EXTRA_FEED_ID = "feed_id";

  private LayoutInflater inflater;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    if (Config.LOGD) Log.d(TAG, "onCreate");
    super.onCreate(savedInstanceState);
    setVolumeControlStream(AudioManager.STREAM_MUSIC);
    inflater = LayoutInflater.from(this);
    String currentFeedId = Core.client.currentFeed.id;
    showFeedsList(currentFeedId);
  }
  @Override
  protected void onDestroy() {
    if (Config.LOGD) Log.d(TAG, "onDestroy");
    super.onDestroy();
  }
  @Override
  protected void onStart() {
    if (Config.LOGD) Log.d(TAG, "onStart");
    super.onStart();
  }
  @Override
  protected void onStop() {
    if (Config.LOGD) Log.d(TAG, "onStop");
    super.onStop();
  }

  @Override
  protected void onPause() {
    if (Config.LOGD) Log.d(TAG, "onPause");
    super.onPause();
  }
  @Override
  protected void onResume() {
    if (Config.LOGD) Log.d(TAG, "onResume");
    super.onResume();
    Core.announceKeyguard();
  }

  class FeedsAdapter extends ArrayAdapter<RssFeed> {
    public FeedsAdapter() {
      super(FeedsListActivity.this, 0);
    }
    @Override
    public int getViewTypeCount() {
      return 1;
    }
    @Override
    public int getItemViewType(int position) {
      return 0;
    }
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
      if (convertView == null) {
        convertView = inflater.inflate(
            R.layout.list_item, parent, false);
      }
      RssFeed feed = getItem(position);
      TextView text = (TextView) convertView.findViewById(android.R.id.text1);
      text.setText(feed.title);
      return convertView;
    }
  }

  private void showFeedsList(String currentFeedId) {
    setContentView(R.layout.list);
    final ListView feedsList = (ListView)findViewById(R.id.list);

    // Add new feed button as list view header.
    final Button addNewButton = new Button(this);
    addNewButton.setText(getString(R.string.new_feed));
    addNewButton.setFocusable(false);
    addNewButton.setClickable(false);  // Handle through ListView callback.
    feedsList.addHeaderView(addNewButton);
    final FeedsAdapter feedsAdapter = new FeedsAdapter();
    feedsList.setAdapter(feedsAdapter);

    // Fill up the adapter with our feeds.
    feedsAdapter.setNotifyOnChange(false);
    for (RssFeed feed : Core.client.rssFeeds.values()) {
      feedsAdapter.add(feed);
    }
    // Sort it.
    final Collator collator = Collator.getInstance();
    collator.setStrength(Collator.SECONDARY);
    feedsAdapter.sort(new Comparator<RssFeed>() {
        @Override
        public int compare(RssFeed feed1, RssFeed feed2) {
          return collator.compare(feed1.title, feed2.title);
        }
      });
    feedsAdapter.notifyDataSetChanged();

    // Speak the currently focussed item.
    feedsList.setOnItemSelectedListener(new OnItemSelectedListener() {
        @Override
        public void onItemSelected(AdapterView<?> parent, View view,
                                int position, long id) {
          if (view == addNewButton) {
            Core.tts.speak(addNewButton.getText().toString(), 0, null);
          } else {
            position -= feedsList.getHeaderViewsCount();
            if (position < 0)
              return;
            RssFeed feed = feedsAdapter.getItem(position);
            Core.tts.speak(feed.title, 0, null);
          }
        }
        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
      });
    feedsList.setOnItemClickListener(new OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view,
                                int position, long id) {
          if (Config.LOGD) Log.d(TAG, String.format("feeds list clicked at %d", position));
          if (view == addNewButton) {
            subscribeFeed();
          } else {
            position -= feedsList.getHeaderViewsCount();
            if (position < 0)
              return;
            RssFeed feed = feedsAdapter.getItem(position);
            Intent intent = new Intent();
            intent.putExtra(ArticleViewActivity.EXTRA_FEED_ID, feed.id);
            setResult(Activity.RESULT_OK, intent);
            finish();
          }
        }
      });
    feedsList.setOnItemLongClickListener(new OnItemLongClickListener() {
        @Override
        public boolean onItemLongClick(AdapterView<?> parent, View view,
                                    int position, long id) {
          if (Config.LOGD) Log.d(TAG, String.format("feeds list long clicked at %d", position));
          if (view == addNewButton) {
            subscribeFeed();
            return true;
          } else {
            position -= feedsList.getHeaderViewsCount();
            if (position < 0)
              return false;
            RssFeed feed = feedsAdapter.getItem(position);
            Intent intent = new Intent(FeedsListActivity.this,
                                       FeedDetailsActivity.class);
            intent.putExtra(EXTRA_FEED_ID, feed.id);
            startActivityForResult(intent, REQUEST_FEED_DETAILS);

            return true;
          }
        }
      });

    feedsList.requestFocus();

    // Initial selection should be the current feed.
    int currentSelection = 0;
    RssFeed selectedFeed
        = Core.client.rssFeeds.get(currentFeedId);
    if (selectedFeed != null) {
      currentSelection = feedsAdapter.getPosition(selectedFeed);
      if (currentSelection < 0)
        currentSelection = 0;
      else
        currentSelection += feedsList.getHeaderViewsCount();
    }
    feedsList.setSelection(currentSelection);
  }

  private void subscribeFeed() {
    Intent intent = new Intent(FeedsListActivity.this,
                               SubscribeFeedActivity.class);
    startActivityForResult(intent, REQUEST_SUBSCRIBE_FEED);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode,
                                  Intent data) {
    switch(requestCode) {
      case Core.REQUEST_LOGIN:
        // Dispatch to common handling code.
        // Actually I don't think we need it here since we don't make
        // requests directly.
        Core.handleLoginActivityResult(this, resultCode, data);
        break;
      case REQUEST_SUBSCRIBE_FEED:
        switch (resultCode) {
          case RESULT_OK:
            // List of subscriptions was refreshed so rebuild it and
            // try to select the new feed.
            String feedId = data.getStringExtra(EXTRA_NEW_FEED_ID);
            showFeedsList(feedId);
            break;
        }
        break;
      case REQUEST_FEED_DETAILS:
        switch (resultCode) {
          case RESULT_OK:
            // List may have been refreshed so rebuild, keep same
            // selection if it hasn't been deleted.
            showFeedsList(null);
            break;
        }
        break;
    }
  }
}
