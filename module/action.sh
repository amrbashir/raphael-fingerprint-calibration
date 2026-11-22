#!/system/bin/sh

MODDIR="${0%/*}"

pm install -r --user 0 "$MODDIR/raphael-fingerprint-calibration.apk" >/dev/null 2>&1 || {
  echo "Could not install the calibration app."
  exit 1
}

am start --user 0 -n me.amrbashir.raphael.fingerprint.calibration/.MainActivity >/dev/null 2>&1 || {
  echo "Could not open the calibration app."
  exit 1
}
