/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop.servlets;

import com.linkedin.kube2hadoop.core.Constants;
import com.linkedin.kube2hadoop.core.ErrorCode;
import com.linkedin.kube2hadoop.core.TokenServiceException;
import com.linkedin.kube2hadoop.core.Utils;
import com.linkedin.kube2hadoop.service.TokenFetcherService;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.MediaType;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;


/**
 * Jetty servlet for getDelegationToken calls
 */
public class GetDelegationTokenServlet extends HttpServlet {
  public static final Log LOG = LogFactory.getLog(GetDelegationTokenServlet.class);
  private final Configuration conf;


  public GetDelegationTokenServlet(final Configuration conf) {
    this.conf = conf;
  }


  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    // TODO: move to metrics
    long requestStartTime = System.currentTimeMillis();

    Map<String, String[]> params = new HashMap<>(request.getParameterMap());
    params.put(Constants.SRCIP, new String[]{getSrcIP(request)});

    String namespace = request.getParameter(Constants.NAMESPACE);
    String podName = request.getParameter(Constants.POD_NAME);
    String[] tokenKinds = request.getParameterValues(Constants.TOKEN_KINDS);

    LOG.info("Received request from namespace: " + namespace + ", pod: "
        + podName + ", to get delegation token for token kinds: "
        + (tokenKinds == null ? Constants.HDFS_DELEGATION_TOKEN : String.join(",", tokenKinds)));

    TokenFetcherService tfsInstance = TokenFetcherService.getInstance(conf);
    try {
      String tokenStr = tfsInstance.getDelegationTokens(params);

      // compose response
      response.setContentType(MediaType.APPLICATION_JSON);
      response.setStatus(HttpServletResponse.SC_OK);
      response.getWriter().println(Utils.genJsonString("Token", tokenStr));

      long requestEndTime = System.currentTimeMillis();
      LOG.info("Tokens fetched for pod: " + podName + " in " + (requestEndTime - requestStartTime) + "ms");
    } catch (TokenServiceException tse) {
      LOG.error("Unable to fetch token due to error " + tse.getErrorCode() + ": " + tse.getErrorMsg());

      // throw {@code TokenServiceException} to fail Token Service when Kubernetes Watch breaks
      if (tse.getErrorCode() == ErrorCode.KUBERNETES_WATCH_EXCEPTION.getCode()) {
        throw new TokenServiceException(tse.getErrorMsg(), ErrorCode.KUBERNETES_WATCH_EXCEPTION);
      }

      // compose response with ErrorCode
      response.setContentType(MediaType.APPLICATION_JSON);
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      response.getWriter().println(Utils.genJsonString("ErrorCode", Integer.toString(tse.getErrorCode())));
    }
  }

  private String getSrcIP(HttpServletRequest request) {
    return request.getRemoteAddr();
  }
}
