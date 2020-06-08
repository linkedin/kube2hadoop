/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop.core;

public class Constants {
  public static final int KUBE2HADOOP_SERVER_PORT = 9966;

  public static final String KUBERNETES = "KUBERNETES";

  // Servlet Paths
  public static final String GET_DELEGATION_TOKEN_PATH = "/getDelegationToken";
  public static final String HEALTH_PATH = "/health";
  public static final String METRICS_PATH = "/metrics";

  // Configuration files
  public static final String KUBE2HADOOP_DEFAULT_XML = "kube2hadoop-default.xml";

  // Hadoop related constants
  public static final String HDFS_DELEGATION_TOKEN = "HDFS_DELEGATION_TOKEN";
  public static final String HADOOP_CONF_DIR = "HADOOP_CONF_DIR";

  public static final String CORE_SITE_CONF = "core-site.xml";
  public static final String HDFS_SITE_CONF = "hdfs-site.xml";

  // Kubernetes related constants
  public static final String HTTPS_PREFIX = "https://";

  public static final String KUBERNETES_SERVICE_HOST = "KUBERNETES_SERVICE_HOST";
  public static final String KUBERNETES_SERVICE_PORT = "KUBERNETES_SERVICE_PORT";
  public static final String NAMESPACE = "namespace";
  public static final String POD_NAME = "pod-name";
  public static final String TOKEN_KINDS = "token-kinds";
  public static final String DO_AS = "doAs";
  public static final String SRCIP = "srcIP";
  public static final String PROXY_USER = "proxyUser";
  public static final String IDDECORATOR_LABEL = "iddecorator";

  public static final String KUBERNETES_USER_ID = "iddecorator.username";

  // LDAP related constants
  public static final String CONTEXT_FACTORY = "com.sun.jndi.ldap.LdapCtxFactory";

  public static final String CN_ATTR = "cn";
  public static final String MEM_UID_ATTR = "memberuid";

  private Constants() {

  }
}
