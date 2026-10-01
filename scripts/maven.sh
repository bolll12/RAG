#!/bin/sh
set -eu
rag_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -x "$rag_root/.tools/jdk/Contents/Home/bin/java" ]; then
  export JAVA_HOME="$rag_root/.tools/jdk/Contents/Home"
elif [ -x "$rag_root/.tools/jdk/bin/java" ]; then
  export JAVA_HOME="$rag_root/.tools/jdk"
fi
if [ -x "$rag_root/.tools/apache-maven-3.9.11/bin/mvn" ]; then
  exec "$rag_root/.tools/apache-maven-3.9.11/bin/mvn" -f "$rag_root/pom.xml" "-Dmaven.repo.local=$rag_root/.tools/m2" "$@"
fi
exec mvn -f "$rag_root/pom.xml" "$@"
