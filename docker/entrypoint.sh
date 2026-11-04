#!/bin/sh
set -e

# Fresh node_modules whenever package-lock.json changes, or always with NPM_CI=1.
stamp=node_modules/.lock-sha256
sum=$(sha256sum package-lock.json | cut -d' ' -f1)
if [ "${NPM_CI:-0}" = 1 ] || [ "$(cat "$stamp" 2>/dev/null)" != "$sum" ]; then
  npm ci
  echo "$sum" > "$stamp"
fi

exec "$@"
