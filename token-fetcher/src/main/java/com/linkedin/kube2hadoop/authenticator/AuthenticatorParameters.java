/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop.authenticator;


import java.util.Map;


public class AuthenticatorParameters {
  private Map<String, String[]> params;

  public AuthenticatorParameters(Map<String, String[]> params) {
    this.params = params;
  }

  String getParamString(String key) {
    String[] values = params.get(key);
    if (values != null && values.length > 0) {
      return values[0];
    }

    return null;
  }

  void addParamString(String key, String value) {
    params.put(key, new String[] {value});
  }

  void addAllToParams(Map<String, String> paramMap) {
    paramMap.forEach(this::addParamString);
  }
}
