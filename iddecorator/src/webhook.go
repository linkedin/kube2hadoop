/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package main

import (
	"encoding/json"
	"fmt"
	"io/ioutil"
	"net/http"
	"path"
	"regexp"
	"time"

	"k8s.io/api/admission/v1beta1"
	admissionregistrationv1beta1 "k8s.io/api/admissionregistration/v1beta1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/runtime/serializer"

	"golang.linkedin.com/iddecorator/iddecorator/jsonpath"
	"golang.linkedin.com/iddecorator/iddecorator/logger"
	"golang.linkedin.com/iddecorator/iddecorator/metrics"
	"golang.linkedin.com/iddecorator/iddecorator/utils"
)

const (
	UserAnnotation = "iddecorator.username"
	AllTypes = "alltypes"
)

var (
	runtimeScheme = runtime.NewScheme()
	codecs        = serializer.NewCodecFactory(runtimeScheme)
	deserializer  = codecs.UniversalDeserializer()

	// (https://github.com/kubernetes/kubernetes/issues/57982)
	defaulter = runtime.ObjectDefaulter(runtimeScheme)
	StartsWithRecursive = regexp.MustCompile(`^\.\.\S+`)
)

var ignoredNamespaces = []string {
	metav1.NamespaceSystem,
	metav1.NamespacePublic,
	metav1.NamespaceDefault,
}
type WebhookServer struct {
	logger            logger.LoggerI
	configMap         utils.ConfigMap
	userAnnotationKey string
	unmarshalledReq   interface{}
	monitoring        metrics.MonitoringMetrics
}


type patchOperation struct {
	Op    string      `json:"op"`
	Path  string      `json:"path"`
	Value interface{} `json:"value,omitempty"`
}

func init() {
	_ = corev1.AddToScheme(runtimeScheme)
	_ = admissionregistrationv1beta1.AddToScheme(runtimeScheme)
}

func (whsvr *WebhookServer) parseJSONPath(input interface{}, name, template string) ([]string, error) {
	start := time.Now()
	j := jsonpath.New(name).AllowMissingKeys(true).SetLogger(whsvr.logger)
	if err := j.Parse(template); err != nil {
		return nil, err
	}
	if _, err := j.FindResults(input); err != nil {
		return nil, err
	}
	elapsed := time.Since(start)
	whsvr.logger.Debugf("Ended parsing, elapsed %s", elapsed)
	return j.FindPaths(), nil
}

func (whsvr *WebhookServer) patchOp(path string, value interface{}) (patchOperation) {
	return patchOperation{
		Op:    "add",
		Path:  path,
		Value: value,
	}
}

func (whsvr *WebhookServer) mustUpdateAnnotations(jPath string, ar *v1beta1.AdmissionReview) []patchOperation {
	if patch, err := whsvr.updateAnnotations(jPath, ar); err != nil {
		whsvr.logger.Errorf("Error updating annotations '%v'", err)
		return nil
	} else {
		return patch
	}
}

// updateAnnotations will come up with the correct patch for the provided path
// It can handle missing annotations and metadata tags
// Depending on what's missing it will generate an add operation patch
func (whsvr *WebhookServer) updateAnnotations(jPath string, ar *v1beta1.AdmissionReview) (patch []patchOperation, err error) {
	var paths []string
	if paths, err = whsvr.getPaths(jPath); err == nil {
		whsvr.logger.Debugf("Found paths %s", paths)
		for _, fpath := range paths {
			patch = append(patch, whsvr.patchOp(path.Join(fpath, whsvr.userAnnotationKey), ar.Request.UserInfo.Username))
		}
	}
	whsvr.logger.Debugf("Created patches %v", patch)
	return patch, err
}

func (whsvr *WebhookServer) mustCreatePatch(ar *v1beta1.AdmissionReview) []patchOperation {
	if patch, err := whsvr.createPatch(ar); err != nil {
		whsvr.logger.Errorf("error creating patch '%v'", err)
		return nil
	} else {
		return patch
	}
}

// create mutation patch for resources
// if the incoming username matches the neverOverwriteAccounts, then skip
// if an exact path cannot be found, this will skip-over with a warning
func (whsvr *WebhookServer) createPatch(ar *v1beta1.AdmissionReview) (patch []patchOperation, err error) {
	var config utils.Config
	var ok bool
	if config, ok = whsvr.configMap.Configs[ar.Request.Resource.Resource]; !ok {
		config = whsvr.configMap.Configs[AllTypes]
	}

	username := ar.Request.UserInfo.Username
	for _, neverOverwriteAccount := range config.NeverOverwriteAccounts {
		if match, err := regexp.MatchString(neverOverwriteAccount, username); match {
			whsvr.logger.Infof("Skipping overwriting with username (%s) from config (%s)", neverOverwriteAccount, config)
			return patch, err
		} else if err != nil {
			whsvr.logger.Errorf("Error with neverOverwriteAccount regexp %s, %v", neverOverwriteAccount, err)
		} else {
			whsvr.logger.Infof("Provided neverOverwriteAccount regexp %s, does not match %s", neverOverwriteAccount, username)
		}
	}
	for _, jPath := range config.Paths {
		var newPatch []patchOperation
		if newPatch, err = whsvr.updateAnnotations(jPath, ar); err == nil {
			patch = append(patch, newPatch...)
		} else {
			return patch, err
		}
	}

	return patch, err
}

// Logs the error and returns a single value
func (whsvr *WebhookServer) mustGetPaths(jPath string) ([]string) {
	if paths, err := whsvr.getPaths(jPath); err != nil {
		fmt.Errorf("error while finding path %v", err)
	} else {
		return paths
	}
	return nil
}

// Go through the JSON blob and query the labels
func (whsvr *WebhookServer) getPaths(jPath string) (paths []string, err error) {
	if !StartsWithRecursive.MatchString(jPath) {
		whsvr.logger.Errorf("path Pattern '%s' must start with recursive '..'", jPath)
		return nil, fmt.Errorf("path Pattern '%s' must start with recursive '..'", jPath)
	}
	jsPath := "{" + jPath + "}"
	whsvr.logger.Debugf("parsing json for path %s", jsPath)
	paths, err = whsvr.parseJSONPath(whsvr.unmarshalledReq, whsvr.userAnnotationKey, jsPath)
	return paths, err
}

// main mutation process
func (whsvr *WebhookServer) mutate(ar *v1beta1.AdmissionReview) *v1beta1.AdmissionResponse {
	req := ar.Request
	if err := json.Unmarshal(req.Object.Raw, &whsvr.unmarshalledReq); err != nil {
		whsvr.logger.Errorf("Could not unmarshal raw object: %v", err)
		return &v1beta1.AdmissionResponse {
			Result: &metav1.Status {
				Message: err.Error(),
			},
		}
	}
	whsvr.logger.Infof("AdmissionReview for Kind=%v, Resource=%v Namespace=%v Name=%v UID=%v patchOperation=%v userinfo=%v",
		req.Kind, req.Resource, req.Namespace, req.Name, req.UID, req.Operation, req.UserInfo)
	patches, err := whsvr.createPatch(ar)
	patchBytes, err := json.Marshal(patches)
	if err != nil {
		return &v1beta1.AdmissionResponse {
			Result: &metav1.Status {
				Message: err.Error(),
			},
		}
	}

	whsvr.logger.Infof("AdmissionResponse: patch=%v\n", string(patchBytes))
	return &v1beta1.AdmissionResponse {
		Allowed: true,
		Patch:   patchBytes,
		PatchType: func() *v1beta1.PatchType {
			pt := v1beta1.PatchTypeJSONPatch
			return &pt
		}(),
	}
}

// Serve method for webhook server
func (whsvr *WebhookServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	var body []byte
	if r.Body != nil {
		if data, err := ioutil.ReadAll(r.Body); err == nil {
			body = data
		}
	}
	if len(body) == 0 {
		whsvr.logger.Errorf("empty body for request %v", r)
		http.Error(w, "empty body", http.StatusBadRequest)
		//push number of bad requests
		whsvr.monitoring.BadRequestErrorCounts.Inc()
		return
	}

	// verify the content type is accurate
	contentType := r.Header.Get("Content-Type")
	if contentType != "application/json" {
		whsvr.logger.Errorf("Content-Type=%s, expect application/json", contentType)
		http.Error(w, "invalid Content-Type, expect `application/json`", http.StatusUnsupportedMediaType)
		//push invalid content types
		whsvr.monitoring.InvalidContentTypeErrorCounts.Inc()
		return
	}

	var admissionResponse *v1beta1.AdmissionResponse
	ar := v1beta1.AdmissionReview{}
	if _, _, err := deserializer.Decode(body, nil, &ar); err != nil {
		whsvr.logger.Errorf("Can't decode body: %v", err)
		admissionResponse = &v1beta1.AdmissionResponse {
			Result: &metav1.Status {
				Message: err.Error(),
			},
		}
		// push number of decode errors
		whsvr.monitoring.DecodeErrorCounts.Inc()
	} else {
		admissionResponse = whsvr.mutate(&ar)
	}

	//TODO: Why do we need another admissionReview? Why not use the same one as above?
	admissionReview := v1beta1.AdmissionReview{}
	if admissionResponse != nil {
		admissionReview.Response = admissionResponse
		if ar.Request != nil {
			admissionReview.Response.UID = ar.Request.UID
		}
	}

	resp, err := json.Marshal(admissionReview)
	if err != nil {
		whsvr.logger.Errorf("Can't encode response: %v", err)
		// push number of encode errors
		whsvr.monitoring.EncodeErrorCounts.Inc()
		http.Error(w, fmt.Sprintf("could not encode response: %v", err), http.StatusInternalServerError)
	}
	whsvr.logger.Debugf("Ready to write response %s", resp)

	if _, err := w.Write(resp); err != nil {
		whsvr.logger.Errorf("Can't write response: %v", err)
		http.Error(w, fmt.Sprintf("could not write response: %v", err), http.StatusInternalServerError)
		// push number of errors in writing responses
		whsvr.monitoring.ResponseWriteErrorCounts.Inc()
	}
	// push number of successful responses
	whsvr.monitoring.SuccessCounts.Inc()
}