/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop.authenticator;

import com.linkedin.kube2hadoop.core.ErrorCode;
import com.linkedin.kube2hadoop.core.TokenServiceException;
import com.linkedin.kube2hadoop.core.conf.ConfigurationKeys;
import java.util.List;
import org.apache.hadoop.conf.Configuration;


/**
 * Create Authenticators based on authentication method and add additional decorators for
 * more flexible authentication
 */
public class AuthenticatorFactory {

  public Authenticator getAuthenticator(ConfigurationKeys.AuthenticationPlatform authenticatorType,
      List<ConfigurationKeys.AuthenticationDecorators> decorators, Configuration conf) {
    Authenticator authenticator;
    if (authenticatorType == null) {
      return null;
    }

    if (authenticatorType.equals(ConfigurationKeys.AuthenticationPlatform.KUBERNETES)) {
      authenticator = new KubernetesAuthenticator(conf);
    } else {
      throw new TokenServiceException(authenticatorType + " is not a supported authenticator type",
          ErrorCode.UNSUPPORTED_AUTHENTICATOR_TYPE);
    }

    for (ConfigurationKeys.AuthenticationDecorators decorator : decorators) {
      if (decorator.equals(ConfigurationKeys.AuthenticationDecorators.LDAP)) {
        authenticator = new LDAPAuthenticatorDecorator(authenticator);
      }
    }

    return authenticator;
  }
}
