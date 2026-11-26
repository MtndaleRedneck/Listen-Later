"""Clean-fixture checks against a running PigeonPod container. Synthetic data only.
usage: fixture.py stock|custom   (expects http://localhost:8834 and ./fx-data as the data dir)"""
import json, sqlite3, subprocess, sys, time, urllib.request, urllib.error
V = sys.argv[1]; BASE = "http://localhost:8834"; DB = "fx-data/pigeon-pod.db"
FEED = "individual-videos-youtube"; results = []

def rec(name, ok, detail=""):
    results.append((name, ok)); print(("PASS " if ok else "FAIL ") + name + (" :: " + str(detail) if detail else ""), flush=True)

def http(method, path, token=None, body=None):
    req = urllib.request.Request(BASE + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    if token: req.add_header("pigeonpod", token)
    try:
        with urllib.request.urlopen(req, timeout=20) as r: code, txt = r.status, r.read().decode()
    except urllib.error.HTTPError as e: code, txt = e.code, e.read().decode()
    try: j = json.loads(txt)
    except Exception: j = None
    return code, j, txt

def login(user="root", pw="Root@123"):
    code, j, txt = http("POST", "/api/auth/login", body={"username": user, "password": pw})
    tok = None
    if j and isinstance(j.get("data"), dict): tok = j["data"].get("token")
    return code, j, tok

def sh(cmd): return subprocess.run(cmd, shell=True, capture_output=True, text=True)

def wait_up():
    for _ in range(90):
        try:
            if http("GET", "/api/auth/status")[0] == 200: return True
        except Exception: pass
        time.sleep(2)
    return False

def db_exec(sql, params=()):
    sh("sudo chmod -R a+rwX fx-data")
    c = sqlite3.connect(DB, timeout=10); c.execute(sql, params); c.commit(); c.close()

def insert_episode(eid, channel_id, status="COMPLETED", feeds=(FEED,)):
    c = sqlite3.connect(DB, timeout=10)
    cols = c.execute("PRAGMA table_info(episode)").fetchall()
    vals = {}
    for _, name, typ, notnull, dflt, pk in cols:
        if name == "id": vals[name] = eid
        elif name == "channel_id": vals[name] = channel_id
        elif name == "download_status": vals[name] = status
        elif notnull and dflt is None: vals[name] = 0 if "INT" in (typ or "").upper() else "x"
    c.execute("INSERT INTO episode (%s) VALUES (%s)" % (",".join(vals), ",".join("?" * len(vals))), list(vals.values()))
    pcols = c.execute("PRAGMA table_info(playlist_episode)").fetchall()
    for f in feeds:
        pv = {}
        for _, name, typ, notnull, dflt, pk in pcols:
            if name == "playlist_id": pv[name] = f
            elif name == "episode_id": pv[name] = eid
            elif notnull and dflt is None: pv[name] = 0 if "INT" in (typ or "").upper() else "x"
        c.execute("INSERT INTO playlist_episode (%s) VALUES (%s)" % (",".join(pv), ",".join("?" * len(pv))), list(pv.values()))
    c.commit(); c.close()

def exists(eid):
    c = sqlite3.connect(DB, timeout=10)
    n = c.execute("SELECT count(*) FROM episode WHERE id=?", (eid,)).fetchone()[0]
    m = c.execute("SELECT count(*) FROM playlist_episode WHERE episode_id=? AND playlist_id=?", (eid, FEED)).fetchone()[0]
    c.close(); return n, m

assert wait_up(), "app did not start"
code, j, tok = login()
rec("login with default synthetic admin works", code == 200 and bool(tok), code)
if not tok: sys.exit(1)

# ---- full delete route (both variants)
code, j, _ = http("GET", "/api/episode/individual/capability", tok)
rec("capability probe says removeIndividual=1", code == 200 and (j or {}).get("data", {}).get("removeIndividual") == 1, j)
code, j, _ = http("GET", "/api/episode/individual/capability")
rec("capability probe refuses anonymous (401)", code == 401, code)
code, j, _ = http("DELETE", "/api/episode/individual/nope123")
rec("anonymous DELETE refused (401)", code == 401, code)
code, j, _ = http("DELETE", "/api/episode/individual/doesnotexist", tok)
rec("unknown id -> already_gone", (j or {}).get("data", {}).get("result") == "already_gone", j)
insert_episode("fxplain", None)
code, j, _ = http("DELETE", "/api/episode/individual/fxplain", tok)
rec("plain individual video -> removed, row and link gone", (j or {}).get("data", {}).get("result") == "removed" and exists("fxplain") == (0, 0), (j, exists("fxplain")))
insert_episode("fxdown", None, status="DOWNLOADING")
code, j, _ = http("DELETE", "/api/episode/individual/fxdown", tok)
rec("downloading -> HTTP 200 + body code 409, nothing changed", code == 200 and (j or {}).get("code") == 409 and exists("fxdown") == (1, 1), (code, j))
insert_episode("fxother", None, feeds=("some-other-playlist",))
code, j, _ = http("DELETE", "/api/episode/individual/fxother", tok)
rec("not in Individual feed -> HTTP 200 + body code 422, nothing changed", code == 200 and (j or {}).get("code") == 422 and exists("fxother")[0] == 1, (code, j))
insert_episode("fxshared", None, feeds=(FEED, "some-other-playlist"))
code, j, _ = http("DELETE", "/api/episode/individual/fxshared", tok)
rec("also in another playlist -> unlinked, row kept", (j or {}).get("data", {}).get("result") == "unlinked" and exists("fxshared") == (1, 0), (j, exists("fxshared")))

if V == "custom":
    # ---- Redis sessions and fence
    sh("docker compose restart pigeon-pod"); rec("app restarted", wait_up())
    code, j, _ = http("GET", "/api/episode/individual/capability", tok)
    rec("still signed in after app-only restart (Redis sessions)", code == 200 and (j or {}).get("code") == 200, (code, j))
    sh("docker compose stop redis"); time.sleep(3)
    code, j, txt = http("GET", "/api/episode/individual/capability", tok)
    rec("Redis down -> HTTP 503, not 401", code == 503, (code, txt[:200]))
    sh("docker compose start redis"); time.sleep(6)
    code, j, _ = http("GET", "/api/episode/individual/capability", tok)
    rec("session works again after Redis returns", code == 200, code)
    code, j, tok2 = login()
    code, j, _ = http("POST", "/api/account/reset-password", tok2, {"password": "Root@123", "newPassword": "Fixture#2026pw"})
    rec("password change accepted", code == 200 and (j or {}).get("code") == 200, (code, j))
    code, j, _ = http("GET", "/api/episode/individual/capability", tok)
    rec("older session refused after password change", code in (401, 403) or (j or {}).get("code") in (401, 403), (code, j))
    code, j, tok3 = login(pw="Fixture#2026pw")
    rec("new password signs in", bool(tok3), code)

bad = [n for n, ok in results if not ok]
print("SUMMARY: %d checks, %d failed" % (len(results), len(bad)))
sys.exit(1 if bad else 0)
