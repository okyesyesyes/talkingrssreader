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

import android.os.Vibrator;
import android.os.SystemClock;
import android.view.View;
import android.view.MotionEvent;
import android.util.Config;
import android.util.Log;
import android.view.KeyEvent;
import android.os.Handler;
import android.os.Message;

import com.googlecode.talkingrssreader.talkingrss.TalkingWebView;

/* Key handling code common to ArticleView and Help activities. */

public abstract class KeyHandling {
  private static final String TAG = "talkingrss-key";

  interface TalkActionHandler {
    void stopTalking();
    void startTalking(boolean continueTalking);
    void continueTalking();
    void nextUtterance(boolean doSkip, boolean continueTalking);
    void previousUtterance(boolean continueTalking);
  }
  interface ContentActionHandler {
    void showNextArticle(boolean isMediaCommand);
    void showPreviousArticle(boolean isMediaCommand);
  }

  private static boolean callIsPressed, didShutup, hadMotion;

  public static boolean onKey(
      KeyEvent event, boolean isTalking,
      TalkActionHandler talkAction, ContentActionHandler contentAction) {
    boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
    int keyCode = event.getKeyCode();
    //if (Config.LOGD && event.getRepeatCount() == 0) Log.d(TAG, String.format("onKey %d %s", keyCode, String.valueOf(down)));
    switch (keyCode) {
      default:
        return false;
      // Some phones don't have a CAMERA button, so we'll use CALL.
      case KeyEvent.KEYCODE_CALL:
        if (down && event.getRepeatCount() == 0) {
          callIsPressed = true;
          if (talkAction != null) {
            if (isTalking) {
              // Shutup on press.
              talkAction.stopTalking();
              // Remember that it was talking and we did shut it up on
              // the press event.
              didShutup = true;
            } else {
              // Wasn't talking, no action on press.
              didShutup = false;
            }
          }
          hadMotion = false;
        } else if(!down) {
          if (talkAction != null) {
            if (hadMotion && didShutup) {
              // Trackball was used to skip to prev/next sentence
              // since we pressed. When initially we pressed, we
              // interrupted speech. So on release, allow it to
              // continue talking beyond just one sentence.
              talkAction.continueTalking();
            } else if (!hadMotion && !didShutup) {
              // Trackball wasn't used to skip to prev/next sentence
              // since we pressed, and when we pressed it wasn't yet
              // talking, so begin talking on release.
              talkAction.startTalking(true);
            }
          }
          callIsPressed = false;
          didShutup = false;
        }
        break;
      case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
      case KeyEvent.KEYCODE_MEDIA_STOP:
      case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
      case KeyEvent.KEYCODE_MEDIA_REWIND:
      case KeyEvent.KEYCODE_MEDIA_NEXT:
      case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
        if (!down && event.getRepeatCount() == 0) {
          switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
              if (talkAction != null) {
                if (isTalking)
                  talkAction.stopTalking();
                else
                  talkAction.startTalking(true);
              }
              break;
            case KeyEvent.KEYCODE_MEDIA_STOP:
              if (talkAction != null)
                talkAction.stopTalking();
              break;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
              if (talkAction != null)
                talkAction.nextUtterance(false, true);
              break;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
              if (talkAction != null) {
                talkAction.previousUtterance(true);
              }
              break;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
              if (contentAction != null)
                contentAction.showNextArticle(true);
              break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: 
              if (contentAction != null)
                contentAction.showPreviousArticle(true);
              break;
          }
        }
        break;
    }
    return true;
  }

  private static long lastTrackballTriggeredTime, lastTimeTrackballMoved;
  private static boolean trackballIsDown;
  private static float trackballAccumulatedX, trackballAccumulatedY;
  private static final float TRACKBALL_TRIGGER_THRESH = 0.15f;

  public static boolean onTrackballEvent(
      MotionEvent event, boolean isTalking,
      TalkActionHandler talkAction, ContentActionHandler contentAction) {
    if (!isTalking && !callIsPressed)
      return false;
    if (MotionEvent.ACTION_DOWN == event.getAction()) {
      trackballIsDown = true;
      return true;
    } else if (MotionEvent.ACTION_UP == event.getAction()) {
      trackballIsDown = false;
      //select();  // nothing here yet...
      // and reset triggered time below.
    } else if (MotionEvent.ACTION_MOVE == event.getAction()) {
      if (trackballIsDown
          || event.getEventTime() - lastTrackballTriggeredTime < 200)
        return true;
      if (event.getEventTime() - lastTimeTrackballMoved > 200) {
        // If the trackball hasn't moved in a little while, clear the
        // accumulation.
        trackballAccumulatedX = 0;
        trackballAccumulatedY = 0;
      }
      // As long as we move, reset this timer.      lastTimeTrackballMoved = event.getEventTime();
      trackballAccumulatedX += event.getX();
      trackballAccumulatedY += event.getY();
      // No horizontal movement yet...
      /*
      if (Math.abs(trackballAccumulatedY) > 0.15
          && Math.abs(trackballAccumulatedX) > 0.15)
        // Diagonal slip: ignore gesture.
        return true;
      */
      if (trackballAccumulatedY < -TRACKBALL_TRIGGER_THRESH)
        trackballAction(true, talkAction, contentAction);
      else if (trackballAccumulatedY > TRACKBALL_TRIGGER_THRESH)
        trackballAction(false, talkAction, contentAction);
      else
        // do not reset lastTrackballTriggeredTime
        return true;
    }
    lastTrackballTriggeredTime = event.getEventTime();
    return true;
  }

  private static void trackballAction(
      boolean up,
      TalkActionHandler talkAction, ContentActionHandler contentAction) {
    if (callIsPressed)
      hadMotion = true;
    if (talkAction != null) {
      if (up)
        talkAction.previousUtterance(!callIsPressed);
      else
        talkAction.nextUtterance(didShutup, !callIsPressed);
    }
  /* I used to have a binding for next/prev article...:
     if (contentAction != null) {
       if (up)
         contentAction.showPreviousArticle(false);
       else
         contentAction.showNextArticle(false);
       }
  */
  }
}
