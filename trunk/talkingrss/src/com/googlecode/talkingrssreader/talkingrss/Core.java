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
import android.app.KeyguardManager;
import android.content.DialogInterface.OnClickListener;
import android.content.DialogInterface.OnKeyListener;
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
import android.widget.EditText;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.text.TextWatcher;
import android.text.Editable;
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
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.text.Collator;

import com.google.tts.TTS;

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

/* This class holds some reader state and code shared across
   activities. Everything in this class is static. */

public abstract class Core {
  private static final String TAG = "talkingrss-core";

  static TTS tts;
  static KeyguardManager keyguardManager;

  static ReaderHttp http = new ReaderHttp();
  static ReaderClientData client = new ReaderClientData();

  static final String PREFS_NAME = "talkingrss";
  static final String PREFS_RAN_BEFORE = "ranBefore";
  static final String PREFS_CLIENT_LOGIN_EMAIL = "client_login_email";
  static final String PREFS_CLIENT_LOGIN_AUTH_TOKEN
    = "client_login_auth_token";

  // Subactivity codes. We can call the login subactivity from any
  // activity (when the auth token expires), so the following request
  // code must be unique across all activities in this app that call
  // handleErrorMessage().
  static final int REQUEST_LOGIN = 999;
  static final String EXTRA_LOGIN_ON_AUTH_EXPIRED = "isAuthExpired";
  static final String EXTRA_LOGIN_USER_REQUEST = "isUserRequest";
  static final int RESULT_LOGIN_FAILED = Activity.RESULT_FIRST_USER;
  static final String EXTRA_LOGIN_ERROR = "error";

  static Context context;

  static String getString(int resourceId) {
    if (context == null)
      return "";
    else
      return context.getString(resourceId);
  }

  // Run this on returning from a successful login subactivity.
  private static Runnable loginPendingRunnable;

  // Logs in. isAuthExpired indicates that the current auth token
  // isn't working, is presumably expired and must be discarded. if
  // isAuthExpired is false, then a previously cached auth token may
  // be used.
  static void login(Activity activity, boolean isAuthExpired,
                      Runnable loginPendingRunnable) {
    Core.loginPendingRunnable = loginPendingRunnable;
    Intent intent;
    intent = new Intent(activity,
                        ClientLoginActivity.class);
    intent.putExtra(EXTRA_LOGIN_ON_AUTH_EXPIRED, isAuthExpired);
    activity.startActivityForResult(intent, REQUEST_LOGIN);
  }
  static void handleLoginActivityResult(
      Activity activity, int resultCode, Intent data) {
    switch (resultCode) {
      case Activity.RESULT_OK:
        if (loginPendingRunnable != null) {
          Runnable r = loginPendingRunnable;
          loginPendingRunnable = null;
          r.run();
        }
        break;
      case Activity.RESULT_CANCELED:
        // Backing out of login: cancel calling activity.
        activity.setResult(Activity.RESULT_CANCELED);
        activity.finish();
        break;
      case RESULT_LOGIN_FAILED:
        String errorMsg = data.getStringExtra(EXTRA_LOGIN_ERROR);
        showErrorDialog(activity, errorMsg, null);
        break;
    }
  }

  static void announceKeyguard() {
    if (keyguardManager != null && tts != null
        && keyguardManager.inKeyguardRestrictedInputMode())
      tts.speak("Press menu to unlock", 0, null);
  }

  // Use as a focus listener to Announce Buttons or EditTexts.
  static View.OnFocusChangeListener focusAnnouncer
    = new View.OnFocusChangeListener() {
        public void onFocusChange(View v, boolean hasFocus) {
          if (hasFocus) {
            TextView textView = (TextView)v;
            if (textView == null)
              return;
            int enqueue = 0;
            if (v instanceof EditText) {
              tts.speak(getString(R.string.edit_text), 0, null);
              enqueue = 1;
            }
            String text = textView.getText().toString();
            if (text.length() == 0) {
              CharSequence hint = textView.getHint();
              if (hint != null)
                text = hint.toString();
            }
            if (text.length() != 0)
              Core.tts.speak(text, enqueue, null);
          }
        }
      };

  static TextWatcher talkingTextWatcher = new TextWatcher() {
      @Override
      public void afterTextChanged(Editable s) {
      }
      @Override
      public void beforeTextChanged(CharSequence s, int start,
                                    int before, int after) {
        if (before == 0) {
          // nothing.
        } else if (after == 0) {
          tts.speak(s.toString().substring(start, start + before), 0, null);
          tts.speak(getString(R.string.text_deleted), 1, null);
        }
      }
      @Override
      public void onTextChanged(CharSequence s, int start,
                                int before, int after) {
        if (before == 0) {
          tts.speak(s.toString().substring(start, start + after), 0, null);
        } else if (after == 0) {
          // nothing.
        } else {
          tts.speak(s.toString(), 0, null);
        }
      }
    };

  interface OnErrorDismissListener {
    void onErrorDismissed();
  }

  // Shows an error dialog.
  static void showErrorDialog(Activity activity, final String message,
                              final OnErrorDismissListener listener) {
    Log.e(TAG, "showErrorDialog: " + message);
    AlertDialog dialog = new AlertDialog.Builder(activity)
      .setMessage((String)message)
      .setCancelable(false)
      .setPositiveButton(
          R.string.error_dialog_ok, new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int which) {
                if (listener != null)
                  listener.onErrorDismissed();
              }
            })
        .setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface dialog,
                                 int keyCode, KeyEvent event) {
              if (keyCode == KeyEvent.KEYCODE_CALL) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) {
                  tts.speak(message, 0, null);
                }
                return true;
              }
              return false;
            }
          })
      .create();
    dialog.show();
    if (tts != null)
      tts.speak(message, 0, null);
  }

  interface OnProgressCancelListener {
    void onCancel();
  }

  // Prepares a cancelable dialog with a message and a progressbar.
  static Dialog makeProgressDialog(Activity activity, final String message,
                                   final OnProgressCancelListener listener) {
    ProgressDialog dialog = new ProgressDialog(activity) {
        @Override
        public void onWindowFocusChanged (boolean hasFocus) {
          super.onWindowFocusChanged(hasFocus);
          if (hasFocus)
            tts.speak(message, 0, null);
        }
      };
    dialog.setIndeterminate(true);
    dialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
    dialog.setMessage(message);
    dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
        @Override
        public boolean onKey(DialogInterface dialog,
                             int keyCode, KeyEvent event) {
          if (keyCode == KeyEvent.KEYCODE_CALL) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
              tts.speak(message, 0, null);
            }
            return true;
          }
          return false;
        }
      });
    dialog.setCancelable(listener != null);
    if (listener != null) {
      dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
          @Override
          public void onCancel(DialogInterface dialog) {
            listener.onCancel();
          }
        });
    }
    return dialog;
  }

  static void showToast(Activity activity, String message) {
    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    tts.speak(message, 0, null);
  }

  // Handler message codes: errors or results from background tasks.
  static final int MSG_EXPIRED_AUTH = 1;
  static final int MSG_READER_ERROR = 2;
  static final int MSG_GOT_ARTICLES = 10;
  static final int MSG_GOT_LISTS_INFO = 11;
  static final int MSG_SUBSCRIBED_OK = 12;
  static final int MSG_TAGGED_OK = 13;
  static final int MSG_LOGGED_IN_OK = 14;
  static final int MSG_LOGIN_FAILED = 15;

  // Handle some errors from background tasks. Shared code between
  // several activities.
  static boolean handleErrorMessage(Activity activity, Message msg,
                                    OnErrorDismissListener listener) {
    switch (msg.what) {
    case MSG_EXPIRED_AUTH:
      Log.i(TAG, "auth expired 401 error");
      // Login / get a fresh auth token.
      // TODO: ideally we'd want to retry whatever failed after
      // re-authenticating. But that's tricky, and expirations are
      // pretty rare vs the life-time of an app.
      login(activity, true, null);
      return true;
    case MSG_READER_ERROR:
      showErrorDialog(activity, (String)msg.obj, listener);
      return true;
    }
    return false;  // Message code not handled here.
  }

  // Background thread that fetches reader feeds. Has a corresponding
  // httpclient instance in thread local storage in ReaderHttp. The
  // thread stays alive between requests so the httpclient may
  // reuse connections. If a new request comes in for a new
  // feed while a previous request is being served, then the thread is
  // killed and restarted.
  static class FeedGetterThread extends HandlerThread {
    // Command to fetch a feed.
    public static final int MSG_GET_ARTICLES = 1;
    // Argument: feed to fecth. Set this before issuing the
    // command. Cleared when done.
    volatile ReaderAtomFeed atomFeed;
    class MyHandler extends Handler {
      private MyHandler(Looper looper) {
        super(looper);
      }
      @Override
      public void handleMessage(Message msg) {
        switch (msg.what) {
          case MSG_GET_ARTICLES:
            Handler replyHandler = (Handler)msg.obj;
            getArticles(replyHandler);
            break;
        }
      }
    }
    MyHandler handler;
    public FeedGetterThread() {
      super("FeedGetterThread");
      start();
      handler = new MyHandler(getLooper());
    }
    private void getArticles(Handler replyHandler) {
      String continuation = atomFeed.continuation;
      try {
        if (Config.LOGD) Log.d(TAG, "FeedGetter starting");
        // Either it's an empty feed, or we're fetching a continuation.
        if (!atomFeed.entries.isEmpty() && atomFeed.continuation == null)
          throw new RuntimeException();
        int nArticles = atomFeed.entries.size();
        final String[] excludeStates
          = atomFeed.excludeRead ? new String[] { http.READ_STATE }
            : null;
        // Get just 5 to begin with. The user can start on those while
        // we get more.
        atomFeed.continuation = null;
        String xmlString = http.getArticlesByTag(
            atomFeed.id, excludeStates,
            5, continuation);
        atomFeed.parse(xmlString);
        int nNewArticles = atomFeed.entries.size() - nArticles;
        if (Config.LOGD) Log.d(TAG, String.format("Got %d new articles", nNewArticles));
        replyHandler.sendMessage(replyHandler.obtainMessage(
                                     MSG_GOT_ARTICLES, atomFeed));
        if (atomFeed.continuation != null) {
          if (Config.LOGD) Log.d(TAG, "FeedGetter starting second round");
          continuation = atomFeed.continuation;
          atomFeed.continuation = null;
          nArticles = atomFeed.entries.size();
          xmlString = http.getArticlesByTag(
              atomFeed.id, excludeStates,
              25, continuation);
          atomFeed.parse(xmlString);
          nNewArticles = atomFeed.entries.size() - nArticles;
          if (Config.LOGD) Log.d(TAG, String.format("Got %d new articles", nNewArticles));
          replyHandler.sendMessage(replyHandler.obtainMessage(
                                       MSG_GOT_ARTICLES, atomFeed));
        }
        if (Config.LOGD) Log.d(TAG, "FeedGetter done");
      } catch (HttpUnauthorizedException e) {
        if (Config.LOGD) Log.d(TAG, "FeedGetter: auth expired");
        atomFeed.continuation = continuation;
        replyHandler.sendMessage(replyHandler.obtainMessage(MSG_EXPIRED_AUTH));
      } catch (ReaderException e) {
        atomFeed.continuation = continuation;
        Log.w(TAG, "Got ReaderException");
        e.printStackTrace();
        replyHandler.sendMessage(replyHandler.obtainMessage(
                                     MSG_READER_ERROR, e.getMessage()));
      } finally {
        atomFeed = null;
      }
    }
  }
  private static FeedGetterThread feedGetterThread;

  // Sends the command to request the current feed in background.
  static void getFeed(Handler replyHandler) {
    if (feedGetterThread != null
        && feedGetterThread.atomFeed != null) {
      if (feedGetterThread.atomFeed == client.currentFeed)
        return;
      // There is an existing thread busy on a different feed: kill it.
      feedGetterThread.getLooper().quit();
      feedGetterThread.interrupt();
      feedGetterThread = null;
    }
    if (feedGetterThread == null) {
      feedGetterThread = new FeedGetterThread();
    }
    feedGetterThread.atomFeed = client.currentFeed;
    feedGetterThread.handler.sendMessage(
        feedGetterThread.handler.obtainMessage(
            feedGetterThread.MSG_GET_ARTICLES,
            replyHandler));
  }

  static boolean isGettingFeed() {
    return feedGetterThread != null
      && feedGetterThread.atomFeed == client.currentFeed;
  }

  // Background thread that handles assorted reader requests: getting
  // subscriptions and other lists, subscribing and tagging. This one
  // also has a corresponding httpclient in thread local storage in
  // ReaderHttp. But this one we don't interrupt, we wait for each
  // operation to complete.
  static class ReaderOpsThread extends HandlerThread {
    // Commands and arguments:
    public static final int MSG_LOGIN = 1;
    static class LoginArgs {
      String email, password;
      Handler replyHandler;
      LoginArgs(String email, String password, Handler replyHandler) {
        this.email = email;
        this.password = password;
        this.replyHandler = replyHandler;
      }
    }
    public static final int MSG_GET_LISTS_INFO = 2;
    public static final int MSG_SUBSCRIBE = 3;  // and unsubscribe
    static class SubscribeArgs {
      String feedId;
      boolean isSubscribe;  // else unsubscribe
      Handler replyHandler;
      SubscribeArgs(String feedId, boolean isSubscribe, Handler replyHandler) {
        this.feedId = feedId;
        this.isSubscribe = isSubscribe;
        this.replyHandler = replyHandler;
      }
    }
    public static final int MSG_TAG_ITEM = 4;
    static class TagItemArgs {
      String itemId;
      boolean add;  // else remove
      String tag;
      Handler replyHandler;
      TagItemArgs(String itemId, boolean add, String tag,
                  Handler replyHandler) {
        this.itemId = itemId;
        this.add = add;
        this.tag = tag;
        this.replyHandler = replyHandler;
      }
    }

    class MyHandler extends Handler {
      private MyHandler(Looper looper) {
        super(looper);
      }
      @Override
      public void handleMessage(Message msg) {
        switch (msg.what) {
          case MSG_LOGIN: {
            LoginArgs args = (LoginArgs)msg.obj;
            doClientLogin(args.email, args.password, args.replyHandler);
            break;
          }
          case MSG_GET_LISTS_INFO: {
            Handler replyHandler = (Handler)msg.obj;
            getListsInfo(replyHandler);
            break;
          }
          case MSG_SUBSCRIBE: {
            SubscribeArgs args = (SubscribeArgs)msg.obj;
            doSubscribe(args.feedId, args.isSubscribe, args.replyHandler);
            break;
          }
          case MSG_TAG_ITEM: {
            TagItemArgs args = (TagItemArgs)msg.obj;
            doTagItem(args);
            break;
          }
        }
      }
    }
    MyHandler handler;
    public ReaderOpsThread() {
      super("ReaderOpsThread");
      start();
      handler = new MyHandler(getLooper());
    }

    private void doClientLogin(String email, String password,
                               Handler replyHandler) {
      try {
        String authToken = http.login(email, password);
        if (authToken == null) {
          replyHandler.sendMessage(replyHandler.obtainMessage(
              MSG_LOGIN_FAILED));
        } else {
          if (Config.LOGD) Log.d(TAG, "Logged in OK");
          replyHandler.sendMessage(replyHandler.obtainMessage(
              MSG_LOGGED_IN_OK, authToken));
        }
      } catch (ReaderException e) {
        Log.w(TAG, "Got ReaderException");
        e.printStackTrace();
        replyHandler.sendMessage(replyHandler.obtainMessage(
            MSG_READER_ERROR, e.getMessage()));
      }
    }
    private void doGetListsInfo()
        throws HttpUnauthorizedException, ReaderException {
      String xmlString1 = http.getList(http.TAG_LIST);
      String xmlString2 = http.getList(http.SUBSCRIPTION_LIST);
      String xmlString3 = http.getList(http.UNREAD_COUNT_LIST);
      client.resetListsInfo(xmlString1, xmlString2, xmlString3);
    }
    private void getListsInfo(Handler replyHandler) {
      try {
        if (Config.LOGD) Log.d(TAG, "ReaderOps starting");
        doGetListsInfo();
        if (Config.LOGD) Log.d(TAG, String.format("Done parsing lists info, %d feeds, %d labels",
                                                  client.rssFeeds.size(), client.userLabels.size()));
        replyHandler.sendMessage(replyHandler.obtainMessage(
                                     MSG_GOT_LISTS_INFO));
      } catch (HttpUnauthorizedException e) {
        if (Config.LOGD) Log.d(TAG, "ReaderOps: auth expired");
        replyHandler.sendMessage(replyHandler.obtainMessage(MSG_EXPIRED_AUTH));
      } catch (ReaderException e) {
        Log.w(TAG, "Got ReaderException");
        e.printStackTrace();
        replyHandler.sendMessage(replyHandler.obtainMessage(
                                     MSG_READER_ERROR, e.getMessage()));
      }
    }
    private void doSubscribe(String feedId, boolean isSubscribe,
                             Handler replyHandler) {
      try {
        http.subscribeFeed(feedId, isSubscribe);
        // Get subscription list back.
        if (Config.LOGD) Log.d(TAG, "Freshening lists");
        doGetListsInfo();
        if (Config.LOGD) Log.d(TAG, String.format("Done parsing lists info, %d feeds, %d labels",
                                                  client.rssFeeds.size(), client.userLabels.size()));
        replyHandler.sendMessage(replyHandler.obtainMessage(
                                     MSG_SUBSCRIBED_OK));
      } catch (HttpUnauthorizedException e) {
        if (Config.LOGD) Log.d(TAG, "ReaderOps: auth expired");
        replyHandler.sendMessage(replyHandler.obtainMessage(MSG_EXPIRED_AUTH));
      } catch (ReaderException e) {
        Log.w(TAG, "Got ReaderException");
        e.printStackTrace();
        replyHandler.sendMessage(replyHandler.obtainMessage(
                                     MSG_READER_ERROR, e.getMessage()));
      }
    }
    private void doTagItem(TagItemArgs args) {
      try {
        ArrayList<String> items = new ArrayList<String>(1);
        items.add(args.itemId);
        String[] tags = new String[] { args.tag };
        http.tagItems(items,
                      args.add ? tags : null,
                      args.add ? null : tags);
        // Modify cached state correspondingly.
        ArticleEntry article = client.articles.get(args.itemId);
        if (article != null) {
          if (args.add)
            article.categories.add(args.tag);
          else
            article.categories.remove(args.tag);
        }
        args.replyHandler.sendMessage(args.replyHandler.obtainMessage(
            MSG_TAGGED_OK, args));
      } catch (HttpUnauthorizedException e) {
        if (Config.LOGD) Log.d(TAG, "ReaderOps: auth expired");
        args.replyHandler.sendMessage(args.replyHandler.obtainMessage(
            MSG_EXPIRED_AUTH));
      } catch (ReaderException e) {
        Log.w(TAG, "Got ReaderException");
        e.printStackTrace();
        args.replyHandler.sendMessage(args.replyHandler.obtainMessage(
            MSG_READER_ERROR, e.getMessage()));
      }
    }
  }
  private static ReaderOpsThread readerOpsThread;

  // Request to perform a client login in background.
  static void clientLogin(String email, String password,
                          Handler replyHandler) {
    ReaderOpsThread.LoginArgs args = new ReaderOpsThread.LoginArgs(
        email, password, replyHandler);
    readerOpsThread.handler.sendMessage(
        readerOpsThread.handler.obtainMessage(
            ReaderOpsThread.MSG_LOGIN, args));
  }

  // Sends the command to get the subscriptions list in background
  // (and tags and unread counts lists which are not yet used).
  static void getListsInfo(Handler replyHandler) {
    readerOpsThread.handler.sendMessage(
        readerOpsThread.handler.obtainMessage(
            ReaderOpsThread.MSG_GET_LISTS_INFO, replyHandler));
  }

  // Sends the command to (un)subscribe in background.
  static void subscribe(String feedId, boolean isSubscribe, Handler replyHandler) {
    ReaderOpsThread.SubscribeArgs args = new ReaderOpsThread.SubscribeArgs(
        feedId, isSubscribe, replyHandler);
    readerOpsThread.handler.sendMessage(
        readerOpsThread.handler.obtainMessage(
            ReaderOpsThread.MSG_SUBSCRIBE, args));
  }

  // Sends the command to tag an article in background.
  static void tagItem(String itemId, boolean add, String tag,
                      Handler replyHandler) {
    ReaderOpsThread.TagItemArgs args = new ReaderOpsThread.TagItemArgs(
        itemId, add, tag, replyHandler);
    readerOpsThread.handler.sendMessage(
        readerOpsThread.handler.obtainMessage(
            ReaderOpsThread.MSG_TAG_ITEM, args));
  }

  // Background thread that marks articles as read. This one handles a
  // queue of requests.

  // I'm seeing seemingly spurious exceptions from
  // LinkedBlockingQueue. So I just rolled my own, it's not rocket
  // science.
  private static class QueueItem {
    String item;  // article tag
    QueueItem next;
    QueueItem(String item) {
      this.item = item;
    }
  }

  static class ReadMarkerThread extends Thread {
    volatile boolean quit;  // Indication to terminate.
    QueueItem queueHead, queueTail;
    Object queueLock = new Object();
    private Handler errorHandler;
    ReadMarkerThread(Handler errorHandler) {
      this.errorHandler = errorHandler;
    }
    public void run() {
      final String[] addStates = new String[] { http.READ_STATE };
      final String[] removeStates = new String[] { http.FRESH_STATE };
      ArrayList<String> itemsToMark = new ArrayList<String>();
      // Stop when quit is set but only after finishing processing the
      // entire queue.
      while (!(quit && queueHead == null)) {
        synchronized (queueLock) {
          if (queueHead == null) {
            // Queue is empty, wait.
            try {
              queueLock.wait();
            } catch (InterruptedException e) {}
            continue;
          }
          // Google Reader will accept a batch of articles in one request.
          while (queueHead != null
                 && itemsToMark.size() < http.MAX_NUM_ITEMS_TO_TAG) {
            itemsToMark.add(queueHead.item);
            queueHead = queueHead.next;
          }
          if (queueHead == null)
            queueTail = null;
        }  // End of locked block.
        if (Config.LOGD) Log.d(TAG, String.format("readMarkerThread: marking %d items", itemsToMark.size()));
        try {
          http.tagItems(itemsToMark, addStates, removeStates);
          itemsToMark.clear();
        } catch (HttpUnauthorizedException e) {
          if (Config.LOGD) Log.d(TAG, "ReadMarkerThread: auth expired");
          errorHandler.sendMessage(
              errorHandler.obtainMessage(MSG_EXPIRED_AUTH));
        } catch (ReaderException e) {
          Log.w(TAG, "Got ReaderException");
          e.printStackTrace();
          errorHandler.sendMessage(
              errorHandler.obtainMessage(MSG_READER_ERROR, e.getMessage()));
        }
      }
      if (Config.LOGD) Log.d(TAG, "readMarkerThread quitting");
    }
  }
  static ReadMarkerThread readMarkerThread;

  // Enqueue an article to be marked as read in background.
  static void markAsRead(String itemTag) {
    if (readMarkerThread != null) {
      QueueItem qi = new QueueItem(itemTag);
      synchronized (readMarkerThread.queueLock) {
        if (readMarkerThread.queueTail == null)
          readMarkerThread.queueHead = readMarkerThread.queueTail = qi;
        else
          readMarkerThread.queueTail.next = qi;
        readMarkerThread.queueLock.notifyAll();
      }  // End of locked block.
    }
  }

  // Stop all threads on app exit. ReadMarkerThread will try to finish
  // its work before exiting.
  static void stopThreads() {
    if (feedGetterThread != null) {
      feedGetterThread.getLooper().quit();
      feedGetterThread.interrupt();
      feedGetterThread = null;
    }
    if (readerOpsThread != null) {
      readerOpsThread.getLooper().quit();
      readerOpsThread.interrupt();
      readerOpsThread = null;
    }
    if (readMarkerThread != null) {
      readMarkerThread.quit = true;
      synchronized (readMarkerThread.queueLock) {
        readMarkerThread.queueLock.notifyAll();
      }
      readMarkerThread = null;
    }
  }
  // Start threads on app init.
  static void startThreads(Handler errorHandler) {
    stopThreads();
    feedGetterThread = new FeedGetterThread();
    readerOpsThread = new ReaderOpsThread();
    readMarkerThread = new ReadMarkerThread(errorHandler);
    readMarkerThread.start();
  }
}
