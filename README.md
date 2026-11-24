# Raphael Fingerprint Calibration

A KernelSU module for calibrating the optical Goodix fingerprint sensor on Redmi K20 Pro / Mi 9T Pro (`raphael` and `raphaelin`). Uses the stock Goodix calibration code from Xiaomi's CIT APK, with sources generated at build time.

## Requirements

- Android 9 or newer.
- KernelSU with an enabled mounting metamodule.
- Flesh-coloured and black calibration flats.
- Compatible display controls: stock `disp_param`, or the supported 2047/4095 backlight and HBM implementations.

Compatibility depends on the installed kernel and display, not the ROM name. If it doesn't work on your device, [open an issue](https://github.com/amrbashir/raphael-fingerprint-calibration/issues/new) with your ROM and kernel versions and the error you encountered.

## Usage

> [!WARNING]
> Turn off adaptive brightness and colour filters before calibration.

1. Install the module ZIP in KernelSU and reboot.
2. In KernelSU's **Superuser** tab, grant **Raphael Fingerprint Calibration** root access.
3. Tap the module's **Action** button and follow the calibration steps.

## License

Original project code is licensed under [MIT](LICENSE). Bundled Xiaomi/Goodix binaries and code derived from them are excluded from this license.
