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

import android.util.Config;
import android.util.Log;
import android.os.SystemClock;

import java.net.URLEncoder;
import java.net.URLDecoder;
import java.io.InputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.io.StringReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.SimpleTimeZone;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;


import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;

import com.google.talkativeapps.talkingrss.ReaderExceptions.UnexpectedException;
import com.google.talkativeapps.talkingrss.ReaderExceptions.ReaderParseException;

/** ReaderClientData: basic data model for all retrieved Reader feed
 * information, with atom XMl parser.
 */

public class ReaderClientData {
  private static final String TAG = "talkingrss-dat";

  // Threading: this module is accessed by multiple threads: one
  // displaying articles, another parsing and adding more articles, a
  // third applying categories changes when a background tagging
  // requests completes.

  private SAXParser parser;

  public ReaderClientData() {
    try {
      SAXParserFactory factory = SAXParserFactory.newInstance();
      factory.setNamespaceAware(false);
      try {
        factory.setFeature(
            "http://xml.org/sax/features/namespace-prefixes", true);
      } catch(SAXNotRecognizedException e) {
        throw new UnexpectedException(e);
      } catch(SAXNotSupportedException e) {
        throw new UnexpectedException(e);
      }
      parser = factory.newSAXParser();
    } catch (ParserConfigurationException e) {
      throw new UnexpectedException(e);
    } catch (SAXException e) {
      throw new UnexpectedException(e);
    }
  }

  // A feed as returned from http://www.google.com/reader/atom/...
  public class ReaderAtomFeed {
    String id;
    String title;
    String continuation;
    List<ArticleEntry> entries
        = Collections.synchronizedList(new ArrayList<ArticleEntry>());

    // Whether we asked to exclude read items when fetching the
    // feed. Used to make a consistent request on refresh or
    // continuation.
    boolean excludeRead;

    public void parse(String xmlString)
        throws ReaderParseException {
      try {
        synchronized (ReaderClientData.this) {
          long startTime = SystemClock.uptimeMillis();
          parser.parse(new InputSource(new StringReader(xmlString)),
                       new ReaderAtomFeedHandler());
          long now = SystemClock.uptimeMillis();
          if (Config.LOGD) Log.d(TAG, String.format("Parsed atom feed %dbytes in %dms", xmlString.length(), now-startTime));
        }
      } catch (IOException e) {
        throw new UnexpectedException(e);
      } catch (SAXException e) {
        throw new ReaderParseException(e);
      }
    }

    class ReaderAtomFeedHandler extends DefaultHandler {
      private StringBuilder charsAccumulator;
      private ArticleEntry entry;
      private RssFeed sourceFeed;
      private boolean inEntryAuthor;
      private int ignoredLevel;  // depth of ignored tags
      private SimpleDateFormat dateFormat;

      public ReaderAtomFeedHandler() {
        dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        dateFormat.setTimeZone(new SimpleTimeZone(0, "GMT"));
      }

      public Date parseDate(String text) {
        try {
          return dateFormat.parse(text);
        } catch (ParseException e) {
          throw new UnexpectedException("Error parsing date", e);
        }
      }

      @Override
      public void characters(char[] ch, int start, int length) {
        if (charsAccumulator != null)
          charsAccumulator.append(ch, start, length);
      }

      private void ignoredElementStart() {
        ++ignoredLevel;
      }
      private boolean ignoredElementEnd() {
        if (ignoredLevel == 0)
          return false;
        --ignoredLevel;
        return true;
      }
      
      @Override
      public void startElement(String uri, String name, String qName,
                               Attributes attrs) throws SAXException {
        if (ignoredLevel > 0) {
          ignoredElementStart();
          return;
        }
        if (entry == null) {
          if (qName.equals("feed")) {
            // do nothing
          } else if (qName.equals("id") || qName.equals("title")
              || qName.equals("gr:continuation")) {
            charsAccumulator = new StringBuilder();
          } else if (qName.equals("entry")) {
            entry = new ArticleEntry();
          } else {
            ignoredElementStart();
          }
        } else {
          if (!inEntryAuthor && sourceFeed == null) {
            if (qName.equals("id") || qName.equals("title")
                || qName.equals("published") || qName.equals("updated")) {
              charsAccumulator = new StringBuilder();
            } else if (qName.equals("category")) {
              String term = attrs.getValue("term");
              if (term != null) {
                term = stripUserId(term);
                entry.categories.add(term);
                if (isUserLabel(term) && !userLabels.containsKey(term))
                  userLabels.put(term, new UserLabel(term));
              }
            } else if (qName.equals("summary") || qName.equals("content")) {
              charsAccumulator = new StringBuilder();
              entry.baseUrl = attrs.getValue("xml:base");
            } else if (qName.equals("link")) {
              entry.link = attrs.getValue("href");
            } else if (qName.equals("author")) {
              inEntryAuthor = true;
            } else if (qName.equals("source")) {
              String id = attrs.getValue("gr:stream-id");
              entry.feedId = id;
              if (id == null || rssFeeds.containsKey(id)) {
                ignoredElementStart();
              } else {
                sourceFeed = new RssFeed();
                sourceFeed.id = id;
              }
            } else {
              ignoredElementStart();
            }
          } else if (inEntryAuthor && qName.equals("name")) {
            charsAccumulator = new StringBuilder();
          } else if (sourceFeed != null && qName.equals("title")) {
            charsAccumulator = new StringBuilder();
          } else {
            ignoredElementStart();
          }
        }
      }

      @Override
      public void endElement(String uri, String name, String qName) {
        if (ignoredElementEnd()) {
          return;
        }
        String text = null;
        if (charsAccumulator != null)
          text = charsAccumulator.toString();
        charsAccumulator = null;
        if (entry == null) {
          if (qName.equals("id")) {
            // This is presumably how we fetched it, don't overwrite.
            //id = text;
          } else if (qName.equals("title")) {
            title = unEscapeEntities(text);
          } else if (qName.equals("gr:continuation")) {
            continuation = text;
          }
        } else {
          if (!inEntryAuthor && sourceFeed == null) {
            if (qName.equals("id")) {
              entry.tag = text;
            } else if (qName.equals("title")) {
              entry.title = unEscapeEntities(text);
            } else if (qName.equals("published")) {
              entry.published = parseDate(text);
            } else if (qName.equals("updated")) {
              entry.updated = parseDate(text);
            } else if (qName.equals("summary") || qName.equals("content")) {
              entry.text = unEscapeEntities(text);
            } else if (qName.equals("entry")) {
              articles.put(entry.tag, entry);
              entries.add(entry);
              entry = null;
            }
          } else if (inEntryAuthor) {
            if (qName.equals("name")) {
              entry.author = unEscapeEntities(text);
            } else if (qName.equals("author")) {
              inEntryAuthor = false;
            }
          } else if (sourceFeed != null) {
            if (qName.equals("title")) {
              sourceFeed.title = unEscapeEntities(text);
            } else if (qName.equals("source")) {
              rssFeeds.put(sourceFeed.id, sourceFeed);
              sourceFeed = null;
            }
          }
        }
      }
    }
  }

  public static String unEscapeEntities(String text) {
    return text.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&");
  }
  public static String escapeEntities(String text) {
    return text.replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("&", "&amp;");
  }
  private String stripUserId(String category) {
    if (category.startsWith("user/")) {
      int index = category.indexOf("/", 5);
      if (index > 5) {
        String userId = category.substring(5, index);
        if (thisUserId == null || thisUserId.equals(userId))
          category = category.substring(0, 5) + "-" + category.substring(index);
      }
    }
    return category;
  }
  public static boolean isUserLabel(String category) {
    return category.startsWith("user/")
        && category.contains("/label/");
  }
  public static boolean isState(String category) {
    return category.startsWith("user/")
        && category.contains("/state/com.google/");
  }
  public static String extractLabel(String category) {
    int index = category.lastIndexOf("/");
    if (index >= 0 && index+1 < category.length()) 
      category = category.substring(index + 1);
    return category;
  }
  public static String makeLabel(String labelText) {
    return "user/-/label/"
        + escapeEntities(labelText);
  }

  class ReaderListsHandler extends DefaultHandler {
    private StringBuilder charsAccumulator;
    private boolean inSubscriptionsList;
    private RssFeed feed;
    private boolean inFeedId, inFeedTitle, inFeedCategories;
    private boolean inCategoryEntry, inCategoryEntryId;
    private boolean inUnreadCountsList;
    private boolean inUnreadEntry;
    private boolean inUnreadEntryId, inUnreadEntryCount;
    private String unreadEntryId;
    private boolean inTagsList;
    private boolean inTagEntry;
    private boolean inTagEntryId;
    private int ignoredLevel;  // depth of ignored tags

    @Override
    public void characters(char[] ch, int start, int length) {
      if (charsAccumulator != null)
        charsAccumulator.append(ch, start, length);
    }

    private void ignoredElementStart() {
      ++ignoredLevel;
    }
    private boolean ignoredElementEnd() {
      if (ignoredLevel == 0)
        return false;
      --ignoredLevel;
      return true;
    }
      
    @Override
    public void startElement(String uri, String name, String qName,
                             Attributes attrs) throws SAXException {
      if (ignoredLevel > 0) {
        ignoredElementStart();
        return;
      }
      if (!inSubscriptionsList && !inUnreadCountsList && !inTagsList) {
        if (qName.equals("object")) {
          // do nothing.
        } else if (qName.equals("list")
                   && attrs.getValue("name").equals("subscriptions")) {
          inSubscriptionsList = true;
        } else if (qName.equals("list")
                   && attrs.getValue("name").equals("unreadcounts")) {
          inUnreadCountsList = true;
        } else if (qName.equals("list")
                   && attrs.getValue("name").equals("tags")) {
          inTagsList = true;
        } else {
          ignoredElementStart();
        }
      } else if (inSubscriptionsList) {
        if (feed == null) {
          if (qName.equals("object")) {
            feed = new RssFeed();
          } else {
            ignoredElementStart();
          }
        } else {  // feed != null
          if (!inFeedCategories) {
            if (qName.equals("string")
                && attrs.getValue("name").equals("id")) {
              inFeedId = true;
              charsAccumulator = new StringBuilder();
            } else if (qName.equals("string")
                       && attrs.getValue("name").equals("title")) {
              inFeedTitle = true;
              charsAccumulator = new StringBuilder();
            } else if (qName.equals("list")
                       && attrs.getValue("name").equals("categories")) {
              inFeedCategories = true;
            } else {
              ignoredElementStart();
            }
          } else {  // inFeedCategories
            if (!inCategoryEntry) {
              if (qName.equals("object")) {
                inCategoryEntry = true;
              } else {
                ignoredElementStart();
              }
            } else {  // inCategoryEntry
              if (qName.equals("string")
                  && attrs.getValue("name").equals("id")) {
                inCategoryEntryId = true;
                charsAccumulator = new StringBuilder();
              } else {
                ignoredElementStart();
              }
            }  // end category entry
          }  // end categories
        }  // end feed
      } else if (inUnreadCountsList) {
        if (!inUnreadEntry) {
          if (qName.equals("object")) {
            inUnreadEntry = true;
          } else {
            ignoredElementStart();
          }
        } else {  // inUnreadEntry
          if (qName.equals("string")
              && attrs.getValue("name").equals("id")) {
            inUnreadEntryId = true;
            charsAccumulator = new StringBuilder();
          } else if (qName.equals("number")
              && attrs.getValue("name").equals("count")) {
            inUnreadEntryCount = true;
            charsAccumulator = new StringBuilder();
          } else {
            ignoredElementStart();
          }
        }  // end unread entry
      } else if (inTagsList) {
        if (!inTagEntry) {
          if (qName.equals("object")) {
            inTagEntry = true;
          } else {
            ignoredElementStart();
          }
        } else {  // inTagEntry
          if (qName.equals("string")
              && attrs.getValue("name").equals("id")) {
            inTagEntryId = true;
            charsAccumulator = new StringBuilder();
          } else {
            ignoredElementStart();
          }
        }  // end tag entry
      }  // end subscriptions list
    }

    @Override
    public void endElement(String uri, String name, String qName) {
      if (ignoredElementEnd()) {
        return;
      }
      String text = null;
      if (charsAccumulator != null)
        text = charsAccumulator.toString();
      charsAccumulator = null;
      if (inSubscriptionsList) {
        if (feed != null) {
          if (!inFeedCategories) {
            if (inFeedId) {
              if (qName.equals("string")) {
                feed.id = text;
                inFeedId = false;
              }
            } else if (inFeedTitle) {
              if (qName.equals("string")) {
                feed.title = unEscapeEntities(text);
                inFeedTitle = false;
              }
            } else {  // !inFeedId && !inFeedTitle
              if (qName.equals("object")) {
                rssFeeds.put(feed.id, feed);
                feed = null;
              }
            }
          } else {  // inFeedCategories
            if (inCategoryEntry) {
              if (inCategoryEntryId) {
                if (qName.equals("string")) {
                  String labelId = stripUserId(unEscapeEntities(text));
                  feed.categories.add(labelId);
                  if (isUserLabel(labelId)
                      && !userLabels.containsKey(labelId))
                    userLabels.put(labelId, new UserLabel(labelId));
                  inCategoryEntryId = false;
                }
              } else {  // !inLabelId
                if (qName.equals("object")) {
                  inCategoryEntry = false;
                }
              }
            } else {  // !inCategoryEntry
              if (qName.equals("list")) {
                inFeedCategories = false;
              }
            }
          }
        } else {  // feed == null
          if (qName.equals("list")) {
            inSubscriptionsList = false;
          }
        }
      } else if (inUnreadCountsList) {
        if (inUnreadEntry) {
          if (inUnreadEntryId) {
            if (qName.equals("string")) {
              unreadEntryId = stripUserId(unEscapeEntities(text));
              inUnreadEntryId = false;
            }
          } else if (inUnreadEntryCount) {
            if (qName.equals("number")) {
              try {
                Integer count = Integer.valueOf(text);
                unreadCounts.put(unreadEntryId, count);
              } catch (NumberFormatException e) {
                new ReaderParseException(e);
              }
              inUnreadEntryCount = false;
            }
          } else {  // !inUnreadEntryId && !inUnreadEntryCount
            if (qName.equals("object")) {
              inUnreadEntry = false;
            }
          }
        } else {  // !inUnreadEntry
          if (qName.equals("list")) {
            inUnreadCountsList = false;
          }
        }
      } else if (inTagsList) {
        if (inTagEntry) {
          if (inTagEntryId) {
            if (qName.equals("string")) {
              String labelId = stripUserId(unEscapeEntities(text));
              if (isUserLabel(labelId)
                  && !userLabels.containsKey(labelId))
                userLabels.put(labelId, new UserLabel(labelId));
              // Hack: get our user id from starred state.
              if (labelId.endsWith("/state/com.google/starred")
                  && labelId.startsWith("user/")) {
                int index = labelId.indexOf("/", 5);
                if (index > 5) {
                  String myUserId = labelId.substring(5, index);
                  if (myUserId.equals("-"))
                    ;  // do nothing
                  else if (thisUserId == null)
                    thisUserId = myUserId;
                  else if (!thisUserId.equals(myUserId))
                    Log.w(TAG, String.format("Inconsistent user ID: %s vs %s", thisUserId, myUserId));
                }
              }
              inTagEntryId = false;
            }
          } else {  // !inTagEntryId
            if (qName.equals("object")) {
              inTagEntry = false;
            }
          }
        } else {  // !inTagEntry
          if (qName.equals("list")) {
            inTagsList = false;
          }
        }
      }
    }
  }

  // One article.
  class ArticleEntry {
    String tag, title = "", link, author, baseUrl, feedId, text = "";
    Set<String> categories
      = Collections.synchronizedSet(new HashSet<String>());
    Date published, updated;

    // Returns the id of the RSS feed from which this article was obtained.
    public RssFeed getRssFeed() {
      return rssFeeds.get(feedId);
    }
  }

  // An RSS feed.
  static class RssFeed {
    String id;
    String title;
    Set<String> categories
      = Collections.synchronizedSet(new HashSet<String>());
  }

  // A label (aka tag / folder) defined by the user.
  static class UserLabel {
    String id, label;
    //boolean pub;  // TODO.
    public UserLabel(String id) {
      this.id = id;
      this.label = extractLabel(id);
    }
  }

  // Map of RSS feeds by id.
  Map<String, RssFeed> rssFeeds
      = Collections.synchronizedMap(new HashMap<String, RssFeed>());
  // Map of user-defined labels by id.
  Map<String, UserLabel> userLabels
      = Collections.synchronizedMap(new HashMap<String, UserLabel>());
  // Map of unread count by label or feed or state id.
  Map<String, Integer> unreadCounts
      = Collections.synchronizedMap(new HashMap<String, Integer>());
  // Map of articles by tag.
  Map<String, ArticleEntry> articles
      = Collections.synchronizedMap(new HashMap<String, ArticleEntry>());

  // Google Reader feed we want to view.
  ReaderAtomFeed currentFeed;
  // Reading index in currentFeed.
  int currentIndex;

  // Whether we completed the initial fetch of the subscription list.
  boolean haveSubscriptionsList;

  String thisUserId;  // Inferred Google Reader user ID.

  public void parseListsInfo(String xmlString)
      throws ReaderParseException {
    try {
      synchronized (ReaderClientData.this) {
        long startTime = SystemClock.uptimeMillis();
        parser.parse(new InputSource(new StringReader(xmlString)),
                     new ReaderListsHandler());
        long now = SystemClock.uptimeMillis();
        if (Config.LOGD) Log.d(TAG, String.format("Parsed list %dbytes in %dms", xmlString.length(), now-startTime));
      }
    } catch (IOException e) {
      throw new UnexpectedException(e);
    } catch (SAXException e) {
      throw new ReaderParseException(e);
    }
  }

  public void resetListsInfo(String xml1, String xml2, String xml3)
    throws ReaderParseException {
    synchronized (ReaderClientData.this) {
      // Zap previous state and parse all lists.
      rssFeeds.clear();
      userLabels.clear();
      unreadCounts.clear();
      parseListsInfo(xml1);
      parseListsInfo(xml2);
      parseListsInfo(xml3);
      haveSubscriptionsList = true;
    }
  }
}
