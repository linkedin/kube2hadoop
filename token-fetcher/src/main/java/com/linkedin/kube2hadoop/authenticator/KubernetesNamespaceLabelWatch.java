/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop.authenticator;

import com.google.gson.reflect.TypeToken;
import com.linkedin.kube2hadoop.core.Constants;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1Namespace;
import io.kubernetes.client.models.V1NamespaceList;
import io.kubernetes.client.util.Watch;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;


class KubernetesNamespaceLabelWatch implements Callable {
  private static final Log LOG = LogFactory.getLog(KubernetesNamespaceLabelWatch.class);
  private CoreV1Api api = new CoreV1Api();

  private KubernetesAuthenticator authenticator;
  private Configuration conf;

  KubernetesNamespaceLabelWatch(KubernetesAuthenticator authenticator, Configuration conf) {
    this.authenticator = authenticator;
    this.conf = conf;
  }

  @Override
  public Object call() throws Exception {
    watchNamespaceLabels();
    return null;
  }

  /**
   * Watch on all namespace metadata changes
   * @throws ApiException Kubernetes Client ApiException
   * @throws IOException Kubernetes watch close exception
   */
  void watchNamespaceLabels() throws ApiException, IOException {
    Watch<V1NamespaceList> namespaceWatch = Watch.createWatch(
        io.kubernetes.client.Configuration.getDefaultApiClient(),
        api.listNamespaceCall(null, null, null, null,
            null, null, null, null, Boolean.TRUE,
            null, null),
        new TypeToken<Watch.Response<V1NamespaceList>>() { }.getType()
    );
    try {
      for (Watch.Response<V1NamespaceList> item : namespaceWatch) {
        V1NamespaceList namespaceList = item.object;
        String namespace = null;
        if (namespaceList.getMetadata() != null && namespaceList.getMetadata().getSelfLink() != null) {
          namespace = getNamespaceFromSelfLink(namespaceList.getMetadata().getSelfLink());
        }

        LOG.debug(namespaceList.toString());

        if (item.type.equalsIgnoreCase("ADDED") || item.type.equalsIgnoreCase("MODIFIED")) {
          checkWatchThisNamespace(namespace);
        } else if (item.type.equalsIgnoreCase("DELETED")) {
          removeFromCacheIfExist(namespace);
          LOG.info("Namespace: " + namespace + " is deleted, removed from watch if currently watching");
        }
      }
    } finally {
      namespaceWatch.close();
    }
  }

  /**
   * Infer {@code namespace} from selfLink metadata entry
   * @param selfLink selfLink
   * @return namespace
   */
  static String getNamespaceFromSelfLink(String selfLink) {
    String[] namespaceSplits = selfLink.split("/");
    return namespaceSplits[(namespaceSplits.length - 1)];
  }

  /**
   * Check whether or not to watch the namespace. This is determined by whether the namespace is labelled with
   * "iddecorator=enabled". If the iddecorator label is disabled or does not exist, the corresponding
   * {@code NamespaceWatch} will be removed and the pod activity in {@param namespace} will no long be monitored.
   * @param namespace Kubernetes namespace
   */
  private void checkWatchThisNamespace(String namespace) {
    try {
      V1Namespace v1Namespace = api.readNamespace(namespace, null, false, false);
      if (v1Namespace.getMetadata() != null && v1Namespace.getMetadata().getLabels() != null) {
        String iddecoratorLabel = v1Namespace.getMetadata().getLabels().get(Constants.IDDECORATOR_LABEL);
        if (iddecoratorLabel != null && iddecoratorLabel.equalsIgnoreCase("enabled")) {
          authenticator.getNamespaceWatchCache().computeIfAbsent(namespace, k -> {
            KubernetesNamespaceWatch namespaceWatch = new KubernetesNamespaceWatch(authenticator, k, conf);
            // launch one watch thread per namespace
            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.submit(namespaceWatch);
            return namespaceWatch;
          });
          LOG.info("Added new namespace to watch: " + namespace);
          LOG.debug(v1Namespace.toString());
        } else {
          removeFromCacheIfExist(namespace);
          LOG.info("Namespace: " + namespace + " isn't labelled for watch");
        }
      }
    } catch (Exception ex) {
      LOG.error("Watch on Namespace: " + namespace + " failed.", ex);
      removeFromCacheIfExist(namespace);
    }
  }

  private void removeFromCacheIfExist(String namespace) {
    authenticator.getNamespaceWatchCache().computeIfPresent(namespace, (k, v) -> {
      try {
        v.close();
      } catch (IOException e) {
        LOG.error(e);
      }
      return null;
    });
  }

}
