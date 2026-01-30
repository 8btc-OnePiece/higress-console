#!/bin/sh
echo $1
if [ "$1" = "--local" ]
then
    java $JVM_ARGS -Dspring.profiles.active=$SPRING_PROFILES_ACTIVE -jar ./console/target/higress-console.jar
else
    java $JVM_ARGS -Dspring.profiles.active=$SPRING_PROFILES_ACTIVE -jar /app/higress-console.jar
fi