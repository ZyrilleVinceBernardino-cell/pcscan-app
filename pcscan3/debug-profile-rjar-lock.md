[OPEN]

# Debug Session: profile-rjar-lock

## Symptom
- Gradle fails at `:app:processDebugResources`
- Locked file:
  `app\build\intermediates\compile_and_runtime_not_namespaced_r_class_jar\debug\processDebugResources\R.jar`

## Scope
- Build environment and file-lock investigation only
- No app logic changes during evidence collection

## Hypotheses
- A Gradle daemon still has `R.jar` open
- A lingering `java` process from Android tooling is locking generated outputs
- `adb` is indirectly keeping related tooling alive
- Android Studio is respawning helper processes that re-lock `R.jar`
- Deleting the exact stale `R.jar` after clearing helpers will unblock the resource task

## Planned Evidence
- Stop Gradle daemons
- Inspect `java`, `adb`, and `studio64` processes
- Delete the exact stale `R.jar` after clearing lock holders
- Re-run `:app:processDebugResources` to verify whether the lock is resolved

## Evidence Collected
- `.\gradlew.bat --stop` succeeded and stopped 2 daemons
- Running processes before cleanup included:
  - `adb` pid `20832`
  - `java` pids `7924`, `10804`, `22124`, `23616`, `24160`
  - `studio64` pid `19944`
- The stale generated file existed at failure time:
  - `app\build\intermediates\compile_and_runtime_not_namespaced_r_class_jar\debug\processDebugResources\R.jar`
- After clearing the current `java` and `adb` helpers and deleting that exact `R.jar`, a rerun of
  `.\gradlew.bat :app:processDebugResources --console=plain`
  completed with `BUILD SUCCESSFUL`

## Conclusion
- Confirmed root issue: Windows file lock on generated `R.jar`
- Effective recovery for this recurrence:
  - stop Gradle
  - kill current `java` and `adb` helpers
  - delete the exact stale `R.jar`
  - rerun `:app:processDebugResources`
- `studio64` remaining open is still the likely reason helper processes keep respawning between retries
