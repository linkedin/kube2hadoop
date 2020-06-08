/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop.authenticator;

import com.google.gson.reflect.TypeToken;
import com.linkedin.kube2hadoop.core.ErrorCode;
import com.linkedin.kube2hadoop.core.TokenServiceException;
import com.linkedin.kube2hadoop.core.conf.ConfigurationKeys;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.util.Watch;
import java.io.Closeable;
import java.io.IOException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;

/**
 * Watch any pod changes (pod creation, deletion etc.) in the given {@code namespace}
 */
public class KubernetesNamespaceWatch implements Runnable, Closeable {
  private static final Log LOG = LogFactory.getLog(KubernetesNamespaceWatch.class);
  private KubernetesAuthenticator authenticator;
  private String namespace;
  private Configuration conf;
  private Watch<V1Pod> podWatch;
  private CoreV1Api api = new CoreV1Api();

  KubernetesNamespaceWatch(KubernetesAuthenticator authenticator, String namespace, Configuration conf) {
    this.authenticator = authenticator;
    this.namespace = namespace;
    this.conf = conf;
  }

  @Override
  public void run() {
    try {
      podWatch = setWatchForNamespace(namespace);
    } catch (ApiException | IOException ex) {
      throw new TokenServiceException(ex.getMessage(), ErrorCode.KUBERNETES_WATCH_EXCEPTION);
    }
  }

  /**
   * Setup watch call to Kubernetes API Server.
   * To avoid watching the entire cluster's update, use {@code labelSelector} to filter out jobs that are outside of
   * the scope.
   * @throws ApiException Kubernetes client API exception
   * @throws IOException throws IOException when failed to close watch
   */
  private Watch<V1Pod> setWatchForNamespace(String namespace) throws ApiException, IOException {
    String labelSelector = conf.get(ConfigurationKeys.KUBE2HADOOP_WATCH_LABEL_SELECTOR);

    Watch<V1Pod> watch = Watch.createWatch(
        io.kubernetes.client.Configuration.getDefaultApiClient(),
        api.listNamespacedPodCall(namespace, null, null, null, null,
            labelSelector, null, null, null, Boolean.TRUE,
            null, null),
        new TypeToken<Watch.Response<V1Pod>>() { }.getType());

    try {
      for (Watch.Response<V1Pod> item : watch) {
        if (item.type.equalsIgnoreCase("ADDED") || item.type.equalsIgnoreCase("MODIFIED")) {
          authenticator.updateWatchCache(item);
        } else {
          // TODO: implement cancel delegation token logic on DELETED
          LOG.debug("type: " + item.type + ". " + item.object.toString());
        }
      }
    } finally {
      watch.close();
    }

    return watch;
  }

  @Override
  public void close() throws IOException {
    podWatch.close();
  }
}
