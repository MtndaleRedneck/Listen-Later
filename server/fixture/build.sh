#!/usr/bin/env bash
# usage: build.sh stock|custom  (run after prepare.sh, from the same directory)
# Runs the server's own tests, then builds the image run-fixture.sh starts.
set -euxo pipefail
V=$1
cd pp
mvn -B -f backend/pom.xml test
docker build -t pp-fixture:$V .
