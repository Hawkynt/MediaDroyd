#!/bin/sh
# Makes the expected files of CubeLutTest: FFmpeg's lut3d (tetrahedral and trilinear) and lut1d (linear) on
# picture.rgb, rgb24 in and out — the oracle the table's reading is held to. Needs ffmpeg (made with n9.1).
set -e
here=$(cd "$(dirname "$0")" && pwd)
python3 -I "$here/make-input.py" "$here"
for interp in tetrahedral trilinear; do
  ffmpeg -v error -y -f rawvideo -pix_fmt rgb24 -s 16x16 -i "$here/picture.rgb" \
    -vf "lut3d=file=$here/look.cube:interp=$interp" -f rawvideo -pix_fmt rgb24 "$here/look-$interp.rgb"
done
ffmpeg -v error -y -f rawvideo -pix_fmt rgb24 -s 16x16 -i "$here/picture.rgb" \
  -vf "lut1d=file=$here/curve.cube:interp=linear" -f rawvideo -pix_fmt rgb24 "$here/curve-linear.rgb"
