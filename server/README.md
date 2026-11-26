# PigeonPod add-ons (all optional)

Listen Later works with stock PigeonPod. These source patches add features to the server.
Target: PigeonPod v1.31.0, commit b6e1a49. Re-apply after any PigeonPod update.

| Patch | What it adds | Needs |
|---|---|---|
| patches/pigeon-0.4.diff | Redis session store (stay signed in across restarts), 503 when Redis is down | Redis |
| patches/pigeon-0.5-fence.diff | Password change signs out older sessions (adds an `auth_version` column, a migration that can't be undone) | 0.4 |
| patches/individual-delete-custom.patch | Full delete of an Individual Video | applies after 0.4 and 0.5 |
| patches/individual-delete-stock.patch | The same full delete on stock PigeonPod | nothing else |

Pick one path: stock (delete only), or custom (0.4, then 0.5, then delete-custom).

Apply order for custom, from a clean checkout of b6e1a49:

    P=/path/to/this/server/patches
    git apply -p1 $P/pigeon-0.4.diff                      # at repo root
    (cd backend && git apply -p1 $P/pigeon-0.5-fence.diff)  # inside backend/
    git apply $P/individual-delete-custom.patch           # at repo root

Then run `mvn test` in `backend/` (the image build skips tests), build your image, restart.
Back up your database and keep the previous image first. To roll back, run the previous image;
the extra `auth_version` column is harmless.

Redis (custom path only): internal network, no published port, a generated password,
`appendonly yes`; give the app `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT` and
`SPRING_DATA_REDIS_PASSWORD`. Never commit the values.

## Verify on a clean fixture

`fixture/prepare.sh stock|custom`, then `fixture/run-fixture.sh stock|custom` from the same directory (needs git, Maven, JDK, Docker) clones upstream, applies the
patches, runs `mvn test`, builds the image, and checks the routes with synthetic data and a
throwaway admin. Last results: stock 75 tests and 9 checks; custom 78 tests and 16 checks
(including restart, Redis stopped, password change), all passing. With Redis stopped the 503 took about a minute to arrive, so expect a slow answer, not an instant one.
