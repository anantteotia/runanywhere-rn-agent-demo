# Troubleshooting

## App crashes immediately

- Make sure you're running on a **physical ARM64 device**.
- x86 Android emulators will crash due to missing native libraries.

## Build fails: Java version / Gradle plugin

- The React Native Gradle plugin does **not** work with Java 25.
- Use Android Studio bundled Java (JBR):
  - `C:\Program Files\Android\Android Studio\jbr`

## SDK artifacts not found

If Gradle can't find RunAnywhere SDK artifacts, re-publish to Maven Local:

```
cd G:\Code\runanywhere-sdks\sdk\runanywhere-kotlin
.\gradlew --% -Prunanywhere.testLocal=false -Prunanywhere.nativeLibVersion=0.17.4 publishToMavenLocal
```

## Model downloads are slow

- First run downloads the model (500MB+).
- Keep the app open and wait for download to finish.

## Responses are poor or off-topic

Switch to a larger model (requires more RAM):

- `qwen2.5-0.5b-instruct-q6_k` (fast, small)
- `mistral-7b-instruct-q4_k_m` (high quality, heavy)

Update in:
- `android/app/src/main/java/com/runanywhereagentdemo/MainApplication.kt`
- `src/state/useAgentRunner.ts`

---

This file is referenced by the main README.
