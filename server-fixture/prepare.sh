#!/usr/bin/env bash
# usage: prepare.sh stock|custom  -> ./pp = PigeonPod b6e1a49 with the chosen patches applied
set -euxo pipefail
V=$1; HERE=$(cd "$(dirname "$0")" && pwd)
rm -rf pp; git clone -q https://github.com/aizhimou/pigeon-pod pp
cd pp; git checkout -q b6e1a49
if [ "$V" = custom ]; then
  git apply -p1 "$HERE/pigeon-0.4.diff"                       # Redis sessions, at repo root
  (cd backend && git apply -p1 "$HERE/pigeon-0.5-fence.diff") # sign-in fence, inside backend/
  git apply "$HERE/individual-delete-custom.patch"            # full delete (+ extra tests)
else
  git apply "$HERE/individual-delete-stock.patch"             # full delete on stock
fi
git status --short | head -50
