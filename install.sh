#!/bin/sh
# Installs MediaDroyd for the desktop into your account on Linux (#497): the app into ~/.local/opt/MediaDroyd, a desktop
# entry and icon into ~/.local/share, so every start menu that reads them (GNOME, KDE, Xfce, TileWin, ...) offers it
# and videos can be opened with it, and `mediadroyd` into ~/.local/bin. Settings stay where the app keeps them
# (~/.local/share/MediaDroyd) through every update and uninstall.
#
#   ./install.sh               the newest published build (a nightly until a stable one is out), the AppImage unpacked
#   ./install.sh --stable      the newest stable release only
#   ./install.sh --build       built from this checkout (needs a JDK 21 and, for the native library, CMake and a C++
#                              compiler)
#   ./install.sh --build --full
#                              the same with Bytedeco's GPL FFmpeg build (x264, x265, ...): for your own machine only,
#                              never to be passed on (README "Building")
#   ./install.sh --uninstall   removes the app, its menu entry, icon and command; keeps the settings
set -eu

REPO="Hawkynt/MediaDroyd"
HERE="$(cd "$(dirname "$0")" && pwd)"
DATA="${XDG_DATA_HOME:-$HOME/.local/share}"
APP="$HOME/.local/opt/MediaDroyd"
ENTRY="$DATA/applications/de.hawkynt.MediaDroyd.desktop"
ICON="$DATA/icons/hicolor/256x256/apps/mediadroyd.png"
COMMAND="$HOME/.local/bin/mediadroyd"

mode=download
stable=0
full=0
for arg in "$@"; do
  case "$arg" in
    --build) mode=build ;;
    --full) full=1 ;;
    --stable) stable=1 ;;
    --uninstall) mode=uninstall ;;
    -h|--help) sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $arg (see --help)" >&2; exit 2 ;;
  esac
done

refresh() {
  command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database -q "$DATA/applications" || true
  command -v gtk-update-icon-cache >/dev/null 2>&1 && gtk-update-icon-cache -q -t "$DATA/icons/hicolor" || true
}

if [ "$mode" = uninstall ]; then
  rm -rf "$APP"
  rm -f "$ENTRY" "$ICON" "$COMMAND"
  refresh
  echo "MediaDroyd removed; its settings stay in $DATA/MediaDroyd."
  exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

if [ "$mode" = build ]; then
  [ -x "$HERE/gradlew" ] || { echo "--build needs the MediaDroyd source checkout this script came with" >&2; exit 1; }
  set -- :desktop:createDistributable --no-daemon
  [ "$full" = 1 ] && set -- "$@" -PfullFfmpeg
  (cd "$HERE" && ./gradlew "$@")
  source="$HERE/desktop/build/compose/binaries/main/app/MediaDroyd"
  launcher="bin/MediaDroyd"
  [ -x "$source/$launcher" ] || { echo "the build left no app in $source" >&2; exit 1; }
else
  [ "$full" = 1 ] && { echo "--full applies to --build only: published builds carry the LGPL FFmpeg" >&2; exit 2; }
  command -v curl >/dev/null 2>&1 || { echo "curl is needed to download MediaDroyd" >&2; exit 1; }
  asset="MediaDroyd-linux-x64.AppImage"
  # The newest release carrying the AppImage — stable ones only with --stable.
  curl -fsSL -o "$work/releases.json" "https://api.github.com/repos/$REPO/releases?per_page=30"
  url="$(awk -v want="$asset" -v stable="$stable" '
    /"prerelease":/ { pre = ($0 ~ /true/) }
    /"browser_download_url":/ && index($0, "/" want "\"") {
      if (stable && pre) next
      gsub(/.*"browser_download_url": *"|".*/, ""); print; exit
    }' "$work/releases.json")"
  [ -n "$url" ] || { echo "no release carries $asset yet" >&2; exit 1; }
  echo "Downloading $url"
  curl -fL --progress-bar -o "$work/$asset" "$url"
  chmod +x "$work/$asset"
  # Unpacked, so no FUSE is needed to run it: the AppImage's own files with its AppRun as the launcher.
  (cd "$work" && "./$asset" --appimage-extract >/dev/null)
  source="$work/squashfs-root"
  launcher="AppRun"
fi

rm -rf "$APP"
mkdir -p "$(dirname "$APP")" "$(dirname "$ENTRY")" "$(dirname "$ICON")" "$(dirname "$COMMAND")"
cp -a "$source" "$APP"

if [ -f "$HERE/desktop/icons/mediadroyd.png" ]; then
  cp "$HERE/desktop/icons/mediadroyd.png" "$ICON"
elif [ -f "$APP/mediadroyd.png" ]; then
  cp "$APP/mediadroyd.png" "$ICON"
else
  icon_found="$(find "$APP" -name 'mediadroyd.png' -o -name 'MediaDroyd.png' | head -n 1)"
  [ -n "$icon_found" ] && cp "$icon_found" "$ICON"
fi

ln -sf "$APP/$launcher" "$COMMAND"

cat > "$ENTRY" <<EOF
[Desktop Entry]
Type=Application
Name=MediaDroyd
GenericName=Media player
Comment=Plays the movies and shows already organised in your folders
Exec="$APP/$launcher" %f
TryExec=$APP/$launcher
Icon=$ICON
Terminal=false
Categories=AudioVideo;Video;Player;
MimeType=video/mp4;video/x-matroska;video/x-msvideo;video/mpeg;video/quicktime;video/webm;video/x-ms-wmv;video/x-ms-asf;application/vnd.rn-realmedia;video/x-dv;video/x-flv;video/ogg;video/mp2t;
EOF
refresh

echo "MediaDroyd installed in $APP: in your start menu, and as 'mediadroyd' (~/.local/bin)."
