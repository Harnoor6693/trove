import { ExpoConfig, ConfigContext } from 'expo/config';
import { ConfigPlugin, withDangerousMod, withAppBuildGradle, withGradleProperties, withProjectBuildGradle } from 'expo/config-plugins';
import fs from 'fs/promises';
import path from 'path';
import pkg from './package.json';
import detoxPkg from 'detox/package.json';

// expo-build-properties' `ios.deploymentTarget` only rewrites the app's own
// `platform :ios` line; some third-party pods vendor a resource-bundle
// target (SDWebImage-SDWebImage, RNSVG-RNSVGFilters) whose
// IPHONEOS_DEPLOYMENT_TARGET (9.0 / 12.4) comes from the pod's own podspec
// and survives react_native_post_install untouched (a known CocoaPods
// resource-bundle-target quirk) - Xcode 27's stricter minimum (15.0+) then
// rejects the whole build. This appends one more post_install pass over
// every pod target, catching resource bundles too.
const withIosPodDeploymentTargetFix: ConfigPlugin = (config) =>
  withDangerousMod(config, [
    'ios',
    async (config) => {
      const podfilePath = path.join(config.modRequest.platformProjectRoot, 'Podfile');
      let contents = await fs.readFile(podfilePath, 'utf8');
      const marker = "post_install do |installer|\n    react_native_post_install(";
      if (!contents.includes(marker)) {
        throw new Error('withIosPodDeploymentTargetFix: post_install marker not found in Podfile');
      }
      const fix =
        "\n    installer.pods_project.targets.each do |target|\n" +
        "      target.build_configurations.each do |build_config|\n" +
        "        if build_config.build_settings['IPHONEOS_DEPLOYMENT_TARGET'] &&\n" +
        "           build_config.build_settings['IPHONEOS_DEPLOYMENT_TARGET'].to_f < 15.1\n" +
        "          build_config.build_settings['IPHONEOS_DEPLOYMENT_TARGET'] = '15.1'\n" +
        "        end\n" +
        "      end\n" +
        "    end\n  end\nend\n";
      contents = contents.replace(/\n  end\nend\n$/, fix);
      await fs.writeFile(podfilePath, contents);
      return config;
    },
  ]);

// Detox's own Android setup docs (project-setup.mdx, via Context7) require
// patching `android/app/build.gradle` to point `testInstrumentationRunner`
// at androidx.test's `AndroidJUnitRunner` and add `com.wix:detox` as an
// `androidTestImplementation` dependency. Expo's prebuild template does
// neither, so without this fix AGP falls back to its decades-old default,
// `android.test.InstrumentationTestRunner`, which builds its test suite by
// reflectively scanning every class in every dex file of the (multidex) APK
// before `Instrumentation.onCreate` returns. On this app's dex count that
// scan is slow enough to blow the OS's fixed process-startup deadline on
// every single launch, surfacing as `ActivityManager: ANR ... failed to
// complete startup` on 100% of `detox test --configuration android.emu.debug`
// runs (step 27, task 27.4) rather than app code ever running.
// `com.wix:detox:20.51.4` isn't on mavenCentral or jitpack — Detox ships its
// own prebuilt local Maven repo *inside the npm package*
// (`node_modules/detox/Detox-android/com/wix/detox/20.51.4/detox-20.51.4.aar`,
// confirmed on disk, step 27, task 27.4) and its docs (project-setup.mdx, via
// Context7) have the root `android/build.gradle` add it as a repository.
// Without this, Gradle falls through to mavenCentral, which really does
// publish an unrelated, ancient `com.wix:detox:0.1.1` under the same
// coordinates (that's what `+` silently resolved to and compiled against,
// missing today's `Detox`/`DetoxConfig` classes), or to jitpack, which has no
// build for a bare `20.51.4` tag and returns an HTML error page that fails
// POM parsing.
const withAndroidDetoxLocalMavenRepo: ConfigPlugin = (config) =>
  withProjectBuildGradle(config, (config) => {
    const marker = "maven { url 'https://www.jitpack.io' }";
    if (!config.modResults.contents.includes(marker)) {
      throw new Error('withAndroidDetoxLocalMavenRepo: jitpack repository marker not found');
    }
    if (!config.modResults.contents.includes('Detox-android')) {
      config.modResults.contents = config.modResults.contents.replace(
        marker,
        `maven { url("$rootDir/../node_modules/detox/Detox-android") }\n    ${marker}`
      );
    }
    return config;
  });

const withAndroidDetoxGradleFix: ConfigPlugin = (config) =>
  withAppBuildGradle(config, (config) => {
    let contents = config.modResults.contents;

    const runnerMarker = 'versionName "1.0.0"';
    if (!contents.includes(runnerMarker)) {
      throw new Error('withAndroidDetoxGradleFix: defaultConfig versionName marker not found');
    }
    if (!contents.includes('testInstrumentationRunner')) {
      contents = contents.replace(
        runnerMarker,
        `${runnerMarker}\n\n        testInstrumentationRunner 'androidx.test.runner.AndroidJUnitRunner'`
      );
    }

    const depsMarker = 'dependencies {';
    // `com.wix:detox:+` resolves to an unrelated, orphaned `0.1.1` artifact
    // squatting the same Maven coordinates (confirmed via `dependencyInsight`,
    // step 27, task 27.4) — the real native Android library is published
    // under the exact npm `detox` package version, so pin to that.
    const detoxDep = `androidTestImplementation('com.wix:detox:${detoxPkg.version}')`;
    if (!contents.includes('com.wix:detox:')) {
      contents = contents.replace(depsMarker, `${depsMarker}\n    ${detoxDep}`);
    }

    // `com.wix:detox` transitively pulls androidx.test artifacts frozen at
    // their 2019-era versions (rules 1.2.0, espresso 3.3.0, ext:junit 1.1.1,
    // uiautomator 2.2.0 -> runner/monitor 1.3.0) and nothing else in the
    // dependency graph requests a newer one, so that's what wins. Those old
    // androidx.test:core manifests don't set `android:exported` on their
    // InstrumentationActivityInvoker activities, which targetSdk 36 (Android
    // 12+'s exported-attribute requirement) rejects at manifest-merge time
    // (`processDebugAndroidTestManifest` failure, step 27, task 27.4). Forcing
    // every configuration to modern androidx.test versions (whose manifests
    // already set `android:exported`) fixes it at the one place all of this
    // app's androidTest dependency resolution routes through, rather than
    // patching the merged manifest output after the fact.
    if (!contents.includes('resolutionStrategy')) {
      contents += `
configurations.all {
    resolutionStrategy {
        force 'androidx.test:core:1.6.1'
        force 'androidx.test:runner:1.6.2'
        force 'androidx.test:monitor:1.7.2'
        force 'androidx.test:rules:1.6.1'
        force 'androidx.test.ext:junit:1.2.1'
    }
}
`;
    }

    config.modResults.contents = contents;
    return config;
  });

// Detox's Android integration is not auto-discovered: its own setup docs
// (project-setup.mdx, via Context7) require a hand-written androidTest JUnit4
// class (`DetoxTest`) that opens the `ActivityTestRule` for the main activity
// and calls `Detox.runTests(...)`, which is what actually starts the
// WebSocket bridge the Node/Jest side connects to. Without it,
// `androidx.test`'s `TestRequestBuilder` scans the test APK's classpath,
// finds zero `@Test` methods, and the instrumentation process exits
// immediately (`finished inst` in logcat, no activity ever launched, no
// WebSocket ever opened) — surfacing identically to the ANR from Detox's
// side as "Failed to run application on the device" (step 27, task 27.4).
const withAndroidDetoxTestClass: ConfigPlugin = (config) =>
  withDangerousMod(config, [
    'android',
    async (config) => {
      const dir = path.join(
        config.modRequest.platformProjectRoot,
        'app/src/androidTest/java/com/trove/app'
      );
      await fs.mkdir(dir, { recursive: true });
      const contents = `package com.trove.app;

import com.wix.detox.Detox;
import com.wix.detox.config.DetoxConfig;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.rule.ActivityTestRule;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class DetoxTest {
    @Rule
    public ActivityTestRule<MainActivity> mActivityRule =
        new ActivityTestRule<>(MainActivity.class, false, false);

    @Test
    public void runDetoxTests() {
        DetoxConfig detoxConfig = new DetoxConfig();
        detoxConfig.idlePolicyConfig.masterTimeoutSec = 90;
        detoxConfig.idlePolicyConfig.idleResourceTimeoutSec = 60;
        detoxConfig.rnContextLoadTimeoutSec = (BuildConfig.DEBUG ? 180 : 60);

        Detox.runTests(mActivityRule, detoxConfig);
    }
}
`;
      await fs.writeFile(path.join(dir, 'DetoxTest.java'), contents);
      return config;
    },
  ]);

// D8 merges this app's (multidex, 20+ dex file) androidTest dex archives
// in-process inside the Gradle daemon (no worker isolation), so it's bound
// by the daemon's own `-Xmx`. The prebuild template's default 2048m is too
// small for that merge and D8 fails with `OutOfMemoryError: Java heap space`
// (`:expo-modules-core:mergeExtDexDebugAndroidTest`, step 27, task 27.4).
// 4096m matches what EAS Build's own "medium" resource class sets for the
// same job (docs.expo.dev/build-reference/infrastructure, via Context7).
const withAndroidGradleHeapFix: ConfigPlugin = (config) =>
  withGradleProperties(config, (config) => {
    const key = 'org.gradle.jvmargs';
    const value = '-Xmx4096m -XX:MaxMetaspaceSize=512m';
    const existing = config.modResults.find(
      (item): item is Extract<typeof item, { type: 'property' }> =>
        item.type === 'property' && item.key === key
    );
    if (existing) {
      existing.value = value;
    } else {
      config.modResults.push({ type: 'property', key, value });
    }
    return config;
  });

/**
 * Expo config for a **sideload-only** v1.0 (owner decision): a signed APK per
 * release plus TestFlight/AltStore, no Play or App Store submission.
 *
 * Notable choices, each tied back to a recovery-doc item:
 *
 *  - `version` comes from `package.json` — one source for the marketing
 *    version. Native build numbers are not set here at all: `eas.json` sets
 *    `cli.appVersionSource: "remote"`, so EAS owns and increments them, which
 *    is what readiness gap P4-21 asks for (a build number that always rises).
 *  - `runtimeVersion` uses the `appVersion` policy, and `updates.enabled` is
 *    `false`: OTA is off for v1 and every change ships as a new binary
 *    (gap P4-21). `expo-updates` is not a dependency.
 *  - **Transport security is on.** iOS keeps `NSAllowsArbitraryLoads: false`
 *    and adds `NSAllowsLocalNetworking: true` — Apple's sanctioned exception
 *    for `.local`/RFC1918 hosts, which is exactly what the connect screen's
 *    "allow http on my network" opt-in permits and nothing more. Android sets
 *    `usesCleartextTraffic: false`.
 *  - **No notification config.** The `expo-notifications` plugin, the
 *    `RECEIVE_BOOT_COMPLETED`/`VIBRATE` permissions and the
 *    `application-groups` entitlement all existed only for the cut native
 *    bank-notification listener and Expo push (owner decision: no push).
 *  - `ios.privacyManifests` declares `PrivacyInfo.xcprivacy` through the
 *    config plugin, which Expo generates at prebuild (gap J9 / P4-22).
 *  - `usesNonExemptEncryption: false` — without it every TestFlight build
 *    stalls on "Missing Compliance" (gap P4-21).
 */
export default ({ config }: ConfigContext): ExpoConfig =>
  withAndroidDetoxTestClass(withAndroidGradleHeapFix(withAndroidDetoxLocalMavenRepo(withAndroidDetoxGradleFix(withIosPodDeploymentTargetFix({
  ...config,
  name: 'Trove',
  slug: 'trove',
  version: pkg.version,
  orientation: 'portrait',
  icon: './assets/icon.png',
  userInterfaceStyle: 'automatic',
  runtimeVersion: { policy: 'appVersion' },
  updates: { enabled: false },
  assetBundlePatterns: ['**/*'],
  ios: {
    supportsTablet: false,
    bundleIdentifier: 'com.trove.app',
    config: {
      usesNonExemptEncryption: false,
    },
    infoPlist: {
      NSCameraUsageDescription: 'Trove uses your camera to scan receipts.',
      NSPhotoLibraryUsageDescription: 'Trove accesses photos to attach receipts.',
      NSFaceIDUsageDescription: 'Trove uses Face ID to unlock your private financial ledger.',
      NSAppTransportSecurity: {
        // No arbitrary loads, ever. `NSAllowsLocalNetworking` is the narrow,
        // Apple-sanctioned carve-out that lets a user reach their own server
        // on their own LAN over http when they explicitly opt in on the
        // connect screen; it does not weaken TLS for any public host.
        NSAllowsArbitraryLoads: false,
        NSAllowsLocalNetworking: true,
      },
    },
    privacyManifests: {
      NSPrivacyTracking: false,
      NSPrivacyTrackingDomains: [],
      NSPrivacyCollectedDataTypes: [],
      NSPrivacyAccessedAPITypes: [
        {
          // Expo modules read/write UserDefaults for their own configuration.
          NSPrivacyAccessedAPIType: 'NSPrivacyAccessedAPICategoryUserDefaults',
          NSPrivacyAccessedAPITypeReasons: ['CA92.1'],
        },
        {
          // expo-file-system / image-picker stat files the user picked.
          NSPrivacyAccessedAPIType: 'NSPrivacyAccessedAPICategoryFileTimestamp',
          NSPrivacyAccessedAPITypeReasons: ['C617.1'],
        },
        {
          NSPrivacyAccessedAPIType: 'NSPrivacyAccessedAPICategoryDiskSpace',
          NSPrivacyAccessedAPITypeReasons: ['E174.1'],
        },
      ],
    },
  },
  android: {
    adaptiveIcon: {
      foregroundImage: './assets/adaptive-icon.png',
      backgroundColor: '#f6f3ec',
    },
    package: 'com.trove.app',
    permissions: ['CAMERA', 'READ_MEDIA_IMAGES'],
    // Not in Expo 58's `ExpoConfig['android']` type yet, but read by prebuild
    // and written into AndroidManifest.xml.
    ...({ usesCleartextTraffic: false } as Record<string, unknown>),
  },
  plugins: [
    'expo-router',
    'expo-font',
    'expo-secure-store',
    // SDK 57 dropped the top-level `splash` key: prebuild ignores it and the
    // native splash falls back to white. The plugin is the only way in now.
    [
      'expo-splash-screen',
      {
        image: './assets/splash.png',
        resizeMode: 'contain',
        backgroundColor: '#f6f3ec',
      },
    ],
    [
      'expo-local-authentication',
      {
        faceIDPermission: 'Trove uses Face ID to unlock your private financial ledger.',
      },
    ],
    [
      'expo-image-picker',
      {
        photosPermission: 'Trove accesses photos to attach receipts.',
        cameraPermission: 'Trove uses the camera to scan receipts.',
        // Without this the default plugin config emits a microphone usage
        // string and RECORD_AUDIO into the prebuild output (gap P4-22).
        microphonePermission: false,
      },
    ],
  ],
  scheme: 'trove',
  extra: {
    // Development convenience only. Release profiles ship this empty and the
    // user enters their own server address on `(auth)/connect` (gap P4-19).
    apiBaseUrl: process.env.EXPO_PUBLIC_API_BASE_URL ?? '',
    eas: {
      projectId: process.env.EAS_PROJECT_ID ?? '',
    },
  },
})))));
