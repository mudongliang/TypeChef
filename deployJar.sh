#!/bin/bash
 
#java -Xmx512M -Xss10m -jar sbt-launch.jar clean

java -Xmx512M -Xss10m -jar sbt-launch.jar update compile

sbt assembly

cp TypeChef*.jar ../
