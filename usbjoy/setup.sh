#!/system/bin/sh
# RC2 -> USB HID joystick gadget.  No double quotes anywhere (this file is
# inlined into a Java string literal in UsbGadget.java).
# 关键：先让 Android 自己把 g1 干净摘掉（setprop sys.usb.config none），
# 再去绑定我们的 gadget —— 直接写 g1/UDC 会在 ffs 还持有 fd 时把内核搞崩（实测会重启）。
G=/config/usb_gadget/etxjoy
D=$(dirname $0)
DESC=$D/desc.b64
UDC=a600000.dwc3

setprop sys.usb.config none
sleep 2
# 只在没有 hidg0 时才重建；已有说明已经是我们的 gadget，直接退出
[ -c /dev/hidg0 ] && { echo already; exit 0; }

[ -d $G ] && {
  : > $G/UDC 2>/dev/null
  rm -f $G/configs/c.1/hid.usb0
  rmdir $G/functions/hid.usb0 $G/configs/c.1/strings/0x409 $G/configs/c.1 $G/strings/0x409 $G 2>/dev/null
}

mkdir -p $G || exit 1
echo 0x1209 > $G/idVendor
echo 0x4F54 > $G/idProduct
echo 0x0100 > $G/bcdDevice
echo 0x0200 > $G/bcdUSB
mkdir -p $G/strings/0x409
echo EdgeTX > $G/strings/0x409/manufacturer
echo RC2_Joystick > $G/strings/0x409/product
echo 0001 > $G/strings/0x409/serialnumber
mkdir -p $G/functions/hid.usb0
echo 0 > $G/functions/hid.usb0/protocol
echo 0 > $G/functions/hid.usb0/subclass
echo 10 > $G/functions/hid.usb0/report_length
base64 -d $DESC > $G/functions/hid.usb0/report_desc || exit 2
mkdir -p $G/configs/c.1
echo 500 > $G/configs/c.1/MaxPower
ln -s $G/functions/hid.usb0 $G/configs/c.1/hid.usb0 || exit 3
mkdir -p $G/configs/c.1/strings/0x409
echo hid > $G/configs/c.1/strings/0x409/configuration

echo $UDC > $G/UDC || { echo bind_failed; exit 4; }
sleep 1
chmod 666 /dev/hidg0 2>/dev/null

ST=$(cat /sys/class/udc/$UDC/state 2>/dev/null)
echo state=$ST
[ $ST = configured ] || { echo not_configured_restoring; sh $D/restore.sh; exit 5; }
ls -l /dev/hidg0
