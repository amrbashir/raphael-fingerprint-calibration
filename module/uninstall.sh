#!/system/bin/sh

# KernelSU removes modules before Android's package manager is ready.
(
  cd / || exit 1
  until [ "$(getprop sys.boot_completed)" = 1 ]; do
    sleep 1
  done

  pm uninstall me.amrbashir.raphael.fingerprint.calibration
) </dev/null >/dev/null 2>&1 &
