# [OPEN] gradle-rjar-lock

## Symptom
- Gradle fails during `:app:processDebugResources`
- Error points to locked generated file:
  `app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar`

## Expected
- Gradle should regenerate resource outputs without Windows file-lock interference.

## Hypotheses
1. Android Studio keeps a file handle on generated resource outputs while Gradle tries to overwrite them.
2. A lingering Gradle/JVM process still holds `R.jar` after a previous build attempt.
3. `adb` or a related Android tooling process indirectly keeps the build directory busy.
4. Stale `app/build` intermediates are causing repeated overwrite/delete failures on Windows.

## Evidence Log
- Pending collection

## Status
- Collecting process/build-folder evidence and verifying after cleanup.
