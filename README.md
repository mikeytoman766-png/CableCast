# CableCast – Android → Android screen sharing over a USB cable

## Build
1. Android Studio > New Project > "Empty Views Activity", Kotlin, package `com.cablecast`, minSdk 26.
2. Replace `app/build.gradle.kts`, `AndroidManifest.xml`, and add the 3 Kotlin files + `res/xml/accessory_filter.xml`.
3. Run on BOTH phones (same APK).

## Hardware
Receiver phone must be the USB *host*: plug a USB-A OTG adapter into the RECEIVER phone,
then a normal USB-A→USB-C (or C→C) cable from the adapter to the SENDER phone.

## Use
1. Connect the cable.
2. Receiver: open CableCast > RECEIVE. Allow the USB prompt(s) (twice: once before and once after the mode switch).
3. Sender: a prompt appears (or open CableCast) > SEND > allow USB + screen capture.
4. Sender's screen appears on the receiver.

## How it works
Sender screen -> MediaProjection -> H.264 hardware encoder (12 Mbps, 60 fps)
 -> Android Open Accessory bulk pipe -> receiver -> H.264 hardware decoder -> SurfaceView.
Packet format: [int width][int height] then repeated [int len][int codecFlags][len bytes].
