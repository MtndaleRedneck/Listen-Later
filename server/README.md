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

Run these in order from an empty working directory (V is `stock` or `custom`):

    /path/to/server/fixture/prepare.sh V    # clone upstream b6e1a49, apply the patches into ./pp
    /path/to/server/fixture/build.sh V      # mvn test in ./pp, then docker build -t pp-fixture:V
    /path/to/server/fixture/run-fixture.sh V  # compose up (127.0.0.1:8834), run fixture.py checks

It uses synthetic data and a throwaway admin. Last results: stock 77 tests and 9 checks; custom
80 tests and 19 checks (restart, Redis stopped, a database-only version bump that only the
sign-in fence can refuse, password change), all passing. With Redis stopped the 503 took about a
minute to arrive, so expect a slow answer, not an instant one. The fixture does not cover a
non-admin account, channel-owned videos, or files on disk; unit tests cover the first two.

## Notes on the fixture

- Prerequisites: git, JDK 17, Maven, Docker with Compose, python3, and `sudo` (the scripts
  `chmod` the data folder so the container user can write it; on a machine without `sudo`, make
  that folder writable yourself). The fixture publishes port 8834 on 127.0.0.1 only.
- The fixture signs in as PigeonPod's stock first-run admin (`root` / `Root@123`) on a throwaway
  container with synthetic data, then changes that password. Never point it at a real server.
- Redis is tested with a password only (`SPRING_DATA_REDIS_PASSWORD`). A Redis username (ACL)
  is not tested.
- After a password change, the older session gets HTTP 401 (the body carries its own code 500).
  Listen Later checks the HTTP status first, so it treats that as "sign in again".
- The fence adds migration `V58__Add_auth_version_to_user.sql`. If a later PigeonPod release adds
  its own V58, Flyway will refuse to start: renumber the file after re-applying.
- With Redis stopped, requests wait about a minute before the 503, because no Redis timeout is set.

## Files left behind and known limits

- What the new delete removes from disk: the video's media file, its chapters file, and its subtitle
  (`<name>.vtt`, `<name>.srt`) and thumbnail (`<name>.jpg`, `.jpeg`, `.png`, `.webp`) files, matched
  by exact name only. Subtitles with a language in the name, such as `<name>.en.vtt`, are left in
  the folder, because a name that merely starts with another video's name cannot be proven to
  belong to the deleted video. Delete them by hand if you want them gone.
- PigeonPod's own cleanup for channels and playlists (not the new delete) matches files by
  "starts with the video's name plus a dot". It can remove a subtitle or thumbnail that belongs to a
  different video whose title begins with the same text and a dot (for example "Title" and
  "Title.Remix"). The new delete does not do this, and the patch does not change that code.
- If a delete lands in the instant a download is starting (after the download read the row, before
  it marks it as downloading), the download can still write its files for a video that no longer
  exists. No entry comes back; the leftover files stay in the folder. PigeonPod's own delete has the
  same gap.
