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
import android.widget.BaseAdapter;
import android.widget.Toast;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemSelectedListener;
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
import android.preference.PreferenceManager;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
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
import com.google.tts.ConfigurationManager;

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

import com.googlecode.talkingrssreader.talkingrss.TalkingWebView;
import com.googlecode.talkingrssreader.talkingrss.TalkingWebView.SetupCallback;

import com.googlecode.talkingrssreader.talkingrss.KeyHandling;

/* Main activity: shows one article and let's you go to the
   next/previous one. Menu to access other functionality. */

public class ArticleViewActivity extends Activity
  implements KeyHandling.ContentActionHandler {
  private static final String TAG = "talkingrss-art";

  // This activity is declared to be singleTask in the manifest, so
  // that there always is a single instance at the bottom of this
  // task's activity stack.

  private static final long[] JUMP_VIBR_PATTERN = {0, 50};

  private static final int ARTICLE_NAV_DEBOUNCE_DELAY = 250;

  // Dialog IDs:
  private static final int TAGGING_DIALOG = 1;
  private static final int UNTAGGING_DIALOG = 2;
  private static final int MENU_DIALOG = 3;

  // Subactivity codes:
  private static final int REQUEST_FEEDS_LIST = 1;
  static final String EXTRA_FEED_ID = "feedId";
  private static final int REQUEST_CLIENT_LOGIN = 2;
  private static final int REQUEST_HELP = 3;
  static final String EXTRA_HTML_RESOURCE = "htmlResource";
  private static final int REQUEST_PREFS = 4;

  private TTS tts;
  private Vibrator vibrator;
  private PowerManager powerManager;

  // Waiting while fetching articles from net.
  private boolean waitingForArticles;
  // Whether we are displaying the special screen indicating there
  // are no more articles to show.
  private boolean showingEndOfFeed;
  // Talking stuff and webView for currently displayed article.
  private TalkingWebView talkingWebView;
  // Backup of previous feed, while fetching a new one.
  private ReaderAtomFeed atomFeedBackup;
  // Timestamp of last next/prev article button click, to debounce.
  private long timeLastArticleNavClick;

  // Lots of life-cycle logging that I used to debug launch modes and
  // how to start activities from a launcher shell.

  // Count instances of this activity. So I can see when this same
  // process is re-used to reinstantiate the activity. This is the
  // case on orientation change for example.
  private static int instanceCount;
  public ArticleViewActivity() {
    if (Config.LOGD) Log.d(TAG, "Instantiating");
    ++instanceCount;
    if (instanceCount > 1)
      Log.i(TAG, String.format("instanceCount: %d", instanceCount));
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    if (Config.LOGD) Log.d(TAG, "onCreate");
    super.onCreate(savedInstanceState);
    Core.context = this;
    setVolumeControlStream(AudioManager.STREAM_MUSIC);
    vibrator = (Vibrator)getSystemService(Context.VIBRATOR_SERVICE);
    powerManager = (PowerManager)getSystemService(Context.POWER_SERVICE);
    Core.keyguardManager
      = (KeyguardManager)getSystemService(Context.KEYGUARD_SERVICE);
    Core.startThreads(handler);
    requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
    showWorkingTextOnly(getString(R.string.initializing));
    if (!ConfigurationManager.allFilesExist()) {
      // Handle smoother download and installation of espeak data
      // files to sdcard on first run.
      Log.i(TAG, "espeak data files are missing, attempting download");
      showWorkingTextOnly(getString(R.string.downloading_espeak_data));
      new Thread() {
        public void run() {
          Log.i(TAG, "Downloading");
          ConfigurationManager.downloadEspeakData();
          Log.i(TAG, "Download returned, TTS init");
          Core.tts = tts = new TTS(
              ArticleViewActivity.this, ttsInitListener, false);
        }
      }.start();
    } else {
      Core.tts = tts = new TTS(this, ttsInitListener, false);
    }
  }
  @Override
  protected void onDestroy() {
    if (Config.LOGD) Log.d(TAG, "onDestroy");
    Core.stopThreads();
    tts.shutdown();
    Core.tts = null;
    super.onDestroy();
  }

  private TTS.InitListener ttsInitListener = new TTS.InitListener() {
      public void onInit(int version) {
        handler.post(new Runnable() {
            public void run() {
              tts.addEarcon(
                  "breakflow",
                  ArticleViewActivity.class.getPackage().getName(),
                  R.raw.woowoo);
              tts.speak(getString(R.string.app_name), 1, null);
              init();  // App initialization.
            }
          });
      }
    };
        
  @Override
  protected void onStart() {
    if (Config.LOGD) Log.d(TAG, "onStart");
    super.onStart();
    registerMediaButtonReceiver();
  }
  @Override
  protected void onStop() {
    if (Config.LOGD) Log.d(TAG, "onStop");
    unregisterMediaButtonReceiver();
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

  @Override
  public void onWindowFocusChanged(boolean focus) {
    if (Config.LOGD) Log.d(TAG, "onWindowFocus " + String.valueOf(focus));
    if (!focus)
      stopTalking();
  }

  @Override
  public void onNewIntent(Intent intent) {
    if (Config.LOGD) Log.d(TAG, "onNewIntent");
    // App re-entered, just start reading.
    if (talkingWebView != null)
      talkingWebView.startTalking(true);
  }

  @Override
  protected void onUserLeaveHint() {
    if (Config.LOGD) Log.d(TAG, "onUserLeaveHint");
  }

  // Shows a simplistic splash while we're busy.
  private void showWorkingTextOnly(String message) {
    if (Config.LOGD) Log.d(TAG, "Working: " + message);
    setContentView(R.layout.working_view);
    setProgressBarIndeterminateVisibility(true);
    TextView workingView = (TextView) findViewById(R.id.workingView);
    workingView.setText(message);
    ProgressBar progress = (ProgressBar)findViewById(R.id.progress);
    progress.setIndeterminate(true);
  }
  private void showWorking(String message) {
    showWorkingTextOnly(message);
    tts.speak(message, 1, null);
  }

  private void init() {
    if (Config.LOGD) Log.d(TAG, "init");
    if (Core.client.currentFeed != null
        && !Core.client.currentFeed.entries.isEmpty()) {
      // The activity is being re-instantiated inside a re-used
      // process, and we have good static data cached, so just show
      // it.
      if (Config.LOGD) Log.d(TAG, "re-using existing reader client feed");
      showArticles();
    } else {
      Core.client.currentFeed = Core.client.new ReaderAtomFeed();
      // Start by showing the reading list (called "All feeds" in  our menu).
      Core.client.currentFeed.id = Core.http.READING_LIST_STATE;
      Core.login(
          this, false,
          new Runnable() {
            public void run() {
              getFeed(Core.http.READING_LIST_STATE, true);
            }
          });
    }
    // First run experience: show help screen.
    SharedPreferences prefs
      = getSharedPreferences(Core.PREFS_NAME, MODE_PRIVATE);
    boolean ranBefore = prefs.getBoolean(Core.PREFS_RAN_BEFORE, false);
    if (!ranBefore)
      launchHelp();
  }

  private void getFeed(String feedId, boolean excludeRead) {
    showWorking(getString(R.string.getting_article_feed));
    showingEndOfFeed = false;
    waitingForArticles = true;
    atomFeedBackup = Core.client.currentFeed;
    Core.client.currentFeed = Core.client.new ReaderAtomFeed();
    Core.client.currentFeed.id = feedId;
    Core.client.currentFeed.excludeRead = excludeRead;
    Core.getListsInfo(handler);  // Get or freshen lists too.
    Core.getFeed(handler);
  }

  // Gets more articles from this feed.
  private void getContinuationIfNeeded() {
    // If we are already doing this, or if there no more articles to
    // get, then just do nothing.
    if (Core.isGettingFeed()
        || Core.client.currentFeed == null
        || Core.client.currentFeed.continuation == null)
      return;
    Core.getFeed(handler);
  }

  private Handler handler = new Handler() {
      @Override
      public void handleMessage(Message msg) {
        // Try common error handling.
        if (Core.handleErrorMessage(
                ArticleViewActivity.this, msg,
                new Core.OnErrorDismissListener() {
                  public void onErrorDismissed() {
                    // Failed feed fetch, restore backup if any.
                    if (Core.client.currentFeed.entries.isEmpty()
                        && atomFeedBackup != null) {
                      Core.client.currentFeed = atomFeedBackup;
                      atomFeedBackup = null;
                    }
                  }
                }))
          return;
        switch (msg.what) {
          case Core.MSG_GOT_ARTICLES:
            ReaderAtomFeed atomFeed = (ReaderAtomFeed)msg.obj;
            if (atomFeed != Core.client.currentFeed) {
              if (Config.LOGD) Log.d(TAG, "Ignoring GOT_ARTICLES from interrupted thread");
              return;
            }
            setProgressBarIndeterminateVisibility(false);
            atomFeedBackup = null;
            // If we were waiting on articles then show
            // them. Otherwise the background thread did a second
            // round and pushed more articles at us while we're
            // viewing the first ones.
            if (waitingForArticles) {
              waitingForArticles = false;
              Core.client.currentIndex = 0;
              showArticles();
            } else if (showingEndOfFeed) {
              // We were waiting on continuation.
              showCurrentArticle(false);
            }
            break;
          case Core.MSG_GOT_LISTS_INFO:
            if (Config.LOGD) Log.d(TAG, "MSG_GOT_LISTS_INFO");
            break;
          case Core.MSG_TAGGED_OK:
            Core.ReaderOpsThread.TagItemArgs args
              = (Core.ReaderOpsThread.TagItemArgs)msg.obj;
            dismissDialog(args.add ? TAGGING_DIALOG : UNTAGGING_DIALOG);
            Core.showToast(ArticleViewActivity.this,
                          getString(args.add ? R.string.tagged
                                    : R.string.untagged));
            updateArticleIcons();
            break;
        }
      }
    };

  private void showArticles() {
    String feedTitle = Html.fromHtml(Core.client.currentFeed.title).toString();
    setTitle(getString(R.string.app_name)
             +": " +feedTitle);

    if (Core.client.currentIndex < 0
        || Core.client.currentIndex > Core.client.currentFeed.entries.size())
      Core.client.currentIndex = 0;
    showCurrentArticle(false);
  }

  // Expresses a Date in a concise format for human consumption.
  private Calendar nowCalendar = Calendar.getInstance();
  private Calendar articleCalendar = Calendar.getInstance();
  private DateFormat dateFormat
      = DateFormat.getDateInstance(DateFormat.DEFAULT);
  private DateFormat timeFormat
      = DateFormat.getTimeInstance(DateFormat.SHORT);
  private String makeShortDate(Date when) {
    nowCalendar.setTime(new Date());
    articleCalendar.setTime(when);
    if (nowCalendar.after(articleCalendar)
        && nowCalendar.get(Calendar.YEAR)
        == articleCalendar.get(Calendar.YEAR)) {
      int daysAgo = nowCalendar.get(Calendar.DAY_OF_YEAR)
        - articleCalendar.get(Calendar.DAY_OF_YEAR);
      if (daysAgo <= 1) {
        String day = (daysAgo == 0) ? getString(R.string.today_at)
          : getString(R.string.yesterday_at);
        String time = timeFormat.format(when);
        return day +" " + time;
      }
    }
    return dateFormat.format(when);
  }

  // Builds an HTML header with the article title, source and date.
  private String metaInfoHtml(ArticleEntry article) {
    StringBuilder sb = new StringBuilder();
    sb.append("<H1>" + article.title + "</H1>");
    sb.append("<H6>");
    RssFeed rssFeed = article.getRssFeed();
    if (rssFeed != null)
      sb.append("From <B>" + rssFeed.title +"</B>");
    Date when =article. published;
    if (article.updated != article.published)
      when = article.updated;
    if (when != null)
      sb.append("(" + makeShortDate(when) + ")");
    sb.append("</H6>");
    return sb.toString();
  }
  // Produces footer HTML with link to full article.
  private String originalLinkHtml(ArticleEntry article) {
    return String.format("<div><hr><H6><a href=\"%s\">Original article</a></H6></div>",
                         article.link);
  }

  private void showArticle(ArticleEntry article,
                           final boolean isMediaCommand) {
    if (Config.LOGD) Log.d(TAG, "showArticle: " + article.title +" tag " +article.tag);
    setContentView(R.layout.article_view);
    Button next_article_btn = (Button)findViewById(R.id.art_next);
    Button prev_article_btn = (Button)findViewById(R.id.art_prev);
    next_article_btn.setOnClickListener(new Button.OnClickListener() {
        public void onClick(View v) {
          // It's easy to double-click by mistake and skip an article,
          // so ignore clicks within too short an interval.
          long now =SystemClock.uptimeMillis();
          if (now - timeLastArticleNavClick > ARTICLE_NAV_DEBOUNCE_DELAY) {
            timeLastArticleNavClick = now;
            showNextArticle(false);
          }
        }
      });
    prev_article_btn.setOnClickListener(new Button.OnClickListener() {
        public void onClick(View v) {
          long now =SystemClock.uptimeMillis();
          if (now - timeLastArticleNavClick > ARTICLE_NAV_DEBOUNCE_DELAY) {
            timeLastArticleNavClick = now;
            showPreviousArticle(false);
          }
        }
      });
    if (Core.client.currentIndex == 0)
      prev_article_btn.setEnabled(false);

    String metaHtml = metaInfoHtml(article);
    if (Config.LOGD) Log.d(TAG, "meta: " +metaHtml);
    String htmlInput = metaInfoHtml(article) + article.text;
    String baseUrl = article.baseUrl;
    String originalLink = originalLinkHtml(article);
    WebView webView = (WebView) findViewById(R.id.articleBody);

    TalkingWebView.SpokenMessages msgs = new TalkingWebView.SpokenMessages();
    msgs.endOfPage = getString(R.string.end_of_article);
    msgs.topOfPage = getString(R.string.top_of_article);
    msgs.speakParseError = getString(R.string.nothing_to_speak);
    msgs.emptyArticle = getString(R.string.empty_article);

    SetupCallback setupCallback = new SetupCallback() {
        @Override
        public void onParseError(HtmlParseException e) {
          Core.showErrorDialog(ArticleViewActivity.this, e.getMessage(), null);
        }
        @Override
        public void onViewReady() {
          // If we're in foreground or the command comes from a media
          // button, then start reading out loud.
          if (hasWindowFocus() || isMediaCommand)
            talkingWebView.startTalking(true);
        }
      };

    talkingWebView = new TalkingWebView(
        webView, tts, vibrator, powerManager,
        msgs, setupCallback,
        htmlInput, originalLink, baseUrl);

    updateArticleIcons();
  }

  // Puts a star icon on starred articles. TODO: add read state and
  // perhaps others.
  private void updateArticleIcons() {
    if (talkingWebView == null)
      return;
    ArticleEntry article = getCurrentArticle();
    if (article == null)
      return;
    LinearLayout layout = (LinearLayout)findViewById(R.id.mid_icons);
    layout.removeAllViews();
    if (article.categories.contains(Core.http.STARRED_STATE)) {
      FrameLayout frame = new FrameLayout(this);
      frame.setForeground(getResources().getDrawable(R.drawable.star_on));
      layout.addView(frame);
    }
  }

  private void stopTalking() {
    if (talkingWebView != null)
      talkingWebView.stopTalking();
  }
  private boolean isTalking() {
    return talkingWebView != null && talkingWebView.isTalking();
  }

  // Special screen shown when we've read through all the articles we
  // have in the current feed.
  private void showEndOfFeed() {
    setContentView(R.layout.end_of_feed);
    Button begin_btn = (Button)findViewById(R.id.beginning_of_feed);
    Button prev_btn = (Button)findViewById(R.id.art_prev);
    begin_btn.setOnClickListener(new Button.OnClickListener() {
        public void onClick(View v) {
          Core.client.currentIndex = 0;
          showCurrentArticle(false);
        }
      });
    prev_btn.setOnClickListener(new Button.OnClickListener() {
        public void onClick(View v) {
          showPreviousArticle(false);
        }
      });
    ProgressBar progress = (ProgressBar)findViewById(R.id.progress);
    progress.setIndeterminate(true);
    TextView text = (TextView)findViewById(R.id.end_of_feed_title);
    if (Core.isGettingFeed()) {
      // We are getting more articles, waiting on them.
      text.setText(getString(R.string.getting_more_articles));
      tts.speak(getString(R.string.getting_more_articles), 1, null);
      progress.setVisibility(View.VISIBLE);
    } else {
      // No more articles to get.
      text.setText(getString(R.string.end_of_feed));
      tts.speak(getString(R.string.end_of_feed), 1, null);
      progress.setVisibility(View.GONE);
    }
  }

  private KeyHandling.TalkActionHandler endOfFeedTalkAction
    = new KeyHandling.TalkActionHandler() {
        public void stopTalking() {}
        public void startTalking(boolean continueTalking) {
          String msg = Core.isGettingFeed()
              ? getString(R.string.getting_more_articles)
              : getString(R.string.end_of_feed);
          tts.speak(msg, 1, null);
        }
        public void continueTalking() {}
        public void nextUtterance(boolean doSkip, boolean continueTalking) {}
        public void previousUtterance(boolean continueTalking) {}
      };

  private ArticleEntry getCurrentArticle() {
    if (Core.client.currentIndex >= Core.client.currentFeed.entries.size())
      return null;
    return Core.client.currentFeed.entries.get(Core.client.currentIndex);
  }

  private void showCurrentArticle(boolean isMediaCommand) {
    if (Config.LOGD) Log.d(TAG, String.format("showing article %d", Core.client.currentIndex));
    if (Core.client.currentFeed.entries.isEmpty()) {
      Core.showErrorDialog(this, getString(R.string.no_articles), null);
    } else {
      if (Core.client.currentIndex + 3
          >= Core.client.currentFeed.entries.size())
        // Nearing the end of this feed: fetch more articles if possible.
        getContinuationIfNeeded();
      if (talkingWebView != null) {
        // Stop talking and discard the previously shown article's
        // view.
        talkingWebView.kill();
        talkingWebView = null;
      }
      if (Core.client.currentIndex >= Core.client.currentFeed.entries.size()) {
        showingEndOfFeed = true;
        showEndOfFeed();
      } else {
        showingEndOfFeed = false;
        ArticleEntry article = getCurrentArticle();
        showArticle(article, isMediaCommand);
      }
    }
  }

  // User command to skip to next/previous article.
  public void showNextArticle(boolean isMediaCommand) {
    if (waitingForArticles)
      return;
    vibrator.vibrate(JUMP_VIBR_PATTERN, -1);
    ArticleEntry article = getCurrentArticle();
    // Queue up this article to have it marked as read when user asks
    // for the next one.
    if (article != null
        && (!article.categories.contains(Core.http.READ_STATE)
            || article.categories.contains(Core.http.FRESH_STATE))) {
      article.categories.add(Core.http.READ_STATE);
      article.categories.remove(Core.http.FRESH_STATE);
      Core.markAsRead(article.tag);
    }
    ++Core.client.currentIndex;
    if (Core.client.currentIndex > Core.client.currentFeed.entries.size())
      // Wraps around to beginning.
      Core.client.currentIndex = 0;
    showCurrentArticle(isMediaCommand);
  }
  public void showPreviousArticle(boolean isMediaCommand) {
    if (waitingForArticles)
      return;
    vibrator.vibrate(JUMP_VIBR_PATTERN, -1);
    if (Core.client.currentFeed.entries.size() > 0) {
      --Core.client.currentIndex;
      if (Core.client.currentIndex < 0)
        // Wraps around to last article.
        Core.client.currentIndex = Core.client.currentFeed.entries.size() - 1;
    }
    showCurrentArticle(isMediaCommand);
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (doKey(event))
      return true;
    return super.onKeyDown(keyCode, event);
  }
  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event) {
    if (doKey(event))
      return true;
    return super.onKeyUp(keyCode, event);
  }
  private boolean doKey(KeyEvent event) {
    if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
      // Menu replacement for a11y.
      if (event.getAction() == KeyEvent.ACTION_DOWN
          && event.getRepeatCount() == 0)
        showDialog(MENU_DIALOG);
      return true;
    }
    return KeyHandling.onKey(
        event, isTalking(),
        showingEndOfFeed ? endOfFeedTalkAction : talkingWebView,
        this);
  }

  @Override
  public boolean onTrackballEvent(MotionEvent event) {
    return KeyHandling.onTrackballEvent(event, isTalking(),
                                        talkingWebView, this);
  }

  // BroadcastReceiver for media buttons. We register it in onStart
  // and remove it in onStop, all this just so we can get media button
  // events while the screen is locked, but only when our activity was
  // showing. Without this, media button presses while the screen is
  // locked would wake up the music player.
  BroadcastReceiver mediaButtonReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        KeyEvent event = (KeyEvent)
          intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (Config.LOGD) Log.d(TAG, "media button handler: event = " + event);
        abortBroadcast();
        KeyHandling.onKey(event, isTalking(),
                          talkingWebView,
                          ArticleViewActivity.this);
      }
    };
  void registerMediaButtonReceiver() {
    IntentFilter mediaButtonIntentFilter =
      new IntentFilter(Intent.ACTION_MEDIA_BUTTON);
    mediaButtonIntentFilter.setPriority(101);
    registerReceiver(mediaButtonReceiver, mediaButtonIntentFilter);
  }
  void unregisterMediaButtonReceiver() {
    unregisterReceiver(mediaButtonReceiver);
  }

  private void launchHelp() {
    Intent intent = new Intent(this, HelpActivity.class);
    intent.putExtra(EXTRA_HTML_RESOURCE, R.raw.help);
    startActivityForResult(intent, REQUEST_HELP);
  }

  @Override
  public Dialog onCreateDialog(int id) {
    switch (id) {
      case TAGGING_DIALOG:
        return Core.makeProgressDialog(
            this, getString(R.string.tagging), null);
      case UNTAGGING_DIALOG:
        return Core.makeProgressDialog(
            this, getString(R.string.untagging), null);
      case MENU_DIALOG: {
        // A dialog to replace the built-in options menu with
        // something that allows us to speak the current selection.
        final Dialog dialog = new Dialog(this, R.style.Theme_listmenu);
        dialog.setCancelable(true);
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey (DialogInterface dialog,
                                  int keyCode, KeyEvent event) {
              if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0)
                  dismissDialog(MENU_DIALOG);
                return true;
              }
              if (event.getKeyCode() == KeyEvent.KEYCODE_CALL)
                return true;
              return false;
            }
          });
        return dialog;
      }
    }
    return null;
  }

  /* Unused. We use a more accessible custom menu implementation.
  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    super.onCreateOptionsMenu(menu);
    MenuInflater inflater = getMenuInflater();
    inflater.inflate(R.menu.options_menu, menu);
    return true;
  }
  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    if (Config.LOGD) Log.d(TAG, "onPrepareOptionsMenu");
    super.onPrepareOptionsMenu(menu);
    return prepareOptionsMenu(menu);
  }
  */

  // Following fuss is all to provide an alternate implementation of
  // the options menu that allows us to speak the current selection.
  private static class MenuEntry {
    int id, title;
    boolean isEnabled;
    MenuEntry(int id, int title) {
      this.id = id;
      this.title = title;
      this.isEnabled = true;
    }  
  }
  private MenuEntry[] menuEntries = new MenuEntry[] {
    // IWBN to get this from the res/menu/options_menu.xml, but I
    // don't know how. So keep this in sync manually.
    new MenuEntry(R.id.add_star, R.string.options_menu_add_star),
    new MenuEntry(R.id.feeds, R.string.options_menu_feeds),
    new MenuEntry(R.id.refresh, R.string.options_menu_refresh),
    new MenuEntry(R.id.starred, R.string.options_menu_starred),
    new MenuEntry(R.id.reading_list, R.string.options_menu_reading_list),
    new MenuEntry(R.id.recently_read, R.string.options_menu_recently_read),
    new MenuEntry(R.id.settings, R.string.options_menu_settings),
    new MenuEntry(R.id.client_login, R.string.options_menu_client_login),
    new MenuEntry(R.id.help, R.string.options_menu_help)
  };
  MenuEntry findListMenuEntryById(int id) {
    for (int i = 0; i < menuEntries.length; ++i)
      if (menuEntries[i].id == id)
        return menuEntries[i];
    return null;
  }
  private class ListMenuAdapter extends BaseAdapter {
    private LayoutInflater inflater;
    public ListMenuAdapter() {
      inflater = LayoutInflater.from(ArticleViewActivity.this);
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
      MenuEntry entry = menuEntries[position];
      TextView text = (TextView) convertView.findViewById(android.R.id.text1);
      text.setText(entry.title);
      return convertView;
    }
    @Override
    public int getCount() {
      return menuEntries.length;
    }
    @Override
    public Object getItem(int position) {
      return menuEntries[position];
    }
    @Override
    public long getItemId(int position) {
      return menuEntries[position].id;
    }
    @Override
    public boolean hasStableIds() {
      return true;
    }
    @Override
    public boolean areAllItemsEnabled() {
      return false;
    }
    @Override
    public boolean isEnabled(int position) {
      return menuEntries[position].isEnabled;
    }
  }
  @Override
  protected void onPrepareDialog (int id, Dialog dialog) {
    if (id != MENU_DIALOG)
      return;
    prepareOptionsMenu(null);
    dialog.setContentView(R.layout.listmenu);
    ListView listMenu = (ListView)dialog.findViewById(R.id.listmenu);
    ListMenuAdapter menuAdapter = new ListMenuAdapter();
    listMenu.setAdapter(menuAdapter);
    listMenu.setOnItemClickListener(new OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view,
                                int position, long id) {
          if (Config.LOGD) Log.d(TAG, String.format("menu list clicked at %d", position));
          MenuEntry entry = menuEntries[position];
          dismissDialog(MENU_DIALOG);
          doOptionsItemSelected(entry.id);
        }
      });
    listMenu.setOnItemSelectedListener(new OnItemSelectedListener() {
        @Override
        public void onItemSelected(AdapterView<?> parent, View view,
                                int position, long id) {
          // This is the whole point of this alternate menu
          // implementation. Beware the terminology inconsistency: in
          // a ListView, "selected" means having focus, while in a
          // menu, it means having been clicked and chosen.
          MenuEntry entry = menuEntries[position];
          // Speak the current menu item.
          tts.speak(getString(entry.title), 0, null);
        }
        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
      });
    listMenu.requestFocus();
    listMenu.setSelection(0);
  }
  private boolean prepareOptionsMenu(Menu menu) {
    stopTalking();
    // Do we have the list of subscribed feeds to show.
    boolean feedsEnabled = Core.client.haveSubscriptionsList;
    if (menu != null) {
      // This is clumsy but I wanted to keep the code for the usual
      // options menu implementation around. This isn't actually used:
      // with the alternate accessible menu implementation, menu is
      // always null.
      MenuItem feedsOption = menu.findItem(R.id.feeds);
      feedsOption.setEnabled(feedsEnabled);
    }
    findListMenuEntryById(R.id.feeds).isEnabled = feedsEnabled;

    ArticleEntry article = getCurrentArticle();
    boolean starEnabled;
    int starTitle;
    if (article == null) {
      // No current article: cannot add star.
      starEnabled = false;
      starTitle = R.string.options_menu_remove_star;
    } else {
      starEnabled = true;
      // Toggle add / remove star.
      if (article.categories.contains(Core.http.STARRED_STATE))
        starTitle = R.string.options_menu_remove_star;
      else
        starTitle = R.string.options_menu_add_star;
    }
    if (menu != null) {
      // Same as above comment.
      MenuItem starOption = menu.findItem(R.id.add_star);
      starOption.setEnabled(starEnabled);
      starOption.setTitle(starTitle);
    }
    MenuEntry entry = findListMenuEntryById(R.id.add_star);
    entry.title = starTitle;
    entry.isEnabled = starEnabled;
    return true;
  }
  /* unused...
  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (Config.LOGD) Log.d(TAG, "onOptionsItemSelected");
    return doOptionsItemSelected(item.getItemId())
    || super.onOptionsItemSelected(item);
  }
  */
  public boolean doOptionsItemSelected(int itemId) {
    switch(itemId) {
      case R.id.refresh:
        getFeed(Core.client.currentFeed.id,
                Core.client.currentFeed.excludeRead);
        return true;
      case R.id.feeds: {
        Intent intent = new Intent(ArticleViewActivity.this,
                                   FeedsListActivity.class);
        startActivityForResult(intent, REQUEST_FEEDS_LIST);
        return true;
      }
      case R.id.add_star:
        ArticleEntry article = getCurrentArticle();
        boolean adding = !article.categories.contains(Core.http.STARRED_STATE);
        showDialog(adding ? TAGGING_DIALOG : UNTAGGING_DIALOG);
        Core.tagItem(article.tag, adding, Core.http.STARRED_STATE, handler);
        return true;
      case R.id.reading_list:
      case R.id.starred:
      case R.id.recently_read: {
        String feedId = null;
        boolean excludeRead = false;
        switch(itemId) {
          case R.id.reading_list:
            feedId = Core.http.READING_LIST_STATE;
            excludeRead = true;
            break;
          case R.id.starred:
            feedId = Core.http.STARRED_STATE;
            break;
          case R.id.recently_read:
            feedId = Core.http.READ_STATE;
            break;
        }
        getFeed(feedId, excludeRead);
        return true;
      }
      case R.id.settings: {
        Intent intent = new Intent(ArticleViewActivity.this,
                                   PrefsActivity.class);
        startActivityForResult(intent, REQUEST_PREFS);
        return true;
      }
      case R.id.client_login: {
        Intent intent = new Intent(ArticleViewActivity.this,
                                   ClientLoginActivity.class);
        intent.putExtra(Core.EXTRA_LOGIN_USER_REQUEST, true);
        startActivityForResult(intent, REQUEST_CLIENT_LOGIN);
        return true;
      }
      case R.id.help: {
        launchHelp();
        return true;
      }
      default:
        return false;
    }
  }
  @Override
  public void onOptionsMenuClosed(Menu menu) {
    if (Config.LOGD) Log.d(TAG, "onOptionsMenuClosed");
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode,
                                  Intent data) {
    switch(requestCode) {
      case Core.REQUEST_LOGIN:
        // Dispatch to common handling.
        Core.handleLoginActivityResult(this, resultCode, data);
        break;
      case REQUEST_FEEDS_LIST:
        switch (resultCode) {
          case RESULT_OK: {
            // New feed chosen.
            String feedId = data.getStringExtra(EXTRA_FEED_ID);
            getFeed(feedId, true);
            break;
          }
        }
        break;
      case REQUEST_CLIENT_LOGIN:
        switch (resultCode) {
          case RESULT_OK:
            Core.client = new ReaderClientData();
            init();
            break;
        }
        break;
      case REQUEST_HELP: {
        // First run experience done.
        SharedPreferences prefs
          = getSharedPreferences(Core.PREFS_NAME, MODE_PRIVATE);
        boolean ranBefore = prefs.getBoolean(Core.PREFS_RAN_BEFORE, false);
        if (!ranBefore) {
          Editor editor = prefs.edit();
          editor.putBoolean(Core.PREFS_RAN_BEFORE, true);
          editor.commit();
        }
        break;
      }
      case REQUEST_PREFS:
        break;
    }
  }
}
