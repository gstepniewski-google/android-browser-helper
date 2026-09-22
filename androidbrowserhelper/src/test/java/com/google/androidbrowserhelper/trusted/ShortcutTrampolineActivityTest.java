// Copyright 2026 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.androidbrowserhelper.trusted;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;

import androidx.annotation.Nullable;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.internal.DoNotInstrument;
import org.robolectric.shadows.ShadowPackageManager;

@RunWith(RobolectricTestRunner.class)
@DoNotInstrument
@Config(sdk = {Build.VERSION_CODES.O_MR1})
public class ShortcutTrampolineActivityTest {
    private Context mContext;
    private ShadowPackageManager mShadowPackageManager;

    private static final String DEFAULT_URL = "https://www.example.com/twa/home";
    private static final int ADDITIONAL_ORIGINS_RES_ID = 0x7f030003;

    public static class SubclassLauncherActivity extends LauncherActivity {}

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mShadowPackageManager = shadowOf(mContext.getPackageManager());

        // Set up the package info with metadata on a dummy LauncherActivity
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = mContext.getPackageName();

        ActivityInfo dummyLauncherActivity = new ActivityInfo();
        dummyLauncherActivity.packageName = mContext.getPackageName();
        dummyLauncherActivity.name = LauncherActivity.class.getName();
        dummyLauncherActivity.metaData = new Bundle();
        dummyLauncherActivity.metaData.putString("android.support.customtabs.trusted.DEFAULT_URL", DEFAULT_URL);

        ActivityInfo trampolineActivity = new ActivityInfo();
        trampolineActivity.packageName = mContext.getPackageName();
        trampolineActivity.name = ShortcutTrampolineActivity.class.getName();

        // Register a fake browser that can handle HTTP/HTTPS intents
        // so that resolveActivity() succeeds in the fallback strategy.
        registerBrowserForUri(Uri.parse("https://www.example.com/twa/shortcut"));

        packageInfo.activities = new ActivityInfo[]{dummyLauncherActivity, trampolineActivity};
        mShadowPackageManager.addPackage(packageInfo);
    }

    private void registerBrowserForUri(Uri uri) {
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, uri);
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.packageName = "com.android.chrome";
        resolveInfo.activityInfo.name = "com.android.chrome.ChromeTabbedActivity";
        mShadowPackageManager.addResolveInfoForIntent(browserIntent, resolveInfo);
    }

    private void registerOwnBrowsableIntentFilter(
            String activityName,
            @Nullable String targetActivity,
            @Nullable Bundle metaData,
            Uri probeUri,
            String... hosts) {
        Intent probe = new Intent(Intent.ACTION_VIEW, probeUri)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(mContext.getPackageName());
        IntentFilter filter = new IntentFilter(Intent.ACTION_VIEW);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        filter.addCategory(Intent.CATEGORY_BROWSABLE);
        if (probeUri.getScheme() != null) {
            filter.addDataScheme(probeUri.getScheme());
        }
        for (String host : hosts) {
            filter.addDataAuthority(host, null);
        }
        ResolveInfo ownFilterInfo = new ResolveInfo();
        ownFilterInfo.activityInfo = new ActivityInfo();
        ownFilterInfo.activityInfo.packageName = mContext.getPackageName();
        ownFilterInfo.activityInfo.name = activityName;
        ownFilterInfo.activityInfo.targetActivity = targetActivity;
        ownFilterInfo.activityInfo.metaData = metaData;
        ownFilterInfo.filter = filter;
        mShadowPackageManager.addResolveInfoForIntent(probe, ownFilterInfo);
    }

    @Test
    public void activityFinishesSynchronously() {
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setData(Uri.parse("https://www.example.com/twa/shortcut"));

        ActivityController<ShortcutTrampolineActivity> controller =
                Robolectric.buildActivity(ShortcutTrampolineActivity.class, intent);

        controller.create();

        assertTrue(controller.get().isFinishing());
    }

    @Test
    public void launchesTwaForTrustedUri() {
        Uri trustedUri = Uri.parse("https://www.example.com/twa/shortcut");
        Intent intent = new Intent(Intent.ACTION_VIEW).setData(trustedUri);

        ActivityController<ShortcutTrampolineActivity> controller =
                Robolectric.buildActivity(ShortcutTrampolineActivity.class, intent);

        controller.create();
        shadowOf(Looper.getMainLooper()).idle();

        // The activity should finish immediately.
        assertTrue(controller.get().isFinishing());

        // Since we didn't set up custom tabs service, it will use the fallback strategy.
        // The fallback strategy uses the application context to start the intent, which
        // should be registered in the shadow application.
        Intent launchedIntent = shadowOf(RuntimeEnvironment.application).getNextStartedActivity();
        assertNotNull(launchedIntent);
        assertEquals(Intent.ACTION_VIEW, launchedIntent.getAction());
        assertEquals(trustedUri, launchedIntent.getData());
        
        // Ensure FLAG_ACTIVITY_NEW_TASK is attached
        int flags = launchedIntent.getFlags();
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, flags & Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Test
    public void dropsUntrustedUri() {
        Uri untrustedUri = Uri.parse("https://www.evil.com/twa/shortcut");
        Intent intent = new Intent(Intent.ACTION_VIEW).setData(untrustedUri);

        ActivityController<ShortcutTrampolineActivity> controller =
                Robolectric.buildActivity(ShortcutTrampolineActivity.class, intent);

        controller.create();

        // The activity should finish immediately.
        assertTrue(controller.get().isFinishing());

        // No activity should be launched because the URI is untrusted.
        Intent launchedIntent = shadowOf(RuntimeEnvironment.application).getNextStartedActivity();
        assertNull(launchedIntent);
    }

    @Test
    public void launchesTwaForHostDeclaredInOwnIntentFilter() {
        Uri secondDomainUri = Uri.parse("https://shop.example.com/deals");
        registerOwnBrowsableIntentFilter(
                LauncherActivity.class.getName(),
                null,
                null,
                secondDomainUri,
                "shop.example.com");
        registerBrowserForUri(secondDomainUri);

        Intent intent = new Intent(Intent.ACTION_VIEW).setData(secondDomainUri);
        ActivityController<ShortcutTrampolineActivity> controller =
                Robolectric.buildActivity(ShortcutTrampolineActivity.class, intent);

        controller.create();
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(controller.get().isFinishing());
        Intent launchedIntent = shadowOf(RuntimeEnvironment.application).getNextStartedActivity();
        assertNotNull(launchedIntent);
        assertEquals(secondDomainUri, launchedIntent.getData());
    }

    @Test
    public void launchesTwaForHostDeclaredOnAliasWithDefaultUrlMetadata() {
        PackageInfo packageInfo = mShadowPackageManager.getInternalMutablePackageInfo(
                mContext.getPackageName());
        ActivityInfo subclassInfo = new ActivityInfo();
        subclassInfo.packageName = mContext.getPackageName();
        subclassInfo.name = SubclassLauncherActivity.class.getName();
        packageInfo.activities = new ActivityInfo[]{
                packageInfo.activities[0], packageInfo.activities[1], subclassInfo
        };

        Bundle aliasMetaData = new Bundle();
        aliasMetaData.putString("android.support.customtabs.trusted.DEFAULT_URL", DEFAULT_URL);

        Uri aliasDomainUri = Uri.parse("https://alias.example.com/deals");
        registerOwnBrowsableIntentFilter(
                mContext.getPackageName() + ".LauncherAlias",
                SubclassLauncherActivity.class.getName(),
                aliasMetaData,
                aliasDomainUri,
                "alias.example.com");
        registerBrowserForUri(aliasDomainUri);

        Intent intent = new Intent(Intent.ACTION_VIEW).setData(aliasDomainUri);
        ActivityController<ShortcutTrampolineActivity> controller =
                Robolectric.buildActivity(ShortcutTrampolineActivity.class, intent);

        controller.create();
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(controller.get().isFinishing());
        Intent launchedIntent = shadowOf(RuntimeEnvironment.application).getNextStartedActivity();
        assertNotNull(launchedIntent);
        assertEquals(aliasDomainUri, launchedIntent.getData());
    }

    @Test
    public void dropsUriForHostDeclaredOnAliasWithoutDefaultUrlMetadata() {
        PackageInfo packageInfo = mShadowPackageManager.getInternalMutablePackageInfo(
                mContext.getPackageName());
        ActivityInfo subclassInfo = new ActivityInfo();
        subclassInfo.packageName = mContext.getPackageName();
        subclassInfo.name = SubclassLauncherActivity.class.getName();
        packageInfo.activities = new ActivityInfo[]{
                packageInfo.activities[0], packageInfo.activities[1], subclassInfo
        };

        Uri aliasDomainUri = Uri.parse("https://alias.example.com/deals");
        registerOwnBrowsableIntentFilter(
                mContext.getPackageName() + ".LauncherAlias",
                SubclassLauncherActivity.class.getName(),
                new Bundle(),
                aliasDomainUri,
                "alias.example.com");
        registerBrowserForUri(aliasDomainUri);

        Intent intent = new Intent(Intent.ACTION_VIEW).setData(aliasDomainUri);
        ActivityController<ShortcutTrampolineActivity> controller =
                Robolectric.buildActivity(ShortcutTrampolineActivity.class, intent);

        controller.create();
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(controller.get().isFinishing());
        Intent launchedIntent = shadowOf(RuntimeEnvironment.application).getNextStartedActivity();
        assertNull(launchedIntent);
    }

    @Test
    public void shortcut_dropsNonHttpsUriEvenWhenItMatchesOwnIntentFilter() {
        Uri customSchemeUri = Uri.parse("myapp://home/dashboard");
        registerOwnBrowsableIntentFilter(
                LauncherActivity.class.getName(),
                null,
                null,
                customSchemeUri,
                "home");

        Intent intent = new Intent(Intent.ACTION_VIEW).setData(customSchemeUri);
        ActivityController<ShortcutTrampolineActivity> controller =
                Robolectric.buildActivity(ShortcutTrampolineActivity.class, intent);

        controller.create();
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(controller.get().isFinishing());
        Intent launchedIntent = shadowOf(RuntimeEnvironment.application).getNextStartedActivity();
        assertNull(launchedIntent);
    }

    public static class HttpOriginShortcutTrampolineActivity extends ShortcutTrampolineActivity {
        @Override
        public Resources getResources() {
            Resources spied = spy(super.getResources());
            doReturn(new String[]{"http://legacy.example.com"})
                    .when(spied).getStringArray(ADDITIONAL_ORIGINS_RES_ID);
            return spied;
        }
    }

    @Test
    public void launchesHttpUriMatchingHttpAdditionalTrustedOrigin() {
        PackageInfo packageInfo = mShadowPackageManager.getInternalMutablePackageInfo(
                mContext.getPackageName());
        packageInfo.activities[0].metaData.putInt(
                "android.support.customtabs.trusted.ADDITIONAL_TRUSTED_ORIGINS",
                ADDITIONAL_ORIGINS_RES_ID);
        ActivityInfo trampolineInfo = new ActivityInfo();
        trampolineInfo.packageName = mContext.getPackageName();
        trampolineInfo.name = HttpOriginShortcutTrampolineActivity.class.getName();
        packageInfo.activities = new ActivityInfo[]{
                packageInfo.activities[0], packageInfo.activities[1], trampolineInfo
        };

        Uri httpUri = Uri.parse("http://legacy.example.com/page");
        registerBrowserForUri(httpUri);

        Intent intent = new Intent(Intent.ACTION_VIEW).setData(httpUri);
        ActivityController<HttpOriginShortcutTrampolineActivity> controller =
                Robolectric.buildActivity(HttpOriginShortcutTrampolineActivity.class, intent);

        controller.create();
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(controller.get().isFinishing());
        Intent launchedIntent = shadowOf(RuntimeEnvironment.application).getNextStartedActivity();
        assertNotNull(launchedIntent);
        assertEquals(httpUri, launchedIntent.getData());
    }
}
