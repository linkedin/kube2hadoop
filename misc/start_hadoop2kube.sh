#!/bin/bash

CONF_FILE=kube2hadoop/conf/kube2hadoop.xml
CONF_PATH=$(dirname $CONF_FILE)
JAVA_HOME=${JAVA_HOME:-jdk/JDK-1_8_0_172}
JAR_PATH=kube2hadoop/token-fetcher.jar
LOG4J=kube2hadoop/log4j.properties

[ ! -f $CONF_FILE ] && echo "ERROR: Unable to find '$CONF_FILE'" && exit 2

echo "$(date +'%F %T') Starting kube2hadoop..."
java -cp "$JAR_PATH:$LOG4J:$CONF_PATH" com.linkedin.kube2hadoop.TokenServer -conf_file $CONF_FILE
