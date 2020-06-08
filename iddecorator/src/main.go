/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package main

import (
        "context"
        "crypto/tls"
        "flag"
        "fmt"
        "log"
        "net/http"
        "os"
        "os/signal"
        "syscall"
        "time"

        "github.com/prometheus/client_golang/prometheus/promhttp"
        "go.uber.org/zap"

        "golang.linkedin.com/iddecorator/iddecorator/healthcheck"
        pamf_logger "golang.linkedin.com/iddecorator/iddecorator/logger"
        "golang.linkedin.com/iddecorator/iddecorator/metrics"
        "golang.linkedin.com/iddecorator/iddecorator/utils"
)

const (
        KubernetesServiceHost = "KUBERNETES_SERVICE_HOST"
        KubernetesServicePort = "KUBERNETES_SERVICE_PORT"
)

// Webhook Server parameters
type WhSvrParameters struct {
        port              int    // webhook server port
        certFile          string // path to the x509 certificate for https
        keyFile           string // path to the x509 private key matching `CertFile`
        nonTLSPort        int    // port to serve metrics and healthcheck endpoints
        metrics           string // path to serve metrics endpoint
        userAnnotationKey string // annotation key
        configPath        string // path to yaml config file
}


func main() {
        var parameters WhSvrParameters
        // get command line parameters
        flag.IntVar(&parameters.port, "port", 443, "Webhook server port.")
        flag.StringVar(&parameters.configPath, "paths-to-decorate",  "/etc/config/paths.yaml", "YAML config with paths to decorate")
        flag.StringVar(&parameters.userAnnotationKey, "username-annotation-key", UserAnnotation, "Annotation parameter.")
        flag.StringVar(&parameters.metrics, "metrics-endpoint", "/metrics", "Metrics endpoint for the webhook.")
        flag.IntVar(&parameters.nonTLSPort, "healthcheck-port", 80, "Health-check port for the webhook.")
        flag.StringVar(&parameters.certFile, "tlsCertFile", "/etc/webhook/certs/cert.pem", "File containing the x509 Certificate for HTTPS.")
        flag.StringVar(&parameters.keyFile, "tlsKeyFile", "/etc/webhook/certs/key.pem", "File containing the x509 private key to --tlsCertFile.")
        logLevel := zap.LevelFlag("log-level", zap.InfoLevel, "Log level")
        flag.Parse()

        logger, cfg, err := pamf_logger.SetupLogging()
        if err != nil {
                panic(err)
        }

        defer logger.Sync()
        cfg.Level.SetLevel(*logLevel)
        sugar := logger.Sugar()
        if err != nil {
                log.Fatalf("Failed to load configuration: %v", err)
        }

        pair, err := tls.LoadX509KeyPair(parameters.certFile, parameters.keyFile)
        if err != nil {
                log.Fatalf("Failed to load key pair: %v", err)
        }
        whsvr := &WebhookServer {
                logger: sugar,
                userAnnotationKey: parameters.userAnnotationKey,
                configMap: utils.ParseYamlToConfigMap(parameters.configPath),
                monitoring : metrics.NewMonitoringMetrics(),
        }


        //setup the main/TLS server
        tlsServer := setupTLSServer(whsvr, parameters, pair)
        // setup the metrics and healthCheck server
        nonTlsServer := setupNonTLSServer(parameters)
        // listening OS shutdown singal
        signalChan := make(chan os.Signal, 1)
        signal.Notify(signalChan, syscall.SIGINT, syscall.SIGTERM)
        <-signalChan

        whsvr.logger.Infof("Got OS shutdown signal, shutting down webhook server gracefully...")
        tlsServer.Shutdown(context.Background())
        nonTlsServer.Shutdown(context.Background())
}

// Sets up the TLS Server with certs
func setupTLSServer(whsvr *WebhookServer, parameters WhSvrParameters, pair tls.Certificate) (tlsServer *http.Server) {
        mux := http.NewServeMux()
        // Wrap the serveChain so we can monitor requests in flight
        serveChain := promhttp.InstrumentHandlerInFlight(whsvr.monitoring.InFlightGauge,
                promhttp.InstrumentHandlerDuration(whsvr.monitoring.Duration,
                        promhttp.InstrumentHandlerCounter(whsvr.monitoring.Counter,
                                promhttp.InstrumentHandlerResponseSize(whsvr.monitoring.ResponseSize, whsvr),
                        ),
                ),
        )
        mux.Handle("/mutate", serveChain)
        tlsServer = &http.Server{
                Addr:         fmt.Sprintf(":%v", parameters.port),
                TLSConfig:    &tls.Config{Certificates: []tls.Certificate{pair}},
                Handler:      mux,
                ReadTimeout:  5 * time.Second,
                WriteTimeout: 10 * time.Second,
                IdleTimeout:  15 * time.Second,
        }
       log.Printf("Serving over TLS with the following parameters: %v", parameters)
        // start webhook server in new rountine
        go func() {
                if err := tlsServer.ListenAndServeTLS("", ""); err != nil {
                        log.Fatalf("Failed to listen and serve webhook TLS server: %v", err)
                }
        }()
        return tlsServer
}

//Sets up /metrics, /live, and /ready endpoints
func setupNonTLSServer(parameters WhSvrParameters) (nonTlsServer *http.Server) {
        mux := http.NewServeMux()
        // admin endpoint
        mux.HandleFunc("/admin", admin)
        // metrics endpoint
        mux.Handle(parameters.metrics, promhttp.Handler())
        //healthcheck endpoint
        health := healthcheck.NewHandler()
        apiserver_host, ok := os.LookupEnv(KubernetesServiceHost)
        if !ok {
                log.Fatalf("Environment variable '%s' was not set", KubernetesServiceHost)
        }
        apiserver_port, ok := os.LookupEnv(KubernetesServicePort)
        if !ok {
                log.Fatalf("Environment variable '%s' was not set", KubernetesServicePort)
        }
        apiserver_url := fmt.Sprintf("%s:%s", apiserver_host, apiserver_port)

        // restart the container if it cannot access iddeployer with a 5s timeout
        // Make sure we can connect to iddeployer over TCP in less than
        // 500ms. Run this check asynchronously in the background every 10 seconds
        // instead of every time the /ready or /live endpoints are hit.
        health.AddLivenessCheck("iddeployer connectivity",
                healthcheck.Async(healthcheck.TCPDialCheck(apiserver_url, 500*time.Millisecond), 10*time.Second))
        // stop sending traffic to the container if it cannot access iddeployer with a 1s timeout
        health.AddReadinessCheck("iddeployer connectivity", healthcheck.TCPDialCheck(apiserver_url, 1*time.Second))
        mux.HandleFunc("/live", health.LiveEndpoint)
        mux.HandleFunc("/ready", health.ReadyEndpoint)
        nonTlsServer = &http.Server {
                Addr:        fmt.Sprintf(":%v", parameters.nonTLSPort),
                Handler: mux,
                ReadTimeout:  5 * time.Second,
                WriteTimeout: 10 * time.Second,
                IdleTimeout:  15 * time.Second,
        }
        //start healthserver separately since serving healthchecks over TLS is not a requirement
        go func() {
                if err := nonTlsServer.ListenAndServe(); err != nil {
                        log.Fatalf("Failed to listen and serve webhook healthcheck server: %v", err)
                }
        }()
        return nonTlsServer
}
