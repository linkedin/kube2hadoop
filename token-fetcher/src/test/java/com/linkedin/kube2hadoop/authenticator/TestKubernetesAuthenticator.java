/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop.authenticator;

import com.google.common.collect.ImmutableMap;
import com.linkedin.kube2hadoop.core.Constants;
import com.linkedin.kube2hadoop.core.TokenServiceException;
import com.linkedin.kube2hadoop.core.conf.ConfigurationKeys;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.models.V1PodStatus;
import io.kubernetes.client.util.Watch;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.conf.Configuration;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.mockito.Mockito.*;


public class TestKubernetesAuthenticator {
  String podName = "testPod";
  String namespace = "testNamespace";
  String podIP = "10.0.150.0";
  String userID = "testUser";

  KubernetesAuthenticator authenticator;

  @BeforeMethod
  public void setup() {
    authenticator = mock(KubernetesAuthenticator.class);
    when(authenticator.getWatchCache()).thenCallRealMethod();
    when(authenticator.getAuthenticatedUserID(any())).thenCallRealMethod();
    when(authenticator.checkAgainstBlacklist(any())).thenCallRealMethod();
    when(authenticator.getBlackListedSuperUsers(any())).thenCallRealMethod();
    doCallRealMethod().when(authenticator).setWatchCache(any());
    doCallRealMethod().when(authenticator).updateWatchCache(any());

    authenticator.setWatchCache(new HashMap<>());
  }

  @Test
  public void testUpdateWatchCacheWithEmptyAnnotation() {
    Watch.Response<V1Pod> item = mockWatchResponse(podName, namespace, podIP, null);

    authenticator.updateWatchCache(item);
    Assert.assertEquals(authenticator.getWatchCache().size(), 0);
  }

  @Test
  public void testUpdateWatchCacheWithValidAnnotation() {
    Map<String, String> annotations = new HashMap<>();
    annotations.put(Constants.KUBERNETES_USER_ID, userID);

    Watch.Response<V1Pod> item = mockWatchResponse(podName, namespace, podIP, annotations);

    authenticator.updateWatchCache(item);

    Map<String, Map<String, Pair<String, Map<String, String>>>> watchCache = authenticator.getWatchCache();
    Assert.assertEquals(watchCache.size(), 1);
    Assert.assertEquals(watchCache.get(namespace).size(), 1);
    Assert.assertEquals(watchCache.get(namespace).get(podName).getValue().get(Constants.KUBERNETES_USER_ID), userID);
    Assert.assertEquals(watchCache.get(namespace).get(podName).getKey(), podIP);
  }

  @Test
  public void testUpdateWatchCacheWithNewIP() {
    String originPodIP = "10.0.150.0";
    String updatedPodIP = "10.0.150.1";
    Map<String, String> annotations = new HashMap<>();
    annotations.put(Constants.KUBERNETES_USER_ID, userID);

    Watch.Response<V1Pod> item = mockWatchResponse(podName, namespace, originPodIP, annotations);
    Watch.Response<V1Pod> updatedItem = mockWatchResponse(podName, namespace, updatedPodIP, annotations);

    authenticator.updateWatchCache(item);
    authenticator.updateWatchCache(updatedItem);

    Map<String, Map<String, Pair<String, Map<String, String>>>> watchCache = authenticator.getWatchCache();
    Assert.assertEquals(watchCache.size(), 1);
    Assert.assertEquals(watchCache.get(namespace).size(), 1);
    Assert.assertEquals(watchCache.get(namespace).get(podName).getValue().get(Constants.KUBERNETES_USER_ID), userID);
    Assert.assertEquals(watchCache.get(namespace).get(podName).getKey(), updatedPodIP);
  }

  @Test
  public void testGetAuthenticatedUserIDPass() {
    Map<String, Map<String, Pair<String, Map<String, String>>>> watchCache =
        makeWatchCache(podName, namespace, userID, podIP);
    AuthenticatorParameters params = makeAuthenticatorParameters(podName, namespace, podIP);

    authenticator.setWatchCache(watchCache);
    Assert.assertEquals(authenticator.getAuthenticatedUserID(params), userID);
  }

  @Test
  public void testGetBlackListedSuperUsers() {
    List<String> expectedBlackListedUsers = Arrays.asList("testUser", "testUser1");
    Configuration conf = new Configuration();
    conf.set(ConfigurationKeys.KUBE2HADOOP_AUTHENTICATOR_BLACKLISTED_USERS, "testUser, testUser1");
    List<String> actualBlackListedUsers = authenticator.getBlackListedSuperUsers(conf);
    Assert.assertEquals(actualBlackListedUsers, expectedBlackListedUsers);
  }


  @Test(expectedExceptions = TokenServiceException.class)
  public void testGetAuthenticatedUserIDFailed() {
    String fakeSrcIP = "10.0.150.1";
    String realSrcIP = "10.0.150.0";

    Map<String, Map<String, Pair<String, Map<String, String>>>> watchCache =
        makeWatchCache(podName, namespace, userID, realSrcIP);
    AuthenticatorParameters params = makeAuthenticatorParameters(podName, namespace, fakeSrcIP);

    authenticator.setWatchCache(watchCache);
    authenticator.getAuthenticatedUserID(params);
  }

  @Test(expectedExceptions = TokenServiceException.class,
      expectedExceptionsMessageRegExp = "Cannot find userID information in pod annotation")
  public void testGetPodInfoByNamespaceAndPodNameReturnsEmptyAnnotations() throws ApiException {
    when(authenticator.getPodInfoByNamespaceAndPodName(namespace, podName))
        .thenReturn(new ImmutablePair<>(podIP, new HashMap<>()));

    AuthenticatorParameters params = makeAuthenticatorParameters(podName, namespace, podIP);
    authenticator.getAuthenticatedUserID(params);
  }

  @Test(expectedExceptions = TokenServiceException.class,
      expectedExceptionsMessageRegExp = "Cannot find pod: .* in namespace .*")
  public void testGetPodInfoByNamespaceAndPodNameReturnsNullPodIP() throws ApiException {
    when(authenticator.getPodInfoByNamespaceAndPodName(namespace, podName))
        .thenReturn(new ImmutablePair<>(null, ImmutableMap.of(Constants.KUBERNETES_USER_ID, userID)));

    AuthenticatorParameters params = makeAuthenticatorParameters(podName, namespace, podIP);
    authenticator.getAuthenticatedUserID(params);
  }

  @Test
  public void testGetNamespaceFromSelfLink() {
    String selfLink = "/api/v1/namespaces/namespace-watch-test";
    String namespace = "namespace-watch-test";

    Assert.assertEquals(KubernetesNamespaceLabelWatch.getNamespaceFromSelfLink(selfLink), namespace);
  }

  private Watch.Response<V1Pod> mockWatchResponse(String podName, String namespace, String podIP,
      Map<String, String> annotations) {
    Watch.Response<V1Pod> item = mock(Watch.Response.class);
    V1ObjectMeta metadata = mock(V1ObjectMeta.class);
    V1PodStatus status = mock(V1PodStatus.class);
    V1Pod pod = mock(V1Pod.class);

    when(metadata.getName()).thenReturn(podName);
    when(metadata.getNamespace()).thenReturn(namespace);
    when(status.getPodIP()).thenReturn(podIP);
    when(metadata.getAnnotations()).thenReturn(annotations);

    when(pod.getMetadata()).thenReturn(metadata);
    when(pod.getStatus()).thenReturn(status);
    item.object = pod;

    return item;
  }

  private AuthenticatorParameters makeAuthenticatorParameters(String podName, String namespace, String srcIP) {
    Map<String, String[]> params = new HashMap<>();
    params.put(Constants.NAMESPACE, new String[]{namespace});
    params.put(Constants.POD_NAME, new String[]{podName});
    params.put(Constants.SRCIP, new String[]{srcIP});

    return new AuthenticatorParameters(params);
  }

  private Map<String, Map<String, Pair<String, Map<String, String>>>> makeWatchCache(
      String podName, String namespace, String userID, String srcIP) {
    Map<String, Map<String, Pair<String, Map<String, String>>>> watchCache = new HashMap<>();
    Map<String, Pair<String, Map<String, String>>> namespacedCache = new HashMap<>();
    Map<String, String> annotations = new HashMap<>();
    annotations.put(Constants.KUBERNETES_USER_ID, userID);
    namespacedCache.put(podName, new ImmutablePair<>(srcIP, annotations));
    watchCache.put(namespace, namespacedCache);

    return watchCache;
  }
}
