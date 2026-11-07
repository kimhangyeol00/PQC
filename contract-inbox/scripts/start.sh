#!/bin/sh
set -eu
umask 077
exec java -jar /workspace/application.jar
