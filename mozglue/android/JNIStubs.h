/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#ifndef GeneratedJNIStubs_h
#define GeneratedJNIStubs_h

#include <jni.h>
#include <vector>

#include "mozilla/IndexSequence.h"
#include "mozilla/Move.h"
#include "mozilla/TypeTraits.h"
#include "mozilla/UniquePtr.h"
#include "mozilla/unused.h"
#include "mozilla/Vector.h"

namespace mozilla {
namespace jni {

namespace {

template<typename T>
using IsRefType = mozilla::IsBaseOf<
        typename mozilla::RemovePointer<jobject>::Type,
        typename mozilla::RemovePointer<T>::Type>;

// Helper class to convert an arbitrary type to a jvalue, e.g. Value(123).val.
struct Value
{
    Value(jboolean z) { val.z = z; }
    Value(jbyte b)    { val.b = b; }
    Value(jchar c)    { val.c = c; }
    Value(jshort s)   { val.s = s; }
    Value(jint i)     { val.i = i; }
    Value(jlong j)    { val.j = j; }
    Value(jfloat f)   { val.f = f; }
    Value(jdouble d)  { val.d = d; }
    Value(jobject l)  { val.l = l; }

    jvalue val;
};

template<typename T>
jvalue ToValue(JNIEnv* env, T val)
{
    if (IsRefType<T>::value) {
        return Value(env->NewGlobalRef(val)).val;
    }
    return Value(val).val;
}

template<typename T>
void ClearValue(JNIEnv* env, const jvalue& val)
{
    if (IsRefType<T>::value) {
        env->DeleteGlobalRef(val.l);
    }
}

} // namespace

struct JNICallBase
{
    virtual void Call() = 0;
    virtual ~JNICallBase() {}
};

extern mozilla::Vector<mozilla::UniquePtr<JNICallBase>> queued_calls;
// JNIEnv for use by queued items; should only be set when using the queue.
extern JNIEnv* env_for_queue;

namespace {

template<bool IsStatic, typename... Args>
class JNICall : public JNICallBase
{
    typedef typename mozilla::Conditional<
            IsStatic, jclass, jobject>::Type ClsOrObj_t;

    const ClsOrObj_t clsOrObj;
    const jmethodID methodID;
    jvalue args[sizeof...(Args)];

    static jmethodID GetMethodID(JNIEnv* env, ClsOrObj_t clsOrObj,
                                 const char* name, const char* sig)
    {
        if (IsStatic) {
            return env->GetStaticMethodID(jclass(clsOrObj), name, sig);
        }
        const jclass cls = env->GetObjectClass(clsOrObj);
        const jmethodID method = env->GetMethodID(cls, name, sig);
        env->DeleteLocalRef(cls);
        return method;
    }

    template<typename... T>
    static void Queue(T&&... t)
    {
        mozilla::UniquePtr<JNICall, mozilla::DefaultDelete<JNICallBase>>
                call(new JNICall(mozilla::Forward<T>(t)...));
        queued_calls.infallibleAppend(mozilla::Move(call));
    }

    template<size_t... Indices>
    void ClearValues(JNIEnv* env, IndexSequence<Indices...>)
    {
        int dummy[] = { (ClearValue<Args>(env, args[Indices]), 0)... };
        mozilla::unused << dummy;
    }

public:
    JNICall(JNIEnv* env, const char* name, const char* sig,
            ClsOrObj_t c, Args... a)
        : clsOrObj(ClsOrObj_t(env->NewGlobalRef(c)))
        , methodID(GetMethodID(env, c, name, sig))
        , args{ToValue<Args>(env, a)...}
    {}

    ~JNICall()
    {
        JNIEnv* const env = env_for_queue;
        env->DeleteGlobalRef(clsOrObj);
        ClearValues(env, typename IndexSequenceFor<Args...>::Type());
    }

    void Call() override
    {
        if (IsStatic) {
            return env_for_queue->CallStaticVoidMethodA(
                    jclass(clsOrObj), methodID, args);
        }
        return env_for_queue->CallVoidMethodA(clsOrObj, methodID, args);
    }

    static void EmptyStub(JNIEnv* env, ClsOrObj_t clsOrObj, Args... args) {}

    template<const char* Name, const char* Signature>
    static void QueuingStub(JNIEnv* env, ClsOrObj_t clsOrObj, Args... args)
    {
        Queue(env, Name, Signature, clsOrObj, args...);
    }
};

} // namespace

void RegisterStubs(JNIEnv* env, const char* clsName,
                   const JNINativeMethod* methods, size_t count);

void RegisterGeneratedStubs(JNIEnv* env);

} // namespace jni
} // namespace mozilla

#endif // GeneratedJNIStubs_h

