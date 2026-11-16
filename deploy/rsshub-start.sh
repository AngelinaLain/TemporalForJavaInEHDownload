#!/bin/sh
# Keep the RSSHub and scraper proxy identity consistent on existing installations.
set -eu
if [ -z "${PROXY_URI:-}" ] && [ -z "${PROXY_AUTH:-}" ] && [ -n "${PROXY_USERNAME:-}" ]; then
    export PROXY_AUTH="${PROXY_USERNAME}:${PROXY_PASSWORD:-}"
fi
unset PROXY_USERNAME PROXY_PASSWORD
exec npm run start
