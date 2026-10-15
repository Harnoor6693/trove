/** @type {Detox.DetoxConfig} */
module.exports = {
  testRunner: {
    args: { $0: 'jest', config: 'e2e/jest.config.js' },
    jest: { setupTimeout: 120000 },
  },
  apps: {
    'ios.debug': {
      type: 'ios.app',
      binaryPath:
        'ios/build/Build/Products/Debug-iphonesimulator/Trove.app',
      build:
        'xcodebuild -workspace ios/Trove.xcworkspace -scheme Trove ' +
        '-configuration Debug -sdk iphonesimulator ' +
        '-derivedDataPath ios/build',
    },
    'android.debug': {
      type: 'android.apk',
      binaryPath:
        'android/app/build/outputs/apk/debug/app-debug.apk',
      build:
        'cd android && ./gradlew assembleDebug assembleAndroidTest ' +
        '-DtestBuildType=debug',
    },
  },
  devices: {
    'ios.sim': {
      type: 'ios.simulator',
      // iPhone 15 is not installed on this Mac (Xcode 27 / iOS 27.0 runtime
      // only ships iPhone 17/18 Pro-generation simulators onward, see
      // `xcrun simctl list devicetypes`). iPhone 17 Pro already has a
      // provisioned (shutdown) simulator instance for the installed
      // runtime.
      device: { type: 'iPhone 17 Pro' },
    },
    'android.emu': {
      type: 'android.emulator',
      // No AVD existed on this Mac at all (step 27). Expo SDK 58 targets
      // target SDK 36, compile SDK 37 (react-native 0.88 gradle/libs.versions.toml),
      // not the API 34 this name used to encode, so the AVD is created fresh
      // at API 36 and named to match rather than reusing the stale '_34' name.
      device: { avdName: 'Pixel_5_API_36' },
    },
  },
  configurations: {
    'ios.sim.debug': {
      device: 'ios.sim',
      app:    'ios.debug',
    },
    'android.emu.debug': {
      device: 'android.emu',
      app:    'android.debug',
    },
  },
};
