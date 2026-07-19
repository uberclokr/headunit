# Thermal camera pane (USB UVC)

Most colorized-output thermal cores enumerate as plain UVC webcams
(InfiRay P2 Pro/T2, Topdon TC001, Hikmicro minis, FLIR Boson on a USB
carrier). The `libausbc` dependency already in the build renders those.

Minimal wiring:

```kotlin
class ThermalFragment : CameraFragment() {
    override fun getCameraView(): IAspectRatio = AspectRatioTextureView(requireContext())
    override fun getCameraViewContainer(): ViewGroup = binding.container
    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View =
        FragmentThermalBinding.inflate(inflater, container, false).also { binding = it }.root
    override fun getDefaultCamera(): UsbDevice? =
        deviceList.firstOrNull { it.vendorId == THERMAL_VID } // filter out OBD/SDR dongles
}
```

Host it from `ThermalView` with `AndroidView { FragmentContainerView(...) }`
or convert the pane to a plain `AndroidView` holding the fragment's
`AspectRatioTextureView`.

Notes:
- InfiRay P2-class cores output 256x384: top half is colorized video,
  bottom half is raw 16-bit. Crop to the top half, or parse the raw field
  for spot temperature readout (format documented in the P2 Pro community
  repos).
- Set the UVC negotiation to YUYV if MJPEG frames arrive corrupted.
- If both OBD serial and thermal share the USB hub, request permission per
  device VID/PID so grants persist.
