[OPEN]

# Debug Session: merge-debug-resources-lock

## Symptom
- Gradle build fails at `:app:mergeDebugResources`
- Locked path mentions `app\build\intermediates\incremental\debug\mergeDebugResources\merged.dir`

## Scope
- Environment/build tooling investigation only
- No business logic changes during initial evidence collection

## Hypotheses
- A Gradle daemon still holds a file handle inside `app/build`
- A lingering `java` process from Android tooling is locking `merged.dir`
- An `adb`-related process indirectly keeps resource outputs open
- Android Studio or a background indexer re-touches generated resources during build
- Stale incremental outputs under `app/build` are corrupt and need a clean rebuild

## Planned Evidence
- Check running Gradle/Java/ADB processes
- Stop Gradle daemons
- Remove stale `app/build` if lock is released
- Re-run `:app:mergeDebugResources` to confirm whether the lock is cleared

## Evidence Collected
- `.\gradlew.bat --stop` succeeded and stopped 2 daemons
- Running processes before cleanup included:
  - `adb` pid `12456`
  - `java` pid `10580`
  - `java` pid `18904`
  - `studio64` pid `19768`
- Forced stop on `adb` and both `java` processes succeeded
- `app/build` was already absent at verification time
- Re-run of `.\gradlew.bat :app:mergeDebugResources` exited with code `0`
- Re-run of `.\gradlew.bat :app:processDebugResources --console=plain` exited with code `0`

## Interim Conclusion
- The lock is currently cleared
- Most likely confirmed cause: lingering Android tooling processes plus stale generated outputs
- `studio64` remained open, but the resource tasks still succeeded once `adb` and `java` were terminated
