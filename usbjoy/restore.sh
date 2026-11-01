#!/system/bin/sh
G=/config/usb_gadget/etxjoy
UDC=a600000.dwc3
[ -d $G ] && {
  : > $G/UDC 2>/dev/null
  rm -f $G/configs/c.1/hid.usb0
  rmdir $G/functions/hid.usb0 $G/configs/c.1/strings/0x409 $G/configs/c.1 $G/strings/0x409 $G 2>/dev/null
  sleep 1
}
setprop sys.usb.config mtp,adb
sleep 2
echo state=$(cat /sys/class/udc/$UDC/state 2>/dev/null)
