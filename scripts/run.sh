#!/bin/sh
set -eu
rag_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$rag_root"
if [ -x "$rag_root/.tools/jdk/Contents/Home/bin/java" ]; then
  rag_java="$rag_root/.tools/jdk/Contents/Home/bin/java"
elif [ -x "$rag_root/.tools/jdk/bin/java" ]; then
  rag_java="$rag_root/.tools/jdk/bin/java"
else
  rag_java=java
fi
exec "$rag_java" -jar "$rag_root/target/rag-service-1.0.0.jar" "$@"
