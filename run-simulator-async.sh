#!/bin/bash

pkill -f local-runner

# For some reason, it only works in IntelliJ IDEA when there is a pipe.
make run-simulator >/dev/null &
