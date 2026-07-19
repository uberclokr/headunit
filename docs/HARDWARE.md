# HARDWARE.md — bench + install checklist

## Bill of connections
| Link | Transport | Endpoint / config location |
|---|---|---|
| OBD-II | USB ELM327 (CH340/FTDI/CP2102) | auto-detected; `can/Elm327Manager.kt` |
| Reverse lamp | 12 V → PC817 opto → hat GPIO | pin # in `can/GpioReverseSensor.kt` |
| Raw CAN (later) | MCP2515/FlexCAN on hat, OBD pins 6/14 | `docs/SOCKETCAN.md` |
| Viofo A329 front/rear/cabin | RTSP over vehicle LAN | URLs in `cameras/CameraRegistry.kt` |
| Drone FPV | RTSP (mavlink-router / vtx bridge) | same registry, id `drone` |
| Thermal | USB UVC | VID/PID filter, `docs/THERMAL.md` |
| RTL-SDR | USB → rtl_tcp on 127.0.0.1:1234 | `sdr/RtlTcpClient.kt` |
| Renogy monitor | BLE (Modbus over GATT) | MAC/kind in `power/BatteryRepository.kt` |
| Starlink (later) | gRPC 192.168.100.1:9200 | roadmap §9 |

## One-time device commands (root shell / adb)
```bash
settings put global enable_freeform_support 1
settings put global force_resizable_activities 1
echo 113 > /sys/class/gpio/export            # your pin number
echo in  > /sys/class/gpio/gpio113/direction
chmod 644 /sys/class/gpio/gpio113/value
# optional: keep screen brightness sane at low house SOC
settings put global low_power_trigger_level 0
```

## Reverse-lamp opto circuit
```
reverse lamp +12V ──[2.2kΩ]──▶ PC817 pin1 (LED+)
lamp ground       ───────────▶ PC817 pin2 (LED−)
hat 3V3 ──[10kΩ]──┬──────────▶ GPIO (input)
                  └──▶ PC817 pin4 (collector)
hat GND ─────────────▶ PC817 pin3 (emitter)
```
GPIO reads LOW when reverse is lit with this arrangement → set
`ACTIVE_LEVEL = "0"` in GpioReverseSensor, or swap collector/emitter
orientation for active-high. Never bring raw 12 V onto the hat.

## Verification order (bench, before install)
1. `adb logcat -s Helm` clean boot, VehicleService notification present.
2. ELM dongle on a bench ECU sim (or in the truck, key-on): RPM/speed live.
3. `ffprobe rtsp://<cam-ip>/live` — confirm each Viofo path, fix registry.
4. nRF Connect → Renogy device: confirm MAC; watch FFF1 notifications while
   the Renogy app polls once if registers need confirming.
5. Short GPIO to ground/3V3 manually → REVERSE flag + overlay appears.
6. SDR Driver app running → SDR pane connects, strong local FM station.
7. `dumpsys battery` shows injected level after first battery poll.
