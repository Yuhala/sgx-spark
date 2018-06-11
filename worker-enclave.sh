#!/bin/bash

source variables.sh

export SGXLKL_TAP=tap5

export IS_ENCLAVE=true
export IS_DRIVER=false
export IS_WORKER=true

export SGXLKL_SHMEM_FILE=sgx-lkl-shmem
export SGXLKL_IP4=10.0.5.1
export SGXLKL_GW4=10.0.5.254

# -Dcom.sun.management.jmxremote \
# -Djava.rmi.server.hostname=${SGXLKL_IP4} \
# -Dcom.sun.management.jmxremote.port=5000 \
# -Dcom.sun.management.jmxremote.authenticate=false \
# -Dcom.sun.management.jmxremote.ssl=false \
# -Xdebug -Xrunjdwp:transport=dt_socket,address=8000,server=y,suspend=n \
#echo gdb --args \
../sgx-lkl-sim/sgx-musl-lkl/obj/sgx-lkl-starter /opt/j2re-image/bin/java \
-XX:InitialCodeCacheSize=${JVM_INITIAL_CODE_CACHE_SIZE} \
-XX:ReservedCodeCacheSize=${JVM_RESERVED_CODE_CACHE_SIZE} \
-Xms${JVM_XMS} \
-Xmx${JVM_XMX} \
-XX:CompressedClassSpaceSize=${JVM_COMPRESSED_CLASS_SPACE_SIZE} \
-XX:MaxMetaspaceSize=${JVM_MAX_METASPACE_SIZE} \
-XX:+UseCompressedClassPointers \
-XX:+PreserveFramePointer \
-XX:+UseMembar \
-XX:+AssumeMP \
-Xint \
-Djava.library.path=/spark/lib/ \
-cp \
/home/scala-library/:/spark/conf/:/spark/assembly/target/scala-${SCALA_VERSION}/jars/\*:/spark/examples/target/scala-${SCALA_VERSION}/jars/* \
org.apache.spark.sgx.SgxMain 2>&1 | tee enclave-worker
