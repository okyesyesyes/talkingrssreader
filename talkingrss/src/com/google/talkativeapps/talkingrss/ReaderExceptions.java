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

/** Collection of exceptions for ReaderHttp and ReaderClientData errors. */

public class ReaderExceptions {
  public static class UnexpectedException extends RuntimeException {
    public UnexpectedException(String message) {
      super(message);
    }
    public UnexpectedException(Throwable cause) {
      super(cause);
    }
    public UnexpectedException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static class ReaderException extends Exception {
    public ReaderException(String message) {
      super(message);
    }
    public ReaderException(Throwable cause) {
      super(cause);
    }
    public ReaderException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static class NetworkException extends ReaderException { 
    public NetworkException(Throwable cause) {
      super(cause);
    }
  }

  public static class ProtocolException extends ReaderException { 
    public ProtocolException(String message) {
      super(message);
    }
  }

  public static class HttpException extends ReaderException {
    public int code;
    private HttpException(int code, String reason) {
      super("http: " +reason);
       this.code = code;
    }
    public static HttpException httpException(int code, String message) {
      switch (code) {
        case 403:
          return new HttpForbiddenException();
        case 401:
          return new HttpUnauthorizedException();
        default:
          return new HttpException(code, message);
      }
    }
  }
  public static class HttpForbiddenException extends HttpException {
    public HttpForbiddenException() {
      super(403, "Forbidden");
    }
  }
  public static class HttpUnauthorizedException extends HttpException {
    public HttpUnauthorizedException() {
      super(401, "Unauthorized");
    }
  }

  public static class ReaderParseException extends ReaderException {
    public ReaderParseException(Throwable e) {
      super(e);
    }
  }
}
