/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop.core;

public enum ErrorCode {
  INVALID_PARAMS(100, "Client provided invalid parameters to token service."),
  UNSUPPORTED_TOKEN_KIND(101, "Client provided unsupported token type."),
  FAILED_TO_CREATE_PROXY_USER(102, "Failed to create proxy user."),
  FAILED_TO_FETCH_HDFS_TOKEN(103, "Failed to fetch HDFS token as superuser."),
  FAILED_TO_LOGIN_WITH_KERBEROS(104, "Failed to login wit kerberos"),
  CREDENTIAL_ENCODING_EXCEPTION(105, "Failed to encode credentials to BASE64 string"),
  KUBERNETES_WATCH_EXCEPTION(106, "Exception happened during the watch of Kubernetes namespace"),
  KUBERNETES_AUTHENTICATION_EXCEPTION(107, "Failed to authenticate pod"),
  KUBERNETES_POD_CALL_EXCEPTION(108, "Failed to retrieve pod information from Kubernetes API server"),
  KUBERNETES_NO_USERID_EXCEPTION(109, "Cannot find userID information in pod annotation"),
  KUBERNETES_POD_NOT_FOUND(110, "Cannot find pod information in Kubernetes API server"),
  UNSUPPORTED_AUTHENTICATOR_TYPE(111, "Unsupported authenticator type"),
  SSL_CERT_EXCEPTION(112, "Unable to retrieve ssl certificate"),
  BEARER_TOKEN_EXCEPTION(113, "Failed to retrieve kubernetes bearer token"),
  READ_KEYTAB_EXCEPTION(114, "Failed to read keytab information"),
  LDAP_LOOKUP_EXCEPTION(115, "Failed to authenticate user to access specified headless account"),
  KUBERNETES_AUTHENTICATION_BLACKLIST_EXCEPTION(116, "Username is blacklisted for fetching delegation token");


  private final int code;
  private final String description;

  ErrorCode(int code, final String description) {
    this.code = code;
    this.description = description;
  }

  public String getDescription() {
    return description;
  }

  public int getCode() {
    return code;
  }

  @Override
  public String toString() {
    return String.valueOf(code);
  }
}
