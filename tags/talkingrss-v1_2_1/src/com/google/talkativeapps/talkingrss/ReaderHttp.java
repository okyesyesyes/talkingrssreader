/*
 * Copyright (C) 2009 Google Inc.
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

/**
 * Talking RSS Reader.
 *
 * @author sdoyon@google.com (Stephane Doyon)
 */

package com.google.talkativeapps.talkingrss;

import android.util.Config;
import android.util.Log;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.net.SocketException;
import java.net.URLEncoder;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.NameValuePair;
import org.apache.http.StatusLine;

import com.google.talkativeapps.talkingrss.ReaderExceptions.UnexpectedException;
import com.google.talkativeapps.talkingrss.ReaderExceptions.ReaderException;
import com.google.talkativeapps.talkingrss.ReaderExceptions.NetworkException;
import com.google.talkativeapps.talkingrss.ReaderExceptions.ProtocolException;
import com.google.talkativeapps.talkingrss.ReaderExceptions.HttpException;
import com.google.talkativeapps.talkingrss.ReaderExceptions.HttpForbiddenException;

/** ReaderHttp: Connecting to the unofficial Google Reader API over HTTP. */

public class ReaderHttp {
  private static final String TAG = "talkingrss-http";

  private static final String AGENT_STRING = "Talkingrss-0.1";

  private static final String COOKIE_NAME = "SID";
  private static final String GOOGLE_LOGIN_URL =
      "https://www.google.com/accounts/ClientLogin";
  private static final String READER_BASE_URL
      = "http://www.google.com/reader/";
  private static final String API_URL = READER_BASE_URL + "api/0/";
  private static final String TOKEN_URL = API_URL + "token";
  private static final String SUBSCRIPTION_URL = API_URL + "subscription/edit";
  private static final String TAG_URL = API_URL + "edit-tag";
  private static final String DISABLE_TAG_URL = API_URL + "disable-tag";
  private static final String ATOM_URL = READER_BASE_URL + "atom/";
  public static final String READING_LIST_STATE =
      "user/-/state/com.google/reading-list";
  public static final String READ_STATE =
      "user/-/state/com.google/read";
  public static final String KEEP_UNREAD_STATE =
      "user/-/state/com.google/keep-unread";
  public static final String FRESH_STATE =
      "user/-/state/com.google/fresh";
  public static final String STARRED_STATE =
      "user/-/state/com.google/starred";
  public static final String BROADCAST_STATE =
      "user/-/state/com.google/broadcast";
  private static final String USER_LABEL = "user/-/label/";
  public static final String FEED_PREFIX = "feed/";
  public static final String TAG_LIST = "tag/list";
  public static final String SUBSCRIPTION_LIST = "subscription/list";
  public static final String UNREAD_COUNT_LIST = "unread-count";
  public static final int MAX_NUM_ITEMS_TO_TAG = 250;

  private static final boolean verbose = false;

  private String clientParam;
  private String authToken;  // non-c18n SID
  private String apiToken;
  ThreadLocal<HttpClient> httpClientPerThread
      = new ThreadLocal<HttpClient>();

  public ReaderHttp() {
    try {
      clientParam = "client=" + URLEncoder.encode(AGENT_STRING, "UTF-8");
    } catch(UnsupportedEncodingException e) {
      throw new UnexpectedException(e);
    }
  }

  public void setAuthToken(String authToken) {
    this.authToken = authToken;
  }

  private HttpClient getHttpClient() {
    HttpClient httpClient = httpClientPerThread.get();
    if (httpClient == null) {
      httpClient = new DefaultHttpClient();
      httpClientPerThread.set(httpClient);
    }
    return httpClient;
  }

  public static String urlEncode(String s) {
    try {
      return URLEncoder.encode(s, "UTF-8");
    } catch(UnsupportedEncodingException e) {
      throw new UnexpectedException(e);
    }
  }

  // Encodes HTTP query parameters.
  public static String EncodeQueryParams(ArrayList<NameValuePair> params) {
    String query_string = "";
    // There's got to be a simpler way to do this...
    for (NameValuePair n : params) {
      try {
        query_string += (query_string.length() == 0 ? "" : "&")
            + URLEncoder.encode(n.getName(), "UTF-8")
            + "=" + URLEncoder.encode(n.getValue(), "UTF-8");
      } catch(UnsupportedEncodingException e) {
        throw new UnexpectedException(e);
      }
    }
    return query_string;
  }

  // Performs an HTTP request (GET or POST) and returns an InputStream
  // to the reply.
  private InputStream doHttpRequest(HttpUriRequest request)
      throws ReaderException {
    try {
      HttpResponse response = getHttpClient().execute(request);
      StatusLine status = response.getStatusLine();
      if (status.getStatusCode() != HttpStatus.SC_OK) {
        request.abort();
        throw HttpException.httpException(status.getStatusCode(),
                                          status.getReasonPhrase());
      }
      HttpEntity reply = response.getEntity();
      if (reply == null) {
        request.abort();
        throw new ProtocolException("null response entity");
      }
      return reply.getContent();  // an InputStream
    } catch (IOException e) {
      request.abort();
      throw new NetworkException(e);
    }
  }

  // Prepares and performs a POST request.
  private InputStream doPost(String url,
                                    ArrayList<NameValuePair> params,
                                    boolean addAuthCookieAndApiToken)
      throws ReaderException {
    if (verbose)
      Log.d(TAG, url);
    HttpPost http_post = new HttpPost(url);
    if (addAuthCookieAndApiToken) {
      String cookie = String.format("%s=%s; domain=.google.com; path=/",
                                    COOKIE_NAME, authToken);
      http_post.addHeader("Cookie", cookie);
      if (params != null) {
        params.add(new BasicNameValuePair("client", AGENT_STRING));
        params.add(new BasicNameValuePair("T", apiToken));
      }
    }
    try {
      UrlEncodedFormEntity post_entity = new UrlEncodedFormEntity(params);
      /*
      if (verbose) {
        try {
        // TODO: following does not actually work on the phone.
          post_entity.writeTo(System.out);
          System.out.println();
          System.out.println();
        } catch(IOException e) {
          e.printStackTrace();
        }
      }
      */
      http_post.setEntity(post_entity);
    } catch(UnsupportedEncodingException e) {
      throw new UnexpectedException(e);
    }
    return doHttpRequest(http_post);
  }

  // Prepares and performs a GET request.
  private InputStream doGet(String url,
                                  ArrayList<NameValuePair> params)
      throws ReaderException {
    String query_string = "";
    if (params != null)
      query_string = EncodeQueryParams(params);
    query_string += (query_string.length() == 0 ? "" : "&")
        + clientParam;
    url += "?" + query_string;
    if (verbose)
      Log.d(TAG, url);
    HttpGet http_get = new HttpGet(url);
    String cookie = String.format("%s=%s; domain=.google.com; path=/",
                                  COOKIE_NAME, authToken);
    http_get.addHeader("Cookie", cookie);
    return doHttpRequest(http_get);
  }

  // Reads an InputStream until the end and returns the content in a String.
  private static String readAll(InputStream stream)
      throws NetworkException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(stream), 16*1024);
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
      throw new NetworkException(e);
    } finally {
      try {
        reader.close();
      } catch(IOException e) {}
    }
    return sw.toString();
  }

  // ClientLogin.
  public String login(String username, String password)
      throws ReaderException {
    if (Config.LOGD) Log.d(TAG, "Logging in");
    long startTime = SystemClock.uptimeMillis();
    ArrayList<NameValuePair> params = new ArrayList<NameValuePair>();
    params.add(new BasicNameValuePair("accountType", "HOSTED_OR_GOOGLE"));
    params.add(new BasicNameValuePair("Email", username));
    params.add(new BasicNameValuePair("Passwd", password));
    params.add(new BasicNameValuePair("service", "reader"));
    params.add(new BasicNameValuePair("source", AGENT_STRING));
    String reply;
    try {
      reply = readAll(doPost(GOOGLE_LOGIN_URL, params, false));
    } catch(HttpForbiddenException e) {
      return null;
    }
    long now = SystemClock.uptimeMillis();
    if (Config.LOGD) Log.d(TAG, String.format("Login request took %dms", now-startTime));
    int index = reply.indexOf("SID=");
    if (index > -1) {
      int end = reply.indexOf('\n', index);
      if (end > index + 4) {
        String authToken = reply.substring(index + 4, end);
        return authToken;
      }
    }
    throw new ProtocolException("Failed to parse login reply");
  }

  public String getArticlesByTag(String tag, String[] exclude,
                                 int howMany, String continuation)
      throws ReaderException {
    // Escaping is unclear. Feed in the form http://blabla.com/bla
    // strangely mustn't be escaped, else they won't be
    // recognized. Spaces in user labels need %20 escaping, unclear
    // about other chars.
    if (tag.startsWith("user/") && tag.contains("/label/")) {
      int index = tag.lastIndexOf("/");
      if (index > 0 && index < tag.length() - 1) {
        String tip = tag.substring(index + 1);
        tip = urlEncode(tip).replace("+", "%20");
        tag = tag.substring(0, index + 1) + tip;
      }
    }
    String url = ATOM_URL + tag;
    ArrayList<NameValuePair> params = new ArrayList<NameValuePair>();
    params.add(new BasicNameValuePair("n", String.valueOf(howMany)));
    if (continuation != null)
      params.add(new BasicNameValuePair("c", continuation));
    if (exclude != null) {
      for (String x : exclude) {
        params.add(new BasicNameValuePair("xt", x));
      }
    }
    long startTime = SystemClock.uptimeMillis();
    InputStream is = doGet(url, params);
    long readTime = SystemClock.uptimeMillis();
    String out = readAll(is);
    long now = SystemClock.uptimeMillis();
    if (Config.LOGD) Log.d(TAG, String.format("Article fetch: request took %dms, transferred %dbytes in %dms", readTime-startTime, out.length(), now-readTime));
    return out;
  }

  /*
  public static String userLabel(String label) {
    return USER_LABEL + urlEncode(label);
  }
  */

  public String getList(String list)
    throws ReaderException {
    String url = API_URL + list;
    ArrayList<NameValuePair> params = new ArrayList<NameValuePair>();
    params.add(new BasicNameValuePair("output", "xml"));
    //all=true
    long startTime = SystemClock.uptimeMillis();
    InputStream is = doGet(url, params);
    long readTime = SystemClock.uptimeMillis();
    String out = readAll(is);
    long now = SystemClock.uptimeMillis();
    if (Config.LOGD) Log.d(TAG, String.format("list fetch: request took %dms, transferred %dbytes in %dms", readTime-startTime, out.length(), now-readTime));
    return out;
  }

  // Obtain the short-lived token used to authenticate API operations.
  private void fetchApiToken()
      throws ReaderException {
    if (Config.LOGD) Log.d(TAG, "Fetching API token");
    apiToken = readAll(doGet(TOKEN_URL, null));
  }

  // Performs a POST request for an API operation, first obtaining an
  // API token if we don't have one, and retrying the operation with a
  // fresh token should the current token expire.
  private void doApiPost(String url,
                                ArrayList<NameValuePair> params)
      throws ReaderException {
    long startTime = SystemClock.uptimeMillis();
    boolean do_retry = true;
    if (apiToken == null) {
      fetchApiToken();
      do_retry = false;
    }
    try {
      checkApiOk(doPost(url, params, true));
    } catch(HttpException e) {
      if (do_retry
          && (e.code == HttpStatus.SC_BAD_REQUEST
              || e.code == HttpStatus.SC_UNAUTHORIZED)) {
        // Get a fresh token
        fetchApiToken();
        checkApiOk(doPost(url, params, true));
      } else {
        throw e;
      }
    }
    long now = SystemClock.uptimeMillis();
    if (Config.LOGD) Log.d(TAG, String.format("API request took %dms", now-startTime));
  }

  // Validates the "OK" reply for a successful API operation.
  private static void checkApiOk(InputStream stream)
      throws NetworkException, ProtocolException {
    String reply = readAll(stream).trim();
    if (!reply.equals("OK"))
      throw new ProtocolException("API failure");
  }

  public void subscribeFeed(String feedId, boolean add)
      throws ReaderException {
    String action = add ? "subscribe" : "unsubscribe";
    if (Config.LOGD) Log.d(TAG, String.format("%s feed: %s", action, feedId));
    if (!feedId.startsWith(FEED_PREFIX))
        throw new IllegalArgumentException();
    ArrayList<NameValuePair> params = new ArrayList<NameValuePair>();
    params.add(new BasicNameValuePair("s", feedId));
    params.add(new BasicNameValuePair("ac", action));
    doApiPost(SUBSCRIPTION_URL, params);
  }

  public void tagItems(ArrayList<String> itemIds,
                       String[] addTags, String[] removeTags)
      throws ReaderException {
    if (Config.LOGD) Log.d(TAG, "Tagging " +String.valueOf(itemIds.size()) + " items");
    ArrayList<NameValuePair> params = new ArrayList<NameValuePair>();
    for (String item : itemIds)
      params.add(new BasicNameValuePair("i", item));
    if (addTags != null) {
      for (String addTag : addTags) {
        params.add(new BasicNameValuePair("a", addTag));
      }
    }
    if (removeTags != null) {
      for (String removeTag : removeTags) {
        params.add(new BasicNameValuePair("r", removeTag));
      }
    }
    params.add(new BasicNameValuePair("ac", "edit"));
    doApiPost(TAG_URL, params);
  }

  public void tagFeed(String feedId, String tag, boolean add)
      throws ReaderException {
    if (Config.LOGD) Log.d(TAG, String.format("%s feed %s as %s", (add ? "tagging" : "untagging"), feedId, tag));
    ArrayList<NameValuePair> params = new ArrayList<NameValuePair>();
    params.add(new BasicNameValuePair("s", feedId));
    params.add(new BasicNameValuePair(add ? "a" : "r", tag));
    params.add(new BasicNameValuePair("ac", "edit"));
    doApiPost(SUBSCRIPTION_URL, params);
  }

  public void disableTag(String tag)
      throws ReaderException {
    if (Config.LOGD) Log.d(TAG, "Disabling tag: " + tag);
    ArrayList<NameValuePair> params = new ArrayList<NameValuePair>();
    params.add(new BasicNameValuePair("s", tag));
    params.add(new BasicNameValuePair("ac", "disable-tags"));
    doApiPost(DISABLE_TAG_URL, params);
  }
}
