/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop.core;

public class TokenServiceException extends RuntimeException {
  ErrorCode errCode;
  String errMsg;

  public TokenServiceException(String msg, ErrorCode errCode) {
    super(msg);
    this.errCode = errCode;
    this.errMsg = msg;
  }

  public int getErrorCode() {
    return errCode.getCode();
  }

  public String getErrorMsg() {
    return this.errMsg;
  }
}
