[OPEN]

# Debug Session: rjar-lock-repeat-2

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
- Android Studio is respawning helper processes that re-lock `app/build`
- Stale `app/build` outputs are amplifying the lock on rerun

## Planned Evidence
- Stop Gradle daemons
- Inspect `java`, `adb`, and `studio64` processes
- Clear current lock holders
- Re-run `:app:processDebugResources` to verify whether the lock is resolved

## Evidence Collected
- `.\gradlew.bat --stop` succeeded and stopped 2 daemons
- Running processes before cleanup included:
  - `adb` pid `20748`
  - `java` pids `2900`, `3220`, `3800`, `3872`, `17776`, `19364`
  - `studio64` pid `19944`
- `app/build` existed at failure time
- The locked output file still existed after failure:
  - `app\build\intermediates\compile_and_runtime_not_namespaced_r_class_jar\debug\processDebugResources\R.jar`
- First rerun reproduced the failure at `:app:processDebugResources` with:
  - `Couldn't delete ...\processDebugResources\R.jar`
- After stopping Gradle again, killing current `java` and `adb` helpers, and deleting that exact `R.jar`, a rerun of `.\gradlew.bat :app:processDebugResources --console=plain` completed with `BUILD SUCCESSFUL`

## Conclusion
- Confirmed root issue: Windows file lock on the generated `R.jar`
- Practical recovery for this recurrence:
  - stop Gradle
  - kill current `java` and `adb` helpers
  - delete the exact stale `R.jar`
  - rerun `:app:processDebugResources`
- `studio64` staying open is still the likely reason the helper processes keep respawning between retries
