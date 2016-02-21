#!/bin/sh

killall cs_receiver_arm
./cs_receiver_arm mfw_v4lsink &

while [ true ];
do
  echo "wait adb device"
  ./adb wait-for-device
  echo "adb device presents, reverse port"
  ./adb reverse tcp:53515 tcp:53515
  ./adb reverse tcp:2222 tcp:22
  while [ true ];
  do
    if [ "`./adb get-state`" = "device" ]; then
      sleep 3
    else
      break
    fi
  done
done
