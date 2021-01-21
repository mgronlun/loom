/*
 * Copyright (c) 200
 * git 3, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

#include <stdio.h>
#include <string.h>
#include <jvmti.h>
#include "jvmti_common.h"

extern "C" {

#define STATUS_FAILED 2
#define PASSED 0

#define METH_NUM 2

static const char *METHODS[] = {
    "bpMethod",
    "runThis"
};

static const char *METHOD_SIGS[] = {
    "()V",
    "()I"
};

static volatile long stepEv[] = {0, 0};

static const char *CLASS_SIG =
    "Lsinglestep01;";

static volatile jint result = PASSED;
static jvmtiEnv *jvmti = NULL;
static jvmtiEventCallbacks callbacks;

static volatile int callbacksEnabled = NSK_FALSE;
static jrawMonitorID agent_lock;

static void setBP(jvmtiEnv *jvmti, JNIEnv *jni, jclass klass) {
  jmethodID mid;
  jvmtiError err;

  mid = jni->GetMethodID(klass, METHODS[0], METHOD_SIGS[0]);
  if (mid == NULL) {
    jni->FatalError("failed to get ID for the java method\n");
  }

  printf("Setting breakpoint....");
  err = jvmti->SetBreakpoint(mid, 0);
  if (err != JVMTI_ERROR_NONE) {
    jni->FatalError("failed to set breakpoint\n");
  }
}

/** callback functions **/
void JNICALL
ClassLoad(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jclass klass) {
  char *sig, *generic;
  jvmtiError err;

  jvmti->RawMonitorEnter(agent_lock);

  if (callbacksEnabled) {
    err = jvmti->GetClassSignature(klass, &sig, &generic);
    if (err != JVMTI_ERROR_NONE) {
      jni->FatalError("failed to obtain a class signature\n");
    }
    if (sig != NULL && (strcmp(sig, CLASS_SIG) == 0)) {
      NSK_DISPLAY1(
          "ClassLoad event received for the class \"%s\"\n"
          "\tsetting breakpoint ...\n",
          sig);
      setBP(jvmti, jni, klass);
    }
  }

  jvmti->RawMonitorExit(agent_lock);
}

void JNICALL
Breakpoint(jvmtiEnv *jvmti, JNIEnv *jni, jthread thr, jmethodID method, jlocation loc) {
  jclass klass;
  char *sig, *generic;
  jvmtiError err;

  jvmti->RawMonitorEnter(agent_lock);

  if (!callbacksEnabled) {
    jvmti->RawMonitorExit(agent_lock);
    return;
  }

  NSK_DISPLAY0("Breakpoint event received\n");
  err = jvmti->GetMethodDeclaringClass(method, &klass);
  if (err != JVMTI_ERROR_NONE) {
    NSK_COMPLAIN0("TEST FAILURE: unable to get method declaring class\n\n");
  }

  err = jvmti->GetClassSignature(klass, &sig, &generic);
  if (err != JVMTI_ERROR_NONE) {
    jni->FatalError("Breakpoint: failed to obtain a class signature\n");
  }

  if (sig != NULL && (strcmp(sig, CLASS_SIG) == 0)) {
    NSK_DISPLAY1("method declaring class \"%s\"\n\tenabling SingleStep events ...\n", sig);
    err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_SINGLE_STEP, thr);
    if (err != JVMTI_ERROR_NONE) {
      result = STATUS_FAILED;
      NSK_COMPLAIN0("TEST FAILURE: cannot enable SingleStep events\n\n");
    }
  } else {
    result = STATUS_FAILED;
    NSK_COMPLAIN1("TEST FAILURE: unexpected breakpoint event in method of class \"%s\"\n\n",
                  sig);
  }
  jvmti->RawMonitorExit(agent_lock);
}

void JNICALL
SingleStep(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread,
           jmethodID method, jlocation location) {
  jclass klass;
  char *sig, *generic, *methNam, *methSig;
  jvmtiError err;

  if (result == STATUS_FAILED) {
    return;
  }

  NSK_DISPLAY0(">>>> SingleStep event received\n");

  err = jvmti->GetMethodName(method, &methNam, &methSig, NULL);
  if (err != JVMTI_ERROR_NONE) {
    result = STATUS_FAILED;
    NSK_COMPLAIN0("TEST FAILED: unable to get method name during SingleStep callback\n\n");
    return;
  }

  err = jvmti->GetMethodDeclaringClass(method, &klass);
  if (err != JVMTI_ERROR_NONE) {
    result = STATUS_FAILED;
    NSK_COMPLAIN0("TEST FAILED: unable to get method declaring class during SingleStep callback\n\n");
    return;
  }

  err = jvmti->GetClassSignature(klass, &sig, &generic);
  if (err != JVMTI_ERROR_NONE) {
    result = STATUS_FAILED;
    NSK_COMPLAIN0("TEST FAILED: unable to obtain a class signature during SingleStep callback\n\n");
    return;
  }

  if (sig != NULL) {
    NSK_DISPLAY3(
        "\tmethod name: \"%s\"\n"
        "\tsignature: \"%s\"\n"
        "\tmethod declaring class: \"%s\"\n",
        methNam, methSig, sig);

    if (stepEv[1] == 1) {
      result = STATUS_FAILED;
      NSK_COMPLAIN0("TEST FAILED: SingleStep event received after disabling the event generation\n\n");
    } else if ((strcmp(methNam, METHODS[0]) == 0) &&
        (strcmp(methSig, METHOD_SIGS[0]) == 0) &&
        (strcmp(sig, CLASS_SIG) == 0)) {
      stepEv[0]++;
      NSK_DISPLAY1("CHECK PASSED: SingleStep event received for the method \"%s\" as expected\n",
                   methNam);
    } else if ((strcmp(methNam, METHODS[1]) == 0) &&
        (strcmp(methSig, METHOD_SIGS[1]) == 0) &&
        (strcmp(sig, CLASS_SIG) == 0)) {
      stepEv[1]++;
      NSK_DISPLAY1(
          "CHECK PASSED: SingleStep event received for the method \"%s\" as expected\n"
          "\tdisabling the event generation\n",
          methNam);
      err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_SINGLE_STEP, thread);
      if (err != JVMTI_ERROR_NONE) {
        result = STATUS_FAILED;
        NSK_COMPLAIN0("TEST FAILED: cannot disable SingleStep events\n\n");
      }
    }
  }

  err = jvmti->Deallocate((unsigned char *) methNam);
  if (err != JVMTI_ERROR_NONE) {
    result = STATUS_FAILED;
    NSK_COMPLAIN0("TEST FAILED: unable to deallocate memory pointed to method name\n\n");
  }
  err = jvmti->Deallocate((unsigned char *) methSig);
  if (err != JVMTI_ERROR_NONE) {
    result = STATUS_FAILED;
    NSK_COMPLAIN0("TEST FAILED: unable to deallocate memory pointed to method signature\n\n");
  }

  NSK_DISPLAY0("<<<<\n\n");
}

void JNICALL
VMStart(jvmtiEnv *jvmti, JNIEnv *jni) {
  jvmti->RawMonitorEnter(agent_lock);

  callbacksEnabled = NSK_TRUE;

  jvmti->RawMonitorExit(agent_lock);
}

void JNICALL
VMDeath(jvmtiEnv *jvmti, JNIEnv *jni) {
  jvmti->RawMonitorEnter(agent_lock);

  callbacksEnabled = NSK_FALSE;

  jvmti->RawMonitorExit(agent_lock);
}
/************************/

JNIEXPORT jint JNICALL
Java_singlestep01_check(JNIEnv *jni, jobject obj) {
  for (int i = 0; i < METH_NUM; i++) {
    if (stepEv[i] == 0) {
      result = STATUS_FAILED;
      NSK_COMPLAIN1("TEST FAILED: no SingleStep events for the method \"%s\"\n\n",
                    METHODS[i]);
    }
  }
  return result;
}

#ifdef STATIC_BUILD
JNIEXPORT jint JNICALL Agent_OnLoad_singlestep01(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNICALL Agent_OnAttach_singlestep01(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNI_OnLoad_singlestep01(JavaVM *jvm, char *options, void *reserved) {
    return JNI_VERSION_1_8;
}
#endif
jint Agent_Initialize(JavaVM *jvm, char *options, void *reserved) {
  jvmtiCapabilities caps;
  jvmtiError err;
  jint res;

  res = jvm->GetEnv((void **) &jvmti, JVMTI_VERSION_1_1);
  if (res != JNI_OK || jvmti == NULL) {
    printf("Wrong result of a valid call to GetEnv!\n");
    return JNI_ERR;
  }
  /* add capability to generate compiled method events */
  memset(&caps, 0, sizeof(jvmtiCapabilities));
  caps.can_generate_breakpoint_events = 1;
  caps.can_generate_single_step_events = 1;

  err = jvmti->AddCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    printf("(AddCapabilities) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    return JNI_ERR;
  }

  err = jvmti->GetCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetCapabilities) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    return JNI_ERR;
  }

  if (!caps.can_generate_single_step_events) {
    NSK_DISPLAY0("Warning: generation of single step events is not implemented\n");
  }

  /* set event callback */
  NSK_DISPLAY0("setting event callbacks ...\n");
  (void) memset(&callbacks, 0, sizeof(callbacks));
  callbacks.ClassLoad = &ClassLoad;
  callbacks.Breakpoint = &Breakpoint;
  callbacks.SingleStep = &SingleStep;
  callbacks.VMStart = &VMStart;
  callbacks.VMDeath = &VMDeath;
  err = jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }

  NSK_DISPLAY0("setting event callbacks done\nenabling JVMTI events ...\n");
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_START, NULL);
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_DEATH, NULL);
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CLASS_LOAD, NULL);
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_BREAKPOINT, NULL);
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }

  NSK_DISPLAY0("enabling the events done\n\n");

  err = jvmti->CreateRawMonitor("agent lock", &agent_lock);
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }

  return JNI_OK;
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}

JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}

}
