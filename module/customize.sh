#!/system/bin/sh

case "$(getprop ro.product.device)" in
  raphael|raphaelin) ;;
  *) abort "This calibration module requires raphael or raphaelin." ;;
esac

interface=/vendor/lib64/vendor.goodix.hardware.biometrics.fingerprint@2.1.so

mount_provider=false

for provider in /data/adb/modules/*; do
  [ -d "$provider" ] || continue
  [ -e "$provider/disable" ] && continue
  [ -e "$provider/remove" ] && continue

  if grep -qE '^metamodule=(1|true)$' "$provider/module.prop" 2>/dev/null; then
    mount_provider=true
    break
  fi
done

[ "$mount_provider" = true ] || abort "An enabled KernelSU mounting metamodule is required. Install a compatible mount provider, reboot, then retry."

file_context="$(ls -Zd "$interface" | awk '{print $1}')"

case "$file_context" in
  u:object_r:*:s0) ;;
  *) abort "Could not determine the current vendor library SELinux label." ;;
esac

ui_print "Reboot, then tap Action to open fingerprint calibration."

set_perm_recursive "$MODPATH/system" 0 0 0755 0644
set_perm "$MODPATH/system/vendor/lib64/vendor.goodix.hardware.biometrics.fingerprint@2.1.so" 0 0 0644 "$file_context"
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/raphael-fingerprint-calibration.apk" 0 0 0644
