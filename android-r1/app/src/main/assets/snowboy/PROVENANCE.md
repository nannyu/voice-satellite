# Snowboy assets (official Kitt-AI)

Upstream-Repo: https://github.com/Kitt-AI/snowboy
Upstream-Commit: c9ff036e2ef3f9c422a3b8c9a01361dbad7a9bd4
License: Apache-2.0

| File | Upstream path / source | SHA-256 |
|------|------------------------|---------|
| `common.res` | `resources/common.res` (also packaged in official demo APK `assets/snowboy/`) | `5dd5258678182f2e055fa7a6167eba50ded3bf8b41f70faab11fd9b221de488b` |
| `snowboy.umdl` | `resources/models/snowboy.umdl` | `7ccc61effbe05c27d8fd3428bf27e71578d2eddcc97ac9c1437fa0f9cacc64f1` |
| `../jniLibs/armeabi-v7a/libsnowboy-detect-android.so` | extracted from `resources/alexa/SnowboyAlexaDemo.apk` → `lib/armeabi-v7a/` | `f1f76d0efdc50a0cb57bdf37c10d1fc9ab5791b0cec80fb91ea7e2eed3fe455f` |

## Chinese wake model (you provide)

Place your personal model as either:

1. `wakeword.pmdl` (or any `*.pmdl`) next to these assets **before build**, or
2. After install: push into the app files dir (does not overwrite existing pmdl on asset sync):

```bash
adb push your_zh.pmdl /data/local/tmp/wakeword.pmdl
adb shell run-as io.nannyu.voicesatellite.r1 cp /data/local/tmp/wakeword.pmdl files/snowboy/wakeword.pmdl
```

On R1 without `run-as`, copy via a world-readable path your app can read, or rebuild with the file under `assets/snowboy/`.

Any `*.pmdl` wins over `snowboy.umdl`. The English umdl is only for pipeline smoke tests.
