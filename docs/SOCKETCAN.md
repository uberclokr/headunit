# Raw CAN on the Edge2 IO hat (SocketCAN)

The ELM327 path covers standard PIDs. Raw bus access adds broadcast frames
(gear position, wheel speeds, steering angle, brake switch) at full rate.

## Hardware
- MCP2515 + TJA1050/SN65HVD230 SPI CAN board on the IO hat SPI pins, or an
  RK3588 CAN controller pin mux if your hat breaks those out.
- Tap HS-CAN at the OBD connector: pin 6 (CAN-H), pin 14 (CAN-L), 500 kbit.
- Keep the tap stub short; the bus is already terminated at both ends —
  do NOT add a 120R terminator on the tap.

## Kernel / device tree
Khadas Android kernels need CONFIG_CAN, CONFIG_CAN_RAW, CONFIG_CAN_MCP251X
and a DT overlay declaring the SPI child with its INT GPIO. Rebuild boot.img
or use the Khadas overlay mechanism (Fenix docs cover the RK3588 DT flow).

## Bring-up (root)
```
ip link set can0 type can bitrate 500000
ip link set can0 up
candump can0            # sanity check
```

## JNI stub (native/socketcan.c)
```c
#include <linux/can.h>
#include <linux/can/raw.h>
#include <sys/socket.h>
#include <net/if.h>
#include <sys/ioctl.h>
#include <string.h>
#include <unistd.h>
#include <jni.h>

JNIEXPORT jint JNICALL
Java_com_xterra_helm_can_SocketCanManager_nativeOpen(JNIEnv *e, jobject o, jstring ifn) {
    int s = socket(PF_CAN, SOCK_RAW, CAN_RAW);
    struct ifreq ifr; const char *n = (*e)->GetStringUTFChars(e, ifn, 0);
    strncpy(ifr.ifr_name, n, IFNAMSIZ);
    ioctl(s, SIOCGIFINDEX, &ifr);
    struct sockaddr_can addr = { .can_family = AF_CAN, .can_ifindex = ifr.ifr_ifindex };
    bind(s, (struct sockaddr *)&addr, sizeof(addr));
    (*e)->ReleaseStringUTFChars(e, ifn, n);
    return s;
}

JNIEXPORT jint JNICALL
Java_com_xterra_helm_can_SocketCanManager_nativeRead(JNIEnv *e, jobject o, jint fd, jbyteArray out) {
    struct can_frame fr;
    int n = read(fd, &fr, sizeof(fr));
    if (n <= 0) return -1;
    jbyte buf[12];
    memcpy(buf, &fr.can_id, 4);
    memcpy(buf + 4, fr.data, 8);
    (*e)->SetByteArrayRegion(e, out, 0, 12, buf);
    return fr.can_dlc;
}

JNIEXPORT void JNICALL
Java_com_xterra_helm_can_SocketCanManager_nativeClose(JNIEnv *e, jobject o, jint fd) { close(fd); }
```
Add an `externalNativeBuild { cmake { ... } }` block and a 6-line
CMakeLists to build `libhelmsocketcan.so`. SELinux on user builds blocks
AF_CAN for apps — run permissive or add a sepolicy rule (you control the
image, so this is a one-time change).

## Reverse-engineering gear position
Run the SnifferWidget diff view, shift R↔N repeatedly, and watch for the ID
whose byte flips in sync. On Nissan D40-platform trucks the gear/PRNDL
status has been reported in the 0x421/0x5xx range but varies by year and
transmission — verify on your truck, then feed it into VehicleState.reverse
as a second (faster) source alongside the GPIO tap.
