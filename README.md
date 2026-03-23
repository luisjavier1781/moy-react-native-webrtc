[<img src="https://avatars.githubusercontent.com/u/42463376" alt="React Native WebRTC" style="height: 6em;" />](https://github.com/react-native-webrtc/react-native-webrtc)

# React-Native-WebRTC (MoyStream Fork)

> **This is a fork of [react-native-webrtc](https://github.com/react-native-webrtc/react-native-webrtc) maintained for the MoyStream mobile app.**

## Why this fork exists

The official `react-native-webrtc` library does not expose camera zoom controls. This fork adds a native zoom API for both Android and iOS.

### Added features

**iOS** (`VideoCaptureController.m`)
- `setZoom(factor)` — sets `AVCaptureDevice.videoZoomFactor` directly. Persists across all frames automatically since it is a device-level property.
- `getZoomRange()` — returns `{ min, max }` from `AVCaptureDevice.minAvailableVideoZoomFactor` / `maxAvailableVideoZoomFactor`.

**Android** (`CameraCaptureController.java`, `ZoomAwareCaptureSession.java`, `ZoomController.java`)
- `setZoom(ratio)` — installs a `ZoomAwareCaptureSession` wrapper around WebRTC's internal `Camera2Session.captureSession` via reflection. The wrapper intercepts every `setRepeatingRequest()` call and injects `CONTROL_ZOOM_RATIO` (API 30+) or `SCALER_CROP_REGION` (API 24-29) into the `CaptureRequest` by mutating its internal `CameraMetadataNative` settings field. This ensures zoom persists across WebRTC's internal stats-polling loop which rebuilds capture requests every ~1 second.
- `getZoomRange()` — reads `CONTROL_ZOOM_RATIO_RANGE` (API 30+) or `SCALER_AVAILABLE_MAX_DIGITAL_ZOOM` from `CameraCharacteristics`.

### JavaScript API

```typescript
const videoTrack = localStream.getVideoTracks()[0];

// Get zoom range supported by the device
const range = await videoTrack._getZoomRange(); // { min: number, max: number }

// Set zoom level (clamped to device range)
const error = await videoTrack._setZoom(2.5); // null = success, string = error message
```

### Build note

`compileSdkVersion` is capped at 34 in `android/build.gradle` to avoid compatibility issues with abstract methods added in Android SDK 36 (`CameraCaptureSession`).

---

[![npm version](https://img.shields.io/npm/v/react-native-webrtc)](https://www.npmjs.com/package/react-native-webrtc)
[![npm downloads](https://img.shields.io/npm/dm/react-native-webrtc)](https://www.npmjs.com/package/react-native-webrtc)
[![Discourse topics](https://img.shields.io/discourse/topics?server=https%3A%2F%2Freact-native-webrtc.discourse.group%2F)](https://react-native-webrtc.discourse.group/)

A WebRTC module for React Native.

## Feature Overview

|  | Android | iOS | tvOS | macOS* | Windows* | Web* | Expo* |
| :- | :-: | :-: | :-: | :-: | :-: | :-: | :-: |
| Audio/Video | :heavy_check_mark: | :heavy_check_mark: | :heavy_check_mark: | - | - | :heavy_check_mark: | :heavy_check_mark: |
| Data Channels | :heavy_check_mark: | :heavy_check_mark: | - | - | - | :heavy_check_mark: | :heavy_check_mark: |
| Screen Capture | :heavy_check_mark: | :heavy_check_mark: | - | - | - | :heavy_check_mark: | :heavy_check_mark: |
| Plan B | - | - | - | - | - | - | - |
| Unified Plan* | :heavy_check_mark: | :heavy_check_mark: | - | - | - | :heavy_check_mark: | :heavy_check_mark: |
| Simulcast* | :heavy_check_mark: | :heavy_check_mark: | - | - | - | :heavy_check_mark: | :heavy_check_mark: |

> **macOS** - We don't currently actively support macOS at this time.  
Support might return in the future.

> **Windows** - We don't currently support the [react-native-windows](https://github.com/microsoft/react-native-windows) platform at this time.  
Anyone interested in getting the ball rolling? We're open to contributions.

> **Web** - The [react-native-webrtc-web-shim](https://github.com/react-native-webrtc/react-native-webrtc-web-shim) project provides a shim for [react-native-web](https://github.com/necolas/react-native-web) support.  
Which will allow you to use [(almost)](https://github.com/react-native-webrtc/react-native-webrtc-web-shim/tree/main#setup) the exact same code in your [react-native-web](https://github.com/necolas/react-native-web) project as you would with [react-native](https://reactnative.dev/) directly.  

> **Expo** - As this module includes native code it is not available in the [Expo Go](https://expo.dev/client) app by default.  
However you can get things working via the [expo-dev-client](https://docs.expo.dev/development/getting-started/) library and out-of-tree [config-plugins/react-native-webrtc](https://github.com/expo/config-plugins/tree/master/packages/react-native-webrtc) package.  

> **Unified Plan** - As of version 106.0.0 Unified Plan is the only supported mode.  
Those still in need of Plan B will need to use an older release.

> **Simulcast** - As of version 111.0.0 Simulcast is now possible with ease.  
Software encode/decode factories have been enabled by default.

## WebRTC Revision

* Currently used revision: [M124](https://github.com/jitsi/webrtc/tree/M124)
* Supported architectures
  * Android: armeabi-v7a, arm64-v8a, x86, x86_64
  * iOS: arm64, x86_64
  * tvOS: arm64
  * macOS: arm64, x86_64

## Getting Started

Use one of the following preferred package install methods to immediately get going.  
Don't forget to follow platform guides below to cover any extra required steps.  

**npm:** `npm install react-native-webrtc --save`  
**yarn:** `yarn add react-native-webrtc`  
**pnpm:** `pnpm install react-native-webrtc`  

## Guides

- [Android Install](./Documentation/AndroidInstallation.md)
- [iOS Install](./Documentation/iOSInstallation.md)
- [tvOS Install](./Documentation/tvOSInstallation.md)
- [Basic Usage](./Documentation/BasicUsage.md)
- [Step by Step Call Guide](./Documentation/CallGuide.md)
- [Improving Call Reliability](./Documentation/ImprovingCallReliability.md)
- [Migrating to Unified Plan](https://docs.google.com/document/d/1-ZfikoUtoJa9k-GZG1daN0BU3IjIanQ_JSscHxQesvU/edit#heading=h.wuu7dx8tnifl)

## Example Projects

We have some very basic example projects included in the [examples](./examples) directory.  
Don't worry, there are plans to include a much more broader example with backend included.  

## Community

Come join our [Discourse Community](https://react-native-webrtc.discourse.group/) if you want to discuss any React Native and WebRTC related topics.  
Everyone is welcome and every little helps.  

## Related Projects

Looking for extra functionality coverage?  
The [react-native-webrtc](https://github.com/react-native-webrtc) organization provides a number of packages which are more than useful when developing Real Time Communication applications.  
