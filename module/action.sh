#!/system/bin/sh

am start --user 0 -n me.amrbashir.raphael.fingerprint.calibration/.MainActivity >/dev/null 2>&1 || {
  echo "Could not open the calibration app."
  exit 1
}
