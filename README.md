# Kube2Hadoop

Use of Kubernetes have flourished for offline AI workload. Offline training jobs on Kubernetes, such as Tensorflow or Spark jobs need secure access to datalake like HDFS However, there exists a gap between the security model of Kubernetes and Hadoop. Kube2Hadoop bridges this gap by providing a scalable and secure integration of Kubernetes and HDFS Kerberos. Kube2Hadoop consists of three main components: 
1. Hadoop Token Fetcher for fetching delegation tokens, deployed as Kubernetes Deployment.
2. IDDecorator for writing authenticated user id, deployed as Kubernetes Admission Controller.
3. Kube2Hadoop Init Container in each worker pod as client for sending request to fetch delegation token from Hadoop Token Service.

For more details on how Kube2Hadoop works internally, and its authentication mechanism, please read Kube2Hadoop blogpost 

## Build and deploy
The Hadoop Token Fetcher is built using Gradle. To build it, run:

`./gradlew build`.

The resulting jar will be located in `./token-fetcher/build/libs/`.

You can find sample Kubernetes Deployment yaml files under `./token-fetcher/resources/`; and Kubernetes related services
under `./core/src/resources`.

Visit this [page](https://github.com/linkedin/kube2hadoop/tree/master/iddecorator) on instructions to deploy IDDecorator 

## Usage
Once the iddecorator and token-fetcher services are deployed on your Kubernetes cluster, you
should be able to use Kube2Hadoop by adding an init container that launches the following command:
`./misc/fetch_delegation_token`. The fetched token should be placed under $HADOOP_TOKEN_FILE_LOCATION. 
Sample init-container config:
```
initContainers:
          - name: tokenfetcher
            image: <init-container-image-path>
            imagePullPolicy: IfNotPresent
            env:
            - name: K8SNAMESPACE
              valueFrom:
                fieldRef:
                  fieldPath: metadata.namespace
            volumeMounts:
            - name: shared-data
              mountPath: "/var/tmp"
```
Be sure to add an empty VolumeMount in the pod for storing the delegation token:
```
Volumes:
    - name: shared-data
      emptyDir:
        sizeLimit: "10Mi"
```
Finally, set up an environment variable in your container to reference the delegation token and reference the Volume in the container to access the token written by the init container:
```
containers:
    - name: <my-main-container>
      image: <my-image-url>
      ...
      env:
      - name: HADOOP_TOKEN_FILE_LOCATION
        value: "/var/tmp/hdfs-delegation-token"
      ...
      volumeMounts:
      - name: shared-data
        mountPath: "/var/tmp"
      ...
```
