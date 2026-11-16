#!/bin/sh
# Normalize proxy credentials without placing them in shell arguments or logs.
set -eu
exec node /usr/local/bin/gallery-rsshub-start.mjs
