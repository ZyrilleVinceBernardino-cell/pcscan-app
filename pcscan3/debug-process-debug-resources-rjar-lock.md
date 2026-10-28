[OPEN]

# Debug Session: process-debug-resources-rjar-lock

## Symptom
- Gradle build fails at `:app:processDebugResources`
- Locked file is `app\build\intermediates\compile_and_runtime_not_namespaced_r_class_jar\debug\processDebugResources\R.jar`

## Scope
- Build environment and file-lock investigation only
- No business logic changes during evidence collection

## Hypotheses
- A lingering Gradle daemon still holds a handle to `R.jar`
- A background `java` process from Android tooling has the generated jar open
- `adb` indirectly keeps Android build tooling alive and delays file release
- Android Studio is touching or indexing generated outputs while Gradle rebuilds
- Stale outputs under `app/build` are causing repeated incremental resource lock failures

## Planned Evidence
- Stop Gradle daemons
- Inspect `java`, `adb`, and `studio64` processes
- Remove `app/build` after lock holders are cleared
- Re-run `:app:processDebugResources` to confirm whether the lock is resolved

## Evidence Collected
- `.\gradlew.bat --stop` succeeded and stopped 1 daemon
- Running processes before cleanup included:
  - `adb` pid `5176`
  - `java` pid `15980`
  - `java` pid `20696`
  - `studio64` pid `19768`
- Forced stop on `adb` and both `java` processes succeeded
- `c:\AndroidProjects\pcscan3\app\build` was deleted successfully
- Re-run of `.\gradlew.bat :app:processDebugResources --console=plain` exited with code `0`

## Interim Conclusion
- The `R.jar` lock is cleared right now
- Most likely confirmed cause: lingering Android tooling processes plus stale generated outputs under `app/build`
- Android Studio remained open, but the failing task still passed once `adb` and `java` were terminated and generated outputs were removed
