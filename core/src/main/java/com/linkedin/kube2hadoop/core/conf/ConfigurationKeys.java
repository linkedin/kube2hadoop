/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop.core.conf;

public class ConfigurationKeys {
  public enum AuthenticationPlatform {
    KUBERNETES
  }

  public enum AuthenticationDecorators {
    LDAP
  }

  private ConfigurationKeys() {

  }

  public static final String KUBE2HADOOP_PREFIX = "kube2hadoop.";

  // Jetty server configurations
  public static final String JETTY_PREFIX = "jetty.";

  public static final String JETTY_MAX_THREADS = JETTY_PREFIX + "maxThreads";
  public static final Integer DEFAULT_JETTY_MAX_THREADS = 50;

  public static final String JETTY_MIN_THREADS = JETTY_PREFIX + "minThreads";
  public static final Integer DEFAULT_JETTY_MIN_THREADS = 10;

  public static final String JETTY_IDLE_TIMEOUT = JETTY_PREFIX + "idleTimeout";
  public static final Integer DEFAULT_JETTY_IDLE_TIMEOUT = 120;


  // Hadoop configurations
  public static final String KUBE2HADOOP_KEYTAB_LOCATION = KUBE2HADOOP_PREFIX + "keytab.location";

  public static final String HADOOP_CONF_DIR = KUBE2HADOOP_PREFIX + "hadoop.conf.dir";


  // Kubernetes configurations
  public static final String KUBERNETES_PREFIX = "kubernetes";
  public static final String KUBERNETES_SERVICE_HOST = KUBERNETES_PREFIX + "service.host";
  public static final String KUBERNETES_SERVICE_PORT = KUBERNETES_PREFIX + "service.port";

  public static final String KUBE2HADOOP_TOKEN_FILE_LOCATION = KUBE2HADOOP_PREFIX + "token.location";
  public static final String KUBE2HADOOP_CERT_LOCATION = KUBE2HADOOP_PREFIX + "cert.location";

  public static final String KUBE2HADOOP_WATCH_LABEL_SELECTOR = KUBERNETES_PREFIX + "watch.labelselector";

  // Authenticator configurations
  public static final String KUBE2HADOOP_AUTHENTICATOR_LDAP_PROVIDER_URL = KUBERNETES_PREFIX + "authenticator.ldap-provider-url";
  public static final String KUBE2HAdOOP_AUTHENTICATOR_LDAP_GROUP_DOMAIN = KUBERNETES_PREFIX + "authenticator.ldap-group-domain";


  // Token renewer configurations
  public static final String KUBE2HADOOP_TOKEN_RENEWER = KUBE2HADOOP_PREFIX + "renewer.";
  public static final String KUBE2HADOOP_RENEWER_NAME = KUBE2HADOOP_TOKEN_RENEWER + "name";
  public static final String DEFAULT_KUBE2HADOOP_RENEWER_NAME = "default";

  public static final String KUBE2HADOOP_TOKEN_RENEWAL_INTERVAL_IN_MILLISECONDS = KUBE2HADOOP_TOKEN_RENEWER + "interval";
  public static final Integer DEFAULT_KUBE2HADOOP_TOKEN_RENEWAL_INTERVAL_IN_MILLISECONDS = 1000 * 60 * 10;

}
