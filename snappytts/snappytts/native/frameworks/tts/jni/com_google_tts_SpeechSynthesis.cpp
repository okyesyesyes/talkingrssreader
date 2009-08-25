/*
 * Copyright (C) 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//#define wchar_t owchar_t

#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>

#define LOG_TAG "SpeechSynthesis"

#include <utils/Log.h>
#include <nativehelper/jni.h>
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>
#include <tts/TtsSynthInterface.h>
#include <media/AudioTrack.h>

#include <dlfcn.h>

using namespace android;

static struct fields_t {
  //jfieldID    mNativeContext;
  //jclass      mSpeechSynthesisClass;
    jmethodID   synthesisCompleted; 
} fields;
static fields_t javaTTSFields;
JavaVM *jvm;


struct tts_callback_cookie {
    jclass      tts_class;
    jobject     tts_ref;
};


tts_callback_cookie mCallbackData;
TtsSynthInterface * nativeSynthInterface;
FILE* targetFilePointer;

static AudioTrack* audout;
uint32_t audioTrack_sampleRate;
AudioSystem::audio_format audioTrack_format;
int audioTrack_channelCount;


static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t cond = PTHREAD_COND_INITIALIZER;
// Audio data produced by the TTS synthesis callback and consumed by
// the AudioTrack callback.
static int8_t* audio_buf;
static size_t audio_buf_size;
static int numberOfBuffers;  // How many buffers AudioTrack consumed.
// Flag indicating that the TTS is finished synthesizing the current
// utterance.
static volatile bool isTtsFinished;
// Flag indicating SpeechSynthesis_stop() was called and we want to
// interrupt speech.
static bool isStopping;
// Whether the AudioTrack is started or stopped.
static volatile bool track_started;

// AudioTrack callback.
static void audioCallback(int event, void* user, void *info) {
  switch (event) {
    case AudioTrack::EVENT_MORE_DATA: {
      AudioTrack::Buffer* buf = static_cast<AudioTrack::Buffer*>(info);
      //LOGI("TTS audio cb: EVENT_MORE_DATA %d", buf->size);
      if (buf->size == 0)  // This may happen from what I read.
        return;
      pthread_mutex_lock(&mutex);
      size_t copied = 0;  // Bytes copied to buf.
      while (true) {
        if (isStopping) {
          LOGI("TTS audio cb: interrupting track");
          // Stopping the track from within the callback, and assuming
          // we can rely on the callback not being called again until
          // we restart the track.
          audout->stop();
          audout->flush();
          track_started = false;
          pthread_cond_broadcast(&cond);
          buf->size = 0;
          pthread_mutex_unlock(&mutex);
          return;
        }
        if (!isTtsFinished && audio_buf_size == 0) {
          //LOGI("TTS audio cb MORE_DATA: waiting for audio data");
          pthread_cond_wait(&cond, &mutex);
          continue;
        }
        // How much room remains in buf.
        size_t len = buf->size - copied;
        // How much of that can we fill from audio_buf.
        if (len > audio_buf_size)
          len = audio_buf_size;
        memcpy(buf->i8 + copied, audio_buf, len);
        //LOGI("TTS audio cb MORE_DATA: copied %d bytes", len);
        audio_buf += len;
        audio_buf_size -= len;
        copied += len;
        if (isTtsFinished && numberOfBuffers < 4) {
          // Very short utterances are consistently not spoken. I'm
          // assuming some minimal amount of data is required before
          // kicking the DMA. I also tried doing
          // audout->setPosition(0), which seemed to mostly worked,
          // except sometimes it would catastrophically fail with some
          // "buffer out of range" error. So we'll just pad it up to 4
          // buffers.
          memset(buf->i8 + copied, 0, buf->size - copied);
          copied = buf->size;
        }
        if (audio_buf_size == 0 && !isTtsFinished) {
          //LOGI("TTS audio cb MORE_DATA: signaling synth");
          pthread_cond_broadcast(&cond);
        }
        if (copied == buf->size || isTtsFinished) {
          buf->size = copied;
          ++numberOfBuffers;
          //LOGI("TTS audio cb MORE_DATA: buffer complete size %d isTtsFinished %d", copied, isTtsFinished);
          pthread_mutex_unlock(&mutex);
          // When we've fed all audio data, we'll be returning from
          // here with buf->size = 0. AudioTrack will call us again
          // every 10ms until stopped. We'll wait until
          // EVENT_UNDERRUN. Seems awkward, but I did see some
          // framework code doing exactly this.
          return;
        }
      }
    }
      break;
    case AudioTrack::EVENT_UNDERRUN:
      if (isTtsFinished) {
        pthread_mutex_lock(&mutex);
        LOGI("TTS audio cb: buffer played");
        audout->stop();
        track_started = false;
        JNIEnv *env;
        jvm->AttachCurrentThread(&env, NULL);
        LOGI("Invoking java callback");
        env->CallStaticVoidMethod(
            mCallbackData.tts_class,
            javaTTSFields.synthesisCompleted,
            mCallbackData.tts_ref);
        //LOGI("java callback done");
        jvm->DetachCurrentThread();
        pthread_cond_broadcast(&cond);
        pthread_mutex_unlock(&mutex);
      } else {
        LOGI("TTS audio cb: underrun");
      }
      break;
  };
}

void prepAudioTrack(uint32_t rate, AudioSystem::audio_format format, int channel)
{   
    // Don't bother creating a new audiotrack object if the current 
    // object is already set.
    if ( (rate == audioTrack_sampleRate) && 
         (format == audioTrack_format) && 
         (channel == audioTrack_channelCount) ){
        return;
    }
    if (audout){
        delete audout;
        audout = NULL;
        track_started = false;
    }
    audioTrack_sampleRate = rate;
    audioTrack_format = format;
    audioTrack_channelCount = channel;
    audout = new AudioTrack(AudioSystem::MUSIC, rate, format, channel, rate/4, 0, audioCallback, 0, 0);
    if (audout->initCheck() != NO_ERROR) {
      LOGE("AudioTrack error");
    } else {
      //LOGI("AudioTrack OK");
    }
}

/* Callback from espeak.  Directly speaks using AudioTrack. */
static bool ttsSynthDoneCB(void * userdata, uint32_t rate, AudioSystem::audio_format format, int channel, int8_t *wav, size_t bufferSize) {
    if ((int)userdata == 0){
        ////LOGI("Direct speech");
        if (wav == NULL) {
            LOGI("Null: speech has completed");
            pthread_mutex_lock(&mutex);
            isTtsFinished = true;
            pthread_cond_broadcast(&cond);
            pthread_mutex_unlock(&mutex);
        } else {
          pthread_mutex_lock(&mutex);
          //LOGI("ttsSynthDoneCallback: %d bytes", bufferSize);
          if (bufferSize > 0 && !isStopping) {
            if (!track_started) {
              audio_buf_size = 0;
              numberOfBuffers = 0;
              LOGI("Starting AudioTrack");
              prepAudioTrack(rate, format, channel);
              audout->flush();
              audout->start();
              track_started = true;
            }
            // Point to the data for audioCallback to grab.
            audio_buf = wav;
            audio_buf_size = bufferSize;
            pthread_cond_broadcast(&cond);
            // Wait for audioCallback to consume it.
            while (audio_buf_size && !isStopping) {
              //LOGI("synth cb: waiting for data to be consumed");
              pthread_cond_wait(&cond, &mutex);
            }
            if (isStopping) {
              //LOGI("synth cb: exit on isStopping");
            }
          }
          pthread_mutex_unlock(&mutex);
        }
        return isStopping;
    } else if ((int)userdata == 1){
        LOGI("ttsSynthDoneCallback: %d bytes", bufferSize);
        LOGI("Save to file");
        if (wav == NULL) {
            LOGI("Null: speech has completed");
        }
        if (bufferSize > 0){
            fwrite(wav, 1, bufferSize, targetFilePointer);
        }
    }
    return false;
}

static void
com_google_tts_SpeechSynthesis_native_setup(
    JNIEnv *env, jobject thiz, jobject weak_this, jstring nativeSoLib)
{
    jclass clazz = env->GetObjectClass(thiz);
    mCallbackData.tts_class = (jclass)env->NewGlobalRef(clazz);
    mCallbackData.tts_ref = env->NewGlobalRef(weak_this);
    javaTTSFields.synthesisCompleted = env->GetStaticMethodID(
            clazz,
            "synthesisCompleted", "(Ljava/lang/Object;)V");
    if (javaTTSFields.synthesisCompleted == NULL) {
        LOGE("Can't find TTS.%s", "synthesisCompleted");
        return;
    }
    audout = NULL;

    const char *nativeSoLibNativeString = env->GetStringUTFChars(nativeSoLib, 0);

    void *engine_lib_handle = dlopen(nativeSoLibNativeString, RTLD_NOW | RTLD_LOCAL);
    if(engine_lib_handle==NULL) {
       LOGI("engine_lib_handle==NULL");
    }

    TtsSynthInterface *(*get_TtsSynthInterface)() = reinterpret_cast<TtsSynthInterface* (*)()>(dlsym(engine_lib_handle, "getTtsSynth"));
    nativeSynthInterface = (*get_TtsSynthInterface)();

    nativeSynthInterface->init(ttsSynthDoneCB);
LOGI("Setup complete");
}


static void
com_google_tts_SpeechSynthesis_setLanguage(JNIEnv *env, jobject thiz, jstring language)
{   
    const char *langNativeString = env->GetStringUTFChars(language, 0);
    nativeSynthInterface->set("language", langNativeString);
    env->ReleaseStringUTFChars(language, langNativeString);
}

static void
com_google_tts_SpeechSynthesis_setSpeechRate(JNIEnv *env, jobject thiz, 
                                             int speechRate)
{
    char buffer [10];
    sprintf(buffer, "%d", speechRate);
    nativeSynthInterface->set("rate", buffer);
}

static void
com_google_tts_SpeechSynthesis_native_finalize(JNIEnv *env,
					       jobject thiz)
{
    nativeSynthInterface->shutdown();
    delete audout;
    if (mCallbackData.tts_ref)
      env->DeleteGlobalRef(mCallbackData.tts_ref);
    if (mCallbackData.tts_class)
      env->DeleteGlobalRef(mCallbackData.tts_class);
}


static void
com_google_tts_SpeechSynthesis_synthesizeToFile(JNIEnv *env, jobject thiz,
						jstring textJavaString,
						jstring filenameJavaString)
{
    const char *filenameNativeString = env->GetStringUTFChars(filenameJavaString, 0);
    const char *textNativeString = env->GetStringUTFChars(textJavaString, 0);


    targetFilePointer = fopen(filenameNativeString, "wb");
    // Write 44 blank bytes for WAV header, then come back and fill them in
    // after we've written the audio data
    char header[44];
    fwrite(header, 1, 44, targetFilePointer);

    unsigned int unique_identifier;

    nativeSynthInterface->synth(textNativeString, false, true, (void *)1);

    long filelen = ftell(targetFilePointer);

    int samples = (((int)filelen) - 44) / 2;
    header[0] = 'R';
    header[1] = 'I';
    header[2] = 'F';
    header[3] = 'F';
    ((uint32_t *)(&header[4]))[0] = filelen - 8;
    header[8] = 'W';
    header[9] = 'A';
    header[10] = 'V';
    header[11] = 'E';

    header[12] = 'f';
    header[13] = 'm';
    header[14] = 't';
    header[15] = ' ';

    ((uint32_t *)(&header[16]))[0] = 16;  // size of fmt

    ((unsigned short *)(&header[20]))[0] = 1;  // format
    ((unsigned short *)(&header[22]))[0] = 1;  // channels
    ((uint32_t *)(&header[24]))[0] = 22050;  // samplerate
    ((uint32_t *)(&header[28]))[0] = 44100;  // byterate
    ((unsigned short *)(&header[32]))[0] = 2;  // block align
    ((unsigned short *)(&header[34]))[0] = 16;  // bits per sample

    header[36] = 'd';
    header[37] = 'a';
    header[38] = 't';
    header[39] = 'a';

    ((uint32_t *)(&header[40]))[0] = samples * 2;  // size of data

    // Skip back to the beginning and rewrite the header
    fseek(targetFilePointer, 0, SEEK_SET);
    fwrite(header, 1, 44, targetFilePointer);

    fflush(targetFilePointer);
    fclose(targetFilePointer);

    env->ReleaseStringUTFChars(textJavaString, textNativeString);
    env->ReleaseStringUTFChars(filenameJavaString, filenameNativeString);
}


static void
com_google_tts_SpeechSynthesis_speak(JNIEnv *env, jobject thiz,
                                     jstring textJavaString, jboolean isSsml)
{
    isTtsFinished = false;
    const char *textNativeString = env->GetStringUTFChars(textJavaString, 0);
    nativeSynthInterface->synth(textNativeString, isSsml, false, (void *)0);
    env->ReleaseStringUTFChars(textJavaString, textNativeString);
}


static void
com_google_tts_SpeechSynthesis_stop(JNIEnv *env, jobject thiz)
{
    pthread_mutex_lock(&mutex);
    // Tell audioCallback and ttsSynthDoneCB to interrupt.
    isStopping = true;
    pthread_cond_broadcast(&cond);
    pthread_mutex_unlock(&mutex);
    nativeSynthInterface->stop();
    // After nativeSynthInterface->stop() returns, there should be no
    // more calls to ttsSynthDoneCB.
    pthread_mutex_lock(&mutex);
    // Wait for audioCallback to set track_started to false. When
    // that's done, the AudioTrack should be stopped and there should
    // be no more calls to audioCallback.
    while (track_started)
      pthread_cond_wait(&cond, &mutex);
    pthread_mutex_unlock(&mutex);
    isStopping = false;
    LOGI("SpeechSynthesis_stop() returning");
}


static void
com_google_tts_SpeechSynthesis_shutdown(JNIEnv *env, jobject thiz)
{
    nativeSynthInterface->shutdown();
}


/*
static void
com_google_tts_SpeechSynthesis_playAudioBuffer(JNIEnv *env, jobject thiz, int bufferPointer, int bufferSize)
{
        short* wav = (short*) bufferPointer;
        audout->write(wav, bufferSize);
        char buf[100];
        sprintf(buf, "AudioTrack wrote: %d bytes", bufferSize);
        LOGI(buf);
}
*/

JNIEXPORT jstring JNICALL
com_google_tts_SpeechSynthesis_getLanguage(JNIEnv *env, jobject thiz)
{
    char buf[100];
    nativeSynthInterface->get("language", buf);
    return env->NewStringUTF(buf);
}

JNIEXPORT int JNICALL
com_google_tts_SpeechSynthesis_getRate(JNIEnv *env, jobject thiz)
{
    char buf[100];
    nativeSynthInterface->get("rate", buf);
    return atoi(buf);
}

// Dalvik VM type signatures
static JNINativeMethod gMethods[] = {
    {   "stop",             
        "()V",
        (void*)com_google_tts_SpeechSynthesis_stop
    },
    {   "speak",             
        "(Ljava/lang/String;Z)V",
        (void*)com_google_tts_SpeechSynthesis_speak
    },
    {   "synthesizeToFile",             
        "(Ljava/lang/String;Ljava/lang/String;)V",
        (void*)com_google_tts_SpeechSynthesis_synthesizeToFile
    },
    {   "setLanguage",
        "(Ljava/lang/String;)V",
        (void*)com_google_tts_SpeechSynthesis_setLanguage
    },
    {   "setSpeechRate",
        "(I)V",
        (void*)com_google_tts_SpeechSynthesis_setSpeechRate
    },
    /*
    {   "playAudioBuffer",
        "(II)V",
        (void*)com_google_tts_SpeechSynthesis_playAudioBuffer
    },
    */
    {   "getLanguage",             
        "()Ljava/lang/String;",
        (void*)com_google_tts_SpeechSynthesis_getLanguage
    },
    {   "getRate",             
        "()I",
        (void*)com_google_tts_SpeechSynthesis_getRate
    },
    {   "shutdown",             
        "()V",
        (void*)com_google_tts_SpeechSynthesis_shutdown
    },
    {   "native_setup",
        "(Ljava/lang/Object;Ljava/lang/String;)V",
        (void*)com_google_tts_SpeechSynthesis_native_setup
    },
    {   "native_finalize",     
        "()V",
        (void*)com_google_tts_SpeechSynthesis_native_finalize
    }
};

static const char* const kClassPathName = "com/google/tts/SpeechSynthesis";

jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
  jvm = vm;
    JNIEnv* env = NULL;
    jint result = -1;
    jclass clazz;

    if (vm->GetEnv((void**)(void*)(&env), JNI_VERSION_1_4) != JNI_OK) {
        LOGE("ERROR: GetEnv failed\n");
        goto bail;
    }
    assert(env != NULL);

    clazz = env->FindClass(kClassPathName);
    if (clazz == NULL) {
      LOGE("Can't find %s", kClassPathName);
        goto bail;
    }

    /*
    fields.mNativeContext = env->GetFieldID(clazz, "mNativeContext", "I");
    if (fields.mNativeContext == NULL) {
        LOGE("Can't find SpeechSynthesis.mNativeContext");
        goto bail;
    }
    */

    if (jniRegisterNativeMethods(
            env, kClassPathName, gMethods, NELEM(gMethods)) < 0)
        goto bail;

    /* success -- return valid version number */
    result = JNI_VERSION_1_4;

 bail:
    return result;
}
