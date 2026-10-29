#!/usr/bin/env bash
set -euo pipefail
core_revision=a79dfe9047e7fec58808aefe48ad2bf499c7af11
if [ ! -d vendor/snes9x2005/.git ]; then
    mkdir -p vendor
    git clone https://github.com/libretro/snes9x2005.git vendor/snes9x2005
fi
git -C vendor/snes9x2005 checkout --detach "$core_revision"
test "$(git -C vendor/snes9x2005 rev-parse HEAD)" = "$core_revision"
mkdir -p app/src/main/assets
cp vendor/snes9x2005/copyright app/src/main/assets/SNES9X-LICENSE.txt
python3 scripts/make-homebrew.py
