#!/bin/sh
# Installs linview for this user (#37, #61): no root, nothing outside the home folder, so it also suits image-based
# systems such as Bazzite or Silverblue. Run it from the unpacked release (or build/release/linview-<version>/):
#
#   ./install.sh               installs, or updates an earlier install
#   ./install.sh --uninstall   removes it
#
# The app goes to ~/.local/share/linview (or $XDG_DATA_HOME/linview), with the app menu entry, its icons and
# "Open with" in the file manager. It doesn't take over double-click: the README shows how to make it the default.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
data=${XDG_DATA_HOME:-$HOME/.local/share}
app=$data/linview
sizes="16 32 48 64 128 256 512"

# Lets the desktop see the change at once; harmless if the tool is missing (it rescans by itself, a little later).
refresh() {
    if command -v update-desktop-database >/dev/null 2>&1; then
        update-desktop-database "$data/applications" || true
    fi
}

if [ "${1:-}" = "--uninstall" ]; then
    rm -rf "$app" "$data/applications/linview.desktop"
    for size in $sizes; do
        rm -f "$data/icons/hicolor/${size}x${size}/apps/linview.png"
    done
    refresh
    echo "linview removed; images open with the system's other viewers again."
    exit 0
fi

if [ ! -x "$here/linview/bin/linview" ]; then
    echo "linview/bin/linview not found next to install.sh: run it from the unpacked release." >&2
    exit 1
fi

rm -rf "$app"   # a new version replaces the old one completely
mkdir -p "$data"
cp -R "$here/linview" "$app"
for size in $sizes; do
    mkdir -p "$data/icons/hicolor/${size}x${size}/apps"
    cp "$here/icons/linview-$size.png" "$data/icons/hicolor/${size}x${size}/apps/linview.png"
done
mkdir -p "$data/applications"
# The path goes inside the Exec quotes; '&', '|' and '\' in it are escaped for sed.
exec_path=$(printf '%s' "$app/bin/linview" | sed 's/[&|\\]/\\&/g')
sed "s|@EXEC@|$exec_path|" "$here/linview.desktop" > "$data/applications/linview.desktop"
refresh
echo "Installed in $app; \"Open with linview\" is in the file manager."
