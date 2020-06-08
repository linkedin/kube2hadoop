/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop;

import com.linkedin.kube2hadoop.core.conf.ConfigurationKeys;
import com.linkedin.kube2hadoop.servlets.GetDelegationTokenServlet;
import com.linkedin.kube2hadoop.servlets.HealthServlet;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import static com.linkedin.kube2hadoop.core.Constants.*;


/**
 * Main jetty server for token fetcher service. Contains endpoints for getDelegationToken, metrics and health.
 */
public class TokenServer {
  private Configuration conf;
  private Options opts;

  private TokenServer(Configuration conf) {
    initOptions();
    this.conf = conf;
  }

  public void run() throws Exception {
    int maxThreads = conf.getInt(ConfigurationKeys.JETTY_MAX_THREADS, ConfigurationKeys.DEFAULT_JETTY_MAX_THREADS);
    int minThreads = conf.getInt(ConfigurationKeys.JETTY_MIN_THREADS, ConfigurationKeys.DEFAULT_JETTY_MIN_THREADS);
    int idleTimeout = conf.getInt(ConfigurationKeys.JETTY_IDLE_TIMEOUT, ConfigurationKeys.DEFAULT_JETTY_IDLE_TIMEOUT);

    QueuedThreadPool threadPool = new QueuedThreadPool(maxThreads, minThreads, idleTimeout);
    Server jetty = new Server(threadPool);
    ServerConnector connector = new ServerConnector(jetty);
    connector.setPort(KUBE2HADOOP_SERVER_PORT);
    jetty.setConnectors(new Connector[] {connector});

    ServletContextHandler context = new ServletContextHandler();
    context.setContextPath("/");

    // getDelegationToken endpoint
    GetDelegationTokenServlet getTokenServlet = new GetDelegationTokenServlet(conf);
    ServletHolder getTokenServletHolder = new ServletHolder(getTokenServlet);

    // health endpoint
    HealthServlet healthServlet = new HealthServlet(conf);
    ServletHolder healthServletHolder = new ServletHolder(healthServlet);

    context.addServlet(getTokenServletHolder, GET_DELEGATION_TOKEN_PATH);
    context.addServlet(healthServletHolder, HEALTH_PATH);

    jetty.setHandler(context);
    jetty.start();
    jetty.join();
  }

  private void initOptions() {
    opts = new Options();
    opts.addOption("conf_file", true, "Name of user specified conf file, on the classpath");
    opts.addOption("help", false, "Print Usage");
  }

  private void init(String[] args) throws ParseException {
    CommandLine cliParser = new GnuParser().parse(opts, args, true);

    if (cliParser.hasOption("help")) {
      printUsage();
    }

    initConf(conf, cliParser);
  }

  private void printUsage() {
    new HelpFormatter().printHelp("TokenServer", opts);
  }

  private void initConf(Configuration conf, CommandLine cliParser) {
    conf.addResource(KUBE2HADOOP_DEFAULT_XML);
    if (cliParser.hasOption("conf_file")) {
      Path confFilePath = new Path(cliParser.getOptionValue("conf_file"));
      conf.addResource(confFilePath);
    }
  }

  public static void main(String[] args) {
    try {
      TokenServer tokenServer = new TokenServer(new Configuration());
      tokenServer.init(args);
      tokenServer.run();
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }
}
