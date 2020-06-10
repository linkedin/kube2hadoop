/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop.authenticator;

import com.linkedin.kube2hadoop.core.Constants;
import com.linkedin.kube2hadoop.core.ErrorCode;
import com.linkedin.kube2hadoop.core.TokenServiceException;
import com.linkedin.kube2hadoop.core.conf.ConfigurationKeys;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.Watch;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;

import static com.linkedin.kube2hadoop.core.Constants.*;


/**
 * Authentication on Kubernetes:
 * Jobs launched on Kubernetes will be annotated with userID to indicate who launched it. This is done via a mutating
 * admission webhook on the server side. Only privileged user can attach the webhook, and since the user is already
 * authenticated via grestin cert to launch the job, the userID annotated by the webhook is also authenticated.
 * This Authenticator thus retrieve the latest metadata about a given pod either from the {@code watchCache} or a live
 * query to Kubernetes API server. If the IP address from the Kubernetes metadata matches the caller's metadata, the
 * user is considered authenticated.
 */
public class KubernetesAuthenticator implements Authenticator {
  private static final Log LOG = LogFactory.getLog(KubernetesAuthenticator.class);
  private Configuration conf;
  private CoreV1Api api;
  // Map of namespace -> (pod name -> (podIP, annotations))
  private Map<String, Map<String, Pair<String, Map<String, String>>>> watchCache = new ConcurrentHashMap<>();
  private Map<String, KubernetesNamespaceWatch> namespaceWatchCache = new ConcurrentHashMap<>();
  private List<String> blackListedSuperUsers;

  /**
   * First checks {@code watchCache}, if given pod name does not exist in {@code watchCache} (which could mean that the
   * watch haven't been updated or that the watch connection have broken), try to directly query api server with the
   * given namespace and pod name.
   * @param params Parameters passed to authenticate
   * @return authenticated user's ID
   */
  @Override
  public String getAuthenticatedUserID(AuthenticatorParameters params) {
    String namespace = params.getParamString(Constants.NAMESPACE);
    String podName = params.getParamString(Constants.POD_NAME);
    String srcIP = params.getParamString(Constants.SRCIP);

    Map<String, Pair<String, Map<String, String>>> namespaceCache = watchCache.get(namespace);
    if (namespaceCache == null) {
      namespaceCache = new ConcurrentHashMap<>();
      watchCache.put(namespace, namespaceCache);
    }
    Pair<String, Map<String, String>> podMetadata = namespaceCache.get(podName);
    if (podMetadata == null) {
      // Query api server for namespace, podName
      try {
        podMetadata = getPodInfoByNamespaceAndPodName(namespace, podName);
      } catch (ApiException | NullPointerException ex) {
        throw new TokenServiceException(
            ex.getMessage(), ErrorCode.KUBERNETES_POD_CALL_EXCEPTION
        );
      }
    }

    if (podMetadata.getValue() == null || podMetadata.getValue().get(Constants.KUBERNETES_USER_ID) == null) {
      throw new TokenServiceException(
        ErrorCode.KUBERNETES_NO_USERID_EXCEPTION.getDescription(), ErrorCode.KUBERNETES_NO_USERID_EXCEPTION
      );
    }

    if (podMetadata.getKey() == null) {
      throw new TokenServiceException(
          "Cannot find pod: " + podName + " in namespace " + namespace, ErrorCode.KUBERNETES_POD_NOT_FOUND
      );
    }

    // Authenticate via ip address
    if (!podMetadata.getKey().equals(srcIP)) {
      throw new TokenServiceException(
          "Failed to authenticate pod: " + podName + " where srcIP is: " + srcIP
              + " actual podIP: " + podMetadata.getKey(),
          ErrorCode.KUBERNETES_AUTHENTICATION_EXCEPTION);
    }

    // Add annotations to params for AuthenticatorDecorators
    params.addAllToParams(podMetadata.getValue());

    return checkAgainstBlacklist(podMetadata.getValue().get(KUBERNETES_USER_ID));
  }

  @Override
  public Configuration getConfiguration() {
    return conf;
  }

  KubernetesAuthenticator(Configuration conf) {
    this.conf = conf;
    String k8sHost = conf.getRaw(ConfigurationKeys.KUBERNETES_SERVICE_HOST);
    String k8sPort = conf.getRaw(ConfigurationKeys.KUBERNETES_SERVICE_PORT);
    if (k8sHost == null || k8sPort == null) {
      k8sHost = System.getenv(Constants.KUBERNETES_SERVICE_HOST);
      k8sPort = System.getenv(Constants.KUBERNETES_SERVICE_PORT);
    }
    String k8sUrl = Constants.HTTPS_PREFIX + k8sHost + ":" + k8sPort;
    LOG.info("Using k8sUrl: " + k8sUrl);

    String tokenFileLocation = conf.get(ConfigurationKeys.KUBE2HADOOP_TOKEN_FILE_LOCATION);
    String certFileLocation = conf.get(ConfigurationKeys.KUBE2HADOOP_CERT_LOCATION);
    blackListedSuperUsers = getBlackListedSuperUsers(conf);

    ApiClient k8sClient = Config.fromToken(k8sUrl, getTokenString(tokenFileLocation));
    k8sClient.setSslCaCert(getCertInputStream(certFileLocation));
    k8sClient.getHttpClient().setReadTimeout(0, TimeUnit.SECONDS); // infinite timeout
    io.kubernetes.client.Configuration.setDefaultApiClient(k8sClient);

    api = new CoreV1Api();

    runWatchThread();
  }

  /**
   * Read a comma-separated list of blacklisted users from conf and return in List format.
   * @return list of blacklisted users or empty list if conf not set
   */
  List<String> getBlackListedSuperUsers(Configuration conf) {
    return Arrays.asList(
        conf.get(ConfigurationKeys.KUBE2HADOOP_AUTHENTICATOR_BLACKLISTED_USERS, "")
            .split("\\s*,\\s*"));
  }

  /**
   * Run {@code KubernetesNamespaceLabelWatch}, capture any exceptions thrown and fail execution.
   */
  private void runWatchThread() {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executor.submit(new KubernetesNamespaceLabelWatch(this, conf));
  }

  /**
   * Make call to Kubernetes API server to get pod information by namespace and pod name
   * @param namespace the namespace of the pod provided by the caller
   * @param podName the pod name provided by the caller
   * @return pod ip address and annotation pair
   * @throws ApiException exception thrown by the Kubernetes client
   * @throws NullPointerException exception thrown when the metadata from api server does not contain required
   *          annotation, or that the pod does not exist.
   */
  Pair<String, Map<String, String>> getPodInfoByNamespaceAndPodName(String namespace, String podName)
      throws ApiException, NullPointerException {
    V1Pod podInfo = api.readNamespacedPodWithHttpInfo(podName, namespace, null, null, null)
        .getData();

    return new ImmutablePair<>(
        podInfo.getStatus().getPodIP(), podInfo.getMetadata().getAnnotations());
  }

  String getTokenString(String tokenFileLocation) {
    String token;
    try (InputStream is = new FileInputStream(tokenFileLocation);
        BufferedReader buf = new BufferedReader(new InputStreamReader(is))) {
      token = buf.readLine();
    } catch (IOException ex) {
      throw new TokenServiceException(ex.getMessage(), ErrorCode.BEARER_TOKEN_EXCEPTION);
    }
    return token;
  }

  InputStream getCertInputStream(String certFileLocation) {
    InputStream retStream;
    final File certFile = new File(certFileLocation);
    try {
      retStream = new DataInputStream(new FileInputStream(certFile));
    } catch (IOException ex) {
      throw new TokenServiceException(ex.getMessage(), ErrorCode.SSL_CERT_EXCEPTION);
    }

    return retStream;
  }

  /**
   * This is to limit the exposure to data leak in the scenario when a Kubernetes admin
   * account has been compromised. A Kubernetes admin impersonating an HDFS superuser can
   * get access to data belonging to multiple HDFS accounts. Blacklisting superusers from
   * using Kube2Hadoop forces an attacker to impersonate user accounts one at a time
   * (and leaving audit trails of requesting delegation tokens for those users).
   *
   * This function checks whether the user belongs to the blacklisted group.
   * @param userName user name to impersonate
   * @return userName if the name does not belong the blacklisted group.
   * @throws TokenServiceException if user trying to impersonate a blacklisted group
   */
  String checkAgainstBlacklist(String userName) throws TokenServiceException {
    if (blackListedSuperUsers != null && blackListedSuperUsers.contains(userName)) {
      throw new TokenServiceException("Username: " + userName + " is blacklisted from fetching delegation token.",
          ErrorCode.KUBERNETES_AUTHENTICATION_BLACKLIST_EXCEPTION);
    }
    return userName;
  }

  void updateWatchCache(Watch.Response<V1Pod> item) {
    String podName = item.object.getMetadata().getName();
    String namespace = item.object.getMetadata().getNamespace();
    String podIP = item.object.getStatus().getPodIP();
    Map<String, String> annotations = item.object.getMetadata().getAnnotations();

    String userID = null;
    if (annotations != null) {
      userID = annotations.get(KUBERNETES_USER_ID);
    }

    if (userID != null) {
      Map<String, Pair<String, Map<String, String>>> namespaceCache = getWatchCache().get(namespace);
      if (namespaceCache == null) {
        namespaceCache = new ConcurrentHashMap<>();
      }
      Pair<String, Map<String, String>> podMetadata = namespaceCache.get(podName);
      if (podMetadata == null) {
        LOG.info("Adding pod: " + podName + " from namespace: " + namespace
            + " with podIP=" + podIP + ", userID=" + userID + " into watch cache");
      } else {
        LOG.info("Updating watch cache at pod: " + podName + " from namespace: " + namespace
            + " from podIP=" + podMetadata.getKey() + ", userID=" + podMetadata.getValue().get(KUBERNETES_USER_ID)
            + " to podIP=" + podIP + ", userID=" + userID);
      }
      podMetadata = new ImmutablePair<>(podIP, annotations);
      namespaceCache.put(podName, podMetadata);
      getWatchCache().put(namespace, namespaceCache);
    } else {
      LOG.debug("Skipping pod updates with no userID annotation");
    }
  }

  Map<String, Map<String, Pair<String, Map<String, String>>>> getWatchCache() {
    return watchCache;
  }

  void setWatchCache(Map<String, Map<String, Pair<String, Map<String, String>>>> watchCacheToSet) {
    watchCache = watchCacheToSet;
  }

  Map<String, KubernetesNamespaceWatch> getNamespaceWatchCache() {
    return namespaceWatchCache;
  }
}
