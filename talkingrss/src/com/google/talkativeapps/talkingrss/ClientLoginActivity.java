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
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;

import com.google.talkativeapps.talkingrss.ReaderHttp;

/* Activity that prompts for an E-mail account and password and logs
 * in using an http request.
 *
 * This activity can be invoked eitherfrom Core.login() or from
 * ArticleView's options menu.
 */

public class ClientLoginActivity extends Activity {
  private static final String TAG = "talkingrss-login";

  private static final int LOGGING_IN_DIALOG = 1;

  private boolean isUserRequest;  // true when called from the options menu.
  private boolean canceled;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    if (Config.LOGD) Log.d(TAG, "onCreate");
    super.onCreate(savedInstanceState);
    setVolumeControlStream(AudioManager.STREAM_MUSIC);
    boolean onAuthExpired
      = getIntent().getBooleanExtra(Core.EXTRA_LOGIN_ON_AUTH_EXPIRED, false);
    isUserRequest
        = getIntent().getBooleanExtra(Core.EXTRA_LOGIN_USER_REQUEST, false);
    SharedPreferences prefs
      = getSharedPreferences(Core.PREFS_NAME, MODE_PRIVATE);
    if (onAuthExpired || isUserRequest) {
      Editor editor = prefs.edit();
      editor.putString(Core.PREFS_CLIENT_LOGIN_AUTH_TOKEN, null);
      editor.commit();
    } else {
      String authToken
          = prefs.getString(Core.PREFS_CLIENT_LOGIN_AUTH_TOKEN, null);
      if (authToken != null && authToken.length() > 0) {
        // Use cached auth token.
        Core.http.setAuthToken(authToken);
        setResult(Activity.RESULT_OK);
        finish();
        return;
      }
    }
    showLoginScreen();
  }

  @Override
  protected void onResume() {
    if (Config.LOGD) Log.d(TAG, "onResume");
    super.onResume();
    Core.announceKeyguard();
  }

  private void showLoginScreen() {
    if (Config.LOGD) Log.d(TAG, "showLoginScreen");
    setContentView(R.layout.login);

    Button button = (Button)findViewById(R.id.login_button);
    final EditText emailEdit = (EditText)findViewById(R.id.login_email);
    final EditText passwordEdit = (EditText)findViewById(R.id.login_password);

    // Preset E-mail if remembered.
    SharedPreferences prefs
      = getSharedPreferences(Core.PREFS_NAME, MODE_PRIVATE);
    String emailPref = prefs.getString(Core.PREFS_CLIENT_LOGIN_EMAIL, null);
    if (emailPref != null && emailPref.length() > 0) {
      emailEdit.setText(emailPref);
    }

    button.setOnClickListener(new Button.OnClickListener() {
        public void onClick(View v) {
          String email = emailEdit.getText().toString();
          String password = passwordEdit.getText().toString();
          // Remember the E-mail.
          SharedPreferences prefs
            = getSharedPreferences(Core.PREFS_NAME, MODE_PRIVATE);
          Editor editor = prefs.edit();
          editor.putString(Core.PREFS_CLIENT_LOGIN_EMAIL, email);
          editor.commit();
          // If a field is empty just do nothing.
          if (password.length() == 0)
            return;
          doLogin(email, password);
        }
      });

    // Spoken indications.
    button.setOnFocusChangeListener(Core.focusAnnouncer);
    emailEdit.setOnFocusChangeListener(Core.focusAnnouncer);
    emailEdit.addTextChangedListener(Core.talkingTextWatcher);
    passwordEdit.setOnFocusChangeListener(new View.OnFocusChangeListener() {
        public void onFocusChange(View v, boolean hasFocus) {
          if (hasFocus)
            // don't speak the value outloud.
            Core.tts.speak(getString(R.string.login_password_hint), 0, null);
        }
      });
  }

  @Override
  public Dialog onCreateDialog(int id) {
    switch (id) {
      case LOGGING_IN_DIALOG:
        return Core.makeProgressDialog(
            this, getString(R.string.logging_in),
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

  String email;
  private void doLogin(String email, String password) {
    this.email = email;
    showDialog(LOGGING_IN_DIALOG);
    Core.clientLogin(email, password, handler);
  }

  private Handler handler = new Handler() {
      @Override
      public void handleMessage(Message msg) {
        if (canceled)
          return;
        // Dispatch to common error handling code.
        if (Core.handleErrorMessage(
                ClientLoginActivity.this, msg,
                new Core.OnErrorDismissListener() {
                  public void onErrorDismissed() {
                    setResult(RESULT_CANCELED);
                    finish();
                  }
                }))
          return;
        switch (msg.what) {
          case Core.MSG_LOGGED_IN_OK:
            String authToken = (String)msg.obj;
            Core.http.setAuthToken(authToken);
            SharedPreferences prefs
              = getSharedPreferences(Core.PREFS_NAME, MODE_PRIVATE);
            Editor editor = prefs.edit();
            editor.putString(Core.PREFS_CLIENT_LOGIN_AUTH_TOKEN, authToken);
            editor.commit();
            dismissDialog(LOGGING_IN_DIALOG);
            Core.showToast(ClientLoginActivity.this,
                           getString(R.string.logged_in_ok));
            setResult(Activity.RESULT_OK);
            finish();
            break;
          case Core.MSG_LOGIN_FAILED:
            dismissDialog(LOGGING_IN_DIALOG);
            // Clear password field for next attempt.
            EditText passwordEdit
              = (EditText)findViewById(R.id.login_password);
            passwordEdit.setText("");
            passwordEdit.requestFocus();
            Core.showErrorDialog(ClientLoginActivity.this,
                                 getString(R.string.login_failed), null);
            break;
        }
      }
    };
}
