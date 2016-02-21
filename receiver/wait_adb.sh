#!/bin/sh

killall cs_receiver
./cs_receiver autovideosink &

while [ true ];
do
  echo "wait adb device"
  adb wait-for-device
  echo "adb device presents, reverse port"
  adb reverse tcp:53515 tcp:53515
  while [ true ];
  do
    if [ "`adb get-state`" = "device" ]; then
      echo "got dev"
      sleep 3
    else
      break
    fi
  done
done
