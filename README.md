# Raphael Fingerprint Calibration

A KernelSU module for calibrating the optical Goodix fingerprint sensor on Redmi K20 Pro / Mi 9T Pro (`raphael` and `raphaelin`). Uses the stock Goodix calibration code from Xiaomi's CIT APK, with sources generated at build time.

## Installation

You'll need Android 9 or newer and flesh-coloured and black calibration flats.

> [!WARNING]
> Turn off adaptive brightness and colour filters before calibration.

1. Install and enable a KernelSU mounting metamodule, such as [meta-overlayfs](https://github.com/KernelSU-Modules-Repo/meta-overlayfs/releases/latest) or [Meta Magic Mount RS](https://github.com/Tools-cx-app/meta-magic_mount-rs/releases/latest), then reboot.
2. Install this module's ZIP in KernelSU and reboot.
3. In KernelSU's **Superuser** tab, grant **Raphael Fingerprint Calibration** root access.
4. Tap the module's **Action** button and follow the calibration steps.

> [!NOTE]
> Tested on LineageOS 24 with the `Soviet-Zundamon-v1.1` kernel. Supported kernels must expose stock `disp_param` controls or a compatible 2047/4095 backlight and HBM implementation. Compatibility depends on the installed kernel and display. If it doesn't work on your device, [open an issue](https://github.com/amrbashir/raphael-fingerprint-calibration/issues/new) with your ROM and kernel versions and the error you encountered.

## License

Licensed under [MIT](LICENSE). Bundled Xiaomi/Goodix binaries and code derived from them are excluded from this license.
