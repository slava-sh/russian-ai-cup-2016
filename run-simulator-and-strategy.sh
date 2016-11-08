#!/bin/bash

make run-simulator &
simulator_pid=$!
while ps -p $simulator_pid >/dev/null; do
  sleep 3
  make run-strategy
done
