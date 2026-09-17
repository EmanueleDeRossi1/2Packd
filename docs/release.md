## How to release a new version of the widget, step by step

Because everytime I forget what I need to do and I should really write this down.

1. Bump the version. In android/app/build.gradle.kts increment versionCode + 1, and set versionName to whatever versionName you'd like (e.g., 1.3)
2. After committing, tag the commit with something like `git tag v1.3.0`
3. Build a signed release. In Android Studio go to Build -> Generate Signed Bundle/APK -> APK. Use the keys you have stored you-know-where and set the keystore path at /Users/emanuelederossi/2packd.jks. Select "release" as the build variant.
4. Rename the file at android/app/release from app-release.apk -> 2packd-v1.3.0.apk (or whatever version you have).
5. Install and test the apk on your phone.
6. Create a new release. Go to Github -> Releases -> Draft a new release and write as a title v1.3.0 and as a tag v1.3.0 and on the release note what new fixes/improvements are there. Remember of course to upload the apk there, otherwise all these steps are for nothing, genius.