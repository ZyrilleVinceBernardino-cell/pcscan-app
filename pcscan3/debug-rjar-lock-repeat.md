[OPEN]

# Debug Session: rjar-lock-repeat

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
- Android Studio is touching generated build outputs while Gradle rebuilds
- Stale `app/build` outputs are causing repeated incremental resource lock failures

## Planned Evidence
- Stop Gradle daemons
- Inspect `java`, `adb`, and `studio64` processes
- Remove `app/build` after clearing lock holders
- Re-run `:app:processDebugResources` to confirm whether the lock is resolved

## Evidence Collected
- `.\gradlew.bat --stop` succeeded and stopped 1 daemon
- Running processes before cleanup included:
  - `adb` pid `18876`
  - `java` pids `11632`, `14640`, `18772`, `20500`, `21340`
  - `studio64` pid `19652`
- `app/build` existed at the time of failure
- After killing current `java` and `adb` processes, a rerun of `:app:processDebugResources` advanced past the original `R.jar` lock
- First rerun failed with a different error:
  - `stable_resource_ids_file\debug\processDebugResources\stableIds.txt: error: failed to open: The system cannot find the file specified. (2)`
- A second rerun of `.\gradlew.bat :app:processDebugResources --console=plain` succeeded with `BUILD SUCCESSFUL`

## Conclusion
- The original `R.jar` lock was cleared by stopping Gradle and killing the active `java` and `adb` processes
- `studio64` remained open and is the likely source of recurring helper process respawns
- The later `stableIds.txt` failure was a transient intermediate-state issue after cleanup, not a persistent code/resource error
- Current state: `:app:processDebugResources` passes successfully
