/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop.authenticator;

import org.apache.hadoop.conf.Configuration;


/**
 * This is a general interface for authenticating whether the user is who
 * they claim to be and that they are authorized to perform certain operations
 */
public interface Authenticator {
  String getAuthenticatedUserID(AuthenticatorParameters params);
  Configuration getConfiguration();
}
