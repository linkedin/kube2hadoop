#!/bin/sh

# Sleep for 15 seconds to wait for IP address of pod to be assigned by API server
if [[ -z "${SKIP_SLEEP}" ]]; then
  echo "Sleeping for 15 seconds"
  sleep 15
fi

retry=0
RETRY=10
SLEEP_INT=3
while [ $retry -lt $RETRY ] ; do
    hostname -i | grep -E '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' -q
    if [ $? -eq 0 ] ; then
        echo "INFO: IP $(hostname -i)"
        break
    else
        echo "Warning: no IP yet, retry ($retry)in $SLEEP_INT seconds"
        retry=$(( retry + 1 ))
        [ $retry -eq $RETRY ] && echo "Max retries reaches, abort....." && exit 2
        sleep $SLEEP_INT
    fi
done

KUBE2HADOOP_URI=${KUBE2HADOOP_URI:-'http://kube2hadoop-svc.kube-system:9966/getDelegationToken'}
CLUSTERNAME=${CLUSTERNAME:-'some-cluster'}
POD_NAME=${POD_NAME:-"$HOSTNAME"}
TMPRES=/tmp/result
HADOOP_TOKEN_FILE_LOCATION=${HADOOP_TOKEN_FILE_LOCATION:-/var/tmp/hdfs-delegation-token}

if [ -z $K8SNAMESPACE ] ; then
    echo "ERROR Missing K8s namespace" && exit 2
fi

if [ ! -z $DEBUG  ] ; then
    while [ 1 ]; do
        sleep 60
    done
fi

echo "INFO: K8s namespace - '$K8SNAMESPACE'"
echo "INFO: kube2hadoop URI - '$KUBE2HADOOP_URI'"
echo "INFO: Pod name - '$POD_NAME'"
echo "INFO: Cluster name - '$CLUSTERNAME'"

curl -X GET -G $KUBE2HADOOP_URI -d pod-name=$POD_NAME -d token-kinds=HDFS_DELEGATION_TOKEN -d namespace=$K8SNAMESPACE -d cluster-name=$CLUSTERNAME > $TMPRES 2>/dev/null

grep -q 'Token' $TMPRES

if [ $? -ne 0 ] ; then
   cat $TMPRES && exit 2
fi

cat $TMPRES | jq '.Token' | sed -e 's/"//g' | base64 -d > $HADOOP_TOKEN_FILE_LOCATION
if [ $? -ne 0 ] ; then
   echo "ERROR: Failed to extract delegation token" && exit 2
fi

exit 0
