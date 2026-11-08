# REVIEW.md (BrewShot)

This file says what failure looks like in THIS repo. Keep it short. Add a class
only when a real fix commit taught it.

## What failure looks like here

- **Green that tested nothing:** a skipped Chrome test, a lane that leaves out a
  source dir, or a smoke script nothing calls, and the run still reads green.
- **Host green, CI red:** macOS Chrome and the local uid behave differently from
  ubuntu-latest and the container's non-root UID 10001.
- **Artifact safety:** a partial, aliased or misnamed output replaces or poses as a
  real capture.
- **Unbounded input:** a caller-sized value reaches an allocation, queue or regex
  before any bound is checked.
- **Teardown:** a Chrome descendant or a recreated profile dir outlives the run.

## Recurring classes (each taught by a real fix)

1. **Skips and unwired tests pass silently.** The REQUIRE_CHROME guard (df9125b) was
   rebuilt to read the JUnit XML and fail closed (76ca9f2). The folder-worker smoke
   ran nowhere (741ba49). Check: the new test runs in a CI lane, with zero skips.
2. **Green on macOS, red on Linux.** Chrome deleted its own lock file during our walk
   (60b4c60). A host-side read failed on a container-owned PNG (3cdbdb4, 34ea538).
   Check: Linux evidence, not only local runs.
3. **A guarantee fixed per site, not per class.** Atomic writes covered some outputs
   but not all (d22c0ca). The alias guard compared spellings, not files (1dbf88b,
   94d86ae). Check: grep every writer and every path compare.
4. **The bound comes after the cost.** Decode before the dimension check (903ebd6),
   a CDP inbox with no total cap (4a27441), an SVG attribute regex with no bound
   (d020fe0). Check: the bound runs before the first allocation.
5. **A refusal no test exercises.** Mutants survived: a witness that never refused
   (5efa14c), an argv probe that always said false (0434ffe), an unreached guard
   branch (ccffcb1). Check: each refusal branch has a test that hits it.
6. **Teardown races.** Kill descendants before deleting the profile (0c7479f,
   19bf22f). Require a cgroup to show no descendants (b7750c0).
7. **Success read from the wrong signal.** The AA diff passed a 1px shift (56fa40c),
   redirect hops were counted twice (64ba4a4), and a trust guard ignored a failed
   producer exit (79ce854).

## Where to put extra depth

build.gradle.kts lane wiring (Chrome-test catalog, no-skip guard, FORBID/REQUIRE),
ci.yml and the Dockerfile, docker/ worker and smoke-test.sh (outside Gradle lanes),
ArtifactWriter and output aliasing, teardown/reaper/cgroup proof, resource caps,
and trust/*.sh exit codes (0 clean, 1 forbidden, 2 VOID). Test-only and build-only
changes are NOT automatically low risk: the guards live there.

## Required evidence

A verdict names the exact tip and the commands that ran on it, with test counts
taken from the JUnit XML: `./gradlew unitTest --no-daemon --stacktrace` (tests
and skips, where skips must be 0) and `docker build --target chrome-test .`
(runs chromeTest). If docker/ or the Dockerfile changed, also name
`sh docker/smoke-test.sh brewshot:ci`. If native or CLI startup changed, also
name `./gradlew nativeImage`. "Targeted tests passed" is not enough.
