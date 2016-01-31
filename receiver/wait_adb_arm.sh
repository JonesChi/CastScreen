#!/bin/sh

while [ true ];
do
  echo "wait adb device"
  ./adb wait-for-device
  echo "adb device presents, try to send mirror cmd"
  ./adb forward tcp:53516 tcp:53515
  ./cs_receiver_conn_arm mfw_v4lsink
  echo "connect failed, sleep 3 seconds"
  sleep 3
done
