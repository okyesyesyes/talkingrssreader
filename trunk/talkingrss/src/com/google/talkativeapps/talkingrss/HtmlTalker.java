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

import org.htmlparser.Node;
import org.htmlparser.nodes.TextNode;
import org.htmlparser.nodes.RemarkNode;
import org.htmlparser.nodes.TagNode;
import org.htmlparser.Attribute;
import org.htmlparser.Parser;
import org.htmlparser.util.NodeList;
import org.htmlparser.util.ParserException;
import org.htmlparser.util.Translate;
import org.htmlparser.tags.CompositeTag;
import org.htmlparser.tags.Html;
import org.htmlparser.tags.Span;
import org.htmlparser.PrototypicalNodeFactory;

import java.util.Vector;
import java.util.HashMap;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import android.util.Config;
import android.util.Log;

/** HtmlTalker: parses HTML, splits in utterances for spoken output
 * and adds spoken/audio indications. Produces slightly filtered HTML
 * with spans added corresponding to spoken utterances.
 */

/* The initial implementation would cut up the html into snippets
   corresponding to utterances, inserting bogus end tags and reopening
   them. The idea was to put those snippets into a ListView.

   When I began this class I did not know about the possibility of
   fairly effective communication between the app and the javascript
   running in a WebView. It would be interesting to explore extracting
   elements to speak in javascript directly from the live DOM, as
   opposed to the offline HTML preprocessing done here.
 */

public class HtmlTalker {
  private static final String TAG = "talkingrss-html";

  // Adds htmlparser support for blockquote.
  public static class BlockQuoteTag extends CompositeTag {
    private static final String[] mIds = new String[] {"BLOCKQUOTE"};    
    private static final String[] mEnders = new String[] {"ADDRESS", "BLOCKQUOTE", "NOFRAMES", };
    private static final String[] mEndTagEnders = new String[] {"BODY", "HTML"};
    public BlockQuoteTag() {
    }
    public String[] getIds ()
    {
      return (mIds);
    }
    public String[] getEnders ()
    {
      return (mEnders);
    }
    public String[] getEndTagEnders ()
    {
      return (mEndTagEnders);
    }
  }

  // Exception on parsing failure.
  public static class HtmlParseException extends Exception {
    public HtmlParseException(Throwable cause) {
      super(cause);
    }
  }

  // The output of the parse is a series of utterances, and each
  // utterance is a series of SpeechElements.
  public static abstract class SpeechElement {
    public abstract int spokenTextLength();
  }
  // Spoken text.
  public static class SpokenText extends SpeechElement {
    String text;
    public SpokenText(String text) {
      this.text = text;
    }
    public int spokenTextLength() {
      return text.length();
    }
    public String toString() {
      return text;
    }
  }
  // Generated indication.
  public static abstract class IndicationElement extends SpeechElement {
    public int spokenTextLength() {
      return 0;
    }
  }
  // An indication spoken out in words.
  public static class SpokenIndication extends IndicationElement {
    String text;
    private SpokenIndication(String text) {
      this.text = text;
    }
    public String toString() {
      return text;
    }
    public static final SpokenIndication articleWithNoText
        = new SpokenIndication("article with no text");
    public static final SpokenIndication image
        = new SpokenIndication("image");
    public static final SpokenIndication imageLink
        = new SpokenIndication("image link");
    public static final SpokenIndication beginQuote
        = new SpokenIndication("begin quote");
    public static final SpokenIndication endQuote
        = new SpokenIndication("end quote");
    public static final SpokenIndication beginTable
        = new SpokenIndication("begin table");
    public static final SpokenIndication endTable
        = new SpokenIndication("end table");
  }
  // An indication as a short sound clip, identified by name.
  public static class EarconIndication extends IndicationElement {
    String name;
    private EarconIndication(String name) {
      this.name = name;
    }
    public String toString() {
      return name;
    }
    public static final EarconIndication silence
        = new EarconIndication("SILENCE");
    public static final EarconIndication breakFlow
        = new EarconIndication("breakflow");
    public static final EarconIndication link
        = new EarconIndication("TICK");
    public static final EarconIndication list_item
        = new EarconIndication("TOCK");
    // Need some for headings, list items, tr/td...
  }

  // Specifies beginning and end indications by HTML tag.
  private static class IndicationsInfo {
    IndicationElement begin;
    IndicationElement end;
    IndicationsInfo(IndicationElement begin, IndicationElement end) {
      this.begin = begin;
      this.end = end;
    }
  }
  private static HashMap<String, IndicationsInfo> indications;
  static {
    indications = new HashMap<String, IndicationsInfo>();
    indications.put(
        "BLOCKQUOTE", new IndicationsInfo(
            SpokenIndication.beginQuote, SpokenIndication.endQuote));
    indications.put(
        "TABLE", new IndicationsInfo(
            SpokenIndication.beginTable, SpokenIndication.endTable));
    // TODO: nice, but ought not to have breakflow before it.
    indications.put(
        "LI", new IndicationsInfo(
            EarconIndication.list_item, null));
  }

  // An utterance: a series of SpeechElements.
  public static class Utterance extends ArrayList<SpeechElement> {
    int numChars;  // count of text chars, excluding indications.
    StringBuilder builder = new StringBuilder();
    void addText(String txt) {
      builder.append(txt +" ");
      numChars += txt.length();
    }
    // Flushes text accumulated in the builder into a new SpokenText
    // element.
    void flush() {
      if (builder.length() > 0) {
        super.add(new SpokenText(builder.toString()));
        builder = new StringBuilder();
      }
    }
    void addIndication(IndicationElement e) {
      flush();
      super.add(e);
    }
  }
  private Utterance currentUtterance = new Utterance();
  ArrayList<Utterance> utterances = new ArrayList<Utterance>();

  // Adds currentUtterance to |utterances| and starts a new utterance.
  private void finishUtterance() {
    currentUtterance.flush();
    utterances.add(currentUtterance);
    currentUtterance = new Utterance();
  }

  // Filtered HTML output, with <span> tags inserted, corresponding to
  // spoken utterances. Multiple span tags may be produced for each
  // utterance (roughly one span for every added piece of
  // SpokenText). Span tags have IDs of the form utt%d_%d with the
  // first number representing the utterance index (in the
  // |utterances| array), and the second number being a 0-based
  // counter.
  StringBuilder fullHtml = new StringBuilder();
  int numberOfSpans;  // Counter for spans in current utterance
  // Number of spans in each utterance. Parallel array with |utterances|.
  ArrayList<Integer> numberOfSpansPerUtterance = new ArrayList<Integer>();

  // Appends a piece of HTML to the HTML output, to be wrapped in a
  // <span> tag corresponding to the current utterance.
  private void addFullHtmlSpan(String html) {
    fullHtml.append(
        String.format(
            "<span id=\"utt%d_%d\">%s</span>",
            utterances.size(), numberOfSpans++, html));
  }
  // Appends a piece of HTML to the HTML output, not wrapped into a
  // span. This is for HTML markup that is not spoken.
  private void addFullHtmlRaw(String html) {
    fullHtml.append(html);
  }

  // Wraps up the spans count when moving on to a new utterance.
  private void finishFullHtmlUtterance() {
    numberOfSpansPerUtterance.add(numberOfSpans);
    numberOfSpans = 0;
  }

  private Node root;  // sentinel single top of document tree node.

  // Finishes the current utterance, called when we encounter a tag
  // that breaks the flow of text. A silence indication is added to
  // the finished utterance to denote the break in text flow.
  private void doBreakFlow(TagNode tag) {
    if (currentUtterance.numChars == 0) {
      if (currentUtterance.isEmpty() && utterances.size() > 0)
        currentUtterance.addIndication(EarconIndication.breakFlow);
      return;
    }
    currentUtterance.addIndication(EarconIndication.silence);
    finishUtterance();
    currentUtterance.addIndication(EarconIndication.breakFlow);
    finishFullHtmlUtterance();
  }

  private HtmlTalker() {
  }

  // Regexp to find the end of a sentence.
  private static final Pattern sentenceBreak = Pattern.compile(
      "(?<![.].)[.!?][.!?)\"]*\\s");
  private Matcher sentenceMatcher = sentenceBreak.matcher("");

  public void doParse(String htmlString)
      throws HtmlParseException {
    Parser p = new Parser();
    PrototypicalNodeFactory factory = new PrototypicalNodeFactory ();
    factory.registerTag(new BlockQuoteTag());
    p.setNodeFactory (factory);
    NodeList list;
    try {
      p.setInputHTML(htmlString);
      list = p.parse (null);
    } catch(ParserException e) {
      throw new HtmlParseException(e);
    }
    if (list == null)
      return;
    // The parser gives us a NodeList. We insert a single root node on top of it.
    root = new Html();
    root.setChildren(list);
    for (int i = 0; i < list.size(); ++i) {
      Node l = list.elementAt(i);
      l.setParent(root);
    }

    Node n = root.getFirstChild();
    // Traverse the tree.
    while (n != null) {
      boolean doRecurse = true;
      if (n instanceof TextNode) {
        String txt = n.getText().trim();
        if (txt.length() > 0) {
          txt = Translate.decode(txt) +" ";
          sentenceMatcher.reset(txt);
          int start = 0;
          // Start new utterances on sentence boundaries.
          while(sentenceMatcher.find()) {
            int end = sentenceMatcher.end();
            int length = end - start;
            // Keep the same utterance if it has less than 30chars.
            if (currentUtterance.numChars + length > 30) {
              String sentence = txt.substring(start, end);
              if (start == 0 && currentUtterance.builder.length() > 0) {
                // Zap the trailing space at the end of the utterance builder.
                // As when <a href="...">This</a>.
                currentUtterance.builder.deleteCharAt(
                    currentUtterance.builder.length() - 1);
              }
              currentUtterance.addText(sentence);
              addFullHtmlSpan(Translate.encode(sentence));
              finishUtterance();
              finishFullHtmlUtterance();
              start = end;
            }
          }
          if (start < txt.length()) {
            String remaining = txt.substring(start);
            currentUtterance.addText(remaining);
            addFullHtmlSpan(Translate.encode(remaining));
          }
        }
      } else if (n instanceof RemarkNode) {
        // skip it.
      } else if (n instanceof TagNode) {
        TagNode tag = (TagNode)n;
        if (tag.breaksFlow()) {
          doBreakFlow(tag);
        }
        if (tag.getTagName().equals("SCRIPT")
                   || tag.getTagName().equals("IFRAME")) {
          // skip that.
          doRecurse = false;
        } else {
          boolean addHtmlAsSpan = false;
          // Lookup indication by tag.
          IndicationsInfo info = indications.get(tag.getTagName());
          if (info != null && info.begin != null) {
            currentUtterance.addIndication(info.begin);
          } else if (tag.getTagName().equals("A")
                     && tag.getAttribute("href") != null) {
            currentUtterance.addIndication(EarconIndication.link);
          } else if (tag.getTagName().equals("IMG")) {
            String alt = tag.getAttribute("alt");
            if (alt != null
                && (alt = alt.trim()).length() > 0) {
              currentUtterance.addIndication(SpokenIndication.image);
              currentUtterance.addText(Translate.decode(alt));
              addHtmlAsSpan = true;
            } else if (tag.getParent() != null
                       && ((TagNode)tag.getParent())
                              .getTagName().equals("A")) {
              currentUtterance.addIndication(SpokenIndication.imageLink);
              addHtmlAsSpan = true;
            }
          }
          if (addHtmlAsSpan)
            addFullHtmlSpan("<" + tag.getText() + ">");
          else
            addFullHtmlRaw("<" + tag.getText() + ">");
        }
      }
      Node next = null;
      if (doRecurse) {
        next = n.getFirstChild();
        if (next != null) {
          n = next;
          continue;
        }
      }
      next = n.getNextSibling();
      while (next == null) {
        Node parent = n.getParent();
        if (parent == root) {
          // We are done, just need to wrap up the current utterance.
          if (!currentUtterance.isEmpty()) {
            finishUtterance();
            finishFullHtmlUtterance();
          }
          return;
        }
        n = parent;
        if (n instanceof CompositeTag) {
          CompositeTag tag = (CompositeTag)n;
          // Lookup closing indication by tag.
          IndicationsInfo info = indications.get(tag.getTagName());
          if (info != null) {
            if (info.end != null)
              currentUtterance.addIndication(info.end);
          }
          Node closeTag = tag.getEndTag();
          if (closeTag != null)
            addFullHtmlRaw("<" + closeTag.getText() + ">");
          if (tag.breaksFlow())
            doBreakFlow(tag);
        }
        next = n.getNextSibling();
      }
      n = next;
    }
  }

  // Parses a string of HTML. The output is just left in |utterances|,
  // |fullHtml| and numberOfSpansPerUtterance|.

  public static HtmlTalker parse(String htmlString)
    throws HtmlParseException {
    HtmlTalker htmlTalker = new HtmlTalker();
    htmlTalker.doParse(htmlString);
    return htmlTalker;
  }
}
