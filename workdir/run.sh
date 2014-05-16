#!/bin/bash

provisioner --scale 3

coordinator     --workerVmOptions "-server -Xms1800m -Xmx3584m -Djava.awt.headless=true -XX:+HeapDumpOnOutOfMemoryError -XX:+PrintGCDateStamps -Xloggc:gc.log -verboseg -XX:+PrintGCDetails -XX:+PrintTenuringDistribution -XX:+UseParallelGC -XX:+UseParallelOldGC -XX:MaxPermSize=300m" \
                --clientHzFile      ../conf/client-hazelcast.xml \
                --hzFile            ../conf/hazelcast.xml \
                --clientWorkerCount 0 \
                --memberWorkerCount 3 \
                --workerClassPath   '../target/*.jar' \
                --duration          5m \
                ../conf/test.properties

provisioner --download

provisioner --terminate