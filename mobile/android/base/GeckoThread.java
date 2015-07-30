/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko;

import org.mozilla.gecko.mozglue.GeckoLoader;
import org.mozilla.gecko.mozglue.RobocopTarget;
import org.mozilla.gecko.util.GeckoEventListener;
import org.mozilla.gecko.util.ThreadUtils;

import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

public class GeckoThread extends Thread implements GeckoEventListener {
    private static final String LOGTAG = "GeckoThread";

    public enum State {
        // After being loaded by class loader.
        INITIAL,
        // After launching Gecko thread
        LAUNCHED,
        // After loading the mozglue library.
        MOZGLUE_READY,
        // After loading the libxul library.
        LIBS_READY,
        // After initializing frontend JS (corresponding to "Gecko:Ready" event)
        RUNNING,
        // After leaving Gecko event loop
        EXITING,
        // After exiting GeckoThread (corresponding to "Gecko:Exited" event)
        EXITED;

        public boolean is(final State other) {
            return this == other;
        }

        public boolean isAtLeast(final State other) {
            return ordinal() >= other.ordinal();
        }

        public boolean isAtMost(final State other) {
            return ordinal() <= other.ordinal();
        }

        // Inclusive
        public boolean isBetween(final State min, final State max) {
            final int ord = ordinal();
            return ord >= min.ordinal() && ord <= max.ordinal();
        }
    }

    public static final State MIN_STATE = State.INITIAL;
    public static final State MAX_STATE = State.EXITED;

    private static final AtomicReference<State> sState = new AtomicReference<>(State.INITIAL);

    /**
     * Interface implemented by an object whose availability depends on the Gecko thread state.
     * Used in conjunction with #newPendingObjectBuffer to provide buffering of method calls
     * until the object becomes available for calls.
     */
    public interface PendingObject {
        /**
         * Return whether the object is available in the current state.
         *
         * @param geckoState The current GeckoThread state
         * @return Whether the object is available for calls.
         */
        boolean readyForCalls(State geckoState);
    }

    private static final int BUFFERED_CALLS_COUNT = 16;
    /* inner */ static final ArrayList<Object> BUFFERED_OBJECTS
            = new ArrayList<>(BUFFERED_CALLS_COUNT);
    /* inner */ static final ArrayList<Method> BUFFERED_METHODS
            = new ArrayList<>(BUFFERED_CALLS_COUNT);
    /* inner */ static final ArrayList<Object[]> BUFFERED_ARGS
            = new ArrayList<>(BUFFERED_CALLS_COUNT);

    private static int sNextBufferedCallIndex = 0;

    private static GeckoThread sGeckoThread;

    private final String mArgs;
    private final String mAction;
    private final String mUri;
    private final boolean mDebugging;

    /**
     * Create a buffer that will store every call on the given interface, until the given
     * PendingObject becomes available. Once the object is available, the saved calls are
     * replayed back on the given object in order.
     *
     * In the following example, MyClass.getInstance() is used to get an object for calling
     * doSomething()/doSomethingElse(). However, depending on the current Gecko thread state,
     * MyClass.getInstance() returns either a buffer object, when Gecko state is before
     * LIBS_READY, or a real MyClass object, after Gecko state changes to LIBS_READY. The buffer
     * object saves all calls to doSomething() and doSomethingElse(). Once the Gecko state
     * reaches LIBS_READY, all the saved calls will be replayed back, in order, on the real
     * MyClass object.
     *
     * <code>
     * interface IMyClass {
     *     void doSomething();
     *     void doSomethingElse();
     * }
     *
     * class MyClass implments IMyClass, PendingObject
     * {
     *     @Override public native void doSomething();
     *     @Override public native void doSomethingElse();
     *
     *     @Override
     *     public boolean readyForCalls(GeckoThread.State geckoState) {
     *         if (geckoState.isAtLeast(GeckoThread.State.LIBS_READY)) {
     *             sInstance = this; // Replace the buffer with the real object.
     *             return true;
     *         }
     *         return false;
     *     }
     *
     *     private static IMyClass sInstance
     *         = GeckoThread.newPendingObjectBuffer(IMyClass.class, new MyClass());
     *
     *     public static IMyClass getInstance() {
     *         return sInstance;
     *     }
     * }
     * </code>
     *
     * @param iface Class of an Interface that the buffer object will implement;
     *              the interface must only have methods that return void.
     * @param obj Object that is pending on the current Gecko state;
     *            must implement iface and must not have public methods
     *            other than methods from PendingObject and iface.
     * @return Buffer object that will implement iface and
     *         will buffer calls made on the interface
     */
    public static <T> T newPendingObjectBuffer(final Class<T> iface,
                                               final PendingObject obj) {
        if (!AppConstants.RELEASE_BUILD) {
            if (!iface.isInstance(obj)) {
                // The pending object must implement the interface given in iface, because
                // that's the interface whose calls we will buffer when the object is pending.
                throw new IllegalArgumentException("Object must be implement given interface");
            }
            for (Method m : iface.getMethods()) {
                if (m.getReturnType() != Void.TYPE) {
                    // The interface must *not* have methods that return values,
                    // because we don't have values to return when calls are buffered.
                    throw new IllegalArgumentException("Interface methods must return void");
                }
            }
            for (Method m : obj.getClass().getMethods()) {
                if (m.getDeclaringClass() == obj.getClass()) {
                    // Pending objects only work through the given interface. Other public
                    // methods that the class declares will *not* be buffered by the returned
                    // object. Consider making these methods private.
                    throw new IllegalArgumentException(
                            "Must not have public methods outside of interface");
                }
            }
        }

        synchronized (BUFFERED_OBJECTS) {
            if (BUFFERED_OBJECTS.size() == 0 && obj.readyForCalls(sState.get())) {
                // If the object is already ready, return the object itself right away.
                return iface.cast(obj);
            }
        }

        // Return a proxy that will buffer pending calls.
        return iface.cast(Proxy.newProxyInstance(iface.getClassLoader(),
                                                 new Class<?>[] { iface },
                                                 new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                synchronized (BUFFERED_OBJECTS) {
                    if (BUFFERED_OBJECTS.size() == 0
                            && obj.readyForCalls(sState.get())) {
                        // If there's nothing in the buffer and the object
                        // has just become ready, make the call right away.
                        return method.invoke(obj, args);
                    }
                    // Otherwise, add the pending call to our list for later.
                    BUFFERED_OBJECTS.add(obj);
                    BUFFERED_METHODS.add(method);
                    BUFFERED_ARGS.add(args);
                }
                return null;
            }
        }));
    }

    /* package */ static void addPendingEvent(final GeckoEvent e) {
        synchronized (BUFFERED_OBJECTS) {
            if (BUFFERED_OBJECTS.size() == 0 && isRunning()) {
                // We may just have switched to running state.
                GeckoAppShell.notifyGeckoOfEvent(e);
                e.recycle();
            } else {
                // Otherwise, add the pending event to our list in a special format.
                BUFFERED_OBJECTS.add(e);
                BUFFERED_METHODS.add(null);
                BUFFERED_ARGS.add(null);
            }
        }
    }

    public static void flushPendingObjectCalls() {
        flushPendingObjectCalls(sState.get());
    }

    private static void flushPendingObjectCalls(final State newState) {
        // See if we have pending calls we can make.
        synchronized (BUFFERED_OBJECTS) {
            flushPendingObjectCallsLocked(newState);
        }
    }

    private static void flushPendingObjectCallsLocked(final State newState) {
        int i = sNextBufferedCallIndex;
        for (; i < BUFFERED_OBJECTS.size(); i++) {
            final Method method = BUFFERED_METHODS.get(i);
            if (method == null) {
                // Special case for pending GeckoEvent objects. To preserve compaibility,
                // only send pending Gecko events in RUNNING state, i.e. after "Gecko:Ready".
                if (!newState.is(State.RUNNING)) {
                    break;
                }
                final GeckoEvent e = (GeckoEvent)BUFFERED_OBJECTS.get(i);
                GeckoAppShell.notifyGeckoOfEvent(e);
                e.recycle();
                continue;
            }

            final PendingObject obj = (PendingObject)BUFFERED_OBJECTS.get(i);
            if (!obj.readyForCalls(newState)) {
                break;
            }
            try {
                method.invoke(obj, BUFFERED_ARGS.get(i));
            } catch (final IllegalAccessException e) {
                throw new IllegalStateException("Unexpected exception", e);
            } catch (final InvocationTargetException e) {
                throw new UnsupportedOperationException("Cannot make buffered call",
                                                        e.getCause());
            }
        }

        sNextBufferedCallIndex = i;
        if (i == BUFFERED_OBJECTS.size()) {
            // Emptied our buffer; reset.
            BUFFERED_OBJECTS.clear();
            BUFFERED_METHODS.clear();
            BUFFERED_ARGS.clear();
        }
    }

    GeckoThread(String args, String action, String uri, boolean debugging) {
        mArgs = args;
        mAction = action;
        mUri = uri;
        mDebugging = debugging;

        setName("Gecko");
        EventDispatcher.getInstance().registerGeckoThreadListener(this, "Gecko:Ready");
    }

    public static boolean ensureInit(String args, String action, String uri) {
        return ensureInit(args, action, uri, /* debugging */ false);
    }

    public static boolean ensureInit(String args, String action, String uri, boolean debugging) {
        ThreadUtils.assertOnUiThread();
        if (isState(State.INITIAL) && sGeckoThread == null) {
            sGeckoThread = new GeckoThread(args, action, uri, debugging);
            return true;
        }
        return false;
    }

    public static boolean launch() {
        ThreadUtils.assertOnUiThread();
        if (checkAndSetState(State.INITIAL, State.LAUNCHED)) {
            sGeckoThread.start();
            return true;
        }
        return false;
    }

    public static boolean isLaunched() {
        return !isState(State.INITIAL);
    }

    @RobocopTarget
    public static boolean isRunning() {
        return isState(State.RUNNING);
    }

    private String initGeckoEnvironment() {
        final Locale locale = Locale.getDefault();

        final Context context = GeckoAppShell.getContext();
        GeckoLoader.loadMozGlue(context);
        setState(State.MOZGLUE_READY);

        final Resources res = context.getResources();
        if (locale.toString().equalsIgnoreCase("zh_hk")) {
            final Locale mappedLocale = Locale.TRADITIONAL_CHINESE;
            Locale.setDefault(mappedLocale);
            Configuration config = res.getConfiguration();
            config.locale = mappedLocale;
            res.updateConfiguration(config, null);
        }

        String resourcePath = "";
        String[] pluginDirs = null;
        try {
            pluginDirs = GeckoAppShell.getPluginDirectories();
        } catch (Exception e) {
            Log.w(LOGTAG, "Caught exception getting plugin dirs.", e);
        }

        resourcePath = context.getPackageResourcePath();
        GeckoLoader.setupGeckoEnvironment(context, pluginDirs, context.getFilesDir().getPath());

        GeckoLoader.loadSQLiteLibs(context, resourcePath);
        GeckoLoader.loadNSSLibs(context, resourcePath);
        GeckoLoader.loadGeckoLibs(context, resourcePath);
        setState(State.LIBS_READY);

        return resourcePath;
    }

    private String getTypeFromAction(String action) {
        if (GeckoApp.ACTION_HOMESCREEN_SHORTCUT.equals(action)) {
            return "-bookmark";
        }
        return null;
    }

    private String addCustomProfileArg(String args) {
        String profileArg = "";
        String guestArg = "";
        if (GeckoAppShell.getGeckoInterface() != null) {
            final GeckoProfile profile = GeckoAppShell.getGeckoInterface().getProfile();

            if (profile.inGuestMode()) {
                try {
                    profileArg = " -profile " + profile.getDir().getCanonicalPath();
                } catch (final IOException ioe) {
                    Log.e(LOGTAG, "error getting guest profile path", ioe);
                }

                if (args == null || !args.contains(BrowserApp.GUEST_BROWSING_ARG)) {
                    guestArg = " " + BrowserApp.GUEST_BROWSING_ARG;
                }
            } else if (!GeckoProfile.sIsUsingCustomProfile) {
                // If nothing was passed in the intent, make sure the default profile exists and
                // force Gecko to use the default profile for this activity
                profileArg = " -P " + profile.forceCreate().getName();
            }
        }

        return (args != null ? args : "") + profileArg + guestArg;
    }

    @Override
    public void run() {
        Looper.prepare();
        ThreadUtils.sGeckoThread = this;
        ThreadUtils.sGeckoHandler = new Handler();

        if (mDebugging) {
            try {
                Thread.sleep(5 * 1000 /* 5 seconds */);
            } catch (final InterruptedException e) {
            }
        }

        String path = initGeckoEnvironment();

        // This can only happen after the call to initGeckoEnvironment
        // above, because otherwise the JNI code hasn't been loaded yet.
        ThreadUtils.postToUiThread(new Runnable() {
            @Override public void run() {
                GeckoAppShell.registerJavaUiThread();
            }
        });

        Log.w(LOGTAG, "zerdatime " + SystemClock.uptimeMillis() + " - runGecko");

        String args = addCustomProfileArg(mArgs);
        String type = getTypeFromAction(mAction);

        if (!AppConstants.MOZILLA_OFFICIAL) {
            Log.i(LOGTAG, "RunGecko - args = " + args);
        }
        // and then fire us up
        GeckoAppShell.runGecko(path, args, mUri, type);

        // And... we're done.
        setState(State.EXITED);

        try {
            final JSONObject msg = new JSONObject();
            msg.put("type", "Gecko:Exited");
            EventDispatcher.getInstance().dispatchEvent(msg, null);
        } catch (final JSONException e) {
            Log.e(LOGTAG, "unable to dispatch event", e);
        }
    }

    @Override
    public void handleMessage(String event, JSONObject message) {
        if ("Gecko:Ready".equals(event)) {
            Log.d(LOGTAG, "GeckoThread Gecko:Ready");
            EventDispatcher.getInstance().unregisterGeckoThreadListener(this, event);
            setState(State.RUNNING);
        }
    }

    /**
     * Check that the current Gecko thread state matches the given state.
     *
     * @param state State to check
     * @return True if the current Gecko thread state matches
     */
    public static boolean isState(final State state) {
        return sState.get().is(state);
    }

    /**
     * Check that the current Gecko thread state is at the given state or further along,
     * according to the order defined in the State enum.
     *
     * @param state State to check
     * @return True if the current Gecko thread state matches
     */
    public static boolean isStateAtLeast(final State state) {
        return sState.get().isAtLeast(state);
    }

    /**
     * Check that the current Gecko thread state is at the given state or prior,
     * according to the order defined in the State enum.
     *
     * @param state State to check
     * @return True if the current Gecko thread state matches
     */
    public static boolean isStateAtMost(final State state) {
        return sState.get().isAtMost(state);
    }

    /**
     * Check that the current Gecko thread state falls into an inclusive range of states,
     * according to the order defined in the State enum.
     *
     * @param minState Lower range of allowable states
     * @param maxState Upper range of allowable states
     * @return True if the current Gecko thread state matches
     */
    public static boolean isStateBetween(final State minState, final State maxState) {
        return sState.get().isBetween(minState, maxState);
    }

    private static void setState(final State newState) {
        ThreadUtils.assertOnGeckoThread();
        sState.set(newState);
        flushPendingObjectCalls(newState);
    }

    private static boolean checkAndSetState(final State currentState, final State newState) {
        if (!sState.compareAndSet(currentState, newState)) {
            return false;
        }
        flushPendingObjectCalls(newState);
        return true;
    }
}
