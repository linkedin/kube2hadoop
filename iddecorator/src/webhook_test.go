/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package main
import (
	"encoding/json"
	"k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/api/admission/v1beta1"
	authenticationv1 "k8s.io/api/authentication/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"path"
	"testing"
	"go.uber.org/zap"
	pamf_logger "golang.linkedin.com/iddecorator/iddecorator/logger"
	"golang.linkedin.com/iddecorator/iddecorator/utils"
	"github.com/stretchr/testify/assert"
	jsonpatch "github.com/evanphx/json-patch"
)


func setup() (*WebhookServer ){
	logger, cfg, err := pamf_logger.SetupLogging()
	if err != nil {
		panic(err)
	}
	defer logger.Sync()
	cfg.Level.SetLevel(zap.DebugLevel)
	sugar := logger.Sugar()
	return &WebhookServer {
		logger: sugar,
		userAnnotationKey: UserAnnotation,
	}
}

var whsvr *WebhookServer = setup()
func TestParseJSONPath_exists(t *testing.T) {
	raw := []byte(`{ "spec":
{ "tfReplicaSpecs": {"Ps": { "template": "haha"}
}}}`)
	var input interface{}
	json.Unmarshal(raw, &input)
    name := "test"
    template := `{..spec.tfReplicaSpecs.Ps.template}`
    value, _ := whsvr.parseJSONPath(input, name, template)
    assert.Equal(t, []string{"/spec/tfReplicaSpecs/Ps/template"}, value, "they should be equal")
}


func TestParseJSONPath_parent(t *testing.T) {
	raw := []byte(`{ "spec":
{ "tfReplicaSpecs": {"Ps": { "template": "haha"}
}}}`)
	var input interface{}
	json.Unmarshal(raw, &input)
	name := "test"
	template := `{..spec.tfReplicaSpecs.Ps.template}`
	if value, err := whsvr.parseJSONPath(input, name, template); err != nil {
		t.Errorf("error %v", err)
	} else {
		assert.Equal(t, []string{"/spec/tfReplicaSpecs/Ps/template"}, value, "they should be equal")
	}
}

func TestParseJSONPath_child(t *testing.T) {
	raw := []byte(`{ "spec":
{ "tfReplicaSpecs": {"Ps": { "template": {"metadata": "hehe"}}
}}}`)
	var input interface{}
	json.Unmarshal(raw, &input)
	name := "test"
	template := `{..spec.tfReplicaSpecs.Ps.template.metadata}`
	value, _ := whsvr.parseJSONPath(input, name, template)
	assert.Equal(t, []string{"/spec/tfReplicaSpecs/Ps/template/metadata"}, value, "they should be equal")
}
var raw = []byte(`{ "spec":
{ "tfReplicaSpecs": {"Ps": { "template": "haha"}
}}}`)
var ar = &v1beta1.AdmissionReview{
Request: &v1beta1.AdmissionRequest{
UID:                "000",
Kind:               v1.GroupVersionKind{},
Resource:           v1.GroupVersionResource{
	Resource:"tfjobs",
},
UserInfo:           authenticationv1.UserInfo{
Username: "testUser",
},
Object:             runtime.RawExtension{
Raw: raw,
},
OldObject:          runtime.RawExtension{},
Options:            runtime.RawExtension{},
},
}
func TestFindPath(t *testing.T) {
	json.Unmarshal(raw, &whsvr.unmarshalledReq)
	assert.Equal(t, []string{"/spec/tfReplicaSpecs/Ps/template"}, whsvr.mustGetPaths("..template"))
}

func TestFindPath_metadata(t *testing.T) {
	metadataRaw := []byte(`{ "spec":
{ "tfReplicaSpecs": {"Ps": { "template": {"metadata" : {"haha":"hehe"}}}
}}}`)
	metadataPath := "..metadata"
	ar.Request.Object.Raw = metadataRaw
	json.Unmarshal(metadataRaw, &whsvr.unmarshalledReq)
	assert.Equal(t, []string{"/spec/tfReplicaSpecs/Ps/template/metadata"}, whsvr.mustGetPaths(metadataPath))
}

func TestFindPath_annotations(t *testing.T) {
	annotationsRaw := []byte(`{ "spec": { "tfReplicaSpecs": {
"Ps": { "template": {"metadata" : {"annotations": {"haha":"hehe"}}}},
"Evaluator": { "template": {"metadata" : {"haha":"hehe"}}}
}}}`)
	annotationsPath := "..metadata.annotations"
	ar.Request.Object.Raw = annotationsRaw
	json.Unmarshal(annotationsRaw, &whsvr.unmarshalledReq)
	assert.Equal(t, []string{"/spec/tfReplicaSpecs/Ps/template/metadata/annotations"}, whsvr.mustGetPaths(annotationsPath))
}

func TestFindPath_pod_root(t *testing.T) {
	podTemplatePath := "..metadata"
	annotationsRaw := []byte(`{"metadata" : {"annotations": {"haha":"hehe"}}}`)
	ar.Request.Object.Raw = annotationsRaw
	json.Unmarshal(annotationsRaw, &whsvr.unmarshalledReq)
	assert.Equal(t, []string{"/metadata"}, whsvr.mustGetPaths(podTemplatePath))
}

func TestFindPath_pod_annotations(t *testing.T) {
	podTemplatePath := "..metadata.annotations"
	annotationsRaw := []byte(`{"metadata" : {"annotations": {"haha":"hehe"}}}`)
	ar.Request.Object.Raw = annotationsRaw
	json.Unmarshal(annotationsRaw, &whsvr.unmarshalledReq)
	assert.Equal(t, []string{"/metadata/annotations"}, whsvr.mustGetPaths(podTemplatePath))
}
var templatePath = "/spec/tfReplicaSpecs/Ps/template"
func TestUpdateAnnotations(t *testing.T) {
	json.Unmarshal(raw, &whsvr.unmarshalledReq)

	expectedPatch := []patchOperation{{
		Op:    "add",
		Path:  path.Join(templatePath, UserAnnotation),
		Value: "testUser",
	}}
	patch := whsvr.mustUpdateAnnotations("..template", ar)
	assert.Equal(t, expectedPatch, patch, "Patches must equal")
}

func TestUpdateAnnotations_metadata(t *testing.T) {
	metadataRaw := []byte(`{ "spec":
{ "tfReplicaSpecs": {"Ps": { "template": {"metadata" : {"haha":"hehe"}}}
}}}`)
	metadataPath := templatePath + "/metadata"
	ar.Request.Object.Raw = metadataRaw
	json.Unmarshal(metadataRaw, &whsvr.unmarshalledReq)
	expectedPatch := []patchOperation{{
		Op:    "add",
		Path:  path.Join(metadataPath, UserAnnotation),
		Value: "testUser",
	}}
	patch := whsvr.mustUpdateAnnotations("..template.metadata", ar)
	ar.Request.Object.Raw = raw
	assert.Equal(t, expectedPatch, patch, "Patches must equal")
}

func TestUpdateAnnotations_annotations(t *testing.T) {
	annotationsRaw := []byte(`{ "spec":
{ "tfReplicaSpecs": {"Ps": { "template": {"metadata" : {"annotations":"hehe"}}}
}}}`)
	annotationsPath := templatePath + "/metadata" + "/annotations"
	ar.Request.Object.Raw = annotationsRaw
	json.Unmarshal(annotationsRaw, &whsvr.unmarshalledReq)

	expectedPatch := []patchOperation{{
		Op:    "add",
		Path:  path.Join(annotationsPath, UserAnnotation),
		Value: "testUser",
	}}
	patch := whsvr.mustUpdateAnnotations("..template.metadata.annotations", ar)
	ar.Request.Object.Raw = raw
	assert.Equal(t, expectedPatch, patch, "Patches must equal")
}

func TestCreatePatch_skip_override(t *testing.T) {
	ar.Request.UserInfo.Username = "system:serviceaccount:kubeflow:tf-job-operator"
	ar.Request.Resource.Resource = "pods"
	whsvr.configMap = utils.ConfigMap{
		map[string]utils.Config{
			"alltypes": utils.Config{
				[]string{"..template"},
				[]string{},
			},
			"pods": utils.Config{
				[]string{"..metadata"},
				[]string{`system:serviceaccount:\S+`},
			},
		},
	}
	var expectedPatch []patchOperation
	assert.ElementsMatch(t, expectedPatch, whsvr.mustCreatePatch(ar), "patches must be equal")
	ar.Request.Resource.Resource = "tfjobs"
	ar.Request.UserInfo.Username = "testUser"
}

func TestCreatePatch_skip_override_path_not_found(t *testing.T) {
	ar.Request.UserInfo.Username = "chicken"
	whsvr.configMap = utils.ConfigMap{
		map[string]utils.Config{
			"alltypes": utils.Config{
				[]string{"..Ps.template.metadata.annotations",
					"..Evaluator.template.metadata.annotations"},
				[]string{},
			},
			"pods": utils.Config{
				[]string{"."},
				[]string{`system:serviceaccount:\S+`},
			},
		},
	}
	expectedPatch := []patchOperation{
		patchOperation {
		  "add",
		  "/spec/tfReplicaSpecs/Ps/template/metadata/annotations/iddecorator.username",
		 "chicken",
		},
	}
	assert.ElementsMatch(t, expectedPatch, whsvr.mustCreatePatch(ar), "patches must be equal")
	ar.Request.UserInfo.Username = "testUser"
}

func TestCreatePatch_update_patch(t *testing.T) {
	annotationsRaw := []byte(`{ "spec":
{ "tfReplicaSpecs": {
  "Ps": { "template": {"metadata" : {"annotations":{"doAs":"tfdl"}}}},
  "Evaluator": { "template": {"metadata" : {"tomatoes":"haha"}}}
}}}`)
	ar.Request.UserInfo.Username = "chicken"
	whsvr.configMap = utils.ConfigMap{
		map[string]utils.Config{
			"alltypes": utils.Config{
				[]string{"..metadata.annotations"},
				[]string{`system:serviceaccount:\S+`},
			},
		},
	}
	expectedPatch := []patchOperation{
		patchOperation {
			"add",
			"/spec/tfReplicaSpecs/Ps/template/metadata/annotations/iddecorator.username",
			"chicken",
		},
	}
	ar.Request.Object.Raw = annotationsRaw
	json.Unmarshal(annotationsRaw, &whsvr.unmarshalledReq)
	patchJson := whsvr.mustCreatePatch(ar)
	assert.ElementsMatch(t, expectedPatch, patchJson, "patches must be equal")
	patchBytes, _ := json.Marshal(patchJson)
	patch, err := jsonpatch.DecodePatch(patchBytes)
	if err != nil {
		t.Error(err)
	}

	modified, err := patch.Apply(annotationsRaw)
	if err != nil {
		t.Error(err)
	}
	expectedJson := []byte(`{ "spec":
{ "tfReplicaSpecs": {
  "Ps": { "template": {"metadata" : {"annotations":{"doAs":"tfdl", "iddecorator.username": "chicken"}}}},
  "Evaluator": { "template": {"metadata" : {"tomatoes":"haha"}}}
}}}`)
	var expectedJsonMap interface{}
	json.Unmarshal(expectedJson, &expectedJsonMap)
	var modifiedJsonMap interface{}
	json.Unmarshal(modified, &modifiedJsonMap)
	assert.Equal(t, expectedJsonMap, modifiedJsonMap, "patches must modify the original document")
	ar.Request.Object.Raw = raw
	ar.Request.UserInfo.Username = "testUser"

}