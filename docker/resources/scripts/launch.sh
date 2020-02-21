#!/bin/bash


set -e

#freeMem=`awk '/MemFree/ { print int($2/1024) }' /proc/meminfo`
#s=$(($freeMem/10*8))
#x=$(($freeMem/10*8))
#n=$(($freeMem/10*2))
export JVM_ARGS="-Xms1024m -Xmx4024m"

echo "START Running Jmeter on `date`"
echo "JVM_ARGS=${JVM_ARGS}"

# Keep entrypoint simple: we must pass the standard JMeter arguments

if [[ "${JMETER_MODE}" == "MASTER" ]]; then
    echo "starting JMeter in Master mode"
    #sleep ${SLEEP}
    exec "$@"
else
    echo "starting Jmeter in Agent mode"
    sleep ${SLEEP}
    exec jmeter-server "$@"
fi