#!/bin/bash

java -Xms512m -Xmx2G -server -jar ./vendor/local-runner/local-runner.jar \
  ./local-runner-no-render.properties &
java -cp ./out/strategy.jar Runner
