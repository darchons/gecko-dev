/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko;

import org.mozilla.gecko.util.ActivityResultHandler;
import org.mozilla.gecko.util.BundleEventListener;
import org.mozilla.gecko.util.EventCallback;
import org.mozilla.gecko.util.JSONUtils;
import org.mozilla.gecko.util.WebActivityMapper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.List;

public final class IntentHelper implements BundleEventListener {
    private static final String LOGTAG = "GeckoIntentHelper";
    private static final String[] EVENTS = {
        "Intent:GetHandlers",
        "Intent:Open",
        "Intent:OpenForResult",
        "Intent:OpenNoHandler",
        "WebActivity:Open"
    };

    // via http://developer.android.com/distribute/tools/promote/linking.html
    private static String MARKET_INTENT_URI_PACKAGE_PREFIX = "market://details?id=";
    private static String EXTRA_BROWSER_FALLBACK_URL = "browser_fallback_url";

    /** A partial URI to an error page - the encoded error URI should be appended before loading. */
    private static String UNKNOWN_PROTOCOL_URI_PREFIX = "about:neterror?e=unknownProtocolFound&u=";

    private static IntentHelper instance;

    private final Activity activity;

    private IntentHelper(Activity activity) {
        this.activity = activity;
    }

    public static IntentHelper init(Activity activity) {
        if (instance == null) {
            instance = new IntentHelper(activity);
            EventDispatcher.getInstance().registerBackgroundThreadListener(instance, EVENTS);
        } else {
            Log.w(LOGTAG, "IntentHelper.init() called twice, ignoring.");
        }

        return instance;
    }

    public static void destroy() {
        if (instance != null) {
            EventDispatcher.getInstance().unregisterBackgroundThreadListener(instance, EVENTS);
            instance = null;
        }
    }

    @Override
    public void handleMessage(String event, Bundle message, EventCallback callback) {
        // On background thread
        if (event.equals("Intent:GetHandlers")) {
            getHandlers(message, callback);
        } else if (event.equals("Intent:Open")) {
            open(message);
        } else if (event.equals("Intent:OpenForResult")) {
            openForResult(message, callback);
        } else if (event.equals("Intent:OpenNoHandler")) {
            openNoHandler(message);
        } else if (event.equals("WebActivity:Open")) {
            openWebActivity(message, callback);
        }
    }

    private void getHandlers(final Bundle message, final EventCallback callback) {
        final Intent intent = GeckoAppShell.getOpenURIIntent(activity,
                                                             message.getString("url", ""),
                                                             message.getString("mime", ""),
                                                             message.getString("action", ""),
                                                             message.getString("title", ""));
        final List<String> appList = Arrays.asList(GeckoAppShell.getHandlersForIntent(intent));

        try {
            final JSONObject response = new JSONObject();
            response.put("apps", new JSONArray(appList));
            callback.sendSuccess(response);
        } catch (final JSONException e) {
            Log.e(LOGTAG, "", e);
        }
    }

    private void open(final Bundle message) {
        GeckoAppShell.openUriExternal(message.getString("url", ""),
                                      message.getString("mime", ""),
                                      message.getString("packageName", ""),
                                      message.getString("className", ""),
                                      message.getString("action", ""),
                                      message.getString("title", ""));
    }

    private void openForResult(final Bundle message, final EventCallback callback) {
        Intent intent = GeckoAppShell.getOpenURIIntent(activity,
                                                       message.getString("url", ""),
                                                       message.getString("mime", ""),
                                                       message.getString("action", ""),
                                                       message.getString("title", ""));
        intent.setClassName(message.getString("packageName", ""),
                            message.getString("className", ""));
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        final ResultHandler handler = new ResultHandler(callback);
        try {
            ActivityHandlerHelper.startIntentForActivity(activity, intent, handler);
        } catch (SecurityException e) {
            Log.w(LOGTAG, "Forbidden to launch activity.", e);
        }
    }

    /**
     * Opens a URI without any valid handlers on device. In the best case, a package is specified
     * and we can bring the user directly to the application page in an app market. If a package is
     * not specified and there is a fallback url in the intent extras, we open that url. If neither
     * is present, we alert the user that we were unable to open the link.
     */
    private void openNoHandler(final Bundle msg) {
        final String uri = msg.getString("uri");

        if (TextUtils.isEmpty(uri)) {
            openUnknownProtocolErrorPage("");
            Log.w(LOGTAG, "Received empty URL - loading about:neterror");
            return;
        }

        final Intent intent;
        try {
            // TODO (bug 1173626): This will not handle android-app uris on non 5.1 devices.
            intent = Intent.parseUri(uri, 0);
        } catch (final URISyntaxException e) {
            try {
                openUnknownProtocolErrorPage(URLEncoder.encode(uri, "UTF-8"));
            } catch (final UnsupportedEncodingException encodingE) {
                openUnknownProtocolErrorPage("");
            }

            // Don't log the exception to prevent leaking URIs.
            Log.w(LOGTAG, "Unable to parse Intent URI - loading about:neterror");
            return;
        }

        // For this flow, we follow Chrome's lead:
        //   https://developer.chrome.com/multidevice/android/intents
        //
        // Note on alternative flows: we could get the intent package from a component, however, for
        // security reasons, components are ignored when opening URIs (bug 1168998) so we should
        // ignore it here too.
        //
        // Our old flow used to prompt the user to search for their app in the market by scheme and
        // while this could help the user find a new app, there is not always a correlation in
        // scheme to application name and we could end up steering the user wrong (potentially to
        // malicious software). Better to leave that one alone.
        if (intent.getPackage() != null) {
            final String marketUri = MARKET_INTENT_URI_PACKAGE_PREFIX + intent.getPackage();
            final Intent marketIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(marketUri));
            marketIntent.addCategory(Intent.CATEGORY_BROWSABLE);
            marketIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(marketIntent);

        } else if (intent.hasExtra(EXTRA_BROWSER_FALLBACK_URL)) {
            final String fallbackUrl = intent.getStringExtra(EXTRA_BROWSER_FALLBACK_URL);
            Tabs.getInstance().loadUrl(fallbackUrl);

        }  else {
            openUnknownProtocolErrorPage(intent.getData().toString());
            // Don't log the URI to prevent leaking it.
            Log.w(LOGTAG, "Unable to open URI, default case - loading about:neterror");
        }
    }

    /**
     * Opens about:neterror with the unknownProtocolFound text.
     * @param encodedUri The encoded uri. While the page does not open correctly without specifying
     *                   a uri parameter, it happily accepts the empty String so this argument may
     *                   be the empty String.
     */
    private void openUnknownProtocolErrorPage(final String encodedUri) {
        final String errorUri = UNKNOWN_PROTOCOL_URI_PREFIX + encodedUri;
        Tabs.getInstance().loadUrl(errorUri);
    }

    private void openWebActivity(final Bundle message, final EventCallback callback) {
        final Intent intent;
        try {
            intent = WebActivityMapper.getIntentForWebActivity(
                    JSONUtils.bundleToJSON(message.getBundle("activity")));
        } catch (final JSONException e) {
            Log.e(LOGTAG, "", e);
            return;
        }
        ActivityHandlerHelper.startIntentForActivity(activity, intent, new ResultHandler(callback));
    }

    private static class ResultHandler implements ActivityResultHandler {
        private final EventCallback callback;

        public ResultHandler(EventCallback callback) {
            this.callback = callback;
        }

        @Override
        public void onActivityResult(int resultCode, Intent data) {
            JSONObject response = new JSONObject();

            try {
                if (data != null) {
                    response.put("extras", JSONUtils.bundleToJSON(data.getExtras()));
                    response.put("uri", data.getData().toString());
                }

                response.put("resultCode", resultCode);
            } catch (JSONException e) {
                Log.w(LOGTAG, "Error building JSON response.", e);
            }

            callback.sendSuccess(response);
        }
    }
}
